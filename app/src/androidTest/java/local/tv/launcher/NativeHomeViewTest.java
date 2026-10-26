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
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "watch:0");
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
  public void homeUsesRecycledNativeRowsAndStartsAtContinueWatching() {
    main(
        () -> {
          assertNotNull(activity.home.findViewById(R.id.home_wallpaper));
          assertTrue(
              activity
                  .home
                  .findFocus()
                  .getContentDescription()
                  .toString()
                  .startsWith("Shared title 0"));
          assertTrue(count(activity.home, RecyclerView.class) >= 2);
          RecyclerView rows = activity.home.findViewById(R.id.home_rows);
          assertEquals(5, rows.getAdapter().getItemCount());
          assertEquals(
              "Continue Watching",
              ((NativeRowAdapter) rows.getAdapter()).getCurrentList().get(0).title);
          assertEquals(
              12, ((NativeRowAdapter) rows.getAdapter()).getCurrentList().get(0).cards.size());
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
  public void recommendationsUseOnlyTheHighlightedAppsProviderIdentity() {
    RecyclerView recommendationsRow =
        NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    AtomicReference<View> outgoingArtwork = new AtomicReference<>();
    main(
        () ->
            outgoingArtwork.set(
                ((NativeCardAdapter.Holder)
                        recommendationsRow.findContainingViewHolder(activity.home.findFocus()))
                    .artwork));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    main(
        () ->
            assertNull(
                "Outgoing focus outline must clear immediately",
                outgoingArtwork.get().getForeground()));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app0");
    main(() -> assertTrue(activity.home.findFocus().isSelected()));
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
    waitMs(150);
    main(
        () -> {
          NativeRowAdapter rows =
              (NativeRowAdapter)
                  ((RecyclerView) activity.home.findViewById(R.id.home_rows)).getAdapter();
          for (NativeRowAdapter.Row row : rows.getCurrentList())
            assertNotEquals("Empty recommendation rails must be absent", "recommendations", row.id);
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
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
              "Searching your apps and Cinemeta…",
              ((TextView) activity.home.findViewById(R.id.search_status)).getText().toString());
          activity.home.renderAppSearch(java.util.Collections.emptyList(), true);
          assertEquals(
              "Cinemeta unavailable.",
              ((TextView) activity.home.findViewById(R.id.search_status)).getText().toString());
          Library.Program result = new Library.Program();
          result.id = "appsearch:result:test";
          result.owner = "fixture.app";
          result.title = "A real app result";
          activity.home.renderAppSearch(java.util.Collections.singletonList(result), true);
          assertEquals(
              "App results must not be described as a failed catalog search",
              "",
              ((TextView) activity.home.findViewById(R.id.search_status)).getText().toString());
        });
  }

  @Test
  public void searchExcludesStremioProgramsAcrossSourcesButKeepsItsAppAndCatalog() {
    main(
        () -> {
          Library.Snapshot snapshot = new Library.Snapshot();
          snapshot.allApps.add(
              new Library.App("com.stremio.one", "Stremio", new Intent("fixture")));
          Library.Channel channel = new Library.Channel();
          snapshot.channels.add(channel);
          java.util.List<Library.Program> provider = new java.util.ArrayList<>();
          for (int i = 0; i < 3; i++) {
            Library.Program excluded = new Library.Program();
            excluded.id = "stremio:" + i;
            excluded.owner = "com.stremio.one";
            excluded.title = "Stremio match";
            if (i == 0) snapshot.programs.add(excluded);
            else if (i == 1) channel.programs.add(excluded);
            else provider.add(excluded);
          }
          Library.Program included = new Library.Program();
          included.id = "other:match";
          included.owner = "fixture.app";
          included.title = "Stremio documentary";
          provider.add(included);
          activity.home.renderSnapshot(snapshot);
          activity.home.showSearch();
          ((EditText) activity.home.findViewById(R.id.search_input)).setText("Stremio");
          activity.home.renderAppSearch(provider, true);
          try {
            activity.home.renderSearch(
                new org.json.JSONObject()
                    .put(
                        "items",
                        new org.json.JSONArray()
                            .put(
                                new org.json.JSONObject()
                                    .put("key", "movie:tt1234567")
                                    .put("title", "Catalog title"))));
          } catch (org.json.JSONException impossible) {
            throw new AssertionError(impossible);
          }
        });
    waitMs(150);
    main(
        () -> {
          NativeRowAdapter adapter =
              (NativeRowAdapter)
                  ((RecyclerView) activity.home.findViewById(R.id.search_rows)).getAdapter();
          assertEquals(3, adapter.getItemCount());
          assertEquals("Apps", adapter.getCurrentList().get(0).title);
          assertEquals("com.stremio.one", adapter.getCurrentList().get(0).cards.get(0).app.id);
          NativeRowAdapter.Row shared = adapter.getCurrentList().get(1);
          assertEquals("From your apps", shared.title);
          assertEquals(1, shared.cards.size());
          assertEquals("other:match", shared.cards.get(0).id);
          assertEquals("Movies and series", adapter.getCurrentList().get(2).title);
          EditText input = activity.home.findViewById(R.id.search_input);
          ViewGroup page = (ViewGroup) input.getParent();
          int headerLeft = activity.home.findViewById(R.id.home_header).getLeft();
          assertEquals(headerLeft, input.getLeft());
          assertEquals(headerLeft, page.getChildAt(0).getPaddingLeft());
          assertEquals(headerLeft, activity.home.findViewById(R.id.search_status).getPaddingLeft());
        });
  }

  @Test
  public void searchOmitsHandoffsAndShowsRealProviderResultsAsLandscapeCards() {
    Library.Program handoff = new Library.Program();
    handoff.id = "appsearch:handoff:fixture";
    handoff.owner = "fixture.app";
    handoff.title = "Search in Fixture";
    handoff.searchHandoff = true;
    main(
        () -> {
          activity.home.showSearch();
          ((EditText) activity.home.findViewById(R.id.search_input)).setText("needle");
          activity.home.renderAppSearch(java.util.Collections.singletonList(handoff), true);
        });
    waitMs(150);
    main(
        () -> {
          NativeRowAdapter adapter =
              (NativeRowAdapter)
                  ((RecyclerView) activity.home.findViewById(R.id.search_rows)).getAdapter();
          assertEquals(
              "A handoff alone must not create an activatable search section",
              0,
              adapter.getItemCount());
          assertTrue(activity.resumes.isEmpty());
          Library.Program result = new Library.Program();
          result.id = "appsearch:result:fixture";
          result.owner = "fixture.app";
          result.title = "Needle from Fixture";
          activity.home.renderAppSearch(java.util.Arrays.asList(handoff, result), true);
        });
    waitMs(150);
    main(
        () -> {
          RecyclerView rows = activity.home.findViewById(R.id.search_rows);
          NativeRowAdapter adapter = (NativeRowAdapter) rows.getAdapter();
          assertEquals(1, adapter.getItemCount());
          NativeRowAdapter.Row row = adapter.getCurrentList().get(0);
          assertEquals("From your apps", row.title);
          assertEquals(NativeCard.Kind.RECOMMENDATION, row.kind);
          assertEquals(1, row.cards.size());
          assertEquals("appsearch:result:fixture", row.cards.get(0).id);
          ViewGroup section = (ViewGroup) rows.findViewHolderForAdapterPosition(0).itemView;
          RecyclerView cards = (RecyclerView) section.getChildAt(2);
          NativeCardAdapter.Holder card =
              (NativeCardAdapter.Holder) cards.findViewHolderForAdapterPosition(0);
          assertEquals(16f / 9, card.artwork.getWidth() / (float) card.artwork.getHeight(), .02f);
          assertEquals("Needle from Fixture", card.title.getText().toString());
          card.itemView.performClick();
          assertEquals(
              java.util.Collections.singletonList("appsearch:result:fixture"), activity.resumes);
          assertNull(activity.openedTitle);
        });
  }

  @Test
  public void detailRefreshPreservesFocusedActionAndClosingCancelsRequest() {
    NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
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
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
  }

  @Test
  public void cinematicPreviewFillsPanelAndRapidReopenSurvivesOldExit() {
    org.json.JSONObject item =
        NativeUiTestActivity.fixtureCatalog().optJSONArray("items").optJSONObject(0);
    main(() -> activity.home.showDetails(item));
    waitMs(300);
    main(
        () -> {
          try {
            java.lang.reflect.Field artField = NativeHomeView.class.getDeclaredField("detailArt");
            artField.setAccessible(true);
            android.widget.ImageView art = (android.widget.ImageView) artField.get(activity.home);
            View panel = (View) art.getParent();
            float scale = activity.getResources().getDisplayMetrics().widthPixels / 1920f;
            assertEquals(Math.round(1650 * scale), panel.getWidth());
            assertEquals(panel.getWidth(), art.getWidth());
            assertEquals(panel.getHeight(), art.getHeight());
            assertEquals(0, art.getLeft());
            assertEquals(0, art.getTop());
            activity.home.handleBack();
            activity.home.showDetails(item);
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        });
    waitMs(350);
    assertEquals("Open in Stremio", activeFocus().getText().toString());
    main(
        () -> {
          assertEquals(1, activity.detailCancellations);
          activity.home.handleBack();
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "watch:0");
  }

  @Test
  public void fixedPreviewKeepsTallArtworkAndLongCopyInsideWindow() {
    org.json.JSONObject item =
        NativeUiTestActivity.fixtureCatalog().optJSONArray("items").optJSONObject(0);
    try {
      item.put(
          "title",
          "A Very Long Movie Title That Would Otherwise Wrap Across Many Lines And Push All Other"
              + " Content Away");
      item.put(
          "overview",
          new String(new char[30])
              .replace(
                  "\0",
                  "A long synopsis remains available in Stremio while this preview stays fixed. "));
      item.put(
          "cast", new org.json.JSONArray().put("A long actor name").put("Another long actor name"));
    } catch (org.json.JSONException e) {
      throw new AssertionError(e);
    }
    main(
        () -> {
          activity.home.showDetails(item);
          android.widget.ImageView art = (android.widget.ImageView) detailField("detailArt");
          art.setImageDrawable(
              new android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE) {
                @Override
                public int getIntrinsicWidth() {
                  return 1650;
                }

                @Override
                public int getIntrinsicHeight() {
                  return 3000;
                }
              });
          art.requestLayout();
        });
    waitMs(350);
    main(
        () -> {
          View art = (View) detailField("detailArt");
          ViewGroup panel = (ViewGroup) art.getParent();
          float scale = activity.getResources().getDisplayMetrics().widthPixels / 1920f;
          assertEquals(
              Math.min(
                  Math.round(860 * scale),
                  Math.round(activity.getResources().getDisplayMetrics().heightPixels * .9f)),
              panel.getHeight());
          assertEquals(panel.getHeight(), art.getHeight());
          for (android.view.ViewParent parent = art.getParent();
              parent instanceof View;
              parent = parent.getParent()) {
            assertFalse(
                "The preview must have no scrollable ancestor",
                parent instanceof android.widget.ScrollView);
          }
          assertEquals(0, count(panel, android.widget.ScrollView.class));
          for (String field :
              new String[] {"detailTitle", "detailMeta", "detailDescription", "detailCast"}) {
            View text = (View) detailField(field);
            android.graphics.Rect bounds =
                new android.graphics.Rect(0, 0, text.getWidth(), text.getHeight());
            panel.offsetDescendantRectToMyCoords(text, bounds);
            assertTrue(field + " must start within the fixed panel", bounds.top >= 0);
            assertTrue(
                field + " must end within the fixed panel", bounds.bottom <= panel.getHeight());
          }
          View action = panel.findFocus();
          assertNotNull(action);
          android.graphics.Rect bounds =
              new android.graphics.Rect(0, 0, action.getWidth(), action.getHeight());
          panel.offsetDescendantRectToMyCoords(action, bounds);
          assertTrue(bounds.top >= 0);
          assertTrue(bounds.bottom <= panel.getHeight());
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    main(
        () -> {
          View panel = (View) ((View) detailField("detailArt")).getParent();
          assertEquals(0, panel.getScrollY());
          assertEquals(0, panel.getScrollX());
        });
  }

  private Object detailField(String name) {
    try {
      java.lang.reflect.Field field = NativeHomeView.class.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(activity.home);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
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
          activity.home.findViewById(R.id.nav_home).performClick();
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "watch:0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    main(() -> assertEquals("watch:0", activity.resumes.get(0)));
  }

  @Test
  public void dpadTraversesHeaderAndContinueWatchingAndActivatesRealProgram() {
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "home");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "watch:0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    instrumentation.waitForIdleSync();
    main(() -> assertEquals("watch:0", activity.resumes.get(0)));
  }

  @Test
  public void fixedGradientUsesReferenceColorsAndHeaderIsCompact() {
    main(
        () -> {
          View background = activity.home.findViewById(R.id.home_wallpaper);
          assertFalse(background instanceof android.widget.ImageView);
          android.graphics.Bitmap pixels =
              android.graphics.Bitmap.createBitmap(
                  100, 100, android.graphics.Bitmap.Config.ARGB_8888);
          android.graphics.drawable.Drawable gradient = background.getBackground();
          android.graphics.Rect bounds = new android.graphics.Rect(gradient.getBounds());
          gradient.setBounds(0, 0, 100, 100);
          gradient.draw(new android.graphics.Canvas(pixels));
          gradient.setBounds(bounds);
          assertColorNear(0xff20263e, pixels.getPixel(0, 0));
          assertColorNear(0xff30243f, pixels.getPixel(99, 0));
          assertColorNear(0xff0b1120, pixels.getPixel(50, 99));
          pixels.recycle();
          RecyclerView rows = activity.home.findViewById(R.id.home_rows);
          NativeRowAdapter.Holder first =
              (NativeRowAdapter.Holder) rows.findViewHolderForAdapterPosition(0);
          int[] title = new int[2];
          first.title.getLocationOnScreen(title);
          assertEquals(activity.home.getHeight() * .30f, title[1], 3f);
          ViewGroup header = activity.home.findViewById(R.id.home_header);
          assertEquals(3, header.getChildCount());
          NativeUi coordinates = new NativeUi(activity);
          assertEquals(
              coordinates.px(5) * 2 + coordinates.px(44) * 3 + coordinates.px(6) * 2,
              header.getWidth());
          assertEquals("Home", activity.home.findViewById(R.id.nav_home).getContentDescription());
          assertEquals(
              "", ((TextView) activity.home.findViewById(R.id.nav_home)).getText().toString());
        });
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
  public void recycledRowsRetainIndependentOffsetsWithContextualStateKeys() {
    AtomicReference<NativeRowAdapter> adapterRef = new AtomicReference<>();
    main(
        () -> {
          NativeRowAdapter adapter =
              new NativeRowAdapter(new NativeUi(activity), activity.images, null, card -> {});
          adapterRef.set(adapter);
          RecyclerView vertical = activity.home.findViewById(R.id.home_rows);
          vertical.setAdapter(adapter);
          vertical.setItemViewCacheSize(0);
          vertical
              .getRecycledViewPool()
              .setMaxRecycledViews(NativeCard.Kind.RECOMMENDATION.ordinal(), 1);
          adapter.submitList(recycledFixtureRows(false));
        });
    waitMs(200);
    java.util.Set<RecyclerView.ViewHolder> seen =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    boolean reused = false;
    for (int position = 0; position < 20; position++) {
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
            androidx.recyclerview.widget.LinearLayoutManager verticalLayout =
                (androidx.recyclerview.widget.LinearLayoutManager) vertical.getLayoutManager();
            assertNotNull(
                "Missing target row "
                    + index
                    + "; visible "
                    + verticalLayout.findFirstVisibleItemPosition()
                    + ".."
                    + verticalLayout.findLastVisibleItemPosition()
                    + "; scroll state "
                    + vertical.getScrollState(),
                holder);
            repeated[0] = !seen.add(holder);
            RecyclerView horizontal = (RecyclerView) ((ViewGroup) holder.itemView).getChildAt(2);
            androidx.recyclerview.widget.LinearLayoutManager layout =
                (androidx.recyclerview.widget.LinearLayoutManager) horizontal.getLayoutManager();
            assertEquals(
                "Fresh logical row inherited another row's offset",
                0,
                layout.findFirstVisibleItemPosition());
            layout.scrollToPositionWithOffset(15, 0);
          });
      reused |= repeated[0];
      waitMs(80);
    }
    assertTrue("Workload must reuse a native row holder", reused);
    main(() -> ((RecyclerView) activity.home.findViewById(R.id.home_rows)).scrollToPosition(0));
    waitMs(200);
    main(
        () -> {
          RecyclerView vertical = activity.home.findViewById(R.id.home_rows);
          NativeRowAdapter.Holder holder =
              (NativeRowAdapter.Holder) vertical.findViewHolderForAdapterPosition(0);
          RecyclerView horizontal = (RecyclerView) ((ViewGroup) holder.itemView).getChildAt(2);
          assertEquals(
              15,
              ((androidx.recyclerview.widget.LinearLayoutManager) horizontal.getLayoutManager())
                  .findFirstVisibleItemPosition());
          horizontal.findViewHolderForAdapterPosition(15).itemView.requestFocus();
          adapterRef.get().submitList(recycledFixtureRows(true));
        });
    waitMs(300);
    main(
        () ->
            assertTrue(
                activity
                    .home
                    .findFocus()
                    .getContentDescription()
                    .toString()
                    .startsWith("Published title 15 refreshed")));
  }

  private static java.util.List<NativeRowAdapter.Row> recycledFixtureRows(boolean refreshed) {
    java.util.List<NativeRowAdapter.Row> rows = new java.util.ArrayList<>();
    java.util.List<NativeCard> cards = new java.util.ArrayList<>();
    for (Library.Program program :
        NativeUiTestActivity.fixtureSnapshot().channels.get(0).programs) {
      if (refreshed) program.title += " refreshed";
      cards.add(NativeCard.recommendation(program));
    }
    for (int i = 0; i < 24; i++)
      rows.add(
          new NativeRowAdapter.Row(
              "test-row:" + i,
              "Provider " + i,
              "",
              NativeCard.Kind.RECOMMENDATION,
              cards,
              "test-state:" + i));
    return rows;
  }

  @Test
  public void rapidAppSwitchAndReorderKeepRecommendationsWithTheirApp() {
    main(
        () -> {
          Library.Snapshot data = twoAppSnapshot();
          activity.home.renderSnapshot(data);
        });
    NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app0");
    // App focus and Down happen before the async vertical/card diffs can commit.
    main(
        () -> {
          RecyclerView apps = (RecyclerView) activity.home.findFocus().getParent();
          apps.findViewHolderForAdapterPosition(1).itemView.requestFocus();
          activity.home.dispatchKeyEvent(
              new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
          activity.home.dispatchKeyEvent(
              new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN));
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "second:0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "second:1");
    main(
        () -> {
          Library.Snapshot data = twoAppSnapshot();
          java.util.Collections.swap(data.apps, 0, 1);
          activity.home.renderSnapshot(data);
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "second:1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "second:1");
  }

  private static Library.Snapshot twoAppSnapshot() {
    Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
    Library.Channel channel = new Library.Channel();
    channel.id = "second";
    channel.owner = "fixture.app1";
    channel.title = "Second app";
    for (int i = 0; i < 8; i++) {
      Library.Program program = new Library.Program();
      program.id = "second:" + i;
      program.title = "Second title " + i;
      program.owner = channel.owner;
      program.art = "https://images.metahub.space/fixture.jpg";
      channel.programs.add(program);
    }
    data.channels.add(channel);
    return data;
  }

  @Test
  public void contextualRecommendationsRefreshAndRemovedAppFallsBackSafely() {
    NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    main(
        () -> {
          Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
          data.channels.get(0).programs.get(1).title = "Updated provider title";
          activity.home.renderSnapshot(data);
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "published:1");
    waitMs(100);
    main(
        () -> {
          RecyclerView row = (RecyclerView) activity.home.findFocus().getParent();
          NativeCardAdapter.Holder focused =
              (NativeCardAdapter.Holder) row.findContainingViewHolder(activity.home.findFocus());
          assertEquals("Updated provider title", focused.title.getText().toString());
          assertEquals(
              16f / 9, focused.artwork.getWidth() / (float) focused.artwork.getHeight(), .02f);
          Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
          data.apps.remove(0);
          activity.home.renderSnapshot(data);
        });
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
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
    // Dispatch a burst in one native UI turn, before newly requested rows can finish layout.
    main(
        () -> {
          for (int key :
              new int[] {
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
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "home");
  }

  @Test
  public void emptyAndDisabledRowsAreSkippedWithoutEmptyHistoryState() {
    main(
        () -> {
          Library.Snapshot data = NativeUiTestActivity.fixtureSnapshot();
          data.programs.clear();
          data.channels.clear();
          data.programStatus = "Provider history unavailable.";
          for (Library.App app : data.apps) app.launch = null;
          activity.home.renderSnapshot(data);
        });
    main(() -> activity.home.findViewById(R.id.nav_home).requestFocus());
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "home");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "home");
    main(
        () -> {
          RecyclerView rows = activity.home.findViewById(R.id.home_rows);
          assertEquals(0, countText(activity.home, "Provider history unavailable."));
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
    main(
        () -> {
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
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "app:fixture.app1");
  }

  @Test
  public void unavailableCatalogStillNavigatesBetweenHeaderAndSharedRows() {
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
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "watch:0");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "home");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "watch:0");
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
          home.requestFocus();
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
  public void appsAndPostersKeepOriginalFocusGeometryAndInputsUseSingleLineLabels() {
    main(
        () -> {
          NativeUi ui = new NativeUi(activity);
          NativeRowAdapter rows = new NativeRowAdapter(ui, activity.images, null, card -> {});
          RecyclerView parent = new RecyclerView(activity);
          for (NativeCard.Kind kind :
              new NativeCard.Kind[] {
                NativeCard.Kind.APP, NativeCard.Kind.POSTER, NativeCard.Kind.INPUT
              }) {
            NativeRowAdapter.Holder section = rows.onCreateViewHolder(parent, kind.ordinal());
            java.util.List<NativeCard> cards = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++)
              cards.add(
                  new NativeCard(
                      "geometry:" + kind + i,
                      "Actual device or content title",
                      "HDMI 3 · Connected",
                      "",
                      "",
                      kind,
                      null,
                      null,
                      true,
                      -1));
            section.cards.setInstant(true);
            section.cards.submitList(cards);
            View view = section.itemView;
            view.measure(
                View.MeasureSpec.makeMeasureSpec(ui.px(1700), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
            RecyclerView rail = (RecyclerView) ((ViewGroup) view).getChildAt(2);
            NativeCardAdapter.Holder first =
                (NativeCardAdapter.Holder) rail.findViewHolderForAdapterPosition(0);
            NativeCardAdapter.Holder second =
                (NativeCardAdapter.Holder) rail.findViewHolderForAdapterPosition(1);
            assertNotNull(first);
            assertNotNull(second);
            if (kind == NativeCard.Kind.INPUT) {
              assertEquals(ui.px(300), first.artwork.getWidth());
              assertEquals(ui.px(105), first.artwork.getHeight());
              assertEquals(first.artwork, first.title.getParent());
              assertEquals(1, first.title.getMaxLines());
              assertEquals(View.GONE, first.subtitle.getVisibility());
              assertTrue(
                  first.itemView.getContentDescription().toString().contains("HDMI 3 · Connected"));
            } else {
              assertEquals(
                  ui.px(kind == NativeCard.Kind.APP ? 220 : 200), first.artwork.getWidth());
              assertEquals(
                  ui.px(kind == NativeCard.Kind.APP ? 124 : 300), first.artwork.getHeight());
              assertEquals(first.itemView, first.title.getParent());
              assertTrue(first.title.getTop() > first.artwork.getBottom());
              assertEquals(0f, first.artwork.getPivotX(), .01f);
              first.itemView.requestFocus();
              assertEquals(
                  kind == NativeCard.Kind.APP ? 290f / 220 : 1.25f,
                  first.artwork.getScaleX(),
                  .001f);
              assertEquals(
                  ui.px(kind == NativeCard.Kind.APP ? 70 : 50),
                  second.itemView.getTranslationX(),
                  1f);
              assertNull(
                  "Original app/poster focus uses scale rather than a new white border",
                  first.artwork.getForeground());
            }
          }
          NativeRowAdapter home =
              (NativeRowAdapter)
                  ((RecyclerView) activity.home.findViewById(R.id.home_rows)).getAdapter();
          assertEquals(NativeCard.Kind.POSTER, home.getCurrentList().get(3).kind);
        });
  }

  @Test
  public void discoveredInputLabelsAndStatusReachTheInputActivationListener() {
    main(
        () -> {
          Library.Snapshot snapshot = NativeUiTestActivity.fixtureSnapshot();
          snapshot.inputs.add(
              new TvInputs.Entry(
                  "fixture/real-input",
                  "Living-room receiver",
                  "HDMI 2",
                  android.media.tv.TvInputManager.INPUT_STATE_CONNECTED));
          activity.home.renderSnapshot(snapshot);
        });
    NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "input:fixture/real-input");
    main(
        () -> {
          String description = activity.home.findFocus().getContentDescription().toString();
          assertTrue(description.contains("Living-room receiver"));
          assertTrue(description.contains("HDMI 2"));
          assertTrue(description.contains("Connected"));
          NativeRowAdapter home =
              (NativeRowAdapter)
                  ((RecyclerView) activity.home.findViewById(R.id.home_rows)).getAdapter();
          java.util.List<NativeCard> inputs = home.getCurrentList().get(4).cards;
          assertEquals("Only discovered inputs belong in the input list", 1, inputs.size());
          assertEquals("input:fixture/real-input", inputs.get(0).id);
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    main(() -> assertEquals("input:fixture/real-input", activity.launches.get(0)));
  }

  @Test
  public void inputsFallbackActionsAreReachableAndGradientStaysFixedWhileRowsScroll() {
    android.graphics.Rect before = new android.graphics.Rect();
    main(() -> activity.home.findViewById(R.id.home_wallpaper).getGlobalVisibleRect(before));
    NativeUiTestDriver.reachProviderRow(instrumentation, activity);
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "movie:tt1000000");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "control:input-menu");
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
    main(
        () -> {
          assertEquals("input:null", activity.launches.get(0));
          NativeRowAdapter home =
              (NativeRowAdapter)
                  ((RecyclerView) activity.home.findViewById(R.id.home_rows)).getAdapter();
          java.util.List<NativeCard> inputs = home.getCurrentList().get(4).cards;
          assertEquals("No devices leaves only the system input-menu fallback", 1, inputs.size());
          assertEquals("control:input-menu", inputs.get(0).id);
          android.graphics.Rect after = new android.graphics.Rect();
          activity.home.findViewById(R.id.home_wallpaper).getGlobalVisibleRect(after);
          assertEquals(before, after);
          int[] header = new int[2];
          activity.home.findViewById(R.id.home_header).getLocationOnScreen(header);
          assertEquals(new NativeUi(activity).px(26), header[1]);
          assertVisibleTextBelowNavigation(
              activity.home.findViewById(R.id.home_rows),
              Math.round(activity.home.getHeight() * .15f));
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);
    NativeUiTestDriver.awaitFocus(instrumentation, activity, "control:input-menu");
    main(
        () ->
            assertEquals(
                "Header Settings remains available",
                "Settings",
                activity.home.findViewById(R.id.nav_settings).getContentDescription()));
  }

  private static void assertColorNear(int expected, int actual) {
    assertEquals(android.graphics.Color.red(expected), android.graphics.Color.red(actual), 2);
    assertEquals(android.graphics.Color.green(expected), android.graphics.Color.green(actual), 2);
    assertEquals(android.graphics.Color.blue(expected), android.graphics.Color.blue(actual), 2);
    assertEquals(255, android.graphics.Color.alpha(actual));
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
