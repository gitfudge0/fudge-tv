package local.tv.launcher;

import static org.junit.Assert.*;

import android.content.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RecommendationCacheTest {
  private Context context;
  private String saved;

  @Before
  public void prepare() {
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    saved =
        context
            .getSharedPreferences("recommendation_cache", Context.MODE_PRIVATE)
            .getString("v1", null);
  }

  @After
  public void restore() {
    context
        .getSharedPreferences("recommendation_cache", Context.MODE_PRIVATE)
        .edit()
        .putString("v1", saved)
        .commit();
  }

  private Library.Snapshot inventory() {
    Library.Snapshot inventory = new Library.Snapshot();
    String owner = context.getPackageName();
    inventory.allApps.add(
        new Library.App(
            owner, "Installed publisher", new Intent(Intent.ACTION_MAIN).setPackage(owner)));
    return inventory;
  }

  private Library.Snapshot published() {
    Library.Snapshot result = inventory();
    String owner = context.getPackageName();
    Library.Program program = new Library.Program();
    program.id = "preview:" + owner + ":1";
    program.owner = owner;
    program.channelId = "channel:" + owner + ":7";
    program.title = "Real provider fixture";
    program.art = "https://example.org/poster.jpg";
    program.launch = result.allApps.get(0).launch;
    Library.Channel row = new Library.Channel();
    row.id = "app:" + owner;
    row.owner = owner;
    row.title = "Old publisher name";
    row.programs.add(program);
    result.channels.add(row);
    Library.Program watch = new Library.Program();
    watch.id = "watchnext:" + owner + ":2";
    watch.owner = owner;
    watch.title = "Real history fixture";
    watch.launch = program.launch;
    watch.duration = 100;
    watch.position = 50;
    result.programs.add(watch);
    return result;
  }

  @Test
  public void diskRoundTripRevalidatesOwnersAndKeepsContentBeforeRefresh() {
    RecommendationCache.write(context, published());
    Library.Snapshot restored = RecommendationCache.read(context, inventory());
    assertEquals(1, restored.channels.size());
    assertEquals("Installed publisher", restored.channels.get(0).title);
    assertEquals("Real provider fixture", restored.channels.get(0).programs.get(0).title);
    assertEquals(1, restored.programs.size());
    assertEquals(50, restored.programs.get(0).position);
    assertFalse(restored.programs.get(0).resumable);
    assertTrue(RecommendationCache.read(context, new Library.Snapshot()).channels.isEmpty());
    Library.Snapshot failed = inventory();
    Library.Snapshot retained = RecommendationCache.reconcile(failed, restored);
    assertEquals(1, retained.channels.size());
    assertEquals(1, retained.programs.size());
    RecommendationCache.write(context, retained);
    assertEquals(1, RecommendationCache.read(context, inventory()).channels.size());
  }

  @Test
  public void successfulEmptyAndDisabledRefreshRemoveStaleRecords() {
    Library.Snapshot fresh = inventory();
    fresh.channelsRead = true;
    fresh.watchNextCapability = fresh.channelCapability = "empty";
    Library.Snapshot result = RecommendationCache.reconcile(fresh, published());
    assertTrue(result.channels.isEmpty());
    assertTrue(result.programs.isEmpty());
    RecommendationCache.write(context, result);
    assertTrue(RecommendationCache.read(context, inventory()).channels.isEmpty());
    fresh.watchNextCapability = fresh.channelCapability = "disabled";
    assertTrue(RecommendationCache.reconcile(fresh, published()).channels.isEmpty());
    assertTrue(RecommendationCache.reconcile(fresh, published()).programs.isEmpty());
  }

  @Test
  public void partialFailureOnlyRetainsTheFailedChannel() {
    Library.Snapshot stale = published();
    Library.Program removed = new Library.Program();
    removed.id = "preview:" + context.getPackageName() + ":3";
    removed.owner = context.getPackageName();
    removed.channelId = "channel:" + removed.owner + ":8";
    stale.channels.get(0).programs.add(removed);
    Library.Snapshot fresh = inventory();
    fresh.channelsRead = true;
    fresh.failedChannels.add(stale.channels.get(0).programs.get(0).channelId);
    Library.Snapshot result = RecommendationCache.reconcile(fresh, stale);
    assertEquals(1, result.channels.size());
    assertEquals(1, result.channels.get(0).programs.size());
    assertEquals("Real provider fixture", result.channels.get(0).programs.get(0).title);
  }

  @Test
  public void malformedCacheNeverPreventsStartup() {
    context
        .getSharedPreferences("recommendation_cache", Context.MODE_PRIVATE)
        .edit()
        .putString("v1", "{broken")
        .commit();
    assertTrue(RecommendationCache.read(context, inventory()).channels.isEmpty());
  }
}
