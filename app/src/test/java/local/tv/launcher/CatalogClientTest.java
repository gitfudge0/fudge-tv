package local.tv.launcher;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class CatalogClientTest {
  @Test
  public void sparseSearchKeepsIdentityWithoutInventingFields() throws Exception {
    JSONObject item =
        CatalogClient.normalize(
            new JSONObject()
                .put("id", "tt1160419")
                .put("type", "movie")
                .put("name", "Dune")
                .put("releaseInfo", "2021"),
            "movie");
    assertEquals("movie:tt1160419", item.getString("key"));
    assertEquals("tt1160419", item.getString("imdbId"));
    assertEquals("2021", item.getString("year"));
    assertEquals("", item.getString("overview"));
    assertEquals("", item.getString("poster"));
    assertFalse(item.has("rating"));
    assertFalse(item.has("runtime"));
    assertEquals(0, item.getJSONArray("cast").length());
  }

  @Test
  public void mapsRichMetadataAndEpisodeIdentity() throws Exception {
    JSONObject raw =
        new JSONObject()
            .put("id", "tt0944947")
            .put("type", "series")
            .put("name", "Game of Thrones")
            .put("description", "Story")
            .put("releaseInfo", "2011–2019")
            .put("runtime", "57 min")
            .put("imdbRating", "9.2")
            .put("genres", new JSONArray().put("Drama"))
            .put("cast", new JSONArray().put("Emilia Clarke"))
            .put("director", new JSONArray().put("Director"))
            .put("writer", new JSONArray().put("Writer"))
            .put(
                "videos",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("id", "tt0944947:1:2")
                            .put("season", 1)
                            .put("number", 2)
                            .put("name", "Episode"))
                    .put(
                        new JSONObject()
                            .put("id", "tt0000001:1:3")
                            .put("season", 1)
                            .put("episode", 3)));
    JSONObject item = CatalogClient.normalize(raw, "series");
    assertEquals(57, item.getInt("runtime"));
    assertEquals(9.2, item.getDouble("rating"), .001);
    assertEquals("Emilia Clarke", item.getJSONArray("cast").getJSONObject(0).getString("name"));
    assertEquals("Director", item.getJSONArray("directors").getString(0));
    assertEquals(1, item.getJSONArray("episodes").length());
    assertEquals(1, item.getJSONArray("seasons").getInt(0));
    assertEquals(2, item.getJSONArray("episodes").getJSONObject(0).getInt("episode"));
  }

  @Test
  public void malformedOrCrossTypeMetadataIsRejected() throws Exception {
    assertNull(
        CatalogClient.normalize(
            new JSONObject().put("id", 438631).put("type", "movie").put("name", "Dune"), "movie"));
    assertNull(
        CatalogClient.normalize(
            new JSONObject().put("id", "tt1160419").put("type", "series").put("name", "Dune"),
            "movie"));
    assertNull(CatalogClient.normalize(new JSONObject().put("id", "tt1160419"), "movie"));
    assertNull(CatalogClient.normalize(null, "movie"));
  }

  @Test
  public void catalogsDeduplicateAndOmitHeavyEpisodesWithoutMutatingSource() throws Exception {
    JSONObject raw =
        new JSONObject()
            .put("id", "tt0944947")
            .put("type", "series")
            .put("name", "Game of Thrones")
            .put(
                "videos",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("id", "tt0944947:1:1")
                            .put("season", 1)
                            .put("episode", 1)));
    JSONArray items = CatalogClient.normalizeList(new JSONArray().put(raw).put(raw), "series");
    assertEquals(1, items.length());
    assertEquals(0, items.getJSONObject(0).getJSONArray("episodes").length());
    assertEquals(1, raw.getJSONArray("videos").length());
  }
}
