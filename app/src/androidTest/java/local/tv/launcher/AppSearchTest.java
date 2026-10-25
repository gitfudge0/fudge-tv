package local.tv.launcher;

import static org.junit.Assert.*;

import android.app.SearchManager;
import android.content.*;
import android.database.MatrixCursor;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppSearchTest {
  @Test
  public void uriHonorsSelectionAndEncodesQueryAndLimit() {
    Uri uri = AppSearch.suggestionUri("owner.provider", "v1", null, "a/b ?");
    assertEquals("content", uri.getScheme());
    assertEquals("a/b ?", uri.getLastPathSegment());
    assertEquals("20", uri.getQueryParameter("limit"));
    assertEquals(
        "/v1/search_suggest_query",
        AppSearch.suggestionUri("owner.provider", "v1", "name = ?", "secret").getPath());
  }

  @Test
  public void suggestionLaunchesStayWithExportedOwnerAndStripPayload() {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    String owner = context.getPackageName();
    ComponentName component = new ComponentName(owner, MainActivity.class.getName());
    MatrixCursor cursor =
        new MatrixCursor(
            new String[] {
              SearchManager.SUGGEST_COLUMN_TEXT_1, SearchManager.SUGGEST_COLUMN_INTENT_DATA,
              SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
                  SearchManager.SUGGEST_COLUMN_INTENT_ACTION
            });
    cursor.addRow(new Object[] {"A title", "content://owner/item", "a/b", Intent.ACTION_VIEW});
    cursor.moveToFirst();
    Library.Program program = AppSearch.suggestion(context, owner, component, null, null, cursor);
    assertNotNull(program);
    assertEquals(component, program.launch.getComponent());
    assertEquals(owner, program.launch.getPackage());
    assertEquals("a/b", program.launch.getData().getLastPathSegment());
    assertNull(program.launch.getExtras());
    assertNull(program.launch.getClipData());
    assertNull(AppSearch.suggestion(context, "other.package", component, null, null, cursor));
    cursor.close();
    for (String action : new String[] {Intent.ACTION_DELETE, Intent.ACTION_VIEW}) {
      MatrixCursor invalid =
          new MatrixCursor(
              new String[] {
                SearchManager.SUGGEST_COLUMN_TEXT_1,
                SearchManager.SUGGEST_COLUMN_INTENT_DATA,
                SearchManager.SUGGEST_COLUMN_INTENT_ACTION
              });
      invalid.addRow(new Object[] {"Unsafe", "file:///private", action});
      invalid.moveToFirst();
      assertNull(AppSearch.suggestion(context, owner, component, null, null, invalid));
      invalid.close();
    }
  }

  private java.util.List<Library.App> fixtureApps(Context context) {
    return java.util.Collections.singletonList(
        new Library.App(
            context.getPackageName(),
            "Fixture",
            new Intent()
                .setComponent(
                    new ComponentName(
                        context.getPackageName(), "local.tv.launcher.SearchFixtureActivity"))));
  }

  @Test
  public void realSearchableProviderReturnsOwnerMappedContentAndLaunchesIt() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AppSearch search = new AppSearch(context);
    CountDownLatch done = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<Library.Program> found =
        new java.util.concurrent.atomic.AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                search.search(
                    "needle",
                    fixtureApps(context),
                    (items, finished) -> {
                      for (Library.Program item : items) if (!item.searchHandoff) found.set(item);
                      if (finished) done.countDown();
                    }));
    try {
      assertTrue(done.await(5, TimeUnit.SECONDS));
      Library.Program result = found.get();
      assertNotNull(result);
      assertEquals("Fixture needle", result.title);
      assertEquals(context.getPackageName(), result.owner);
      assertEquals("needle", result.launch.getData().getLastPathSegment());
      context.getSharedPreferences("search-fixture", Context.MODE_PRIVATE).edit().clear().commit();
      context.startActivity(new Intent(result.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      long deadline = android.os.SystemClock.uptimeMillis() + 2000;
      while (android.os.SystemClock.uptimeMillis() < deadline
          && !context.getSharedPreferences("search-fixture", Context.MODE_PRIVATE).contains("data"))
        Thread.sleep(30);
      assertEquals(
          result.launch.getData().toString(),
          context
              .getSharedPreferences("search-fixture", Context.MODE_PRIVATE)
              .getString("data", ""));
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync(search::close);
    }
  }

  @Test
  public void canceledSlowProviderDoesNotPublishStaleResultsOrBlockNextQuery() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AppSearch search = new AppSearch(context);
    CountDownLatch done = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger obsolete =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicReference<java.util.List<Library.Program>> received =
        new java.util.concurrent.atomic.AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                search.search(
                    "slow", fixtureApps(context), (items, finished) -> obsolete.incrementAndGet()));
    Thread.sleep(150);
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              search.search(
                  "fresh",
                  fixtureApps(context),
                  (items, finished) -> {
                    if (finished) {
                      received.set(items);
                      done.countDown();
                    }
                  });
            });
    int before = obsolete.get();
    try {
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertTrue(received.get().stream().anyMatch(item -> "Fixture fresh".equals(item.title)));
      for (Library.Program item : received.get())
        if (!item.searchHandoff) assertEquals("Fixture fresh", item.title);
      Thread.sleep(1000);
      assertEquals(before, obsolete.get());
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync(search::close);
    }
  }

  @Test
  public void providerFailureKeepsExplicitSearchHandoffAvailable() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AppSearch search = new AppSearch(context);
    CountDownLatch done = new CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<java.util.List<Library.Program>> received =
        new java.util.concurrent.atomic.AtomicReference<>();
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                search.search(
                    "broken",
                    fixtureApps(context),
                    (items, finished) -> {
                      if (finished) {
                        received.set(items);
                        done.countDown();
                      }
                    }));
    try {
      assertTrue(done.await(5, TimeUnit.SECONDS));
      assertFalse(received.get().isEmpty());
      assertTrue(received.get().stream().allMatch(item -> item.searchHandoff));
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync(search::close);
    }
  }

  @Test
  public void actualAppUidSearchProbe() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    AppSearch search = new AppSearch(context);
    Library.Snapshot snapshot = Library.load(context);
    CountDownLatch done = new CountDownLatch(1);
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () ->
                search.search(
                    "batman",
                    snapshot.allApps,
                    (results, finished) -> {
                      int content = 0, handoffs = 0;
                      for (Library.Program result : results) {
                        if (result.searchHandoff) handoffs++;
                        else content++;
                      }
                      android.util.Log.i(
                          "FudgeAppSearchProbe", "content=" + content + ",handoffs=" + handoffs);
                      if (finished) done.countDown();
                    }));
    assertTrue(done.await(5, TimeUnit.SECONDS));
    InstrumentationRegistry.getInstrumentation().runOnMainSync(search::close);
  }
}
