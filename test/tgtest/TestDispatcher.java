package tgtest;

import java.util.Vector;

import tg.app.UiDispatcher;

/**
 * A {@link UiDispatcher} the test drives by hand.
 *
 * On a device the display thread belongs to the AMS and the moment a queued
 * runnable runs is not observable, let alone controllable. That is exactly what
 * the {@link tg.app.Worker} admission contract is about, so the desktop suite
 * substitutes this: posts land in a queue, and nothing runs until the test says
 * {@link #drainOne} or {@link #drain}.
 *
 * That inversion is what makes "the worker is still busy while the callback is
 * queued" an assertion rather than a race the test would usually lose.
 *
 * Lives in test/ deliberately: it is not on the device classpath and cannot end
 * up in the JAR.
 */
public final class TestDispatcher implements UiDispatcher
{
    private final Vector queue = new Vector();

    /** How many posts have arrived and not yet been run. */
    public synchronized int pending()
    {
        return queue.size();
    }

    public synchronized void post(Runnable work)
    {
        if (work == null) { throw new IllegalArgumentException("work"); }
        queue.addElement(work);
        notifyAll();
    }

    /**
     * Run the oldest queued runnable, if there is one.
     *
     * Runs it outside the monitor: a callback may post again - or submit a task
     * whose completion posts - and holding the lock across that would deadlock
     * the very flows this exists to test.
     *
     * @return false when the queue was empty
     */
    public boolean drainOne()
    {
        Runnable work;
        synchronized (this)
        {
            if (queue.isEmpty()) { return false; }
            work = (Runnable) queue.elementAt(0);
            queue.removeElementAt(0);
        }
        work.run();
        return true;
    }

    /** Run everything queued, including anything those runnables queue. */
    public int drain()
    {
        int ran = 0;
        while (drainOne()) { ran++; }
        return ran;
    }

    /**
     * Wait until at least one runnable is queued, then run exactly one.
     *
     * The worker posts from its own thread, so a test that called
     * {@link #drainOne} straight after {@code submit} would usually find an
     * empty queue. Every wait is bounded: a contract test that hangs is worse
     * than one that fails, because CI reports it as nothing at all.
     */
    public boolean awaitAndDrainOne(long timeoutMs) throws InterruptedException
    {
        synchronized (this)
        {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (queue.isEmpty())
            {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) { return false; }
                wait(left);
            }
        }
        return drainOne();
    }

    /** Wait for a post to arrive without running it. */
    public synchronized boolean awaitPost(long timeoutMs) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (queue.isEmpty())
        {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) { return false; }
            wait(left);
        }
        return true;
    }
}
