package local.tv.launcher;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.Drawable;
import android.os.Process;
import java.util.*;

final class Library {
  static final class App {
    String id, label;
    Intent launch;
    Drawable banner, icon;
    long recent;

    App(String id, String label, Intent launch) {
      this.id = id;
      this.label = label;
      this.launch = launch;
    }
  }

  static final class Program {
    String id, owner, title, description, art, season, episode, channelId, contentId, target;
    int contentType = -1, aspectRatio = -1;
    long updated;
    Intent launch;
    boolean resumable, portrait, searchHandoff;
    long position, duration, engagement;
    int weight;
  }

  static final class Channel {
    String id, owner, title, logo, systemChannelKey;
    boolean browsable;
    long numericId, order = -1;
    final List<Program> programs = new ArrayList<>();
  }

  static final class Snapshot {
    final List<App> apps = new ArrayList<>(), allApps = new ArrayList<>();
    final List<Program> programs = new ArrayList<>();
    final List<Channel> channels = new ArrayList<>();
    final List<Channel> recommendationChannels = new ArrayList<>();
    boolean providerAvailable;
    String channelCapability = "unavailable", watchNextCapability = "unavailable";
    String channelStatus = "Your apps have not shared preview channels.";
    boolean usageEnabled, listingsGranted;
    String programStatus = "No shared Continue Watching items yet.";
  }

  static boolean usageAllowed(Context c) {
    AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
    return ops != null
        && ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
            == AppOpsManager.MODE_ALLOWED;
  }

  static Snapshot load(Context c) {
    return load(c, null);
  }

  static Snapshot load(Context c, android.os.CancellationSignal signal) {
    return load(c, signal, true);
  }

  // The capability probe reads the actual app UID's inventory without asking publishers to write.
  static Snapshot load(
      Context c, android.os.CancellationSignal signal, boolean initializePrograms) {
    Snapshot result = new Snapshot();
    PackageManager pm = c.getPackageManager();
    android.content.SharedPreferences history =
        c.getSharedPreferences("history", Context.MODE_PRIVATE);
    result.usageEnabled = usageAllowed(c);
    Map<String, Long> usage = new HashMap<>();
    if (result.usageEnabled)
      try {
        UsageStatsManager manager =
            (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        List<UsageStats> stats =
            manager.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST,
                System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000,
                System.currentTimeMillis());
        if (stats != null)
          for (UsageStats stat : stats)
            usage.put(
                stat.getPackageName(),
                Math.max(usage.getOrDefault(stat.getPackageName(), 0L), stat.getLastTimeUsed()));
      } catch (SecurityException ignored) {
        result.usageEnabled = false;
      }
    for (int i = 0; i < Policy.PACKAGES.length; i++) {
      String id = Policy.PACKAGES[i];
      Intent launch = pm.getLeanbackLaunchIntentForPackage(id);
      App app = new App(id, Policy.LABELS[i], launch);
      app.recent = Math.max(history.getLong(id, 0L), usage.getOrDefault(id, 0L));
      try {
        app.banner = pm.getApplicationBanner(id);
        if (app.banner == null) app.banner = pm.getApplicationIcon(id);
      } catch (PackageManager.NameNotFoundException ignored) {
      }
      result.apps.add(app);
    }
    result.apps.sort((a, b) -> Policy.compareRecent(a.recent, b.recent, a.label, b.label));
    Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
    Set<String> found = new HashSet<>();
    for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
      if (info.activityInfo == null
          || !info.activityInfo.exported
          || !found.add(info.activityInfo.packageName)) continue;
      String id = info.activityInfo.packageName;
      if (!Policy.safeUserApp(id, info.activityInfo.name, info.loadLabel(pm).toString())) continue;
      Intent launch =
          new Intent(Intent.ACTION_MAIN)
              .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
              .setComponent(new ComponentName(id, info.activityInfo.name));
      App app = new App(id, info.loadLabel(pm).toString(), launch);
      try {
        app.icon = info.loadIcon(pm);
      } catch (RuntimeException ignored) {
      }
      try {
        app.banner = info.activityInfo.loadBanner(pm);
        if (app.banner == null) app.banner = info.loadIcon(pm);
      } catch (RuntimeException ignored) {
      }
      result.allApps.add(app);
    }
    result.allApps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
    result.listingsGranted =
        c.checkSelfPermission("android.permission.READ_TV_LISTINGS")
            == PackageManager.PERMISSION_GRANTED;
    if (initializePrograms) RecommendationInitialization.initialize(c, result.allApps, signal);
    Recommendations.load(c, result, signal);
    return result;
  }

  static Intent validatedResume(Context c, String raw, String owner) {
    return Recommendations.validatedLink(c, raw, owner);
  }
}
