package tgtest;

import tg.io.DelayedWake;

/**
 * One waiter, the earliest deadline, and nothing left running after a cancel.
 *
 * What this replaces is {@code new Thread(sleep(delay); drain()).start()}. That
 * works exactly once. A FLOOD_WAIT is per message, so a queue of them asked for
 * it once per message - each a sleeping thread with a stack, on a handset whose
 * whole Java heap was measured at 2 MB, all of them waking to run the same
 * drain against the same store.
 *
 * The two rules that make one waiter enough are both about which deadline wins.
 * An earlier one has to interrupt a longer wait already in progress, or a retry
 * legitimately due in two seconds sits behind one due in five minutes. A later
 * one has to be dropped rather than replacing the earlier one, which is the
 * same failure the other way round.
 *
 * Timing tests are a bad habit and these are written to avoid the usual
 * trouble: every assertion is about an ordering or a count, waits are bounded,
 * and the only durations compared are separated by a factor of ten or more.
 */
public final class DelayedWakeTest implements Test
{
    /** Generous: a loaded CI box is slow, and a hang says nothing at all. */
    private static final long TIMEOUT_MS = 10000L;

    public String name() { return "io/delayed-wake"; }

    public void run() throws Exception
    {
        itFiresOnce();
        itFiresOnceZeroDelay();
        anEarlierDeadlineWins();
        aLaterDeadlineIsDropped();
        repeatedSchedulingKeepsOneWaiter();
        cancelStopsAPendingWake();
        aCancelledWakeCanBeScheduledAgain();
        aCallbackMayScheduleTheNextWake();
        aFailingCallbackDoesNotPoisonTheWaiter();
        pendingIsReportedForDiagnostics();
    }

    // ------------------------------------------------------------- the basics

    private static void itFiresOnce() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);

        wake.schedule(10);
        Assert.isTrue("the wake arrives", fired.await(1, TIMEOUT_MS));

        // Nothing rearms itself: one schedule, one wake.
        Thread.sleep(80);
        Assert.equal("and does not repeat", 1, fired.count());
        Assert.equal("with nothing left pending", -1L, wake.pendingMs());
    }

    /**
     * A non-positive delay means now, and still goes through the waiter: the
     * caller is usually holding something it must not run a drain underneath.
     */
    private static void itFiresOnceZeroDelay() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);
        wake.schedule(0);
        Assert.isTrue("zero fires", fired.await(1, TIMEOUT_MS));
    }

    // --------------------------------------------------------- which one wins

    /**
     * The waiter is already asleep against a long deadline when a short one
     * arrives. Without a notify it would serve the long one first, and an
     * outbox retry due in a moment would sit behind a five-minute FLOOD_WAIT.
     */
    private static void anEarlierDeadlineWins() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);

        wake.schedule(60000);
        Thread.sleep(30);                    // let the waiter get to its wait()
        wake.schedule(10);

        Assert.isTrue("the earlier deadline is served, not the minute",
                fired.await(1, TIMEOUT_MS));
    }

    private static void aLaterDeadlineIsDropped() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);

        wake.schedule(50);
        long afterFirst = wake.pendingMs();
        wake.schedule(60000);
        long afterSecond = wake.pendingMs();

        Assert.isTrue("the later deadline did not replace the earlier one:"
                + " was " + afterFirst + "ms, now " + afterSecond + "ms",
                afterSecond <= afterFirst);
        Assert.isTrue("so the wake still arrives on the first one",
                fired.await(1, TIMEOUT_MS));
    }

    /**
     * The shape the outbox produces: every queued message asks for its own
     * retry, all at once. The old code answered that with a thread each.
     */
    private static void repeatedSchedulingKeepsOneWaiter() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);

        int before = Thread.activeCount();
        for (int i = 0; i < 40; i++) { wake.schedule(2000 + i * 100); }
        int during = Thread.activeCount();

        // One waiter, not forty. Compared loosely because the count is of the
        // whole JVM and the runner is not the only thing in it.
        Assert.isTrue("forty schedules did not start forty threads: " + before
                + " -> " + during, during - before < 5);

        wake.schedule(10);
        Assert.isTrue("and one wake arrives", fired.await(1, TIMEOUT_MS));
        Thread.sleep(80);
        Assert.equal("exactly one, not forty", 1, fired.count());
    }

    // ------------------------------------------------------------ cancelling

    private static void cancelStopsAPendingWake() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);

        wake.schedule(60);
        Assert.isTrue("something is pending", wake.pendingMs() >= 0);
        wake.cancel();
        Assert.equal("and then nothing is", -1L, wake.pendingMs());

        Thread.sleep(200);
        Assert.equal("the wake never came", 0, fired.count());
    }

    private static void aCancelledWakeCanBeScheduledAgain() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);

        wake.schedule(60000);
        wake.cancel();
        // The cancelled waiter has to have released its slot, or this schedule
        // finds a waiter that is on its way out and never gets served.
        wake.schedule(10);

        Assert.isTrue("a cancel does not retire the wake", fired.await(1, TIMEOUT_MS));
    }

    // ------------------------------------------------------------ the callers

    /**
     * What the outbox does on every FLOOD_WAIT: the drain the wake started
     * schedules the next one from inside the callback. If the slot were still
     * held at that point the chain would stop after one link.
     */
    private static void aCallbackMayScheduleTheNextWake() throws Exception
    {
        final Counter fired = new Counter();
        final DelayedWake[] self = new DelayedWake[1];
        self[0] = new DelayedWake("test", new DelayedWake.Wake()
        {
            public void onWake()
            {
                if (fired.hit() < 3) { self[0].schedule(5); }
            }
        });

        self[0].schedule(5);
        Assert.isTrue("the chain runs", fired.await(3, TIMEOUT_MS));
        Thread.sleep(80);
        Assert.equal("and stops where the callback stopped it", 3, fired.count());
    }

    private static void aFailingCallbackDoesNotPoisonTheWaiter() throws Exception
    {
        final Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", new DelayedWake.Wake()
        {
            public void onWake()
            {
                fired.hit();
                throw new IllegalStateException("bad callback");
            }
        });

        wake.schedule(5);
        Assert.isTrue("it ran", fired.await(1, TIMEOUT_MS));

        wake.schedule(5);
        Assert.isTrue("and the next one still runs", fired.await(2, TIMEOUT_MS));
    }

    private static void pendingIsReportedForDiagnostics() throws Exception
    {
        Counter fired = new Counter();
        DelayedWake wake = new DelayedWake("test", fired);

        Assert.equal("idle reports nothing pending", -1L, wake.pendingMs());
        wake.schedule(60000);
        long left = wake.pendingMs();
        Assert.isTrue("a duration, not an absolute time: " + left,
                left > 0 && left <= 60000);
        wake.cancel();
    }

    // ---------------------------------------------------------------- helpers

    /** Counts wakes and lets the test wait for the n-th, with a deadline. */
    private static final class Counter implements DelayedWake.Wake
    {
        private int count;

        public void onWake() { hit(); }

        synchronized int hit()
        {
            count++;
            notifyAll();
            return count;
        }

        synchronized int count() { return count; }

        synchronized boolean await(int target, long timeoutMs)
                throws InterruptedException
        {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (count < target)
            {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) { return false; }
                wait(left);
            }
            return true;
        }
    }
}
