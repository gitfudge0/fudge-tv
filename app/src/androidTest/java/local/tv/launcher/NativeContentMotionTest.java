package local.tv.launcher;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Attached native holders verify cancellation settles the logical state, not an in-between frame.
 */
@RunWith(AndroidJUnit4.class)
public class NativeContentMotionTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private NativeUiTestActivity activity;
  private NativeImages images;
  private NativeCardAdapter adapter;
  private RecyclerView row;

  @Before
  public void open() {
    activity =
        (NativeUiTestActivity)
            instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), NativeUiTestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.runOnMainSync(
        () -> {
          NativeUi ui = new NativeUi(activity);
          images = new NativeImages(activity);
          row = new RecyclerView(activity);
          row.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false));
          row.setItemAnimator(null);
          adapter = new NativeCardAdapter(ui, images, NativeCard.Kind.WATCH, row, card -> {});
          row.setAdapter(adapter);
          activity.setContentView(row);
          adapter.submitList(Collections.singletonList(card("first", 20)));
        });
    awaitCard("first");
  }

  @After
  public void close() {
    instrumentation.runOnMainSync(
        () -> {
          if (images != null) images.close();
          if (activity != null) activity.finish();
        });
  }

  private NativeCard card(String id, int progress) {
    return new NativeCard(id, id, "", "", "", NativeCard.Kind.WATCH, null, null, true, progress);
  }

  private NativeCardAdapter.Holder awaitCard(String id) {
    NativeCardAdapter.Holder[] found = new NativeCardAdapter.Holder[1];
    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
    do {
      instrumentation.runOnMainSync(
          () -> {
            RecyclerView.ViewHolder holder = row.findViewHolderForItemId(Policy.stableId(id));
            if (holder instanceof NativeCardAdapter.Holder)
              found[0] = (NativeCardAdapter.Holder) holder;
          });
      if (found[0] != null) return found[0];
      android.os.SystemClock.sleep(20);
    } while (android.os.SystemClock.uptimeMillis() < deadline);
    throw new AssertionError("Card did not attach: " + id);
  }

  private int animationCount(String name) {
    try {
      java.lang.reflect.Field field = NativeCardAdapter.class.getDeclaredField(name);
      field.setAccessible(true);
      return ((java.util.List<?>) field.get(adapter)).size();
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  @Test
  public void insertedCardRevealsAfterLayoutAndSupersededRemovalNeverStarts() {
    instrumentation.runOnMainSync(
        () -> adapter.submitList(java.util.Arrays.asList(card("first", 20), card("inserted", 10))));
    NativeCardAdapter.Holder inserted = awaitCard("inserted");
    boolean[] revealed = {false};
    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
    do {
      instrumentation.runOnMainSync(() -> revealed[0] = inserted.revealMotion != null);
      if (revealed[0]) break;
      android.os.SystemClock.sleep(10);
    } while (android.os.SystemClock.uptimeMillis() < deadline);
    assertTrue("Inserted holder missed its post-layout reveal", revealed[0]);
    NativeCardAdapter.Holder first = awaitCard("first");
    instrumentation.runOnMainSync(
        () -> {
          first.itemView.requestFocus();
          java.util.List<NativeCard> accepted = adapter.getCurrentList();
          adapter.submitList(Collections.singletonList(card("first", 20)));
          assertEquals("Exit starts only after accepted diff", 0, animationCount("removals"));
          assertEquals(1, animationCount("pendingRemovals"));
          adapter.submitList(accepted);
          assertEquals(0, animationCount("pendingRemovals"));
          assertEquals(0, animationCount("removals"));
        });
    instrumentation.waitForIdleSync();
    assertNotNull(awaitCard("inserted"));
  }

  @Test
  public void removalOverlayStartsOnlyWhenItsDiffCommits() throws Exception {
    instrumentation.runOnMainSync(
        () -> adapter.submitList(java.util.Arrays.asList(card("first", 20), card("removed", 10))));
    awaitCard("removed");
    NativeCardAdapter.Holder first = awaitCard("first");
    java.util.concurrent.CountDownLatch committed = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean startedAtCommit =
        new java.util.concurrent.atomic.AtomicBoolean();
    instrumentation.runOnMainSync(
        () -> {
          first.itemView.requestFocus();
          adapter.submitList(
              Collections.singletonList(card("first", 20)),
              () -> {
                startedAtCommit.set(
                    animationCount("removals") == 1 && animationCount("pendingRemovals") == 0);
                committed.countDown();
              });
          assertEquals(0, animationCount("removals"));
          assertEquals(1, animationCount("pendingRemovals"));
        });
    assertTrue(
        "Removal diff did not commit", committed.await(3, java.util.concurrent.TimeUnit.SECONDS));
    assertTrue("Accepted removal did not start its exit", startedAtCommit.get());
  }

  @Test
  public void inactiveAndDetachSettleProgressAndSelection() {
    NativeCardAdapter.Holder holder = awaitCard("first");
    instrumentation.runOnMainSync(
        () -> adapter.submitList(Collections.singletonList(card("first", 80))));
    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
    boolean[] updated = {false};
    do {
      instrumentation.runOnMainSync(() -> updated[0] = holder.card.progress == 80);
      if (updated[0]) break;
      android.os.SystemClock.sleep(10);
    } while (android.os.SystemClock.uptimeMillis() < deadline);
    assertTrue("Updated progress did not bind", updated[0]);
    instrumentation.runOnMainSync(
        () -> {
          adapter.setContextSelection("first");
          adapter.setInstant(true);
          assertEquals(80, holder.progress.getProgress());
          assertEquals(1f, holder.marker.getAlpha(), .001f);
          assertEquals(0xffe4d7bf, holder.title.getCurrentTextColor());
          assertFalse(holder.progressMotion != null && holder.progressMotion.isRunning());
          adapter.onViewDetachedFromWindow(holder);
          assertEquals(80, holder.progress.getProgress());
          assertEquals(1f, holder.itemView.getAlpha(), .001f);
        });
  }

  @Test
  public void recycledIdentityCannotRetainSelectionOrProgress() {
    NativeCardAdapter.Holder holder = awaitCard("first");
    instrumentation.runOnMainSync(
        () -> {
          adapter.setContextSelection("first");
          adapter.onViewRecycled(holder);
          assertNull(holder.card);
          assertNull(holder.selectionMotion);
          assertNull(holder.progressMotion);
          assertEquals(0f, holder.marker.getAlpha(), .001f);
          // Reduced/inactive rebinding assigns state immediately and cannot start reveal motion.
          adapter.setInstant(true);
          adapter.submitList(Collections.singletonList(card("second", 7)));
        });
    NativeCardAdapter.Holder second = awaitCard("second");
    instrumentation.runOnMainSync(
        () -> {
          assertEquals(7, second.progress.getProgress());
          assertEquals(0f, second.marker.getAlpha(), .001f);
          assertEquals(Color.WHITE, second.title.getCurrentTextColor());
          assertEquals(View.VISIBLE, second.progress.getVisibility());
          assertTrue(second.revealMotion == null || !second.revealMotion.isRunning());
        });
  }
}
