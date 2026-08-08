package tg.io;

import tg.diag.Diag;

/**
 * One thread, one pending deadline, one callback.
 *
 * <h3>What it replaces</h3>
 * {@code new Thread(sleep(delay); doTheThing()).start()}. That works exactly
 * once. Ask for it twice - which a FLOOD_WAIT on every queued message does, and
 * which a burst of updates does - and there are two sleeping threads, then
 * three, each holding a stack on a heap measured in single-digit megabytes, all
 * of them waking to do the same job.
 *
 * <h3>The contract</h3>
 * At most one waiter exists at a time. {@link #schedule} keeps the
 * <em>earliest</em> deadline asked for: a later one is dropped rather than
 * postponing a retry that is already due sooner, and an earlier one wakes the
 * waiter to re-read it. {@link #cancel} drops the deadline and the waiter exits.
 *
 * <h3>What it does not promise</h3>
 * A wake already past its deadline check cannot be cancelled - the monitor is
 * released before the callback runs, so a {@code cancel} landing in that
 * instant is simply too late. The callback has to be safe to run once more
 * after the thing it was scheduled for has gone away, which for both users here
 * it is: the outbox drain re-checks the account epoch and the store, and the
 * snapshot refresh re-checks the worker.
 *
 * <h3>Clocks</h3>
 * CLDC has {@code System.currentTimeMillis()} and nothing monotonic, and one of
 * the handsets this runs on resets its clock to 2011 on every power cycle. A
 * backward jump would otherwise turn a two-second wait into a fourteen-year
 * one, so a single wait is capped and the deadline re-read afterwards. The cost
 * is a few timed-out waits per minute of waiting, which is nothing; the
 * alternative is an outbox that never drains again until the MIDlet restarts.
 */
public final class DelayedWake
{
    /** What runs when the deadline arrives. Never on the display thread. */
    public interface Wake
    {
        void onWake();
    }

    /** Longest single wait, so a clock that moves is noticed within it. */
    private static final long MAX_WAIT_MS = 30000L;

    private final String name;
    private final Wake wake;

    /** When to fire, absolute; 0 when nothing is scheduled. */
    private long deadline;
    private Thread waiter;

    public DelayedWake(String name, Wake wake)
    {
        if (wake == null) { throw new IllegalArgumentException("wake"); }
        this.name = name;
        this.wake = wake;
    }

    /**
     * Fire in {@code delayMs}, unless something sooner is already pending.
     *
     * A non-positive delay means now, and still goes through the waiter rather
     * than running inline: the caller is usually holding something it should
     * not run a drain underneath.
     */
    public synchronized void schedule(long delayMs)
    {
        long at = System.currentTimeMillis() + (delayMs > 0 ? delayMs : 0);

        // A later deadline than the one already set is not news. Keeping it
        // would push back a retry that is legitimately due sooner, which is how
        // a FLOOD_WAIT on a second message delays the first one past its own
        // wait.
        if (deadline != 0 && at >= deadline) { return; }

        deadline = at;
        if (waiter == null)
        {
            waiter = new Thread(new Runnable()
            {
                public void run() { loop(); }
            });
            waiter.start();
        }
        else
        {
            // Sleeping against the old, later deadline. Wake it to re-read.
            notifyAll();
        }
    }

    /** Drop the pending deadline; the waiter exits without firing. */
    public synchronized void cancel()
    {
        deadline = 0;
        notifyAll();
    }

    /**
     * Milliseconds until the pending wake, or -1 when nothing is scheduled.
     *
     * For diagnostics. Deliberately a duration rather than an absolute time:
     * the absolute one is only meaningful beside the clock that produced it,
     * and on these handsets that clock is not to be trusted.
     */
    public synchronized long pendingMs()
    {
        if (deadline == 0) { return -1; }
        long left = deadline - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    private void loop()
    {
        for (;;)
        {
            synchronized (this)
            {
                if (deadline == 0)
                {
                    // Cancelled, or already fired by a previous pass.
                    waiter = null;
                    return;
                }
                long left = deadline - System.currentTimeMillis();
                if (left > 0)
                {
                    try { wait(left < MAX_WAIT_MS ? left : MAX_WAIT_MS); }
                    catch (InterruptedException ignored) { }
                    // Round again rather than firing: the deadline may have
                    // moved earlier, been cancelled, or the clock may have.
                    continue;
                }
                // Released before the callback, so a callback that schedules
                // the next wake - which the outbox does on every FLOOD_WAIT -
                // gets a fresh waiter instead of being refused by this one.
                deadline = 0;
                waiter = null;
            }

            try { wake.onWake(); }
            catch (Throwable t) { Diag.error("delayed wake " + name + " failed", t); }
            return;
        }
    }
}
