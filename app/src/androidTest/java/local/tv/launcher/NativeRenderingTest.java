package local.tv.launcher;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Frozen native-only workload. Timings describe this device/rendering pipeline; they are not a
 * WebView comparison or a production-TV performance guarantee.
 */
@RunWith(AndroidJUnit4.class)
public class NativeRenderingTest {
  private static final String TAG = "NativeRendering";
  private static final int MOVEMENTS = 60;
  private static final long EVENT_INTERVAL_MS = 80;
  private static final long ANIMATION_TAIL_MS = 250;
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Object metricsLock = new Object();
  private final List<Sample> samples = new ArrayList<>();
  private NativeUiTestActivity activity;
  private RecyclerView providerRow;

  private static final class Frame {
    final long total, layout, deadline;

    Frame(long total, long layout, long deadline) {
      this.total = total;
      this.layout = layout;
      this.deadline = deadline;
    }
  }

  private static final class Sample {
    final String name;
    final boolean warmup;
    final long startNs = System.nanoTime();
    long endNs = Long.MAX_VALUE;
    int completed, firstDrawExcluded, invalidMetrics, droppedReports;
    final List<Frame> frames = new ArrayList<>();
    final List<Long> dispatchTimes = new ArrayList<>();

    Sample(String name, boolean warmup) {
      this.name = name;
      this.warmup = warmup;
    }
  }

  @Test
  public void providerPosterDpadWorkloadReportsNativeFrameDurations() throws Throwable {
    HandlerThread collector = new HandlerThread("native-frame-metrics");
    collector.start();
    Handler metricsHandler = new Handler(collector.getLooper());
    Window.OnFrameMetricsAvailableListener listener = this::recordFrame;
    Throwable failure = null;
    JSONObject context = new JSONObject();
    try {
      Intent intent =
          new Intent(instrumentation.getTargetContext(), NativeUiTestActivity.class)
              .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (NativeUiTestActivity) instrumentation.startActivitySync(intent);
      instrumentation.waitForIdleSync();
      instrumentation.runOnMainSync(
          () -> activity.getWindow().addOnFrameMetricsAvailableListener(listener, metricsHandler));
      context = deviceContext();
      prepareProviderRow();
      runSample("warmup", true);
      for (int i = 1; i <= 3; i++) runSample("sample_" + i, false);
    } catch (Throwable error) {
      failure = error;
    } finally {
      if (activity != null) {
        instrumentation.runOnMainSync(
            () -> activity.getWindow().removeOnFrameMetricsAvailableListener(listener));
      }
      CountDownLatch drained = new CountDownLatch(1);
      metricsHandler.post(drained::countDown);
      boolean collectorDrained = drained.await(3, TimeUnit.SECONDS);
      if (!collectorDrained && failure == null)
        failure = new AssertionError("Frame-metrics callback queue did not drain");
      if (failure == null) {
        try {
          synchronized (metricsLock) {
            assertEquals(4, samples.size());
            for (Sample sample : samples) {
              assertEquals(sample.name + " incomplete work", MOVEMENTS, sample.completed);
              assertTrue(
                  sample.name + " captured no usable frame metrics", !sample.frames.isEmpty());
            }
          }
        } catch (AssertionError error) {
          failure = error;
        }
      }
      JSONObject summary = summary(context, collectorDrained, failure);
      // Keep each logcat record below its line limit; instrumentation status retains the full JSON.
      Log.i(TAG, new JSONObject().put("event", "context").put("context", context).toString());
      JSONArray records = summary.getJSONArray("samples");
      for (int i = 0; i < records.length(); i++) Log.i(TAG, records.getJSONObject(i).toString());
      Log.i(
          TAG,
          new JSONObject()
              .put("event", "completed")
              .put("workload", summary.getString("workload"))
              .put("errors", summary.getJSONArray("errors"))
              .put("collectorDrained", collectorDrained)
              .toString());
      Bundle status = new Bundle();
      status.putString("nativeRendering", summary.toString());
      instrumentation.sendStatus(0, status);
      collector.quitSafely();
      collector.join(3000);
      if (activity != null) instrumentation.runOnMainSync(activity::finish);
    }
    if (failure != null) throw failure;
  }

