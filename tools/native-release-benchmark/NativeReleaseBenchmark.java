package local.tv.launcher.benchmark;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Framework-only instrumentation of the actual production APK; no app classes or test libraries.
 */
public final class NativeReleaseBenchmark extends Instrumentation {
  private static final String HEADING = "Discover with Cinemeta";
  private final List<Sample> samples = new ArrayList<>();
  private final List<String> identities = new ArrayList<>();
  private Bundle arguments;
  private Activity activity;
  private String lastFocusDiagnostic = "No focus probe attempted";

  private static final class Blocked extends Exception {
    Blocked(String message) {
      super(message);
    }
  }

  private static final class Focus {
    final String description;
    final int position, count;

    Focus(String description, int position, int count) {
      this.description = description;
      this.position = position;
      this.count = count;
    }
  }

  private static final class Sample {
    final String name;
    final long start = System.nanoTime();
    long end = Long.MAX_VALUE;
    int completed, firstDraw, dropped, invalid;
    final List<Long> total = new ArrayList<>(),
        layout = new ArrayList<>(),
        unknownDelay = new ArrayList<>(),
        draw = new ArrayList<>(),
        sync = new ArrayList<>(),
        gpu = new ArrayList<>(),
        deadline = new ArrayList<>(),
        cadence = new ArrayList<>();
    int misses;

    Sample(String name) {
      this.name = name;
    }
  }

