package local.tv.launcher;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Offline Activity fixture; actual Activity cancellation, scheduler, and native view remain intact.
 */
public final class NativeLifecycleTestActivity extends MainActivity {
  static volatile Fixture fixture;

  static final class Fixture {
    final AtomicInteger searches = new AtomicInteger();
    boolean stagedLibrary;
    final AtomicInteger libraryLoads = new AtomicInteger();
    final CountDownLatch libraryStarted = new CountDownLatch(1);
    final CountDownLatch releaseWatch = new CountDownLatch(1);
    final CountDownLatch watchReady = new CountDownLatch(1);
    final CountDownLatch releasePreviews = new CountDownLatch(1);
    final CountDownLatch secondLibrary = new CountDownLatch(1);
    final CountDownLatch releaseSecondLibrary = new CountDownLatch(1);
    final CountDownLatch libraryCanceled = new CountDownLatch(1);
    final CountDownLatch firstStarted = new CountDownLatch(1);
    final CountDownLatch firstCanceled = new CountDownLatch(1);
    final CountDownLatch resumedSearch = new CountDownLatch(1);

    JSONObject response(String path) throws Exception {
      if (path.contains("search=")) {
        int number = searches.incrementAndGet();
        if (number == 1) {
          firstStarted.countDown();
          try {
            new CountDownLatch(1).await(5, TimeUnit.SECONDS);
          } catch (InterruptedException canceled) {
            firstCanceled.countDown();
            throw canceled;
          }
        } else resumedSearch.countDown();
      }
      String type = path.contains("/movie/") ? "movie" : "series";
      return new JSONObject()
          .put(
              "metas",
              new JSONArray()
                  .put(
                      new JSONObject()
                          .put("id", "movie".equals(type) ? "tt1160419" : "tt0944947")
                          .put("type", type)
                          .put("name", "Native fixture")));
    }
  }

  @Override
  protected Library.Snapshot loadLibraryApps(android.os.CancellationSignal signal) {
    if (!fixture.stagedLibrary) return super.loadLibraryApps(signal);
    Library.Snapshot apps = NativeUiTestActivity.fixtureSnapshot();
    apps.programs.clear();
    apps.channels.clear();
    return apps;
  }

  @Override
  protected Library.Snapshot loadLibraryRecommendations(
      Library.Snapshot apps,
      android.os.CancellationSignal signal,
      java.util.function.Consumer<Library.Snapshot> watchCallback) {
    Fixture current = fixture;
    if (!current.stagedLibrary)
      return super.loadLibraryRecommendations(apps, signal, watchCallback);
    int load = current.libraryLoads.incrementAndGet();
    if (load == 1) {
      current.libraryStarted.countDown();
      try {
        awaitLibrary(current.releaseWatch, signal);
      } catch (android.os.OperationCanceledException canceled) {
        current.libraryCanceled.countDown();
        throw canceled;
      }
      Library.Snapshot watch = NativeUiTestActivity.fixtureSnapshot();
      watch.channels.clear();
      watchCallback.accept(watch);
      current.watchReady.countDown();
      awaitLibrary(current.releasePreviews, signal);
    } else {
      current.secondLibrary.countDown();
      awaitLibrary(current.releaseSecondLibrary, signal);
    }
    return NativeUiTestActivity.fixtureSnapshot();
  }

  private static void awaitLibrary(CountDownLatch release, android.os.CancellationSignal signal) {
    try {
      if (!release.await(8, TimeUnit.SECONDS))
        throw new AssertionError("Fixture provider timed out");
    } catch (InterruptedException canceled) {
      throw new android.os.OperationCanceledException();
    }
    signal.throwIfCanceled();
  }

  @Override
  protected CatalogClient createCatalog() {
    Fixture current = fixture;
    return new CatalogClient(
        new CatalogClient.Cache(
            new File(getCacheDir(), "lifecycle-test-" + UUID.randomUUID()), 1024 * 1024),
        current::response);
  }
}
