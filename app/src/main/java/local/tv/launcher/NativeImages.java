package local.tv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Native image loading with bounded, shared work and recycled-view suppression. */
final class NativeImages implements AutoCloseable {
  interface Source {
    byte[] read(String uri) throws IOException;
  }

  private static final int LIMIT = 5 * 1024 * 1024;
  private static final int MAX_IN_FLIGHT = 64;
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ImageDiskCache disk;
  private final Source source;
  private final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_IN_FLIGHT));
  private final Map<String, Work> inFlight = new HashMap<>();
  private final WeakHashMap<ImageView, Work> targets = new WeakHashMap<>();
  private final Set<HttpURLConnection> connections = Collections.synchronizedSet(new HashSet<>());
  private final LruCache<String, Bitmap> memory =
      new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap image) {
          return image.getAllocationByteCount();
        }
      };
  private volatile boolean closed;

  private static final class Work {
    final String key, uri;
    final int width, height;
    FutureTask<Void> task;

    Work(String key, String uri, int width, int height) {
      this.key = key;
      this.uri = uri;
      this.width = width;
      this.height = height;
    }
  }

  NativeImages(Context context) {
    this(context, new File(context.getCacheDir(), "native_art"), null);
  }

  NativeImages(Context context, File directory, Source source) {
    this.context = context.getApplicationContext();
    this.source = source == null ? this::readSource : source;
    disk = new ImageDiskCache(directory, LIMIT, 16L * 1024 * 1024, NativeImages::validJpeg);
  }

  /** Call on the main thread. The target keeps its current image until replacement is ready. */
  void load(ImageView target, String rawUri, int widthPx, int heightPx) {
    if (closed || !validUri(rawUri)) {
      clear(target);
      return;
    }
    int width = Math.max(1, Math.min(1920, widthPx));
    int height = Math.max(1, Math.min(1080, heightPx));
    String key = hash(rawUri + ":" + width + "x" + height);
    Work current = targets.get(target);
    if (current != null && key.equals(current.key)) return;
    clear(target);
    Bitmap cached = memory.get(key);
    if (cached != null) {
      target.setImageBitmap(cached);
      return;
    }
    Work work = inFlight.get(key);
    if (work != null) {
      targets.put(target, work);
      return;
    }
    if (inFlight.size() >= MAX_IN_FLIGHT) return;
    work = new Work(key, rawUri, width, height);
    Work submitted = work;
    targets.put(target, work);
    inFlight.put(key, work);
    work.task =
        new FutureTask<>(
            () -> {
              decode(submitted);
              return null;
            });
    executor.execute(work.task);
  }

  void clear(ImageView target) {
    Work previous = targets.remove(target);
    if (previous != null && !targets.containsValue(previous)) {
      previous.task.cancel(true);
      executor.remove(previous.task);
      if (inFlight.get(previous.key) == previous) inFlight.remove(previous.key);
    }
  }

  void showApp(ImageView target, Library.App app, boolean icon) {
    clear(target);
    if (closed) return;
    Drawable drawable = icon ? app.icon : app.banner;
    if (drawable == null) drawable = icon ? app.banner : app.icon;
    Drawable.ConstantState state = drawable == null ? null : drawable.getConstantState();
    target.setImageDrawable(
        state == null ? drawable : state.newDrawable(context.getResources()).mutate());
  }

  private void decode(Work work) {
    Bitmap bitmap = null;
    try {
      byte[] cached = disk.read(work.key);
      byte[] bytes = cached == null ? source.read(work.uri) : cached;
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
      if (bounds.outWidth < 1
          || bounds.outHeight < 1
          || bounds.outWidth > 16384
          || bounds.outHeight > 16384) return;
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 1;
      while (bounds.outWidth / (options.inSampleSize * 2) >= work.width
          && bounds.outHeight / (options.inSampleSize * 2) >= work.height)
        options.inSampleSize *= 2;
      // Extremely panoramic assets must also respect the absolute bitmap budget.
      while (bounds.outWidth / options.inSampleSize > 1920
          || bounds.outHeight / options.inSampleSize > 1080) options.inSampleSize *= 2;
      options.inPreferredConfig = Bitmap.Config.RGB_565;
      bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
      if (bitmap == null || closed || Thread.currentThread().isInterrupted()) return;
      if (cached == null) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out))
          disk.write(work.key, out.toByteArray());
      }
      if (closed || Thread.currentThread().isInterrupted()) return;
      synchronized (memory) {
        if (closed || Thread.currentThread().isInterrupted()) return;
        memory.put(work.key, bitmap);
      }
      Bitmap delivered = bitmap;
      bitmap =
          null; // Cache/targets now own this bitmap; eviction must never recycle a displayed image.
      main.post(() -> complete(work, delivered));
    } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
      // Keep the native card's placeholder on failures.
    } finally {
      if (bitmap != null) bitmap.recycle();
      main.post(() -> complete(work, null));
    }
  }

  private void complete(Work work, Bitmap image) {
    if (inFlight.get(work.key) == work) inFlight.remove(work.key);
    Iterator<Map.Entry<ImageView, Work>> entries = targets.entrySet().iterator();
    while (entries.hasNext()) {
      Map.Entry<ImageView, Work> entry = entries.next();
      if (entry.getValue() != work) continue;
      ImageView target = entry.getKey();
      if (!closed && image != null && target != null) target.setImageBitmap(image);
      entries.remove();
    }
  }

  private byte[] readSource(String raw) throws IOException {
    Uri uri = Uri.parse(raw);
    if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
      try (InputStream in = context.getContentResolver().openInputStream(uri)) {
        return ImageDiskCache.bounded(in, LIMIT);
      }
    }
    URL url = new URL(raw);
    for (int redirects = 0; redirects < 3; redirects++) {
      if (!validUri(url.toString())) throw new IOException("Image unavailable");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(4000);
      connection.setReadTimeout(4000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Accept", "image/*");
      connection.setRequestProperty("User-Agent", "FudgeTV/2.0");
      synchronized (connections) {
        if (closed) throw new IOException("Image loader closed");
        connections.add(connection);
      }
      try {
        int code = connection.getResponseCode();
        if (code >= 300 && code < 400) {
          String next = connection.getHeaderField("Location");
          if (next == null) throw new IOException("Image unavailable");
          url = new URL(url, next);
          continue;
        }
        if (code != 200 || connection.getContentLengthLong() > LIMIT)
          throw new IOException("Image unavailable");
        try (InputStream in = connection.getInputStream()) {
          return ImageDiskCache.bounded(in, LIMIT);
        }
      } finally {
        connections.remove(connection);
        connection.disconnect();
      }
    }
    throw new IOException("Image unavailable");
  }

  private static boolean validUri(String raw) {
    if (raw == null || raw.isEmpty() || raw.length() > 8192) return false;
    try {
      java.net.URI uri = new java.net.URI(raw);
      String scheme = uri.getScheme();
      if ("content".equals(scheme) || "android.resource".equals(scheme)) return true;
      return ("https".equals(scheme) || "http".equals(scheme))
          && uri.getHost() != null
          && uri.getUserInfo() == null
          && !NetworkPolicy.obsoleteMetadataHost(uri.getHost());
    } catch (Exception malformed) {
      return false;
    }
  }

  static boolean validJpeg(byte[] bytes) {
    if (bytes.length < 4
        || (bytes[0] & 255) != 255
        || (bytes[1] & 255) != 216
        || (bytes[bytes.length - 2] & 255) != 255
        || (bytes[bytes.length - 1] & 255) != 217) return false;
    Bitmap bitmap = null;
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
      if (bounds.outWidth < 1
          || bounds.outHeight < 1
          || bounds.outWidth > 1920
          || bounds.outHeight > 1080) return false;
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inSampleSize = 4;
      bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
      return bitmap != null;
    } catch (RuntimeException | OutOfMemoryError failed) {
      return false;
    } finally {
      if (bitmap != null) bitmap.recycle();
    }
  }

  private static String hash(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte part : digest)
        hex.append(Character.forDigit((part & 255) >>> 4, 16))
            .append(Character.forDigit(part & 15, 16));
      return hex.toString();
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Call on the main thread when the owning native view is destroyed. */
  @Override
  public void close() {
    if (closed) return;
    closed = true;
    for (Work work : inFlight.values()) work.task.cancel(true);
    inFlight.clear();
    targets.clear();
    executor.shutdownNow();
    synchronized (connections) {
      for (HttpURLConnection connection : connections) connection.disconnect();
      connections.clear();
    }
    main.removeCallbacksAndMessages(null);
    synchronized (memory) {
      memory.evictAll();
    }
  }
}
