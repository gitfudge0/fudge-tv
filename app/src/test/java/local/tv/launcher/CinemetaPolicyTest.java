package local.tv.launcher;
import org.junit.Test;
import static org.junit.Assert.*;
public class CinemetaPolicyTest {
 @Test public void pathsAndEscaping(){assertEquals("/catalog/movie/top/search=Wall%20%26%20E%2F%23.json",CinemetaPolicy.catalogPath("movie","top","search="+CinemetaPolicy.encode("Wall & E/#")));assertEquals("/catalog/series/imdbRating.json",CinemetaPolicy.catalogPath("series","imdbRating",""));assertEquals("/meta/movie/tt1160419.json",CinemetaPolicy.metaPath("movie","tt1160419"));assertEquals("Sci-Fi",CinemetaPolicy.genre("science fiction","movie"));assertEquals("",CinemetaPolicy.genre("Reality-TV","movie"));}
 @Test public void detailsNeverAutoplay(){assertEquals("stremio:///detail/movie/tt1160419/tt1160419?autoPlay=false",CinemetaPolicy.detailUri("movie","tt1160419"));assertEquals("stremio:///detail/series/tt0944947?autoPlay=false",CinemetaPolicy.detailUri("series","tt0944947"));for(String id:new String[]{"438631","tt1160419?autoplay=1","tt1160419:1:1"})try{CinemetaPolicy.detailUri("movie",id);fail(id);}catch(IllegalArgumentException expected){}}
}
