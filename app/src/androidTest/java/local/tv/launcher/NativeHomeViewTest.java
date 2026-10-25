package local.tv.launcher;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the actual native tree, adapters, focus and timers in an attached Android window. */
@RunWith(AndroidJUnit4.class)
public class NativeHomeViewTest {
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private NativeUiTestActivity activity;

  @Before
  public void launch() {
    // Exercise initial programmatic focus even when the last input source was touch.
    instrumentation.setInTouchMode(true);
    Intent intent =
        new Intent(instrumentation.getTargetContext(), NativeUiTestActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    activity = (NativeUiTestActivity) instrumentation.startActivitySync(intent);
    instrumentation.waitForIdleSync();
    awaitStartupFocus();
  }

  private void awaitStartupFocus() {
    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
    AtomicReference<String> diagnostic = new AtomicReference<>("not inspected");
    java.util.concurrent.atomic.AtomicBoolean ready =
        new java.util.concurrent.atomic.AtomicBoolean();
    do {
      instrumentation.runOnMainSync(
          () -> {
            View target = activity.home.findViewById(R.id.hero_open);
            View current = activity.home.findFocus();
            ready.set(target != null && target.hasFocus() && target.hasWindowFocus());
            diagnostic.set(
                "window="
                    + activity.home.hasWindowFocus()
                    + ", touchMode="
                    + activity.home.isInTouchMode()
                    + ", attached="
                    + (target != null && target.isAttachedToWindow())
                    + ", focusable="
                    + (target != null && target.isFocusable())
                    + ", current="
                    + (current == null
                        ? "none"
                        : current.getClass().getName() + ":" + current.getId()));
          });
      if (ready.get()) {
        android.os.Bundle evidence = new android.os.Bundle();
        evidence.putString("nativeUiStartup", diagnostic.get());
        instrumentation.sendStatus(0, evidence);
        return;
      }
      android.os.SystemClock.sleep(16);
    } while (android.os.SystemClock.uptimeMillis() < deadline);
    fail("Gallery startup focus deadline exceeded: " + diagnostic.get());
  }

  @After
  public void close() {
    if (activity != null) instrumentation.runOnMainSync(activity::finish);
  }

  private void main(Runnable action) {
    instrumentation.runOnMainSync(action);
    instrumentation.waitForIdleSync();
  }

  private void waitMs(long delay) {
    android.os.SystemClock.sleep(delay);
    instrumentation.waitForIdleSync();
  }

  private static int count(View view, Class<?> type) {
    int count = type.isInstance(view) ? 1 : 0;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        count += count(((ViewGroup) view).getChildAt(i), type);
    return count;
  }

  private static void assertNativeTree(View view) {
    assertFalse(view.getClass().getName(), view.getClass().getName().startsWith("android.webkit."));
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        assertNativeTree(((ViewGroup) view).getChildAt(i));
  }

  private android.view.accessibility.AccessibilityNodeInfo activeFocus() {
    long deadline = android.os.SystemClock.uptimeMillis() + 3000;
    do {
      android.view.accessibility.AccessibilityNodeInfo root =
          instrumentation.getUiAutomation().getRootInActiveWindow();
      if (root != null) {
        android.view.accessibility.AccessibilityNodeInfo focus =
            root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null
            && focus.getText() != null
            && "Open in Stremio".contentEquals(focus.getText())) return focus;
      }
      android.os.SystemClock.sleep(16);
    } while (android.os.SystemClock.uptimeMillis() < deadline);
    fail("Actual dialog window did not expose its focused action within 3 seconds");
    return null;
  }

  @Test
  public void homeUsesRecycledNativeRowsAndStartsAtGalleryAction() {
    main(
        () -> {
          assertNotNull(activity.home.findViewById(R.id.hero_open));
          assertTrue(activity.home.findViewById(R.id.hero_open).hasFocus());
          assertTrue(count(activity.home, RecyclerView.class) >= 2);
          RecyclerView rows = activity.home.findViewById(R.id.home_rows);
          assertEquals(5, rows.getAdapter().getItemCount());
          assertEquals(
              "Continue Watching",
              ((NativeRowAdapter) rows.getAdapter()).getCurrentList().get(1).title);
          assertEquals(
              12, ((NativeRowAdapter) rows.getAdapter()).getCurrentList().get(1).cards.size());
          assertTrue(
              "Only visible cards are allocated",
              count(activity.home, android.widget.ImageView.class) < 40);
          assertNativeTree(activity.home);
        });
  }

