package local.tv.launcher;

import java.util.*;

/** Platform-independent validation and deterministic ordering. */
final class RecommendationPolicy {
    private RecommendationPolicy() {}
    static int limit(int requested) { return Math.max(1,Math.min(40,requested)); }
    static boolean validProgress(long position,long duration) { return duration>0 && position>=0 && position<=duration; }
    static boolean ownerMatches(String owner,String explicitPackage,String componentPackage) {
        return owner!=null && !owner.isEmpty() && (explicitPackage==null || owner.equals(explicitPackage)) && (componentPackage==null || owner.equals(componentPackage));
    }
    static boolean validTitle(String title) { return title!=null && !title.trim().isEmpty(); }
    static int rowRank(String id,String order) {
        String[] ids=order.split(",");for(int i=0;i<ids.length;i++)if(id.equals(ids[i]))return i;
        return Integer.MAX_VALUE;
    }
    static String artwork(String raw) {
        if(raw==null || raw.length()>8192)return null;
        String lower=raw.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("content://") || lower.startsWith("android.resource://") ? raw:null;
    }
}
