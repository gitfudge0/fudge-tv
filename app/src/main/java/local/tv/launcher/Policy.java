package local.tv.launcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Pure boundaries for provider links, safe apps, stable identities and MRU ordering. */
public final class Policy {
  public static final String[] PACKAGES = {
    "com.stremio.one",
    "com.crunchyroll.crunchyroid",
    "com.netflix.ninja",
    "com.amazon.amazonvideo.livingroom",
    "in.startv.hotstar",
    "com.google.android.youtube.tv",
    "com.apple.atve.sony.appletv"
  };
  public static final String[] LABELS = {
    "Stremio", "Crunchyroll", "Netflix", "Prime Video", "JioHotstar", "YouTube", "Apple TV"
  };
  public static final Set<String> RETAINED =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList(PACKAGES)));

  private Policy() {}

  public static boolean ownerAllowed(
      String owner, String explicitPackage, String componentPackage) {
    return RETAINED.contains(owner)
        && (explicitPackage == null || owner.equals(explicitPackage))
        && (componentPackage == null || owner.equals(componentPackage));
  }

  public static boolean linkSchemeAllowed(String scheme) {
    if (scheme == null || scheme.isEmpty()) return false;
    String s = scheme.toLowerCase(java.util.Locale.ROOT);
    return !Arrays.asList("file", "content", "javascript", "data", "android.resource", "intent")
        .contains(s);
  }

  public static boolean resolvedOwnerAllowed(
      String owner, String resolvedPackage, boolean exported) {
    return RETAINED.contains(owner) && owner.equals(resolvedPackage) && exported;
  }

  public static boolean safeUserApp(String packageName, String activityName, String label) {
    String value =
        (String.valueOf(packageName)
                + " "
                + String.valueOf(activityName)
                + " "
                + String.valueOf(label))
            .toLowerCase(java.util.Locale.ROOT);
    for (String blocked :
        new String[] {
          "factory",
          "production",
          "fac.menu",
          "facmenu",
          "fac_menu",
          "servicemenu",
          "servicemode",
          "service menu",
          "service mode",
          "com.sony.dtv.servicemode"
        }) if (value.contains(blocked)) return false;
    return true;
  }

  public static long stableId(String key) {
    long h = 0xcbf29ce484222325L;
    for (int i = 0; i < key.length(); i++) h = (h ^ key.charAt(i)) * 0x100000001b3L;
    return h;
  }

  public static int compareRecent(long first, long second, String firstLabel, String secondLabel) {
    int byTime = Long.compare(second, first);
    return byTime != 0 ? byTime : firstLabel.compareToIgnoreCase(secondLabel);
  }
}