  private void prepareProviderRow() {
    instrumentation.runOnMainSync(
        () -> {
          activity.home.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
          RecyclerView vertical = activity.home.findViewById(R.id.home_rows);
          NativeRowAdapter rows = (NativeRowAdapter) vertical.getAdapter();
          assertEquals("recommendations", rows.getCurrentList().get(2).id);
          assertEquals(NativeCard.Kind.RECOMMENDATION, rows.getCurrentList().get(2).kind);
          assertEquals(24, rows.getCurrentList().get(2).cards.size());
        });
    providerRow = NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    SystemClock.sleep(400);
    instrumentation.waitForIdleSync();
    assertFocusedPosition(0);
  }

  private void runSample(String name, boolean warmup) {
    assertFocusedPosition(0);
    Sample sample = new Sample(name, warmup);
    synchronized (metricsLock) {
      samples.add(sample);
    }
    int expected = 0;
    long nextDispatch = SystemClock.uptimeMillis();
    try {
      for (int movement = 0; movement < MOVEMENTS; movement++) {
        long remaining = nextDispatch - SystemClock.uptimeMillis();
        if (remaining > 0) SystemClock.sleep(remaining);
        long dispatch = SystemClock.uptimeMillis();
        sample.dispatchTimes.add(System.nanoTime());
        boolean right = movement % 20 < 10;
        String before = assertFocusedPosition(expected);
        expected += right ? 1 : -1;
        instrumentation.sendKeyDownUpSync(
            right ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
        instrumentation.waitForIdleSync();
        String after = assertFocusedPosition(expected);
        assertNotEquals(
            "Actual focused identity must change rather than clamp at an edge", before, after);
        sample.completed++;
        nextDispatch = dispatch + EVENT_INTERVAL_MS;
      }
      SystemClock.sleep(ANIMATION_TAIL_MS);
      instrumentation.waitForIdleSync();
      assertFocusedPosition(0);
      assertEquals(
          "No fixture content launches are part of this workload", 0, activity.resumes.size());
      assertEquals("No app launches are part of this workload", 0, activity.launches.size());
    } finally {
      synchronized (metricsLock) {
        sample.endNs = System.nanoTime();
      }
    }
  }

  private String assertFocusedPosition(int expected) {
    AtomicReference<String> identity = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          assertNotNull("Provider row must exist", providerRow);
          View focus = activity.home.findFocus();
          assertNotNull("Each D-pad movement must leave actual input focus", focus);
          RecyclerView.ViewHolder holder = providerRow.findContainingViewHolder(focus);
          assertTrue(
              "Focus must remain in the provider poster row",
              holder instanceof NativeCardAdapter.Holder);
          NativeCardAdapter.Holder card = (NativeCardAdapter.Holder) holder;
          assertEquals(
              "One actual card movement per key event", expected, card.getBindingAdapterPosition());
          assertNotNull(card.card);
          assertEquals(
              "Correct provider identity must receive focus",
              "published:" + expected,
              card.card.id);
          assertEquals(
              "Stable ID must match focused provider identity",
              Policy.stableId("published:" + expected),
              card.getItemId());
          identity.set(card.card.id);
        });
    return identity.get();
  }

  private void recordFrame(Window window, FrameMetrics metrics, int reportsDropped) {
    // Read reused FrameMetrics synchronously; retain only primitive values on this dedicated
    // thread.
    long timestamp = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
    if (timestamp <= 0) timestamp = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP);
    long total = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
    long layout = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION);
    long deadline = Build.VERSION.SDK_INT >= 31 ? metrics.getMetric(FrameMetrics.DEADLINE) : -1;
    boolean firstDraw = metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1;
    synchronized (metricsLock) {
      for (int i = samples.size() - 1; i >= 0; i--) {
        Sample sample = samples.get(i);
        if (timestamp < sample.startNs || timestamp > sample.endNs) continue;
        sample.droppedReports += reportsDropped;
        if (firstDraw) sample.firstDrawExcluded++;
        else if (total < 0 || layout < 0) sample.invalidMetrics++;
        else sample.frames.add(new Frame(total, layout, deadline));
        break;
      }
    }
  }

  private JSONObject deviceContext() throws Exception {
    AtomicReference<JSONObject> result = new AtomicReference<>();
    instrumentation.runOnMainSync(
        () -> {
          try {
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            Display display = activity.getWindow().getDecorView().getDisplay();
            result.set(
                new JSONObject()
                    .put("sdk", Build.VERSION.SDK_INT)
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("hardware", Build.HARDWARE)
                    .put("fingerprint", Build.FINGERPRINT)
                    .put("abis", new JSONArray(java.util.Arrays.asList(Build.SUPPORTED_ABIS)))
                    .put("windowWidthPx", activity.getWindow().getDecorView().getWidth())
                    .put("windowHeightPx", activity.getWindow().getDecorView().getHeight())
                    .put("densityDpi", metrics.densityDpi)
                    .put("refreshRateHz", display.getRefreshRate())
                    .put("displayWidthPx", display.getMode().getPhysicalWidth())
                    .put("displayHeightPx", display.getMode().getPhysicalHeight())
                    .put(
                        "hardwareAcceleratedWindow",
                        activity.getWindow().getDecorView().isHardwareAccelerated())
                    .put("animatorsEnabled", ValueAnimator.areAnimatorsEnabled())
                    .put(
                        "animatorDurationScale",
                        Settings.Global.getFloat(
                            activity.getContentResolver(),
                            Settings.Global.ANIMATOR_DURATION_SCALE,
                            1)));
          } catch (Exception error) {
            throw new AssertionError(error);
          }
        });
    return result.get();
  }

  private JSONObject summary(JSONObject context, boolean drained, Throwable failure)
      throws Exception {
    JSONObject result =
        new JSONObject()
            .put("schemaVersion", 1)
            .put("buildUnderTest", "unminified fixture verification")
            .put(
                "measurementScope",
                "Observed fixture/device only; emulator timings do not establish TV smoothness")
            .put(
                "validationOverhead",
                "Actual focus identity is checked on the main thread for each real key event")
            .put("workload", "provider_poster_zigzag_v1")
            .put("context", context)
            .put("fixtureProviderCards", 24)
            .put("warmupSamples", 1)
            .put("measuredSamples", 3)
            .put("movementsPerSample", MOVEMENTS)
            .put("nominalEventIntervalMs", EVENT_INTERVAL_MS)
            .put("animationTailMs", ANIMATION_TAIL_MS)
            .put("collectorDrained", drained)
            .put("pipeline", "native Android; no cross-pipeline before/after comparison")
            .put(
                "metricReference",
                "https://developer.android.com/reference/android/view/FrameMetrics")
            .put("errors", new JSONArray());
    JSONArray output = new JSONArray();
    synchronized (metricsLock) {
      for (Sample sample : samples) {
        List<Long> total = new ArrayList<>(),
            layout = new ArrayList<>(),
            deadlines = new ArrayList<>(),
            cadence = new ArrayList<>();
        int misses = 0;
        for (Frame frame : sample.frames) {
          total.add(frame.total);
          layout.add(frame.layout);
          if (frame.deadline > 0) {
            deadlines.add(frame.deadline);
            if (frame.total >= frame.deadline) misses++;
          }
        }
        for (int i = 1; i < sample.dispatchTimes.size(); i++)
          cadence.add(sample.dispatchTimes.get(i) - sample.dispatchTimes.get(i - 1));
        output.put(
            new JSONObject()
                .put("name", sample.name)
                .put("warmup", sample.warmup)
                .put("completedMovements", sample.completed)
                .put("expectedFinalPosition", 0)
                .put("workloadComplete", sample.completed == MOVEMENTS)
                .put("wallTimeMs", (sample.endNs - sample.startNs) / 1_000_000d)
                .put("frameCount", sample.frames.size())
                .put("firstDrawExcluded", sample.firstDrawExcluded)
                .put("invalidMetricsExcluded", sample.invalidMetrics)
                .put("droppedMetricReports", sample.droppedReports)
                .put("metricReportsComplete", sample.droppedReports == 0)
                .put("totalDurationMs", distribution(total))
                .put("layoutMeasureDurationMs", distribution(layout))
                .put("frameDeadlineMs", distribution(deadlines))
                .put("deadlineSamples", deadlines.size())
                .put("deadlineMisses", misses)
                .put("actualDispatchIntervalMs", distribution(cadence)));
      }
    }
    result.put("samples", output);
    if (failure != null) result.getJSONArray("errors").put(failure.toString());
    return result;
  }

  private static JSONObject distribution(List<Long> values) throws Exception {
    if (values.isEmpty())
      return new JSONObject()
          .put("count", 0)
          .put("median", JSONObject.NULL)
          .put("p95", JSONObject.NULL)
          .put("max", JSONObject.NULL);
    Collections.sort(values);
    int count = values.size();
    double median =
        count % 2 == 0
            ? (values.get(count / 2 - 1) / 2d + values.get(count / 2) / 2d)
            : values.get(count / 2);
    return new JSONObject()
        .put("count", count)
        .put("median", median / 1_000_000d)
        .put("p95", values.get((int) Math.ceil(count * .95) - 1) / 1_000_000d)
        .put("max", values.get(count - 1) / 1_000_000d);
  }
}
