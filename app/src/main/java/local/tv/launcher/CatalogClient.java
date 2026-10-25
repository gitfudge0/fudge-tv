package local.tv.launcher;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Blocking client called by lifecycle-controlled workers; source fan-out has its own bound. */
public final class CatalogClient implements AutoCloseable {
  private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1);
  private static final String HOME_KEY = "home:v1";
  private final Cache cache;
  private final Transport transport;
  private final Map<String, Flight> flights = new HashMap<>();
  // Separate workers prevent a caller waiting on the activity's small pool from deadlocking it.
  private final ThreadPoolExecutor sources =
      new ThreadPoolExecutor(
          4,
          4,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(4),
          task -> {
            Thread thread = new Thread(task, "cinemeta-source");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private volatile boolean closed;

  interface Transport {
    JSONObject get(String path) throws Exception;
  }

  private interface Fetch {
    JSONObject get() throws Exception;
  }

  private static final class Flight {
    final Thread owner;
    final FutureTask<String> result;

    Flight(Thread owner, FutureTask<String> result) {
      this.owner = owner;
      this.result = result;
    }
  }

  public CatalogClient(Context context) {
    this(
        new Cache(context, "cinemeta_metadata_v1", 8 * 1024 * 1024),
        path ->
            new JSONObject(
                new String(
                    SecureHttp.get(
                        "https://v3-cinemeta.strem.io" + path,
                        Collections.singletonMap("Accept", "application/json"),
                        path.startsWith("/meta/") ? 5 * 1024 * 1024 : 1024 * 1024),
                    StandardCharsets.UTF_8)));
    context
        .getSharedPreferences("catalog_private", Context.MODE_PRIVATE)
        .edit()
        .remove("credential")
        .apply();
  }

  CatalogClient(Cache cache, Transport transport) {
    this.cache = cache;
    this.transport = transport;
    sources.allowCoreThreadTimeOut(true);
  }

  private void checkActive() throws InterruptedIOException {
    if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
  }

  private JSONObject request(String path) throws Exception {
    checkActive();
    try {
      JSONObject value = transport.get(path);
      checkActive();
      return value;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException();
    }
  }

  /** Disk-only first delivery. Stale output is interim; the caller then schedules home(). */
  public JSONObject cachedHome() {
    JSONObject saved = cache.read(HOME_KEY);
    if (saved == null) return null;
    boolean stale = !cache.fresh(HOME_KEY, CACHE_TTL_MS);
    return status(
        saved,
        "cached",
        stale ? "Previously fetched catalog; refreshing" : "Cached within the last hour",
        stale);
  }

  public JSONObject home() {
    return load(HOME_KEY, this::fetchHome);
  }

  private JSONObject fetchHome() throws Exception {
    List<Future<JSONArray>> pending = new ArrayList<>();
    List<JSONArray> results = new ArrayList<>();
    int successes = 0;
    try {
      for (String catalog : new String[] {"top"}) {
        for (String type : new String[] {"movie", "series"}) {
          checkActive();
          pending.add(sources.submit(() -> catalog(type, catalog, "")));
        }
      }
      for (int index = 0; index < pending.size(); index++) {
        checkActive();
        try {
          results.add(pending.get(index).get());
          successes++;
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        } catch (ExecutionException failed) {
          checkActive();
          results.add(new JSONArray());
        }
      }
      if (successes == 0 || results.get(0).length() + results.get(1).length() == 0) {
        throw new IOException("Catalog unavailable");
      }
      boolean partial = successes < 2;
      return envelope(
              interleave(results.get(0), results.get(1), 24),
              new JSONArray(),
              "live",
              partial ? "Some catalog results are unavailable" : "")
          .put("partial", partial);
    } finally {
      for (Future<?> future : pending) if (!future.isDone()) future.cancel(true);
      sources.purge();
    }
  }

  private JSONArray catalog(String type, String catalog, String extra) throws Exception {
    JSONArray raw = request(CinemetaPolicy.catalogPath(type, catalog, extra)).getJSONArray("metas");
    JSONArray normalized = normalizeList(raw, type);
    if (raw.length() > 0 && normalized.length() == 0) throw new IOException("Invalid metadata");
    return normalized;
  }

  public JSONObject search(String query) {
    String q = NetworkPolicy.query(query);
    if (q.isEmpty())
      return envelope(new JSONArray(), new JSONArray(), "live", "Enter a title or app name");
    return load("search:v1:" + q, () -> fetchSearch(q));
  }

  private JSONObject fetchSearch(String query) throws Exception {
    JSONArray movies = new JSONArray();
    JSONArray series = new JSONArray();
    int successes = 0;
    boolean partial = false;
    for (String type : new String[] {"movie", "series"}) {
      checkActive();
      JSONArray found = new JSONArray();
      boolean successful = false;
      try {
        found = catalog(type, "top", "search=" + CinemetaPolicy.encode(query));
        successful = true;
      } catch (Exception failed) {
        checkActive();
        partial = true;
      }
      String genre = CinemetaPolicy.genre(query, type);
      if (!genre.isEmpty()) {
        checkActive();
        try {
          found = merge(catalog(type, "top", "genre=" + CinemetaPolicy.encode(genre)), found);
          successful = true;
        } catch (Exception failed) {
          checkActive();
          partial = true;
        }
      }
      if (successful) successes++;
      if (type.equals("movie")) movies = found;
      else series = found;
    }
    if (successes == 0) throw new IOException("Catalog unavailable");
    return envelope(
            interleave(movies, series, 24),
            new JSONArray(),
            "live",
            partial ? "Some catalog results are unavailable" : "")
        .put("partial", partial);
  }

  public JSONObject details(String type, String id) {
    if (!NetworkPolicy.validType(type) || !NetworkPolicy.validId(id)) {
      return envelope(new JSONArray(), new JSONArray(), "error", "Invalid title");
    }
    return load(
        "details:v1:" + type + ":" + id,
        () -> {
          JSONObject raw = request(CinemetaPolicy.metaPath(type, id)).optJSONObject("meta");
          JSONObject item = normalize(raw, type);
          if (item == null || !id.equals(item.optString("id")))
            throw new IOException("Invalid metadata");
          return envelope(new JSONArray(), new JSONArray(), "live", "").put("item", item);
        });
  }

  private JSONObject load(String key, Fetch fetch) {
    JSONObject saved = cache.read(key);
    if (closed || Thread.currentThread().isInterrupted()) return unavailable(saved);
    if (saved != null && cache.fresh(key, CACHE_TTL_MS)) {
      return status(saved, "cached", "Cached within the last hour", false);
    }
    Flight flight;
    boolean owner = false;
    synchronized (flights) {
      if (closed) return unavailable(saved);
      flight = flights.get(key);
      // A replacement request must not attach to work already canceled by the lifecycle owner.
      if (flight != null && (flight.owner.isInterrupted() || flight.result.isCancelled())) {
        flight.result.cancel(true);
        flight = null;
      }
      if (flight == null) {
        FutureTask<String> task = new FutureTask<>(() -> fetchAndCache(key, fetch).toString());
        flight = new Flight(Thread.currentThread(), task);
        flights.put(key, flight);
        owner = true;
      }
    }
    try {
      if (owner) flight.result.run();
      // Every consumer receives its own mutable JSON tree (details callers compact theirs).
      return new JSONObject(flight.result.get());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return unavailable(saved);
    } catch (ExecutionException | CancellationException | JSONException failed) {
      return unavailable(saved);
    } finally {
      if (owner) {
        synchronized (flights) {
          if (flights.get(key) == flight) flights.remove(key);
        }
      }
    }
  }

  private JSONObject fetchAndCache(String key, Fetch fetch) throws Exception {
    checkActive();
    // Another completed request may have populated disk before this flight was registered.
    JSONObject saved = cache.read(key);
    if (saved != null && cache.fresh(key, CACHE_TTL_MS)) {
      return status(saved, "cached", "Cached within the last hour", false);
    }
    try {
      JSONObject result = fetch.get();
      checkActive();
      if (!result.optBoolean("partial")) cache.write(key, result);
      return result;
    } catch (Exception failed) {
      return unavailable(saved);
    }
  }

  private static JSONObject unavailable(JSONObject saved) {
    return saved == null
        ? envelope(
            new JSONArray(), new JSONArray(), "error", "Catalog unavailable; check connection")
        : status(saved, "cached", "Offline: showing previously fetched catalog", true);
  }

  @Override
  public void close() {
    closed = true;
    synchronized (flights) {
      for (Flight flight : flights.values()) flight.result.cancel(true);
      flights.clear();
    }
    sources.shutdownNow();
  }

  private static JSONObject status(JSONObject value, String status, String message, boolean stale) {
    try {
      value.put("status", status).put("message", message).put("stale", stale);
    } catch (JSONException impossible) {
      /* Constants and JSON values are valid. */
    }
    return value;
  }

  static JSONObject envelope(JSONArray items, JSONArray featured, String status, String message) {
    JSONObject value = new JSONObject();
    try {
      value
          .put("items", items)
          .put("featured", featured)
          .put("status", status)
          .put("message", message)
          .put("stale", false);
    } catch (JSONException impossible) {
      /* Constants and JSON values are valid. */
    }
    return value;
  }

  static JSONArray normalizeList(JSONArray rows, String type) throws JSONException {
    JSONArray out = new JSONArray();
    if (rows != null) {
      for (int index = 0; index < rows.length() && out.length() < 24; index++) {
        JSONObject raw = rows.optJSONObject(index);
        if (raw != null) {
          // Skip large episode lists without mutating a transport-owned JSON tree.
          add(out, normalize(raw, type, false));
        }
      }
    }
    return out;
  }

  private static void add(JSONArray out, JSONObject item) throws JSONException {
    if (item == null || out.length() >= 24) return;
    for (int index = 0; index < out.length(); index++) {
      if (out.getJSONObject(index).optString("key").equals(item.optString("key"))) return;
    }
    out.put(item);
  }

  private static JSONArray merge(JSONArray first, JSONArray second) throws JSONException {
    for (int index = 0; index < second.length(); index++) add(first, second.getJSONObject(index));
    return first;
  }

  private static JSONArray interleave(JSONArray first, JSONArray second, int limit)
      throws JSONException {
    JSONArray out = new JSONArray();
    for (int index = 0;
        index < Math.max(first.length(), second.length()) && out.length() < limit;
        index++) {
      if (index < first.length()) add(out, first.getJSONObject(index));
      if (out.length() < limit && index < second.length()) add(out, second.getJSONObject(index));
    }
    return out;
  }

  static JSONObject normalize(JSONObject raw, String requestedType) throws JSONException {
    return normalize(raw, requestedType, true);
  }

  private static JSONObject normalize(JSONObject raw, String requestedType, boolean includeVideos)
      throws JSONException {
    if (raw == null) return null;
    String id = raw.optString("id", raw.optString("imdb_id", ""));
    String type = raw.optString("type", requestedType);
    String title = text(raw, "name");
    if (!NetworkPolicy.validType(type)
        || !type.equals(requestedType)
        || !NetworkPolicy.validId(id)
        || title.isEmpty()) return null;
    String year = text(raw, "releaseInfo");
    if (year.isEmpty()) year = text(raw, "year");
    String released = text(raw, "released");
    if (year.isEmpty() && released.matches("[0-9]{4}-.*")) year = released.substring(0, 4);
    JSONObject out =
        new JSONObject()
            .put("key", type + ":" + id)
            .put("id", id)
            .put("imdbId", id)
            .put("mediaType", type)
            .put("title", title)
            .put("year", year)
            .put("released", released)
            .put("overview", text(raw, "description"))
            .put("poster", image(text(raw, "poster")))
            .put("art", image(text(raw, "background")))
            .put("tagline", "");
    JSONArray genres = raw.optJSONArray("genres");
    if (genres == null) genres = raw.optJSONArray("genre");
    out.put("genres", strings(genres, 12));
    String runtime = text(raw, "runtime");
    out.put("runtimeText", runtime);
    if (runtime.matches("[0-9]{1,4} min"))
      out.put("runtime", Integer.parseInt(runtime.split(" ")[0]));
    double rating = raw.optDouble("imdbRating", Double.NaN);
    if (!Double.isNaN(rating) && rating >= 0 && rating <= 10) out.put("rating", rating);
    JSONArray cast = new JSONArray();
    JSONArray people = raw.optJSONArray("cast");
    if (people != null) {
      for (int index = 0; index < people.length() && index < 12; index++) {
        Object person = people.opt(index);
        if (person instanceof String && !((String) person).isEmpty()) {
          cast.put(new JSONObject().put("name", person).put("character", "").put("photo", ""));
        }
      }
    }
    out.put("cast", cast)
        .put("directors", strings(raw.optJSONArray("director"), 12))
        .put("writers", strings(raw.optJSONArray("writer"), 12));
    JSONArray episodes = new JSONArray();
    JSONArray videos = includeVideos ? raw.optJSONArray("videos") : null;
    Set<Integer> seasons = new TreeSet<>();
    if (videos != null) {
      for (int index = 0; index < videos.length(); index++) {
        JSONObject video = videos.optJSONObject(index);
        if (video == null) continue;
        int season = video.optInt("season", -1);
        int episode = video.optInt("episode", video.optInt("number", -1));
        String videoId = video.optString("id", "");
        if (season < 0 || episode < 0 || !videoId.equals(id + ":" + season + ":" + episode))
          continue;
        seasons.add(season);
        episodes.put(
            new JSONObject()
                .put("id", videoId)
                .put("title", text(video, "name"))
                .put("season", season)
                .put("episode", episode)
                .put("released", text(video, "released"))
                .put("overview", text(video, "description"))
                .put("thumbnail", image(text(video, "thumbnail"))));
      }
    }
    JSONArray seasonNumbers = new JSONArray();
    for (Integer season : seasons) seasonNumbers.put(season);
    return out.put("episodes", episodes).put("seasons", seasonNumbers);
  }

  private static String text(JSONObject raw, String key) {
    return raw.isNull(key) ? "" : raw.optString(key, "");
  }

  private static JSONArray strings(JSONArray values, int max) {
    JSONArray out = new JSONArray();
    if (values != null) {
      for (int index = 0; index < values.length() && out.length() < max; index++) {
        Object value = values.opt(index);
        if (value instanceof String && !((String) value).isEmpty()) out.put(value);
      }
    }
    return out;
  }

  private static String image(String url) {
    return NetworkPolicy.imageUrl(url) ? url : "";
  }

  /** Native private cache keys are digests of metadata identifiers, never credentials. */
  static final class Cache {
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    final File dir;
    final long cap;

    Cache(Context context, String name, long cap) {
      this(new File(context.getCacheDir(), name), cap);
    }

    Cache(File directory, long cap) {
      dir = directory;
      dir.mkdirs();
      this.cap = cap;
    }

    private File file(String key) {
      try {
        byte[] bytes =
            MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        char[] hex = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
          hex[index * 2] = HEX[(bytes[index] & 255) >>> 4];
          hex[index * 2 + 1] = HEX[bytes[index] & 15];
        }
        return new File(dir, new String(hex) + ".json");
      } catch (Exception unavailable) {
        throw new IllegalStateException("Cache unavailable");
      }
    }

    synchronized boolean fresh(String key, long ttl) {
      File file = file(key);
      long age = System.currentTimeMillis() - file.lastModified();
      return file.exists() && age >= 0 && age < ttl;
    }

    synchronized JSONObject read(String key) {
      File file = file(key);
      if (!file.isFile() || file.length() > MAX_BYTES) return null;
      try (FileInputStream in = new FileInputStream(file);
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int size;
        while ((size = in.read(buffer)) != -1) {
          if (out.size() + size > MAX_BYTES) return null;
          out.write(buffer, 0, size);
        }
        return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
      } catch (Exception unavailable) {
        return null;
      }
    }

    synchronized void write(String key, JSONObject value) {
      byte[] data = value.toString().getBytes(StandardCharsets.UTF_8);
      if (data.length > MAX_BYTES) return;
      File temporary = null;
      try {
        // Activity recreation may briefly leave two client instances writing this directory.
        temporary = File.createTempFile("pending-", ".tmp", dir);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
          out.write(data);
        }
        if (!temporary.renameTo(file(key))) return;
        File[] files = dir.listFiles((directory, name) -> name.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
          if (total <= cap) break;
          long size = file.length();
          if (file.delete()) total -= size;
        }
      } catch (Exception unavailable) {
        // Cache failure does not turn a usable network response into an error.
      } finally {
        if (temporary != null) temporary.delete();
      }
    }

    synchronized void clear() {
      File[] files = dir.listFiles();
      if (files != null) for (File file : files) file.delete();
    }
  }
}
