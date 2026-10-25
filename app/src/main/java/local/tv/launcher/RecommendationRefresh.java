package local.tv.launcher;

/** Lifecycle-bound provider invalidation. All methods and scheduler callbacks use the UI thread. */
final class RecommendationRefresh {
  interface Scheduler {
    void schedule(Runnable task, long delayMillis);

    void cancel(Runnable task);
  }

  private final Scheduler scheduler;
  private final Runnable register;
  private final Runnable unregister;
  private final Runnable refresh;
  private boolean active;
  private Runnable pending;

  RecommendationRefresh(
      Scheduler scheduler, Runnable register, Runnable unregister, Runnable refresh) {
    this.scheduler = scheduler;
    this.register = register;
    this.unregister = unregister;
    this.refresh = refresh;
  }

  void start() {
    if (active) return;
    active = true;
    // Registration may be partially successful when an OEM rejects one observed URI.
    // Keep the lifecycle active so stop() always attempts cleanup.
    try {
      register.run();
    } catch (RuntimeException ignored) {
    }
  }

  void signal() {
    if (!active) return;
    cancelPending();
    pending = new Refresh();
    scheduler.schedule(pending, 400);
  }

  void stop() {
    if (!active) return;
    active = false;
    cancelPending();
    try {
      unregister.run();
    } catch (RuntimeException ignored) {
    }
  }

  private void cancelPending() {
    Runnable previous = pending;
    pending = null;
    if (previous != null) scheduler.cancel(previous);
  }

  private final class Refresh implements Runnable {
    @Override
    public void run() {
      // Identity also rejects already-dequeued work from an earlier signal or lifecycle.
      if (!active || pending != this) return;
      pending = null;
      refresh.run();
    }
  }
}
