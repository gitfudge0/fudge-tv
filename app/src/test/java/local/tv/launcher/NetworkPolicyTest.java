package local.tv.launcher;

import static org.junit.Assert.*;

import org.junit.Test;

public class NetworkPolicyTest {
  @Test
  public void strictHttpsOrigins() {
    assertTrue(NetworkPolicy.allowedUrl("https://v3-cinemeta.strem.io/meta/movie/tt1160419.json"));
    for (String u :
        new String[] {
          "http://v3-cinemeta.strem.io/",
          "https://v3-cinemeta.strem.io.evil.test/",
          "https://user@v3-cinemeta.strem.io/",
          "https://v3-cinemeta.strem.io:444/",
          "https://127.0.0.1/",
          "https://v3-cinemeta.strem.io/#secret",
          "file:///tmp/a",
          "https://api.themoviedb.org/3/movie/1"
        }) assertFalse(u, NetworkPolicy.allowedUrl(u));
  }

  @Test
  public void onlyOfficialExactCatalogHandoffMayRedirect() {
    String origin = "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Action.json",
        target = "https://cinemeta-catalogs.strem.io/top/catalog/movie/top/genre=Action.json";
    assertTrue(NetworkPolicy.catalogRedirect(origin, target));
    for (String rejected :
        new String[] {
          "http://cinemeta-catalogs.strem.io/top/catalog/movie/top/genre=Action.json",
          target + "?token=x",
          target + "#fragment",
          target.replace("genre=Action", "genre=Drama"),
          target.replace("/movie/", "/series/"),
          target.replace("cinemeta-catalogs.strem.io", "evil.test")
        }) assertFalse(rejected, NetworkPolicy.catalogRedirect(origin, rejected));
    assertFalse(NetworkPolicy.catalogRedirect(target, target));
    assertFalse(NetworkPolicy.catalogRedirect("https://api.open-meteo.com/v1/forecast", target));
    assertFalse(
        NetworkPolicy.catalogRedirect(
            "https://v3-cinemeta.strem.io/meta/movie/tt1160419.json", target));
  }

  @Test
  public void imagesUseVerifiedPathsOnly() {
    for (String u :
        new String[] {
          "https://images.metahub.space/background/medium/tt1160419/img",
          "https://episodes.metahub.space/tt0944947/1/2/w780.jpg",
          "https://m.media-amazon.com/images/M/MV5abc@._V1_SX250.jpg"
        }) assertTrue(u, NetworkPolicy.imageUrl(u));
    for (String u :
        new String[] {
          "https://v3-cinemeta.strem.io/t/p/a.jpg",
          "https://images.metahub.space/background/medium/../img",
          "https://images.metahub.space/poster/small/tt1160419/img?token=value",
          "https://images.metahub.space/poster/small/%2e%2e/img",
          "https://image.tmdb.org/t/p/w500/a.jpg"
        }) assertFalse(u, NetworkPolicy.imageUrl(u));
  }

  @Test
  public void canonicalIdsAndTypes() {
    assertTrue(NetworkPolicy.validType("series"));
    assertTrue(NetworkPolicy.validType("movie"));
    assertFalse(NetworkPolicy.validType("tv"));
    assertTrue(NetworkPolicy.validId("tt1160419"));
    for (String id : new String[] {"123", "tt0", "tt1160419/else", "tt1160419:1:2", null})
      assertFalse(NetworkPolicy.validId(id));
    assertEquals("", NetworkPolicy.query(null));
    assertEquals("ab", NetworkPolicy.query("a\nb"));
    assertEquals(120, NetworkPolicy.query(new String(new char[150]).replace('\0', 'x')).length());
  }

  @Test
  public void retiredHostsNeverDownload() {
    assertTrue(NetworkPolicy.obsoleteMetadataHost("image.tmdb.org"));
    assertTrue(NetworkPolicy.obsoleteMetadataHost("IMAGE.TMDB.ORG"));
    assertTrue(NetworkPolicy.obsoleteMetadataHost("image.tmdb.org."));
    assertTrue(NetworkPolicy.obsoleteMetadataHost("api.themoviedb.org"));
    assertFalse(NetworkPolicy.obsoleteMetadataHost("images.metahub.space"));
    assertFalse(NetworkPolicy.obsoleteMetadataHost("tmdb.org.evil.test"));
  }

  @Test
  public void genresUseManifestNames() {
    assertTrue(NetworkPolicy.genreMatches("science fiction", "Sci-Fi"));
    assertTrue(NetworkPolicy.genreMatches("action", "Action"));
    assertFalse(NetworkPolicy.genreMatches("act", "Action"));
    assertFalse(NetworkPolicy.genreMatches("", ""));
  }
}
