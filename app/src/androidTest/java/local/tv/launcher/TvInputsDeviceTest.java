package local.tv.launcher;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.util.Log;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import org.junit.Test;

public class TvInputsDeviceTest {
  @Test
  public void publicInputMetadataIsReadableFromAppUid() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    List<TvInputs.Entry> entries = TvInputs.load(context);
    assertNotNull(entries);
    for (TvInputs.Entry entry : entries) {
      assertFalse(entry.id.isEmpty());
      assertFalse(entry.label.isEmpty());
      assertNotNull(entry.status);
      Log.i("TvInputsVerification", entry.label + " | " + entry.portLabel + " | " + entry.status);
      Intent launch = TvInputs.viewIntent(entry.id);
      assertEquals(Intent.ACTION_VIEW, launch.getAction());
      assertEquals(TvContract.buildChannelUriForPassthroughInput(entry.id), launch.getData());
    }
    Intent home = TvInputs.systemHomeIntent(context);
    if (home != null) {
      assertNotNull(home.getComponent());
      assertNotEquals(context.getPackageName(), home.getComponent().getPackageName());
    }
  }

  @Test
  public void passthroughLaunchRetainsExactInputId() {
    String id = "vendor.package/.InputService/HDMI 2";
    assertEquals(
        TvContract.buildChannelUriForPassthroughInput(id), TvInputs.viewIntent(id).getData());
  }
}
