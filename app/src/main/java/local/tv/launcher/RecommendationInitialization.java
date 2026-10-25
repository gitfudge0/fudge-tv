package local.tv.launcher;

import android.content.*;
import android.content.pm.*;
import android.media.tv.TvContract;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.util.*;

/** Requests publisher-owned default channels using Android's documented home-screen broadcast. */
final class RecommendationInitialization {
  private static final Object LOCK = new Object();

  interface Source {
    PackageInfo packageInfo(String owner) throws PackageManager.NameNotFoundException;

    List<ResolveInfo> receivers(Intent intent);

    boolean permitted(String permission);

    void send(Intent intent);
  }

  private RecommendationInitialization() {}

  static void initialize(Context context, List<Library.App> apps, CancellationSignal signal) {
    PackageManager pm = context.getPackageManager();
    initialize(
        context,
        apps,
        signal,
        new Source() {
          public PackageInfo packageInfo(String owner) throws PackageManager.NameNotFoundException {
            return pm.getPackageInfo(owner, 0);
          }

          public List<ResolveInfo> receivers(Intent intent) {
            return pm.queryBroadcastReceivers(intent, 0);
          }

          public boolean permitted(String permission) {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
          }

          public void send(Intent intent) {
            context.sendBroadcast(intent);
          }
        });
  }

  static void initialize(
      Context context, List<Library.App> apps, CancellationSignal signal, Source source) {
    synchronized (LOCK) {
      if (!context
          .getSharedPreferences("recommendations", Context.MODE_PRIVATE)
          .getBoolean("enabled", true)) return;
      SharedPreferences state =
          context.getSharedPreferences("recommendation_initialization", Context.MODE_PRIVATE);
      Set<String> seen = new HashSet<>();
      for (Library.App app : apps) {
        check(signal);
        if (app == null || app.id == null || !seen.add(app.id)) continue;
        try {
          PackageInfo info = source.packageInfo(app.id);
          String revision = info.getLongVersionCode() + ":" + info.lastUpdateTime;
          Intent request = new Intent(TvContract.ACTION_INITIALIZE_PROGRAMS).setPackage(app.id);
          List<ResolveInfo> receivers = source.receivers(request);
          if (receivers == null) continue;
          for (ResolveInfo resolved : receivers) {
            check(signal);
            try {
              ActivityInfo receiver = resolved == null ? null : resolved.activityInfo;
              if (receiver == null
                  || !app.id.equals(receiver.packageName)
                  || receiver.name == null
                  || !receiver.exported
                  || !receiver.enabled
                  || receiver.applicationInfo == null
                  || !receiver.applicationInfo.enabled
                  || (receiver.permission != null
                      && !receiver.permission.isEmpty()
                      && !source.permitted(receiver.permission))) continue;
              ComponentName component = new ComponentName(receiver.packageName, receiver.name);
              String key = component.flattenToString();
              if (revision.equals(state.getString(key, ""))) continue;
              source.send(new Intent(request).setComponent(component));
              // Persist each successful dispatch independently: later receiver failures can retry.
              state.edit().putString(key, revision).commit();
            } catch (OperationCanceledException canceled) {
              throw canceled;
            } catch (RuntimeException unavailable) {
              // One inaccessible or removed receiver must not block another publisher.
            }
          }
        } catch (OperationCanceledException canceled) {
          throw canceled;
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
          // Package inventory can change while loading recommendations.
        }
      }
    }
  }

  private static void check(CancellationSignal signal) {
    if (signal != null) signal.throwIfCanceled();
  }
}
