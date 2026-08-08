package tg.app;

import tg.diag.CrashLog;
import tg.diag.Diag;

/**
 * Runs one network operation at a time, off the UI thread, and hands the result
 * back on it.
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
 *
 * <h3>One operation at a time, per worker</h3>
 * Not per client. {@code docs/architecture.md} lists which workers and threads
 * exist and which of them may overlap; a fourth one added without reading it
 * would look admissible.
 *
 * <h3>Admission</h3>
 * A task result is delivered through the {@link UiDispatcher}, and the worker
 * stays busy until that delivery reaches the display thread. It is released
 * immediately before the callback body runs, in the same display-thread turn -
 * so a callback can submit the next task (several flows do), while no other
 * action can be admitted into the gap between the release and the transition
 * the callback performs.
 */
public final class Worker
{
    /** What a background task reports back to the UI. */
    public interface Callback
    {
        /**
         * Called on the display thread, through the worker's
         * {@link UiDispatcher} - never on the thread that ran the task.
         *
         * So this may touch lcdui, the navigation stack and the application
         * model directly, and may submit the next task.
         */
        void onSuccess(Object result);

        /** Same thread as {@link #onSuccess}, same freedoms. */
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

    private final UiDispatcher dispatcher;

    private volatile boolean busy;
    private volatile String currentTask;

    public Worker(UiDispatcher dispatcher)
    {
        if (dispatcher == null) { throw new IllegalArgumentException("dispatcher"); }
        this.dispatcher = dispatcher;
    }

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
     * Call this from the display thread. A producer on any other thread posts
     * through the {@link UiDispatcher} first, so that the refusal - which is an
     * ordinary outcome, not an error - is handled where the screen it has to
     * undo lives.
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
                    deliver(task, callback, result, null);
                }
                catch (Throwable t)
                {
                    // Errors as well as exceptions: an OutOfMemoryError while
                    // parsing a large dialog list has to reach the UI as a
                    // message, not kill the MIDlet silently.
                    Diag.error("task " + task.name() + " failed", t);
                    CrashLog.save(task.name(), t);
                    deliver(task, callback, null, t);
                }
            }
        }).start();
        return true;
    }

    /**
     * Queue the result for the display thread.
     *
     * The worker is still busy when this returns, and stays busy until the
     * posted runnable reaches the front of the display queue. That is the point:
     * the old code released it here, on the worker thread, which opened a window
     * in which the next action was admitted and could apply its own transition
     * before the previous one had been applied at all.
     */
    private void deliver(final Task task, final Callback callback,
                         final Object result, final Throwable error)
    {
        Runnable delivery = new Runnable()
        {
            public void run()
            {
                // Released before the callback body, not after: the connect
                // callback submits getDialogs, sign-in does the same, and a
                // chat open submits its history fetch from inside the callback
                // that preceded it. Safe here in a way it was not on the worker
                // thread, because the next user action would have to be
                // delivered to this same thread, and this thread is here.
                finish();
                try
                {
                    if (error == null) { callback.onSuccess(result); }
                    else { callback.onFailure(error); }
                }
                catch (Throwable t)
                {
                    // The task's own outcome is already logged and, on failure,
                    // already in the crash log. This is a second, separate
                    // fault in the code that handled it - recorded under its own
                    // name so a device report does not read as one failure.
                    Diag.error("callback for " + task.name() + " failed", t);
                    CrashLog.save(task.name() + " callback", t);
                }
            }
        };

        try
        {
            dispatcher.post(delivery);
        }
        catch (Throwable t)
        {
            // Nothing will ever run the delivery, so nothing will ever clear
            // busy, and this worker would refuse every action for the rest of
            // the session. Release it here; the result is lost, which is worse
            // than a lost result would be if the alternative were not a client
            // that has stopped responding to its own menu.
            Diag.error("dispatching " + task.name() + " failed", t);
            CrashLog.save(task.name() + " dispatch", t);
            finish();
        }
    }

    private synchronized void finish()
    {
        busy = false;
        currentTask = null;
    }
}
