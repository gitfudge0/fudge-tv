package local.tv.launcher;

import static org.junit.Assert.*;

import org.junit.Test;

public class PolicyTest {
  @Test
  public void rejectsForeignDeepLinks() {
    String owner = "com.netflix.ninja";
    assertTrue(Policy.ownerAllowed(owner, null, null));
    assertTrue(Policy.ownerAllowed(owner, owner, owner));
    assertFalse(Policy.ownerAllowed(owner, "com.other", null));
    assertFalse(Policy.ownerAllowed(owner, null, "com.other"));
    assertFalse(Policy.ownerAllowed("com.unretained", null, null));
    assertFalse(Policy.ownerAllowed(null, null, null));
  }

  @Test
  public void rejectsLocalAndExecutableSchemes() {
    for (String blocked :
        new String[] {
          null,
          "",
          "file",
          "content",
          "javascript",
          "data",
          "android.resource",
          "intent",
          "JAVASCRIPT"
        }) assertFalse(Policy.linkSchemeAllowed(blocked));
    assertTrue(Policy.linkSchemeAllowed("https"));
    assertTrue(Policy.linkSchemeAllowed("netflix"));
  }

  @Test
  public void newestOpenedComesFirstAndTiesAreDeterministic() {
    assertTrue(Policy.compareRecent(200, 100, "Z", "A") < 0);
    assertTrue(Policy.compareRecent(0, 0, "Apple TV", "Netflix") < 0);
    assertEquals(0, Policy.compareRecent(1, 1, "Netflix", "Netflix"));
  }

  @Test
  public void resolvedResumeMustStayWithExportedOwner() {
    assertTrue(Policy.resolvedOwnerAllowed("com.netflix.ninja", "com.netflix.ninja", true));
    assertFalse(Policy.resolvedOwnerAllowed("com.netflix.ninja", "com.other", true));
    assertFalse(Policy.resolvedOwnerAllowed("com.netflix.ninja", "com.netflix.ninja", false));
  }

  @Test
  public void hiddenFactoryAppsAreNeverListed() {
    assertFalse(Policy.safeUserApp("com.sony.dtv.fac.menu", "Main", "Menu"));
    assertFalse(Policy.safeUserApp("com.vendor.productiontest", "Main", "Tools"));
    assertFalse(Policy.safeUserApp("com.sony.tools", "FactoryActivity", "Tools"));
    assertTrue(Policy.safeUserApp("com.netflix.ninja", "Main", "Netflix"));
  }

  @Test
  public void focusIdentitySurvivesPositionChanges() {
    assertEquals(Policy.stableId("com.netflix.ninja"), Policy.stableId("com.netflix.ninja"));
    assertNotEquals(Policy.stableId("com.netflix.ninja"), Policy.stableId("com.stremio.one"));
  }
}
