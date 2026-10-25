package local.tv.launcher;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class RecommendationRefreshTest {
    private static final class Clock implements RecommendationRefresh.Scheduler {
        final List<Runnable> queued = new ArrayList<>();
        final List<Runnable> cancelled = new ArrayList<>();
        long delay;
        @Override public void schedule(Runnable task, long delayMillis) { queued.add(task); delay = delayMillis; }
        @Override public void cancel(Runnable task) { cancelled.add(task); queued.remove(task); }
    }

    @Test public void repeatedStartRegistersOnceAndStoppedSignalsAreIgnored() {
        Clock clock = new Clock(); int[] calls = new int[3];
        RecommendationRefresh controller = new RecommendationRefresh(clock, () -> calls[0]++, () -> calls[1]++, () -> calls[2]++);
        controller.signal(); assertTrue(clock.queued.isEmpty());
        controller.start(); controller.start(); assertEquals(1, calls[0]);
        controller.stop(); controller.stop(); assertEquals(1, calls[1]);
        controller.signal(); assertTrue(clock.queued.isEmpty()); assertEquals(0, calls[2]);
        controller.start(); assertEquals(2, calls[0]);
    }

    @Test public void notificationsDebounceAndRefreshExactlyOnce() {
        Clock clock = new Clock(); int[] refreshes = {0};
        RecommendationRefresh controller = new RecommendationRefresh(clock, () -> {}, () -> {}, () -> refreshes[0]++);
        controller.start(); controller.signal(); Runnable obsolete = clock.queued.get(0);
        controller.signal(); assertEquals(400, clock.delay); assertEquals(1, clock.queued.size());
        assertSame(obsolete, clock.cancelled.get(0)); obsolete.run(); assertEquals(0, refreshes[0]);
        Runnable current = clock.queued.get(0); current.run(); current.run(); assertEquals(1, refreshes[0]);
    }

    @Test public void stopCancelsAndRejectsDequeuedCallbacksEvenAfterRestart() {
        Clock clock = new Clock(); int[] refreshes = {0};
        RecommendationRefresh controller = new RecommendationRefresh(clock, () -> {}, () -> {}, () -> refreshes[0]++);
        controller.start(); controller.signal(); Runnable previousSession = clock.queued.get(0);
        controller.stop(); assertTrue(clock.queued.isEmpty()); assertSame(previousSession, clock.cancelled.get(0));
        previousSession.run(); assertEquals(0, refreshes[0]);
        controller.start(); controller.signal(); previousSession.run(); assertEquals(0, refreshes[0]);
        clock.queued.get(0).run(); assertEquals(1, refreshes[0]);
    }

    @Test public void partialRegistrationAndCleanupFailuresDoNotBreakFutureLifecycles() {
        Clock clock = new Clock(); int[] calls = new int[3];
        RecommendationRefresh controller = new RecommendationRefresh(clock,
            () -> { calls[0]++; throw new SecurityException("OEM rejects observation"); },
            () -> { calls[1]++; throw new IllegalArgumentException("already detached"); },
            () -> calls[2]++);
        controller.start(); controller.start(); controller.signal(); controller.stop();
        assertEquals(1, calls[0]); assertEquals(1, calls[1]); assertTrue(clock.queued.isEmpty());
        controller.start(); controller.signal(); clock.queued.get(0).run(); controller.stop();
        assertEquals(2, calls[0]); assertEquals(2, calls[1]); assertEquals(1, calls[2]);
    }
}
