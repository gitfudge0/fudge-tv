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
}