  @Override
  public void onCreate(Bundle arguments) {
    this.arguments = arguments == null ? new Bundle() : arguments;
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    JSONObject result = new JSONObject();
    HandlerThread thread = new HandlerThread("release-frame-metrics");
    thread.start();
    Handler handler = new Handler(thread.getLooper());
    android.view.Window.OnFrameMetricsAvailableListener listener =
        (window, frame, dropped) -> record(frame, dropped);
    String status = "complete", error = "";
    boolean drained = false;
    try {
      result
          .put("schemaVersion", 1)
          .put("workload", "production_discovery_zigzag_v1")
          .put("apkSha256", arguments.getString("apkSha256", ""))
          .put("versionName", arguments.getString("versionName", ""))
          .put("versionCode", arguments.getString("versionCode", ""))
          .put(
              "context",
              new JSONObject(
                  new String(
                      Base64.decode(arguments.getString("contextBase64", "e30="), Base64.DEFAULT),
                      StandardCharsets.UTF_8)))
          .put(
              "cachePolicy",
              "Existing production one-hour catalog cache; not read, cleared, seeded or modified by"
                  + " this runner")
          .put(
              "limitations",
              "Observed production APK on this emulator only; live catalog/artwork and"
                  + " instrumentation overhead limit reproducibility; no TV smoothness or WebView"
                  + " comparison claim");
      connectAccessibilityProbe();
      activity =
          startActivitySync(
              new Intent(Intent.ACTION_MAIN)
                  .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                  .setComponent(
                      new ComponentName("local.tv.launcher", "local.tv.launcher.MainActivity"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      runOnMainSync(
          () -> activity.getWindow().addOnFrameMetricsAvailableListener(listener, handler));
      deviceContext(result);
      if (!result.getJSONObject("context").getBoolean("animatorsEnabled")) {
        throw new Blocked(
            "Focus-animation workload requires effective animators enabled; inspect"
                + " context.animatorsEnabled and context.animatorDurationScale");
      }
      findDiscovery();
      Focus initial = focus();
      if (initial.count != 24)
        throw new Blocked("Expected frozen 24-card discovery dataset, observed " + initial.count);
      for (int i = 0; initial.position > 0 && i < 24; i++) {
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
        waitForIdleSync();
        initial = focus();
      }
      require(initial.position == 0, "Could not stabilize first discovery card");
      identities.add(initial.description);
      SystemClock.sleep(400);
      runSample("warmup", true);
      for (int i = 1; i <= 3; i++) runSample("sample_" + i, false);
    } catch (Blocked blocked) {
      status = "blocked";
      error = blocked.getMessage();
    } catch (Throwable failed) {
      status = "failed";
      error = failed.toString();
    } finally {
      try {
        if (activity != null)
          runOnMainSync(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
        CountDownLatch barrier = new CountDownLatch(1);
        handler.post(barrier::countDown);
        drained = barrier.await(3, TimeUnit.SECONDS);
        if (!drained && error.isEmpty()) {
          status = "failed";
          error = "Frame collector did not drain";
        }
        JSONArray output = new JSONArray();
        int measured = 0, warmup = 0;
        synchronized (samples) {
          for (Sample sample : samples) {
            if (sample.name.equals("warmup")) warmup += sample.completed;
            else measured += sample.completed;
            if (sample.total.isEmpty() && error.isEmpty()) {
              status = "blocked";
              error = "No usable frame metrics captured";
            }
            JSONObject entry =
                new JSONObject()
                    .put("name", sample.name)
                    .put("completedMovements", sample.completed)
                    .put("wallTimeMs", (sample.end - sample.start) / 1e6)
                    .put("frameCount", sample.total.size())
                    .put("firstDrawExcluded", sample.firstDraw)
                    .put("invalidMetricsExcluded", sample.invalid)
                    .put("droppedMetricReports", sample.dropped)
                    .put("totalDurationMs", distribution(sample.total))
                    .put("layoutMeasureDurationMs", distribution(sample.layout))
                    .put("unknownDelayDurationMs", distribution(sample.unknownDelay))
                    .put("drawDurationMs", distribution(sample.draw))
                    .put("syncDurationMs", distribution(sample.sync))
                    .put("gpuDurationMs", distribution(sample.gpu))
                    .put("frameDeadlineMs", distribution(sample.deadline))
                    .put("deadlineMisses", sample.misses)
                    .put("deadlineSamples", sample.deadline.size())
                    .put("actualDispatchIntervalMs", distribution(sample.cadence));
            output.put(entry);
            Log.i("NativeReleaseBenchmark", entry.toString());
          }
        }
        boolean success =
            error.isEmpty() && drained && warmup == 60 && measured == 180 && output.length() == 4;
        result
            .put("status", status)
            .put("success", success)
            .put("error", error)
            .put("collectorDrained", drained)
            .put("warmupCompletedMovements", warmup)
            .put("measuredCompletedMovements", measured)
            .put("nominalEventIntervalMs", 80)
            .put("animationTailMs", 250)
            .put("frozenFocusDescriptions", new JSONArray(identities))
            .put("samples", output);
        Bundle summary = new Bundle();
        summary.putString("nativeReleaseBenchmark", result.toString());
        finish(success ? Activity.RESULT_OK : Activity.RESULT_CANCELED, summary);
      } catch (Throwable reportingFailure) {
        Bundle summary = new Bundle();
        summary.putString(
            "nativeReleaseBenchmark",
            "{\"success\":false,\"status\":\"failed\",\"error\":\"Result reporting failed\"}");
        finish(Activity.RESULT_CANCELED, summary);
      } finally {
        thread.quitSafely();
      }
    }
  }

  private void connectAccessibilityProbe() throws Exception {
    // RecyclerView installs item accessibility delegates while binding only when accessibility
    // is enabled. Connect before Activity creation so its public item metadata is available.
    getUiAutomation();
    boolean[] enabled = new boolean[1];
    long deadline = SystemClock.uptimeMillis() + 3000;
    do {
      runOnMainSync(
          () -> {
            AccessibilityManager manager =
                getTargetContext().getSystemService(AccessibilityManager.class);
            enabled[0] = manager != null && manager.isEnabled();
          });
      if (enabled[0]) return;
      SystemClock.sleep(25);
    } while (SystemClock.uptimeMillis() < deadline);
    throw new Blocked("UiAutomation accessibility probe did not enable before Activity creation");
  }

  private void findDiscovery() throws Exception {
    long until = SystemClock.uptimeMillis() + 30000;
    while (SystemClock.uptimeMillis() < until) {
      try {
        if (focus() != null) return;
      } catch (Blocked unavailable) {
      }
      sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
      SystemClock.sleep(150);
      waitForIdleSync();
    }
    throw new Blocked(
        "No focusable discovery row within 30 seconds; last public probe: " + lastFocusDiagnostic);
  }

  private Focus focus() throws Exception {
    Focus[] found = new Focus[1];
    runOnMainSync(
        () -> {
          require(
              !activity.isFinishing() && activity.getWindow().getDecorView().hasWindowFocus(),
              "Production Activity must retain window focus; no external launches allowed");
          View card = activity.getCurrentFocus();
          lastFocusDiagnostic =
              card == null
                  ? "Activity has no focused view"
                  : "Focused " + card.getClass().getName() + ": " + card.getContentDescription();
          while (card != null && card.getParent() instanceof ViewGroup) {
            ViewGroup row = (ViewGroup) card.getParent();
            if (row.getParent() instanceof LinearLayout) {
              LinearLayout section = (LinearLayout) row.getParent();
              if (section.getChildCount() > 0
                  && section.getChildAt(0) instanceof TextView
                  && HEADING.contentEquals(((TextView) section.getChildAt(0)).getText())) {
                AccessibilityNodeInfo.CollectionInfo collection =
                    row.createAccessibilityNodeInfo().getCollectionInfo();
                AccessibilityNodeInfo.CollectionItemInfo item =
                    card.createAccessibilityNodeInfo().getCollectionItemInfo();
                CharSequence description = card.getContentDescription();
                lastFocusDiagnostic =
                    "Discovery focus="
                        + description
                        + ", collectionMetadata="
                        + (collection != null)
                        + ", itemMetadata="
                        + (item != null)
                        + ", columns="
                        + (collection == null ? -1 : collection.getColumnCount());
                if (collection != null
                    && item != null
                    && description != null
                    && description.length() > 0)
                  found[0] =
                      new Focus(
                          description.toString(),
                          item.getColumnIndex(),
                          collection.getColumnCount());
                break;
              }
            }
            card = row;
          }
        });
    if (found[0] == null)
      throw new Blocked("Discovery public focus metadata unavailable: " + lastFocusDiagnostic);
    return found[0];
  }

  private void runSample(String name, boolean learn) throws Exception {
    Focus first = focus();
    require(
        first.position == 0 && first.description.equals(identities.get(0)),
        "Frozen catalog first identity changed");
    Sample sample = new Sample(name);
    synchronized (samples) {
      samples.add(sample);
    }
    int position = 0;
    long next = SystemClock.uptimeMillis(), previous = 0;
    try {
      for (int step = 0; step < 60; step++) {
        long pause = next - SystemClock.uptimeMillis();
        if (pause > 0) SystemClock.sleep(pause);
        long now = SystemClock.uptimeMillis(), nanos = System.nanoTime();
        if (previous != 0) sample.cadence.add(nanos - previous);
        previous = nanos;
        Focus before = focus();
        require(
            before.position == position && before.description.equals(identities.get(position)),
            "Focus changed outside frozen workload");
        boolean right = step % 20 < 10;
        position += right ? 1 : -1;
        sendKeyDownUpSync(right ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
        waitForIdleSync();
        Focus after = focus();
        require(
            after.count == 24
                && after.position == position
                && !after.description.equals(before.description),
            "D-pad movement failed, clamped, or changed dataset");
        if (learn && step < 10) {
          if (identities.contains(after.description))
            throw new Blocked(
                "Discovery descriptions are not unique; public focus identity cannot be frozen");
          identities.add(after.description);
        } else
          require(
              after.description.equals(identities.get(position)),
              "Forward/reverse focus sequence diverged from warmup");
        sample.completed++;
        next = now + 80;
      }
      SystemClock.sleep(250);
      waitForIdleSync();
      require(focus().position == 0, "Workload did not return to its first card");
    } finally {
      synchronized (samples) {
        sample.end = System.nanoTime();
      }
    }
  }

  private void record(FrameMetrics frame, int dropped) {
    long timestamp = frame.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
    synchronized (samples) {
      for (int i = samples.size() - 1; i >= 0; i--) {
        Sample sample = samples.get(i);
        if (timestamp < sample.start || timestamp > sample.end) continue;
        sample.dropped += dropped;
        long total = frame.getMetric(FrameMetrics.TOTAL_DURATION),
            layout = frame.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION);
        if (frame.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) sample.firstDraw++;
        else if (total < 0 || layout < 0) sample.invalid++;
        else {
          sample.total.add(total);
          sample.layout.add(layout);
          addSupported(sample.unknownDelay, frame.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION));
          addSupported(sample.draw, frame.getMetric(FrameMetrics.DRAW_DURATION));
          addSupported(sample.sync, frame.getMetric(FrameMetrics.SYNC_DURATION));
          addSupported(sample.gpu, frame.getMetric(FrameMetrics.GPU_DURATION));
          long deadline = frame.getMetric(FrameMetrics.DEADLINE);
          if (deadline > 0) {
            sample.deadline.add(deadline);
            if (total >= deadline) sample.misses++;
          }
        }
        break;
      }
    }
  }

  private void deviceContext(JSONObject result) throws Exception {
    JSONObject context = result.getJSONObject("context");
    runOnMainSync(
        () -> {
          try {
            View decor = activity.getWindow().getDecorView();
            context
                .put("sdk", Build.VERSION.SDK_INT)
                .put("model", Build.MODEL)
                .put("hardware", Build.HARDWARE)
                .put("fingerprint", Build.FINGERPRINT)
                .put("windowWidthPx", decor.getWidth())
                .put("windowHeightPx", decor.getHeight())
                .put("refreshRateHz", decor.getDisplay().getRefreshRate())
                .put("hardwareAcceleratedWindow", decor.isHardwareAccelerated())
                .put("animatorsEnabled", ValueAnimator.areAnimatorsEnabled())
                .put(
                    "accessibilityProbe",
                    "UiAutomation connected before Activity creation for public collection"
                        + " metadata")
                .put(
                    "accessibilityEnabled",
                    activity.getSystemService(AccessibilityManager.class).isEnabled())
                .put(
                    "animatorDurationScale",
                    Settings.Global.getFloat(
                        activity.getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE,
                        1f));
          } catch (Exception failed) {
            throw new AssertionError(failed);
          }
        });
  }

  private static void require(boolean valid, String message) {
    if (!valid) throw new AssertionError(message);
  }

  private static void addSupported(List<Long> values, long duration) {
    if (duration >= 0) values.add(duration);
  }

  private static JSONObject distribution(List<Long> values) throws Exception {
    if (values.isEmpty())
      return new JSONObject()
          .put("count", 0)
          .put("median", JSONObject.NULL)
          .put("p95", JSONObject.NULL)
          .put("max", JSONObject.NULL);
    List<Long> sorted = new ArrayList<>(values);
    Collections.sort(sorted);
    int n = sorted.size();
    double median =
        n % 2 == 0 ? sorted.get(n / 2 - 1) / 2d + sorted.get(n / 2) / 2d : sorted.get(n / 2);
    return new JSONObject()
        .put("count", n)
        .put("median", median / 1e6)
        .put("p95", sorted.get((int) Math.ceil(n * .95) - 1) / 1e6)
        .put("max", sorted.get(n - 1) / 1e6);
  }
}
