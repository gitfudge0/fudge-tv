package local.tv.launcher;

import static org.junit.Assert.*;

import java.io.File;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CatalogConcurrencyTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private CatalogClient.Cache cache(String name) {
    return new CatalogClient.Cache(new File(temporary.getRoot(), name), 8 * 1024 * 1024);
  }

  private static JSONObject rows(String path) throws Exception {
    String type = path.contains("/movie/") ? "movie" : "series";
    return new JSONObject()
        .put(
            "metas",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("id", type.equals("movie") ? "tt1160419" : "tt0944947")
                        .put("type", type)
                        .put("name", type)));
  }

  @Test
  public void homeStartsAllIndependentSourcesBeforeAnyFinishes() throws Exception {
    CountDownLatch started = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try (CatalogClient client =
        new CatalogClient(
            cache("parallel"),
            path -> {
              calls.incrementAndGet();
              started.countDown();
              assertTrue("Test source was never released", release.await(5, TimeUnit.SECONDS));
              return rows(path);
            })) {
      Future<JSONObject> response = caller.submit(client::home);
      try {
        assertTrue(
            "Home serialized independent source requests", started.await(2, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
      JSONObject result = response.get(2, TimeUnit.SECONDS);
      assertEquals("live", result.getString("status"));
      assertFalse(result.getBoolean("partial"));
      assertEquals(2, result.getJSONArray("items").length());
      assertEquals(2, result.getJSONArray("featured").length());
      assertEquals(4, calls.get());
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  public void simultaneousDetailsShareFetchButNeverShareMutableJson() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<JSONObject> first = new AtomicReference<>();
    AtomicReference<JSONObject> second = new AtomicReference<>();
    try (CatalogClient client =
        new CatalogClient(
            cache("coalesced"),
            path -> {
              calls.incrementAndGet();
              started.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return new JSONObject()
                  .put(
                      "meta",
                      new JSONObject()
                          .put("id", "tt1160419")
                          .put("type", "movie")
                          .put("name", "Dune"));
            })) {
      Thread leader = new Thread(() -> first.set(client.details("movie", "tt1160419")));
      Thread follower = new Thread(() -> second.set(client.details("movie", "tt1160419")));
      leader.start();
      assertTrue(started.await(2, TimeUnit.SECONDS));
      follower.start();
      try {
        awaitWaiting(follower);
        assertEquals("Two callers must issue only one provider request", 1, calls.get());
      } finally {
        release.countDown();
        leader.join(2000);
        follower.join(2000);
      }
      assertFalse(leader.isAlive());
      assertFalse(follower.isAlive());
      assertEquals("live", first.get().getString("status"));
      assertEquals("live", second.get().getString("status"));
      first.get().getJSONObject("item").remove("title");
      assertEquals("Dune", second.get().getJSONObject("item").getString("title"));
      assertEquals("cached", client.details("movie", "tt1160419").getString("status"));
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  public void cancelingOneFollowerDoesNotCancelTheSharedFetch() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<JSONObject> first = new AtomicReference<>();
    AtomicBoolean followerInterrupted = new AtomicBoolean();
    try (CatalogClient client =
        new CatalogClient(
            cache("follower-cancel"),
            path -> {
              calls.incrementAndGet();
              started.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return new JSONObject()
                  .put(
                      "meta",
                      new JSONObject()
                          .put("id", "tt1160419")
                          .put("type", "movie")
                          .put("name", "Dune"));
            })) {
      Thread leader = new Thread(() -> first.set(client.details("movie", "tt1160419")));
      Thread follower =
          new Thread(
              () -> {
                client.details("movie", "tt1160419");
                followerInterrupted.set(Thread.currentThread().isInterrupted());
              });
      leader.start();
      assertTrue(started.await(2, TimeUnit.SECONDS));
      follower.start();
      try {
        awaitWaiting(follower);
        follower.interrupt();
        follower.join(2000);
        assertFalse(follower.isAlive());
        assertTrue(followerInterrupted.get());
        assertTrue("Other consumer must still own its provider request", leader.isAlive());
        assertEquals(1, calls.get());
      } finally {
        release.countDown();
        leader.join(2000);
        follower.join(2000);
      }
      assertEquals("live", first.get().getString("status"));
      assertEquals("cached", client.details("movie", "tt1160419").getString("status"));
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
    }
  }

  @Test
  public void staleHomeIsAvailableBeforeRefreshCompletes() throws Exception {
    CatalogClient.Cache disk = cache("stale-first");
    try (CatalogClient initial = new CatalogClient(disk, CatalogConcurrencyTest::rows)) {
      initial.home();
    }
    for (File file : disk.dir.listFiles()) {
      assertTrue(file.setLastModified(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(2)));
    }
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch started = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService caller = Executors.newSingleThreadExecutor();
    try (CatalogClient client =
        new CatalogClient(
            disk,
            path -> {
              calls.incrementAndGet();
              started.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return rows(path);
            })) {
      JSONObject cached = client.cachedHome();
      assertTrue(cached.getBoolean("stale"));
      assertEquals("cached", cached.getString("status"));
      assertEquals(2, cached.getJSONArray("items").length());
      assertEquals(0, calls.get());
      Future<JSONObject> refreshed = caller.submit(client::home);
      try {
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertFalse(refreshed.isDone());
        assertTrue(client.cachedHome().getBoolean("stale"));
      } finally {
        release.countDown();
      }
      assertEquals("live", refreshed.get(2, TimeUnit.SECONDS).getString("status"));
      assertFalse(client.cachedHome().getBoolean("stale"));
      assertEquals(4, calls.get());
    } finally {
      release.countDown();
      caller.shutdownNow();
    }
  }

  @Test
  public void cancelingHomeInterruptsEverySourceAndAllowsReplacement() throws Exception {
    CountDownLatch started = new CountDownLatch(4);
    CountDownLatch stopped = new CountDownLatch(4);
    AtomicBoolean blocking = new AtomicBoolean(true);
    AtomicInteger calls = new AtomicInteger();
    CatalogClient.Cache disk = cache("cancellation");
    try (CatalogClient client =
        new CatalogClient(
            disk,
            path -> {
              calls.incrementAndGet();
              if (blocking.get()) {
                started.countDown();
                try {
                  new CountDownLatch(1).await();
                } finally {
                  stopped.countDown();
                }
              }
              return rows(path);
            })) {
      Thread owner = new Thread(client::home);
      owner.start();
      assertTrue(started.await(2, TimeUnit.SECONDS));
      owner.interrupt();
      owner.join(2000);
      assertFalse("Canceled caller must stop waiting", owner.isAlive());
      assertTrue("All outstanding sources must be canceled", stopped.await(2, TimeUnit.SECONDS));
      assertEquals(0, disk.dir.listFiles().length);
      blocking.set(false);
      assertEquals("live", client.home().getString("status"));
      assertEquals(8, calls.get());
      assertNotNull(client.cachedHome());
    }
  }

  @Test
  public void interruptionStopsSearchBeforeStartingItsNextSource() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean interrupted = new AtomicBoolean();
    try (CatalogClient client =
        new CatalogClient(
            cache("search-canceled"),
            path -> {
              calls.incrementAndGet();
              throw new InterruptedException();
            })) {
      Thread owner =
          new Thread(
              () -> {
                assertEquals("error", client.search("Action").optString("status"));
                interrupted.set(Thread.currentThread().isInterrupted());
              });
      owner.start();
      owner.join(2000);
      assertFalse(owner.isAlive());
      assertTrue(interrupted.get());
      assertEquals(1, calls.get());
    }
  }

  @Test
  public void shutdownCancelsRunningHomeAndPreventsNewNetworkWork() throws Exception {
    CountDownLatch started = new CountDownLatch(4);
    CatalogClient client =
        new CatalogClient(
            cache("close"),
            path -> {
              started.countDown();
              new CountDownLatch(1).await();
              return rows(path);
            });
    Thread owner = new Thread(client::home);
    try {
      owner.start();
      assertTrue(started.await(2, TimeUnit.SECONDS));
      client.close();
      owner.join(2000);
      assertFalse(owner.isAlive());
      assertEquals("error", client.home().getString("status"));
    } finally {
      client.close();
    }
  }

  @Test
  public void transportBudgetExpiresAndPreservesInterruption() throws Exception {
    assertTrue(
        SecureHttp.remainingTimeout(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)) <= 100);
    try {
      SecureHttp.remainingTimeout(System.nanoTime() - 1);
      fail("Expired total request budget must fail");
    } catch (SocketTimeoutException expected) {
    }
    Thread.currentThread().interrupt();
    try {
      SecureHttp.remainingTimeout(System.nanoTime() + TimeUnit.SECONDS.toNanos(10));
      fail("Canceled request must fail before network work");
    } catch (InterruptedIOException expected) {
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  private static void awaitWaiting(Thread thread) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(
        "Second caller must enter the pending request", Thread.State.WAITING, thread.getState());
  }
}