  @Test
  public void pendingDebouncePausesAndResumesWithoutDuplicateSubmission() {
    main(
        () -> {
          activity.home.showSearch();
          ((EditText) activity.home.findViewById(R.id.search_input)).setText("Strem");
          activity.home.setActive(false);
        });
    waitMs(400);
    main(() -> assertEquals(0, activity.searches.size()));
    main(() -> activity.home.setActive(true));
    waitMs(400);
    main(
        () -> {
          assertEquals(1, activity.searches.size());
          assertEquals("Strem", activity.searches.get(0));
          activity.home.setActive(false);
          activity.home.setActive(true);
        });
    waitMs(400);
    main(
        () ->
            assertEquals(
                "Activity owns retry for already submitted searches", 1, activity.searches.size()));
  }

  @Test
  public void localAppsAppearBeforeRemoteSearchAndErrorsRemainHonest() {
    main(
        () -> {
          activity.home.showSearch();
          ((EditText) activity.home.findViewById(R.id.search_input)).setText("Strem");
        });
    waitMs(100);
    main(
        () -> {
          RecyclerView rows = activity.home.findViewById(R.id.search_rows);
          NativeRowAdapter adapter = (NativeRowAdapter) rows.getAdapter();
          assertEquals(1, adapter.getItemCount());
          assertEquals("Stremio", adapter.getCurrentList().get(0).cards.get(0).title);
          try {
            activity.home.renderSearch(
                new org.json.JSONObject()
                    .put("items", new org.json.JSONArray())
                    .put("message", "Cinemeta unavailable."));
          } catch (org.json.JSONException e) {
            throw new AssertionError(e);
          }
          assertEquals(
              "Cinemeta unavailable.",
              ((TextView) activity.home.findViewById(R.id.search_status)).getText().toString());
        });
  }

  @Test
  public void detailRefreshPreservesFocusedActionAndClosingCancelsRequest() {
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    instrumentation.waitForIdleSync();
    main(() -> assertTrue(activity.home.findViewById(R.id.hero_more).hasFocus()));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    instrumentation.waitForIdleSync();
    android.view.accessibility.AccessibilityNodeInfo before = activeFocus();
    assertEquals("Open in Stremio", before.getText().toString());
    main(
        () -> {
          assertEquals(1, activity.detailRequests);
          org.json.JSONObject enriched =
              NativeUiTestActivity.fixtureCatalog().optJSONArray("items").optJSONObject(0);
          org.json.JSONArray cast = new org.json.JSONArray();
          for (int i = 0; i < 8; i++) cast.put("Actor " + i);
          try {
            enriched.put("cast", cast);
          } catch (org.json.JSONException e) {
            throw new AssertionError(e);
          }
          activity.home.showDetails(enriched);
        });
    android.view.accessibility.AccessibilityNodeInfo after = activeFocus();
    assertEquals("The dialog action must keep its actual accessibility node", before, after);
    android.view.accessibility.AccessibilityNodeInfo detailRoot =
        instrumentation.getUiAutomation().getRootInActiveWindow();
    assertFalse(detailRoot.findAccessibilityNodeInfosByText("FILM · Cinemeta").isEmpty());
    assertFalse(
        detailRoot
            .findAccessibilityNodeInfosByText(
                "Starring Actor 0, Actor 1, Actor 2, Actor 3, Actor 4, Actor 5")
            .isEmpty());
    assertTrue(
        "At most six cast names are displayed",
        detailRoot.findAccessibilityNodeInfosByText("Actor 6").isEmpty());
    main(
        () -> {
          activity.home.handleBack();
          assertEquals(1, activity.detailCancellations);
          assertTrue(activity.home.findViewById(R.id.hero_more).hasFocus());
        });
  }

