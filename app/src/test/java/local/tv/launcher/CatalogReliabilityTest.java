package local.tv.launcher;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CatalogReliabilityTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private JSONObject rows(String type) throws Exception {
    return new JSONObject()
        .put(
            "metas",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("id", type.equals("movie") ? "tt1160419" : "tt0944947")
                        .put("type", type)
                        .put("name", type)));
  }

  private CatalogClient.Cache cache(String name) {
    return new CatalogClient.Cache(new File(temporary.getRoot(), name), 8 * 1024 * 1024);
  }

  private static void expire(CatalogClient.Cache cache) {
    for (File file : cache.dir.listFiles()) {
      assertTrue(file.setLastModified(System.currentTimeMillis() - 7200000));
    }
  }

  @Test
  public void freshCacheAvoidsNetworkAndExpiredCacheSurvivesOffline() throws Exception {
    CatalogClient.Cache disk = cache("cinemeta_metadata_v1");
    AtomicInteger calls = new AtomicInteger();
    try (CatalogClient online =
        new CatalogClient(
            disk,
            path -> {
              calls.incrementAndGet();
              return rows(path.contains("/movie/") ? "movie" : "series");
            })) {
      assertEquals(2, online.search("Dune").getJSONArray("items").length());
      assertEquals(2, calls.get());
      assertEquals("cached", online.search("Dune").getString("status"));
      assertEquals(2, calls.get());
    }
    expire(disk);
    try (CatalogClient offline =
        new CatalogClient(
            disk,
            path -> {
              throw new IOException();
            })) {
      JSONObject result = offline.search("Dune");
      assertTrue(result.getBoolean("stale"));
      assertEquals(2, result.getJSONArray("items").length());
      assertEquals("error", offline.search("uncached").getString("status"));
    }
  }

  @Test
  public void numericLegacyCacheIsNeverReadOrCleared() throws Exception {
    CatalogClient.Cache legacy = cache("catalog_metadata");
    CatalogClient.Cache current = cache("cinemeta_metadata_v1");
    legacy.write(
        "search:v1:Dune",
        new JSONObject()
            .put("items", new JSONArray().put(new JSONObject().put("key", "movie:438631"))));
    try (CatalogClient offline =
        new CatalogClient(
            current,
            path -> {
              throw new IOException();
            })) {
      assertEquals("error", offline.search("Dune").getString("status"));
      assertEquals(
          "movie:438631",
          legacy.read("search:v1:Dune").getJSONArray("items").getJSONObject(0).getString("key"));
    }
  }

  @Test
  public void genreFailurePreservesSuccessfulTitlesAndPartialIsNotCached() throws Exception {
    CatalogClient.Cache disk = cache("partial");
    try (CatalogClient client =
        new CatalogClient(
            disk,
            path -> {
              if (path.contains("genre=")) throw new IOException();
              return rows(path.contains("/movie/") ? "movie" : "series");
            })) {
      JSONObject result = client.search("Action");
      assertEquals(2, result.getJSONArray("items").length());
      assertTrue(result.getBoolean("partial"));
      assertEquals(0, disk.dir.listFiles().length);
    }
  }

  @Test
  public void matchedDetailLookupReusesCanonicalMetadataCache() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    try (CatalogClient client =
        new CatalogClient(
            cache("details"),
            path -> {
              calls.incrementAndGet();
              assertEquals("/meta/movie/tt1160419.json", path);
              return new JSONObject()
                  .put(
                      "meta",
                      new JSONObject()
                          .put("id", "tt1160419")
                          .put("type", "movie")
                          .put("name", "Dune")
                          .put("description", "Full synopsis"));
            })) {
      assertEquals(
          "Full synopsis",
          client.details("movie", "tt1160419").getJSONObject("item").getString("overview"));
      assertEquals("cached", client.details("movie", "tt1160419").getString("status"));
      assertEquals(1, calls.get());
      assertEquals("error", client.details("movie", "438631").getString("status"));
      assertEquals(1, calls.get());
    }
  }

  @Test
  public void malformedCatalogDoesNotReplaceStaleCache() throws Exception {
    CatalogClient.Cache disk = cache("schema");
    try (CatalogClient valid =
        new CatalogClient(disk, path -> rows(path.contains("/movie/") ? "movie" : "series"))) {
      valid.search("Dune");
    }
    expire(disk);
    try (CatalogClient malformed =
        new CatalogClient(disk, path -> new JSONObject().put("error", "unavailable"))) {
      JSONObject result = malformed.search("Dune");
      assertEquals("cached", result.getString("status"));
      assertTrue(result.getBoolean("stale"));
      assertEquals(2, result.getJSONArray("items").length());
      assertEquals("error", malformed.search("new query").getString("status"));
    }
  }

  @Test
  public void homeSurvivesOneSourceFailure() throws Exception {
    try (CatalogClient client =
        new CatalogClient(
            cache("home"),
            path -> {
              if (path.contains("/series/")) throw new IOException();
              return rows("movie");
            })) {
      JSONObject result = client.home();
      assertEquals("live", result.getString("status"));
      assertTrue(result.getBoolean("partial"));
      assertEquals(1, result.getJSONArray("items").length());
      assertEquals(1, result.getJSONArray("featured").length());
    }
  }

  @Test
  public void corruptCacheFailsClosed() throws Exception {
    CatalogClient.Cache disk = cache("corrupt");
    disk.write("title", new JSONObject().put("valid", true));
    for (File file : disk.dir.listFiles()) {
      try (FileWriter writer = new FileWriter(file)) {
        writer.write("not-json");
      }
    }
    assertNull(disk.read("title"));
  }

  @Test
  public void cacheBudgetIsBounded() throws Exception {
    CatalogClient.Cache disk = new CatalogClient.Cache(new File(temporary.getRoot(), "tiny"), 100);
    disk.write("a", new JSONObject().put("data", new String(new char[500]).replace('\0', 'x')));
    assertNull(disk.read("a"));
    assertFalse(disk.fresh("missing", 3600000));
  }

  @Test
  public void invalidNonemptyResultsPreserveStaleCacheButEmptyResultsRemainValid()
      throws Exception {
    CatalogClient.Cache disk = cache("invalid-rows");
    try (CatalogClient initial =
        new CatalogClient(disk, path -> rows(path.contains("/movie/") ? "movie" : "series"))) {
      initial.search("Dune");
    }
    expire(disk);
    try (CatalogClient malformed =
        new CatalogClient(
            disk, path -> new JSONObject().put("metas", new JSONArray().put(new JSONObject())))) {
      JSONObject result = malformed.search("Dune");
      assertTrue(result.getBoolean("stale"));
      assertEquals(2, result.getJSONArray("items").length());
    }
    try (CatalogClient empty =
        new CatalogClient(disk, path -> new JSONObject().put("metas", new JSONArray()))) {
      JSONObject result = empty.search("Dune");
      assertEquals("live", result.getString("status"));
      assertFalse(result.getBoolean("partial"));
      assertEquals(0, result.getJSONArray("items").length());
      assertEquals("cached", empty.search("Dune").getString("status"));
    }
  }
}
