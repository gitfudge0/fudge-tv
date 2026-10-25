package local.tv.launcher;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NativeLifecycleTest {
  @Test
  public void pendingSubmittedSearchIsCanceledOnPauseAndRetriedOnResume() throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    NativeLifecycleTestActivity.Fixture fixture = new NativeLifecycleTestActivity.Fixture();
    NativeLifecycleTestActivity.fixture = fixture;
    Intent intent =
        new Intent()
            .setClassName(
                instrumentation.getTargetContext().getPackageName(),
                NativeLifecycleTestActivity.class.getName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    NativeLifecycleTestActivity activity =
        (NativeLifecycleTestActivity) instrumentation.startActivitySync(intent);
    try {
      instrumentation.runOnMainSync(() -> activity.onSearch("Fixture search"));
      assertTrue(fixture.firstStarted.await(3, TimeUnit.SECONDS));
      instrumentation.runOnMainSync(() -> instrumentation.callActivityOnPause(activity));
      assertTrue(fixture.firstCanceled.await(3, TimeUnit.SECONDS));
      instrumentation.runOnMainSync(() -> instrumentation.callActivityOnResume(activity));
      assertTrue(fixture.resumedSearch.await(3, TimeUnit.SECONDS));
      instrumentation.waitForIdleSync();
    } finally {
      instrumentation.runOnMainSync(activity::finish);
      instrumentation.waitForIdleSync();
      NativeLifecycleTestActivity.fixture = null;
    }
  }

  @Test
  public void appsAndWatchArriveBeforeBlockedPreviewsAndRefreshChurnDoesNotCancelLoad()
      throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    NativeLifecycleTestActivity.Fixture fixture = new NativeLifecycleTestActivity.Fixture();
    fixture.stagedLibrary = true;
    NativeLifecycleTestActivity.fixture = fixture;
    NativeLifecycleTestActivity activity =
        (NativeLifecycleTestActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), NativeLifecycleTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      assertTrue(fixture.libraryStarted.await(3, TimeUnit.SECONDS));
      awaitRows(instrumentation, activity, 7, 0, 0);
      long focusDeadline = android.os.SystemClock.uptimeMillis() + 3000;
      java.util.concurrent.atomic.AtomicBoolean appFocused =
          new java.util.concurrent.atomic.AtomicBoolean();
      do {
        instrumentation.runOnMainSync(
            () ->
                appFocused.set(
                    "app:fixture.app0"
                        .equals(
                            NativeUiTestDriver.identity(
                                activity.findViewById(R.id.native_home).findFocus()))));
        if (appFocused.get()) break;
        android.os.SystemClock.sleep(16);
      } while (android.os.SystemClock.uptimeMillis() < focusDeadline);
      assertTrue(
          "Cold-start apps must receive focus before the blocked provider, without jumping to"
              + " inputs",
          appFocused.get());
      instrumentation.runOnMainSync(
          () -> {
            for (int i = 0; i < 20; i++) activity.onRefresh();
          });
      assertEquals(
          "Refresh signals must not interrupt the in-flight provider",
          1,
          fixture.libraryLoads.get());
      fixture.releaseWatch.countDown();
      assertTrue(fixture.watchReady.await(3, TimeUnit.SECONDS));
      awaitRows(instrumentation, activity, 7, 12, 0);
      fixture.releasePreviews.countDown();
      assertTrue(
          "Signals coalesce into a trailing refresh",
          fixture.secondLibrary.await(3, TimeUnit.SECONDS));
      awaitRows(instrumentation, activity, 7, 12, 24);
      instrumentation.runOnMainSync(
          () -> {
            NativeRowAdapter rows =
                (NativeRowAdapter)
                    ((androidx.recyclerview.widget.RecyclerView)
                            activity.findViewById(R.id.home_rows))
                        .getAdapter();
            assertEquals(
                "Previously delivered provider rows remain visible during refresh",
                24,
                rows.getCurrentList().get(2).cards.size());
          });
      assertEquals("One bounded rerun for all pending signals", 2, fixture.libraryLoads.get());
      fixture.releaseSecondLibrary.countDown();
    } finally {
      fixture.releaseWatch.countDown();
      fixture.releasePreviews.countDown();
      fixture.releaseSecondLibrary.countDown();
      instrumentation.runOnMainSync(activity::finish);
      instrumentation.waitForIdleSync();
      NativeLifecycleTestActivity.fixture = null;
    }
  }

  @Test
  public void pausedProviderLoadIsCanceledAndResumeDeliversANewGeneration() throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    NativeLifecycleTestActivity.Fixture fixture = new NativeLifecycleTestActivity.Fixture();
    fixture.stagedLibrary = true;
    NativeLifecycleTestActivity.fixture = fixture;
    NativeLifecycleTestActivity activity =
        (NativeLifecycleTestActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), NativeLifecycleTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      assertTrue(fixture.libraryStarted.await(3, TimeUnit.SECONDS));
      awaitRows(instrumentation, activity, 7, 0, 0);
      instrumentation.runOnMainSync(() -> instrumentation.callActivityOnPause(activity));
      assertTrue(fixture.libraryCanceled.await(3, TimeUnit.SECONDS));
      instrumentation.runOnMainSync(() -> instrumentation.callActivityOnResume(activity));
      assertTrue(fixture.secondLibrary.await(3, TimeUnit.SECONDS));
      fixture.releaseSecondLibrary.countDown();
      awaitRows(instrumentation, activity, 7, 12, 24);
      assertEquals(2, fixture.libraryLoads.get());
    } finally {
      fixture.releaseWatch.countDown();
      fixture.releasePreviews.countDown();
      fixture.releaseSecondLibrary.countDown();
      instrumentation.runOnMainSync(activity::finish);
      instrumentation.waitForIdleSync();
      NativeLifecycleTestActivity.fixture = null;
    }
  }

  @Test
  public void disablingRecommendationsInvalidatesBlockedReadAndNeverRepublishesItsRecords()
      throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    android.content.SharedPreferences prefs =
        instrumentation
            .getTargetContext()
            .getSharedPreferences("recommendations", android.content.Context.MODE_PRIVATE);
    boolean wasEnabled = prefs.getBoolean("enabled", true);
    prefs.edit().putBoolean("enabled", true).commit();
    NativeLifecycleTestActivity.Fixture fixture = new NativeLifecycleTestActivity.Fixture();
    fixture.stagedLibrary = true;
    NativeLifecycleTestActivity.fixture = fixture;
    NativeLifecycleTestActivity activity =
        (NativeLifecycleTestActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), NativeLifecycleTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      assertTrue(fixture.libraryStarted.await(3, TimeUnit.SECONDS));
      fixture.releaseWatch.countDown();
      assertTrue(fixture.watchReady.await(3, TimeUnit.SECONDS));
      awaitRows(instrumentation, activity, 7, 12, 0);
      instrumentation.runOnMainSync(
          () -> {
            prefs.edit().putBoolean("enabled", false).commit();
            activity.refreshRecommendationPreferences();
          });
      awaitRows(instrumentation, activity, 7, 0, 0);
      assertTrue(
          "Preference changes restart work rather than wait behind blocked previews",
          fixture.secondLibrary.await(3, TimeUnit.SECONDS));
      fixture.releasePreviews.countDown();
      fixture.releaseSecondLibrary.countDown();
      instrumentation.waitForIdleSync();
      awaitRows(instrumentation, activity, 7, 0, 0);
    } finally {
      fixture.releaseWatch.countDown();
      fixture.releasePreviews.countDown();
      fixture.releaseSecondLibrary.countDown();
      instrumentation.runOnMainSync(activity::finish);
      instrumentation.waitForIdleSync();
      NativeLifecycleTestActivity.fixture = null;
      prefs.edit().putBoolean("enabled", wasEnabled).commit();
    }
  }

  private static void awaitRows(
      Instrumentation instrumentation,
      MainActivity activity,
      int apps,
      int watch,
      int recommendations) {
    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
    java.util.concurrent.atomic.AtomicBoolean ready =
        new java.util.concurrent.atomic.AtomicBoolean();
    do {
      instrumentation.runOnMainSync(
          () -> {
            androidx.recyclerview.widget.RecyclerView view = activity.findViewById(R.id.home_rows);
            NativeRowAdapter adapter = (NativeRowAdapter) view.getAdapter();
            java.util.List<NativeRowAdapter.Row> rows = adapter.getCurrentList();
            ready.set(
                rows.size() == 5
                    && rows.get(0).cards.size() == watch
                    && rows.get(1).cards.size() == apps
                    && rows.get(2).cards.size() == recommendations);
          });
      if (ready.get()) return;
      android.os.SystemClock.sleep(16);
    } while (android.os.SystemClock.uptimeMillis() < deadline);
    fail(
        "Library stages did not arrive: apps="
            + apps
            + ", watch="
            + watch
            + ", recommendations="
            + recommendations);
  }
}
