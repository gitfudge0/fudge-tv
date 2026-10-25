package local.tv.launcher;

import java.util.EnumMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One replaceable request per lane. Replacements remove canceled queued work immediately. */
final class LatestRequests implements AutoCloseable {
  enum Lane {
    HOME,
    SEARCH,
    DETAILS,
    LIBRARY
  }

  private final ThreadPoolExecutor executor;
  private final EnumMap<Lane, FutureTask<Void>> requests = new EnumMap<>(Lane.class);
  private boolean closed;

  LatestRequests(int parallelism) {
    executor =
        new ThreadPoolExecutor(
            parallelism,
            parallelism,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(Lane.values().length));
  }

  synchronized void submit(Lane lane, Runnable request) {
    if (closed) return;
    cancel(lane);
    FutureTask<Void> task = new FutureTask<>(request, null);
    requests.put(lane, task);
    // At most one queued task per lane exists; capacity covers every lane.
    executor.execute(task);
  }

  synchronized void cancel(Lane lane) {
    FutureTask<Void> previous = requests.remove(lane);
    if (previous != null) {
      previous.cancel(true);
      executor.remove(previous);
    }
  }

  synchronized void cancelAll() {
    for (Lane lane : Lane.values()) cancel(lane);
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    cancelAll();
    executor.shutdownNow();
  }
}
