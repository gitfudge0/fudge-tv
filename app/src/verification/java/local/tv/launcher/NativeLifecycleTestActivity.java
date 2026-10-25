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
  protected CatalogClient createCatalog() {
    Fixture current = fixture;
    return new CatalogClient(
        new CatalogClient.Cache(
            new File(getCacheDir(), "lifecycle-test-" + UUID.randomUUID()), 1024 * 1024),
        current::response);
  }
}
