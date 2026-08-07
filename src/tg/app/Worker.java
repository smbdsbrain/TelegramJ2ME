package tg.app;

import tg.diag.CrashLog;
import tg.diag.Diag;

/**
 * Runs one network operation at a time, off the UI thread.
 *
 * MIDP delivers {@code commandAction} on the display thread. Blocking it for a
 * GPRS round trip - which can be several seconds, and for the first auth_key
 * generation far longer - freezes the screen and can trip the AMS watchdog into
 * killing the MIDlet. So everything that touches the network goes through here.
 *
 * Deliberately single-threaded and serial. Telegram operations share one
 * connection and one session; letting two run concurrently would interleave
 * their msg_ids and seq_nos. Queueing is not needed either - the UI disables
 * whatever it just triggered until the result comes back.
 *
 * CLDC has no java.util.concurrent, no Executor and no thread pool, so this is
 * a bare Thread per task.
 */
public final class Worker
{
    /** What a background task reports back to the UI. */
    public interface Callback
    {
        /**
         * Called on the worker thread, not the UI thread. lcdui is documented
         * as thread safe for Displayable mutation, which is what makes this
         * workable without a dispatch queue.
         */
        void onSuccess(Object result);

        void onFailure(Throwable error);
    }

    /** A unit of work. Runs on the worker thread. */
    public interface Task
    {
        /** @return whatever the callback should receive */
        Object run() throws Exception;

        /** Short name, used in diagnostics and crash records. */
        String name();
    }

    private volatile boolean busy;
    private volatile String currentTask;

    public boolean isBusy()
    {
        return busy;
    }

    public String currentTask()
    {
        return currentTask;
    }

    /**
     * What is holding the worker, in words a refusal message can use.
     *
     * Never null, which {@link #currentTask()} is: it is cleared the moment the
     * running task finishes, and that happens between a refused submission and
     * the alert the caller builds from it. Reading the field directly there
     * shows "Finishing null first" to whoever is unlucky with the timing - a
     * message that is wrong exactly when the operation the user was waiting on
     * has already ended.
     *
     * One read of the field, for the same reason.
     */
    public String busyWith()
    {
        String task = currentTask;
        return task == null ? "another operation" : task;
    }

    /**
     * Start a task unless one is already running.
     *
     * @return false when the worker was busy and nothing was started
     */
    public synchronized boolean submit(final Task task, final Callback callback)
    {
        if (busy)
        {
            Diag.warn("worker busy with " + currentTask + ", refused " + task.name());
            return false;
        }
        busy = true;
        currentTask = task.name();

        new Thread(new Runnable()
        {
            public void run()
            {
                long t0 = System.currentTimeMillis();
                Diag.info("task " + task.name() + " started");
                try
                {
                    Object result = task.run();
                    Diag.info("task " + task.name() + " ok in "
                              + (System.currentTimeMillis() - t0) + " ms");
                    finish();
                    callback.onSuccess(result);
                }
                catch (Throwable t)
                {
                    // Errors as well as exceptions: an OutOfMemoryError while
                    // parsing a large dialog list has to reach the UI as a
                    // message, not kill the MIDlet silently.
                    Diag.error("task " + task.name() + " failed", t);
                    CrashLog.save(task.name(), t);
                    finish();
                    try
                    {
                        callback.onFailure(t);
                    }
                    catch (Throwable ignored)
                    {
                        // A failing error handler must not mask the original.
                    }
                }
            }
        }).start();
        return true;
    }

    private synchronized void finish()
    {
        busy = false;
        currentTask = null;
    }
}
