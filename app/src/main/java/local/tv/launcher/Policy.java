package local.tv.launcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Pure, testable boundaries for provider links and remote movement. */
public final class Policy {
    public static final String[] PACKAGES = {"com.stremio.one", "com.crunchyroll.crunchyroid", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar", "com.google.android.youtube.tv", "com.apple.atve.sony.appletv"};
    public static final String[] LABELS = {"Stremio", "Crunchyroll", "Netflix", "Prime Video", "JioHotstar", "YouTube", "Apple TV"};
    public static final Set<String> RETAINED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(PACKAGES)));
    private Policy() {}
    public static boolean ownerAllowed(String owner, String explicitPackage, String componentPackage) {
        return RETAINED.contains(owner) && (explicitPackage == null || owner.equals(explicitPackage)) && (componentPackage == null || owner.equals(componentPackage));
    }
    public static boolean linkSchemeAllowed(String scheme) {
        if (scheme == null || scheme.isEmpty()) return false;
        String s = scheme.toLowerCase(java.util.Locale.ROOT);
        return !Arrays.asList("file", "content", "javascript", "data", "android.resource", "intent").contains(s);
    }
    public static boolean resolvedOwnerAllowed(String owner, String resolvedPackage, boolean exported) {
        return RETAINED.contains(owner) && owner.equals(resolvedPackage) && exported;
    }
    public static boolean safeUserApp(String packageName, String activityName, String label) {
        String value=(String.valueOf(packageName)+" "+String.valueOf(activityName)+" "+String.valueOf(label)).toLowerCase(java.util.Locale.ROOT);
        for(String blocked:new String[]{"factory","production","fac.menu","facmenu","fac_menu","servicemenu","servicemode","service menu","service mode","com.sony.dtv.servicemode"})if(value.contains(blocked))return false;
        return true;
    }
    public static int gridMove(int index, int dx, int dy, int count, int columns) {
        if(count<=0)return -1;
        int next=move(index,0,count);
        if(dx<0 && next%columns>0)next--;
        if(dx>0 && next%columns<columns-1 && next+1<count)next++;
        if(dy<0 && next>=columns)next-=columns;
        if(dy>0 && next+columns<count)next+=columns;
        return next;
    }
    public static boolean cinematicArtwork(int width,int height) {
        return width>0 && height>0 && width/(double)height>=1.3;
    }
    public static boolean shouldDefaultFocus(boolean firstSnapshot, boolean explicitFocusChoice) {
        return firstSnapshot && !explicitFocusChoice;
    }
    public static int move(int index, int delta, int count) {
        return count <= 0 ? -1 : Math.max(0, Math.min(count - 1, index + delta));
    }
    public static long stableId(String key) { long h = 0xcbf29ce484222325L; for (int i=0;i<key.length();i++) h = (h ^ key.charAt(i)) * 0x100000001b3L; return h; }
    public static int compareRecent(long first, long second, String firstLabel, String secondLabel) {
        int byTime = Long.compare(second, first);
        return byTime != 0 ? byTime : firstLabel.compareToIgnoreCase(secondLabel);
    }
}