  @Test
  public void homeActionRetainsIdentityAfterHundredsOfSearchResults() {
    main(
        () -> {
          activity.home.showSearch();
          org.json.JSONArray items = new org.json.JSONArray();
          try {
            for (int i = 0; i < 250; i++)
              items.put(
                  new org.json.JSONObject()
                      .put("key", "movie:tt" + (3000000 + i))
                      .put("title", "Search " + i));
            activity.home.renderSearch(new org.json.JSONObject().put("items", items));
          } catch (org.json.JSONException e) {
            throw new AssertionError(e);
          }
          activity.home.showHome();
          activity.home.findViewById(R.id.hero_open).performClick();
          assertEquals("movie:tt1000000", activity.openedTitle.optString("key"));
        });
  }

  @Test
  public void dpadTraversesGalleryNavigationSelectorsAndSharedWatchRow() {
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    instrumentation.waitForIdleSync();
    main(() -> assertTrue(activity.home.findViewById(R.id.nav_home).hasFocus()));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    instrumentation.waitForIdleSync();
    main(() -> assertTrue(activity.home.findViewById(R.id.hero_open).hasFocus()));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    instrumentation.waitForIdleSync();
    main(() -> assertTrue(activity.home.findViewById(R.id.feature_selectors).hasFocus()));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    waitMs(250);
    main(
        () -> {
          View focused = activity.home.findFocus();
          assertNotNull(focused);
          assertTrue(focused.getContentDescription().toString().startsWith("Shared title"));
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    instrumentation.waitForIdleSync();
    main(() -> assertEquals("watch:0", activity.resumes.get(0)));
  }

  @Test
  public void carouselDescriptionHeightStaysFixedAndSelectorsPauseRotation() {
    main(
        () -> {
          TextView description = activity.home.findViewById(R.id.hero_description);
          int height = description.getHeight();
          ViewGroup dots = activity.home.findViewById(R.id.feature_selectors);
          dots.getChildAt(1).performClick();
          assertEquals(height, description.getHeight());
          assertEquals(
              "Fixture title 1",
              ((TextView) activity.home.findViewById(R.id.hero_title)).getText().toString());
          dots.getChildAt(1).requestFocus();
        });
    waitMs(8200);
    main(
        () ->
            assertEquals(
                "Fixture title 1",
                ((TextView) activity.home.findViewById(R.id.hero_title)).getText().toString()));
  }

  @Test
  public void horizontalMotionDoesNotResizeCardsOrRows() {
    RecyclerView row = NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    final int[] dimensions = new int[2];
    main(
        () -> {
          dimensions[0] = row.getWidth();
          dimensions[1] = row.findViewHolderForAdapterPosition(0).itemView.getWidth();
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    instrumentation.waitForIdleSync();
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    waitMs(200); // Observe dimensions during/after the native transform animation, never relayout.
    main(
        () -> {
          assertEquals(dimensions[0], row.getWidth());
          assertEquals(dimensions[1], row.findViewHolderForAdapterPosition(0).itemView.getWidth());
          assertTrue(row.hasFocus());
        });
  }

  @Test
  public void recycledProviderRowsKeepTheirOwnScrollAndFocusAfterRefresh() {
    main(
        () -> {
          Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
          Library.Channel source = data.channels.get(0);
          data.channels.clear();
          for (int i = 0; i < 24; i++) {
            Library.Channel channel = new Library.Channel();
            channel.id = "many:" + i;
            channel.title = "Provider " + i;
            channel.owner = "Fixture app";
            channel.programs.addAll(source.programs);
            data.channels.add(channel);
          }
          activity.home.renderSnapshot(data);
          RecyclerView vertical = activity.home.findViewById(R.id.home_rows);
          vertical.setItemViewCacheSize(0);
          vertical.getRecycledViewPool().setMaxRecycledViews(NativeCard.Kind.POSTER.ordinal(), 1);
        });
    waitMs(200);
    java.util.Set<RecyclerView.ViewHolder> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    boolean reused = false;
    // Every visible holder is deliberately offset. A fresh logical row must still start at zero.
    for (int position = 3; position < 23; position++) {
      final int index = position;
      main(
          () ->
              ((RecyclerView) activity.home.findViewById(R.id.home_rows)).scrollToPosition(index));
      waitMs(100);
      final boolean[] repeated = new boolean[1];
      main(
          () -> {
            RecyclerView vertical = activity.home.findViewById(R.id.home_rows);
            NativeRowAdapter.Holder holder =
                (NativeRowAdapter.Holder) vertical.findViewHolderForAdapterPosition(index);
            assertNotNull(holder);
            repeated[0] = !seen.add(holder);
            RecyclerView horizontal = (RecyclerView) ((ViewGroup) holder.itemView).getChildAt(2);
            androidx.recyclerview.widget.LinearLayoutManager layout =
                (androidx.recyclerview.widget.LinearLayoutManager) horizontal.getLayoutManager();
            assertEquals(
                "Fresh logical row " + index + " inherited another provider's offset",
                0,
                layout.findFirstVisibleItemPosition());
            layout.scrollToPositionWithOffset(15, 0);
          });
      reused |= repeated[0];
      waitMs(80);
    }
    assertTrue("Workload must actually reuse at least one native row holder", reused);
    main(() -> ((RecyclerView) activity.home.findViewById(R.id.home_rows)).scrollToPosition(3));
    waitMs(200);
    main(
        () -> {
          RecyclerView vertical = activity.home.findViewById(R.id.home_rows);
          NativeRowAdapter.Holder holder =
              (NativeRowAdapter.Holder) vertical.findViewHolderForAdapterPosition(3);
          RecyclerView horizontal = (RecyclerView) ((ViewGroup) holder.itemView).getChildAt(2);
          assertEquals(
              "Returning to the first provider restores its own position",
              15,
              ((androidx.recyclerview.widget.LinearLayoutManager) horizontal.getLayoutManager())
                  .findFirstVisibleItemPosition());
          RecyclerView.ViewHolder card = horizontal.findViewHolderForAdapterPosition(15);
          assertNotNull(card);
          card.itemView.requestFocus();
          // Change values with the same IDs so AsyncListDiffer really commits a new card list.
          Library.Snapshot updated = NativeUiTestActivity.fixtureSnapshot();
          java.util.List<Library.Program> originals =
              new java.util.ArrayList<>(updated.channels.get(0).programs);
          for (int i = 0; i < 24; i++) {
            Library.Channel channel = new Library.Channel();
            channel.id = "many:" + i;
            channel.title = "Provider " + i;
            channel.owner = "Fixture app";
            for (Library.Program original : originals) {
              Library.Program program = new Library.Program();
              program.id = original.id;
              program.title = original.title + " refreshed";
              program.owner = original.owner;
              program.art = original.art;
              channel.programs.add(program);
            }
            if (i == 0) updated.channels.set(0, channel);
            else updated.channels.add(channel);
          }
          activity.home.renderSnapshot(updated);
        });
    waitMs(300);
    main(
        () -> {
          View focused = activity.home.findFocus();
          assertNotNull(focused);
          assertTrue(
              "Refresh must retain the actual focused program",
              focused
                  .getContentDescription()
                  .toString()
                  .startsWith("Published title 15 refreshed"));
        });
  }

  @Test
  public void navigationIconsRemainVisibleOnTheirFocusedWhiteSurface() {
    main(
        () -> {
          for (int kind = 0; kind < 3; kind++) {
            NativeNavIcon icon = new NativeNavIcon(kind);
            icon.setBounds(0, 0, 100, 100);
            android.graphics.Bitmap normal =
                android.graphics.Bitmap.createBitmap(
                    100, 100, android.graphics.Bitmap.Config.ARGB_8888);
            normal.eraseColor(NativeUi.BACKGROUND);
            icon.setState(new int[] {android.R.attr.state_enabled});
            icon.draw(new android.graphics.Canvas(normal));
            android.graphics.Bitmap focused =
                android.graphics.Bitmap.createBitmap(
                    100, 100, android.graphics.Bitmap.Config.ARGB_8888);
            focused.eraseColor(android.graphics.Color.WHITE);
            icon.setState(new int[] {android.R.attr.state_enabled, android.R.attr.state_focused});
            icon.draw(new android.graphics.Canvas(focused));
            int light = 0, dark = 0;
            for (int y = 20; y < 80; y++)
              for (int x = 20; x < 80; x++) {
                if (android.graphics.Color.red(normal.getPixel(x, y)) > 220) light++;
                if (android.graphics.Color.red(focused.getPixel(x, y)) < 40) dark++;
              }
            assertTrue("Unfocused icon needs visible white strokes", light > 20);
            assertTrue("Focused icon needs visible dark strokes on white", dark > 20);
            normal.recycle();
            focused.recycle();
          }
          View nav = activity.home.findViewById(R.id.nav_home);
          nav.requestFocus();
          assertTrue(nav.getForeground().isStateful());
          assertTrue(
              android.util.StateSet.stateSetMatches(
                  new int[] {android.R.attr.state_focused}, nav.getForeground().getState()));
        });
  }

  @Test
  public void rapidVerticalReversalUsesPendingRowsAndRestoresTheirPreferredCard() {
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    instrumentation.waitForIdleSync();
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "selector");
    // Dispatch a burst in one native UI turn, before newly requested rows can finish layout.
    main(
        () -> {
          for (int key :
              new int[] {
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN
              }) {
            activity.home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            activity.home.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
          }
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    main(
        () -> {
          for (int i = 0; i < 3; i++) {
            activity.home.dispatchKeyEvent(
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
            activity.home.dispatchKeyEvent(
                new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP));
          }
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "selector");
  }

  @Test
  public void emptyAndDisabledRowsAreSkippedAndExplainUnavailableHistoryOnce() {
    main(
        () -> {
          Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
          data.programs.clear();
          data.channels.clear();
          data.programStatus = "Provider history unavailable.";
          for (Library.App app : data.apps) app.launch = null;
          activity.home.renderSnapshot(data);
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "selector");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "selector");
    main(
        () -> {
          RecyclerView rows = activity.home.findViewById(R.id.home_rows);
          assertEquals(1, countText(activity.home, "Provider history unavailable."));
        });
  }

  private static int countText(View view, String text) {
    int count =
        view instanceof TextView
                && view.isShown()
                && text.contentEquals(((TextView) view).getText())
            ? 1
            : 0;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        count += countText(((ViewGroup) view).getChildAt(i), text);
    return count;
  }

  @Test
  public void pendingRowRefreshFallsBackWhenPreferredAppBecomesDisabled() {
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "selector");
    main(
        () -> {
          activity.home.dispatchKeyEvent(
              new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
          activity.home.dispatchKeyEvent(
              new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
          activity.home.dispatchKeyEvent(
              new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
          activity.home.dispatchKeyEvent(
              new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
          Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
          data.apps.get(0).launch = null;
          activity.home.renderSnapshot(data);
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
  }

  @Test
  public void unavailableFeaturedCatalogStillNavigatesBetweenGalleryAndSharedRows() {
    main(
        () -> {
          try {
            activity.home.renderCatalog(
                new org.json.JSONObject()
                    .put("items", new org.json.JSONArray())
                    .put("featured", new org.json.JSONArray())
                    .put("message", "Catalog unavailable."));
          } catch (org.json.JSONException e) {
            throw new AssertionError(e);
          }
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "gallery");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "watch:0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "gallery");
  }

  @Test
  public void searchFirstResultRowReturnsUpToActualSearchInput() {
    main(
        () -> {
          activity.home.showSearch();
          ((EditText) activity.home.findViewById(R.id.search_input)).setText("Strem");
          activity.home.renderSearch(NativeUiTestActivity.fixtureCatalog());
          activity.home.handleBack();
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "searchInput");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "searchInput");
  }

  @Test
  public void navigationTracksCurrentPageWithClearInactiveAndContrastingFocusedIcons() {
    main(
        () -> {
          View home = activity.home.findViewById(R.id.nav_home),
              search = activity.home.findViewById(R.id.nav_search),
              settings = activity.home.findViewById(R.id.nav_settings);
          assertTrue(home.isSelected());
          assertFalse(search.isSelected());
          assertFalse(settings.isSelected());
          assertEquals("Current page", home.getStateDescription().toString());
          android.graphics.Bitmap normal =
              android.graphics.Bitmap.createBitmap(
                  search.getWidth(), search.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
          search.getBackground().setBounds(0, 0, normal.getWidth(), normal.getHeight());
          search.getBackground().draw(new android.graphics.Canvas(normal));
          assertEquals(
              "Inactive controls share the capsule without separate tiles",
              0,
              android.graphics.Color.alpha(
                  normal.getPixel(normal.getWidth() / 2, normal.getHeight() / 2)));
          normal.recycle();
          activity.home.showSearch();
          assertTrue(search.isSelected());
          assertFalse(home.isSelected());
          activity.home.showSettings();
          assertTrue(settings.isSelected());
          assertFalse(search.isSelected());
          activity.home.handleBack();
          assertTrue(search.isSelected());
          activity.home.showHome();
          assertTrue(home.isSelected());
          assertTrue(home.hasFocus());
          android.graphics.Bitmap focus =
              android.graphics.Bitmap.createBitmap(
                  home.getWidth(), home.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
          home.draw(new android.graphics.Canvas(focus));
          int dark = 0, light = 0;
          for (int y = 5; y < focus.getHeight() - 5; y++)
            for (int x = 5; x < focus.getWidth() - 5; x++) {
              int color = focus.getPixel(x, y);
              if (android.graphics.Color.red(color) < 60) dark++;
              if (android.graphics.Color.red(color) > 230) light++;
            }
          assertTrue("Focused navigation keeps dark icon strokes", dark > 10);
          assertTrue("Focused surface stays white", light > 50);
          focus.recycle();
        });
  }

  @Test
  public void catalogMediaTagsDoNotInventTypesForPublishedProviderCards() {
    RecyclerView provider = NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    main(
        () ->
            assertEquals(
                View.GONE,
                ((NativeCardAdapter.Holder) provider.findViewHolderForAdapterPosition(0))
                    .tag.getVisibility()));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    main(
        () -> {
          View focused = activity.home.findFocus();
          RecyclerView row = (RecyclerView) focused.getParent();
          NativeCardAdapter.Holder card =
              (NativeCardAdapter.Holder) row.findContainingViewHolder(focused);
          assertEquals(View.VISIBLE, card.tag.getVisibility());
          assertEquals("FILM", card.tag.getText().toString());
          assertTrue(card.itemView.getContentDescription().toString().contains(". FILM. "));
        });
  }

  @Test
  public void fixedCardBoundsContainEnabledAndUnavailableAppAndWatchSubtitles() {
    main(
        () -> {
          NativeUi ui = new NativeUi(activity);
          NativeRowAdapter rows = new NativeRowAdapter(ui, activity.images, null, card -> {});
          RecyclerView parent = new RecyclerView(activity);
          for (NativeCard.Kind kind :
              new NativeCard.Kind[] {NativeCard.Kind.APP, NativeCard.Kind.WATCH}) {
            for (boolean enabled : new boolean[] {true, false}) {
              NativeRowAdapter.Holder section = rows.onCreateViewHolder(parent, kind.ordinal());
              NativeCard card =
                  new NativeCard(
                      "geometry:" + kind + enabled,
                      "A title",
                      "Not installed · A second line",
                      "",
                      "",
                      kind,
                      null,
                      null,
                      enabled,
                      -1);
              section.cards.submitList(java.util.Collections.singletonList(card));
              View view = section.itemView;
              view.measure(
                  View.MeasureSpec.makeMeasureSpec(ui.px(1700), View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
              view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
              RecyclerView row = (RecyclerView) ((ViewGroup) section.itemView).getChildAt(2);
              NativeCardAdapter.Holder holder =
                  (NativeCardAdapter.Holder) row.findViewHolderForAdapterPosition(0);
              assertNotNull(holder);
              assertEquals(enabled, holder.itemView.isEnabled());
              assertFalse(
                  "Artwork motion supplies the card focus indicator",
                  holder.itemView.getDefaultFocusHighlightEnabled());
              assertTrue(
                  kind + " subtitle must fit its fixed card bounds",
                  holder.subtitle.getBottom() <= holder.itemView.getHeight());
              assertEquals(section.cards.contentHeight(), holder.itemView.getHeight());
              int fixedHeight = row.getHeight();
              holder.itemView.requestFocus();
              assertEquals("Focus must not resize the row", fixedHeight, row.getHeight());
            }
          }
        });
  }

  @Test
  public void offscreenRowAndHeaderScrollContinuouslyInsideNavSafeViewport() {
    java.util.List<Integer> deltas = new java.util.ArrayList<>();
    AtomicReference<RecyclerView> rowsRef = new AtomicReference<>();
    main(
        () -> {
          NativeUi ui = new NativeUi(activity);
          View gallery = activity.home.findViewById(R.id.hero_open);
          int[] position = new int[2];
          gallery.getLocationOnScreen(position);
          assertEquals(
              "The clipped viewport preserves the original gallery action position",
              ui.px(734),
              position[1]);
          RecyclerView rows = activity.home.findViewById(R.id.home_rows);
          rowsRef.set(rows);
          assertEquals(ui.px(100), rows.getTop());
          assertTrue(rows.getClipChildren());
          Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
          data.programs.clear();
          data.channels.clear();
          data.programStatus = "Provider history unavailable.";
          for (Library.App app : data.apps) app.launch = null;
          activity.home.renderSnapshot(data);
          rows.addOnScrollListener(
              new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(RecyclerView recycler, int dx, int dy) {
                  if (dy != 0) deltas.add(dy);
                }
              });
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "selector");
    main(deltas::clear);
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    main(
        () -> {
          assertContinuousScroll(deltas);
          assertVisibleTextBelowNavigation(rowsRef.get(), new NativeUi(activity).px(100));
          View focus = activity.home.findFocus();
          android.graphics.Rect bounds = new android.graphics.Rect();
          assertTrue(focus.getGlobalVisibleRect(bounds));
          assertEquals(
              "Focused card must fit the clipped viewport", focus.getHeight(), bounds.height());
          deltas.clear();
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "selector");
    main(
        () -> {
          assertContinuousScroll(deltas);
          int[] position = new int[2];
          activity.home.findViewById(R.id.hero_open).getLocationOnScreen(position);
          assertEquals(new NativeUi(activity).px(734), position[1]);
          assertVisibleTextBelowNavigation(rowsRef.get(), new NativeUi(activity).px(100));
        });
  }

  private static void assertContinuousScroll(java.util.List<Integer> deltas) {
    if (NativeUi.reducedMotion()) return;
    int total = 0, largest = 0;
    for (int delta : deltas) {
      total += Math.abs(delta);
      largest = Math.max(largest, Math.abs(delta));
    }
    assertTrue(
        "Offscreen movement must span several rendered native scroll steps: " + deltas,
        deltas.size() >= 4);
    assertTrue(
        "A single attachment/layout step must not perform most of the movement: " + deltas,
        largest < total * .75f);
  }

  private static void assertVisibleTextBelowNavigation(View view, int viewportTop) {
    if (view instanceof TextView && view.isShown()) {
      android.graphics.Rect visible = new android.graphics.Rect();
      if (view.getGlobalVisibleRect(visible))
        assertTrue(
            "Previously scrolled text must be clipped clear of nav: " + ((TextView) view).getText(),
            visible.top >= viewportTop);
    }
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        assertVisibleTextBelowNavigation(((ViewGroup) view).getChildAt(i), viewportTop);
  }
}
