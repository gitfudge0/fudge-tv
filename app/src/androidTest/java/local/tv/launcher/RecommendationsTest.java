package local.tv.launcher;

import static org.junit.Assert.*;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

/** Fake-source Android tests prove mapping/failure handling, never third-party provider access. */
@RunWith(AndroidJUnit4.class)
public class RecommendationsTest {
  private Context context;
  private String owner;
  private SharedPreferences preferences;
  private Map<String, ?> saved;

  @Before
  public void prepare() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    owner = context.getPackageName();
    preferences = context.getSharedPreferences("recommendations", Context.MODE_PRIVATE);
    saved = new HashMap<>(preferences.getAll());
    preferences
        .edit()
        .clear()
        .putBoolean("enabled", true)
        .putBoolean("watchNext", true)
        .putInt("maxItems", 2)
        .commit();
  }

  @After
  public void restorePreferences() {
    SharedPreferences.Editor e = preferences.edit().clear();
    for (Map.Entry<String, ?> entry : saved.entrySet()) {
      Object value = entry.getValue();
      String key = entry.getKey();
      if (value instanceof Boolean) e.putBoolean(key, (Boolean) value);
      else if (value instanceof Integer) e.putInt(key, (Integer) value);
      else if (value instanceof Long) e.putLong(key, (Long) value);
      else if (value instanceof Float) e.putFloat(key, (Float) value);
      else if (value instanceof String) e.putString(key, (String) value);
      else if (value instanceof Set) e.putStringSet(key, new HashSet<>((Set<String>) value));
    }
    e.commit();
  }

  private Library.Snapshot snapshot(boolean installed) {
    Library.Snapshot result = new Library.Snapshot();
    if (installed) result.allApps.add(new Library.App(owner, "Test", null));
    return result;
  }

  private MatrixCursor channels() {
    MatrixCursor cursor =
        new MatrixCursor(new String[] {"_id", "package_name", "display_name", "type", "browsable"});
    cursor.addRow(new Object[] {1, owner, "First", "TYPE_PREVIEW", 1});
    cursor.addRow(new Object[] {2, owner, "Second", "TYPE_PREVIEW", 1});
    return cursor;
  }

  private MatrixCursor programs(long channel) {
    MatrixCursor cursor =
        new MatrixCursor(
            new String[] {
              "_id",
              "package_name",
              "channel_id",
              "title",
              "browsable",
              "weight",
              "duration_millis",
              "last_playback_position_millis",
              "watch_next_type",
              "intent_uri",
              "poster_art_uri",
              "poster_art_aspect_ratio"
            });
    cursor.addRow(
        new Object[] {
          1,
          owner,
          channel,
          "  Valid title  ",
          1,
          1,
          100,
          25,
          0,
          null,
          "content://example/poster",
          TvContract.PreviewPrograms.ASPECT_RATIO_2_3
        });
    cursor.addRow(new Object[] {2, owner, channel, " ", 1, 20, 100, 200, 0, null, null, null});
    cursor.addRow(
        new Object[] {
          3, "uninstalled.example", channel, "Stale", 1, 20, 100, 50, 0, null, null, null
        });
    return cursor;
  }

  private class FakeSource implements Recommendations.Source {
    boolean available = true, denied = false, crash = false, firstChannelFails = false;
    int calls;

    public boolean available() {
      return available;
    }

    public Cursor query(Uri uri, CancellationSignal signal) {
      calls++;
      if (signal != null) signal.throwIfCanceled();
      if (denied) throw new SecurityException("test denied");
      if (crash) throw new IllegalStateException("test crash");
      if (uri.equals(TvContract.Channels.CONTENT_URI)) return channels();
      if (uri.equals(TvContract.WatchNextPrograms.CONTENT_URI)) return programs(0);
      if (uri.equals(TvContract.buildPreviewProgramsUriForChannel(1))) {
        if (firstChannelFails) throw new SecurityException("one channel");
        return programs(1);
      }
      return programs(2);
    }
  }

  @Test
  public void mappingSkipsMalformedAndStaleRecordsAndPreservesMetadata() {
    Library.Snapshot result = snapshot(true);
    Recommendations.load(context, result, null, new FakeSource());
    assertEquals("available", result.channelCapability);
    assertEquals(2, result.channels.size());
    assertEquals(1, result.channels.get(0).programs.size());
    Library.Program p = result.channels.get(0).programs.get(0);
    assertEquals("Valid title", p.title);
    assertTrue(p.portrait);
    assertEquals(-1, p.position);
    assertEquals(100, p.duration);
    assertEquals(1, result.programs.size());
    assertEquals(25, result.programs.get(0).position);
  }

  @Test
  public void unavailableAndDeniedAndCrashingSourcesRemainDistinct() {
    FakeSource source = new FakeSource();
    source.available = false;
    Library.Snapshot result = snapshot(true);
    Recommendations.load(context, result, null, source);
    assertFalse(result.providerAvailable);
    assertEquals(0, source.calls);
    assertEquals("unavailable", result.channelCapability);
    source.available = true;
    source.denied = true;
    result = snapshot(true);
    Recommendations.load(context, result, null, source);
    assertEquals("restricted", result.channelCapability);
    assertEquals("restricted", result.watchNextCapability);
    source.denied = false;
    source.crash = true;
    result = snapshot(true);
    Recommendations.load(context, result, null, source);
    assertEquals("unavailable", result.channelCapability);
    assertTrue(result.channels.isEmpty());
  }

  @Test
  public void removedOwnersAreFilteredAndOneChannelFailureDoesNotBlockOthers() {
    Library.Snapshot result = snapshot(false);
    Recommendations.load(context, result, null, new FakeSource());
    assertTrue(result.channels.isEmpty());
    assertTrue(result.programs.isEmpty());
    FakeSource source = new FakeSource();
    source.firstChannelFails = true;
    result = snapshot(true);
    Recommendations.load(context, result, null, source);
    assertEquals(1, result.channels.size());
    assertEquals(2, result.channels.get(0).numericId);
  }

  @Test
  public void settingsHideReorderAndBoundRows() {
    preferences
        .edit()
        .putStringSet("hidden", Collections.singleton("channel:" + owner + ":1"))
        .putString("order", "channel:" + owner + ":2,channel:" + owner + ":1")
        .commit();
    Library.Snapshot result = snapshot(true);
    Recommendations.load(context, result, null, new FakeSource());
    assertEquals(1, result.channels.size());
    assertEquals(2, result.recommendationChannels.get(0).numericId);
  }

  @Test
  public void visibleRowsAndCatalogMemoryRemainBounded() {
    preferences.edit().putInt("maxItems", 500).commit();
    FakeSource source =
        new FakeSource() {
          @Override
          public Cursor query(Uri uri, CancellationSignal signal) {
            if (uri.equals(TvContract.Channels.CONTENT_URI)) {
              MatrixCursor cursor =
                  new MatrixCursor(
                      new String[] {"_id", "package_name", "display_name", "type", "browsable"});
              for (int i = 0; i < 100; i++)
                cursor.addRow(new Object[] {i, owner, "Row " + i, "TYPE_PREVIEW", 1});
              return cursor;
            }
            long channel =
                uri.equals(TvContract.WatchNextPrograms.CONTENT_URI)
                    ? 0
                    : Long.parseLong(uri.getQueryParameter("channel"));
            MatrixCursor cursor =
                new MatrixCursor(
                    new String[] {
                      "_id",
                      "package_name",
                      "channel_id",
                      "title",
                      "browsable",
                      "weight",
                      "watch_next_type"
                    });
            for (int i = 0; i < 200; i++)
              cursor.addRow(new Object[] {i, owner, channel, "Item " + i, 1, i, 0});
            return cursor;
          }
        };
    Library.Snapshot result = snapshot(true);
    Recommendations.load(context, result, null, source);
    assertEquals(64, result.recommendationChannels.size());
    assertEquals(12, result.channels.size());
    for (Library.Channel row : result.channels) {
      assertEquals(40, row.programs.size());
      assertEquals(199, row.programs.get(0).weight);
    }
    assertEquals(40, result.programs.size());
  }

  @Test
  public void duplicatePublishedLinksKeepSingleCardWithoutChangingRecordIdentity() {
    FakeSource source =
        new FakeSource() {
          @Override
          public Cursor query(Uri uri, CancellationSignal signal) {
            if (uri.equals(TvContract.Channels.CONTENT_URI)) return channels();
            long channel =
                uri.equals(TvContract.WatchNextPrograms.CONTENT_URI)
                    ? 0
                    : Long.parseLong(uri.getQueryParameter("channel"));
            MatrixCursor cursor =
                new MatrixCursor(
                    new String[] {
                      "_id",
                      "package_name",
                      "channel_id",
                      "title",
                      "browsable",
                      "watch_next_type",
                      "intent_uri"
                    });
            for (int i = 1; i <= 2; i++)
              cursor.addRow(
                  new Object[] {
                    i, owner, channel, "Same item", 1, 0, "https://example.invalid/same"
                  });
            return cursor;
          }
        };
    Library.Snapshot result = snapshot(true);
    Recommendations.load(context, result, null, source);
    assertEquals(1, result.channels.get(0).programs.size());
    assertEquals("preview:" + owner + ":1", result.channels.get(0).programs.get(0).id);
    assertEquals(1, result.programs.size());
  }

  @Test
  public void cancellationPropagatesWithoutPublishingObsoleteRows() {
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    try {
      Recommendations.load(context, snapshot(true), signal, new FakeSource());
      fail("cancel expected");
    } catch (OperationCanceledException expected) {
    }
  }

  @Test
  public void safeLinksDiscardPayloadAndRejectCrossPackageAndDangerousActions() {
    String raw =
        "intent://video/1#Intent;scheme=https;package="
            + owner
            + ";S.token=secret;launchFlags=0x1;end";
    Intent safe = Recommendations.sanitizedLink(raw, owner);
    assertNotNull(safe);
    assertEquals(owner, safe.getPackage());
    assertEquals(Intent.ACTION_VIEW, safe.getAction());
    assertNull(safe.getExtras());
    assertNull(safe.getClipData());
    assertNull(safe.getSelector());
    assertNull(safe.getComponent());
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, safe.getFlags());
    assertNull(
        Recommendations.sanitizedLink(
            "intent://video#Intent;scheme=https;package=other;end", owner));
    assertNull(
        Recommendations.sanitizedLink(
            "intent://video#Intent;scheme=https;action=android.intent.action.DELETE;end", owner));
    assertNull(Recommendations.sanitizedLink("file:///private", owner));
    assertNull(
        Recommendations.validatedLink(
            context, "https://example.invalid/item", "uninstalled.example"));
  }

  @Test
  public void actualProviderCapabilityProbeDoesNotAssumeVisibility() {
    Library.Snapshot result = Library.load(context, new CancellationSignal());
    android.util.Log.i(
        "FudgeRecommendationProbe",
        "channels="
            + result.channelCapability
            + ",watchNext="
            + result.watchNextCapability
            + ",rows="
            + result.channels.size()
            + ",programs="
            + result.programs.size());
    assertTrue(
        Arrays.asList("available", "empty", "restricted", "unavailable")
            .contains(result.channelCapability));
    Set<String> installed = new HashSet<>();
    for (Library.App app : result.allApps) installed.add(app.id);
    for (Library.Channel channel : result.channels) assertTrue(installed.contains(channel.owner));
  }
}
