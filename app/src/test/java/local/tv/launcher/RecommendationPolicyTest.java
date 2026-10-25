package local.tv.launcher;

import static org.junit.Assert.*;

import org.junit.Test;

public class RecommendationPolicyTest {
  @Test
  public void progressRequiresCompleteValidMetadata() {
    assertTrue(RecommendationPolicy.validProgress(50, 100));
    assertTrue(RecommendationPolicy.validProgress(0, 100));
    assertFalse(RecommendationPolicy.validProgress(-1, 100));
    assertFalse(RecommendationPolicy.validProgress(101, 100));
    assertFalse(RecommendationPolicy.validProgress(0, 0));
  }

  @Test
  public void ownerCannotRedirectToAnotherPackage() {
    assertTrue(RecommendationPolicy.ownerMatches("app", null, null));
    assertTrue(RecommendationPolicy.ownerMatches("app", "app", "app"));
    assertFalse(RecommendationPolicy.ownerMatches("app", "other", null));
    assertFalse(RecommendationPolicy.ownerMatches("app", null, "other"));
    assertFalse(RecommendationPolicy.ownerMatches(null, null, null));
  }

  @Test
  public void optionalMetadataAndBoundsAreSanitized() {
    assertEquals(1, RecommendationPolicy.limit(-5));
    assertEquals(40, RecommendationPolicy.limit(500));
    assertFalse(RecommendationPolicy.validTitle("  "));
    assertFalse(RecommendationPolicy.validTitle(null));
    assertNull(RecommendationPolicy.artwork("file:///private"));
    assertNull(RecommendationPolicy.artwork("javascript:alert(1)"));
    assertEquals("content://tv/logo", RecommendationPolicy.artwork("content://tv/logo"));
  }

  @Test
  public void persistedRowOrderIsStableForUnknownChannels() {
    assertEquals(1, RecommendationPolicy.rowRank("b", "a,b,c"));
    assertEquals(Integer.MAX_VALUE, RecommendationPolicy.rowRank("new", "a,b,c"));
  }
}
