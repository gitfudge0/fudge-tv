package local.tv.launcher;

import static org.junit.Assert.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;

public class LatestRequestsTest {
  @Test
  public void pressureKeepsOnlyLatestPerLaneWithoutDroppingOtherRequests() throws Exception {
    LatestRequests requests = new LatestRequests(1);
    CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
    CountDownLatch finished = new CountDownLatch(3);
    List<String> ran = Collections.synchronizedList(new ArrayList<>());
    try {
      requests.submit(
          LatestRequests.Lane.LIBRARY,
          () -> {
            started.countDown();
            try {
              release.await();
            } catch (InterruptedException ignored) {
            }
          });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      for (int i = 0; i < 1000; i++) {
        final int version = i;
        for (LatestRequests.Lane lane :
            new LatestRequests.Lane[] {
              LatestRequests.Lane.SEARCH, LatestRequests.Lane.HOME, LatestRequests.Lane.DETAILS
            }) {
          requests.submit(
              lane,
              () -> {
                ran.add(lane + ":" + version);
                finished.countDown();
              });
        }
      }
      release.countDown();
      assertTrue(finished.await(2, TimeUnit.SECONDS));
      assertEquals(
          new HashSet<>(Arrays.asList("SEARCH:999", "HOME:999", "DETAILS:999")),
          new HashSet<>(ran));
      assertEquals(3, ran.size());
    } finally {
      release.countDown();
      requests.close();
    }
  }

  @Test
  public void cancelInterruptsRunningWorkAndRemovesQueuedRequest() throws Exception {
    LatestRequests requests = new LatestRequests(1);
    CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1);
    CountDownLatch survivor = new CountDownLatch(1);
    List<String> ran = Collections.synchronizedList(new ArrayList<>());
    try {
      requests.submit(
          LatestRequests.Lane.HOME,
          () -> {
            started.countDown();
            try {
              new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
              interrupted.countDown();
            }
          });
      assertTrue(started.await(2, TimeUnit.SECONDS));
      requests.submit(LatestRequests.Lane.SEARCH, () -> ran.add("canceled"));
      requests.cancel(LatestRequests.Lane.SEARCH);
      requests.cancel(LatestRequests.Lane.HOME);
      assertTrue(interrupted.await(2, TimeUnit.SECONDS));
      requests.submit(LatestRequests.Lane.DETAILS, survivor::countDown);
      assertTrue(survivor.await(2, TimeUnit.SECONDS));
      assertTrue(ran.isEmpty());
    } finally {
      requests.close();
    }
  }

  @Test
  public void closedSchedulerAcceptsNoFurtherWork() {
    LatestRequests requests = new LatestRequests(1);
    requests.close();
    requests.close();
    requests.submit(LatestRequests.Lane.SEARCH, () -> fail("closed scheduler ran a request"));
  }
}
