package tgtest;

import tg.app.UiDispatcher;
import tg.app.Worker;

/**
 * Which thread a result arrives on, and when the worker becomes available.
 *
 * The defect: {@code Worker} used to call {@code finish()} on its own thread and
 * then run the callback there too. Two consequences, both invisible on a desktop
 * and both reachable by pressing a key at the wrong moment on a handset.
 *
 * First, callbacks that mutate the navigation stack, the dialog array and lcdui
 * objects ran on the worker thread while the display thread was doing the same.
 * lcdui is documented as thread safe per {@code Displayable} mutation, which is
 * true and does not help: "replace the array, rebuild the list, swap the screen"
 * is a transition, and safety per call says nothing about it.
 *
 * Second, the release came <em>before</em> the transition rather than
 * immediately before the callback body. Between the two the worker was idle and
 * the display thread was free, so a keypress could be admitted and apply its own
 * transition against state the previous result had not written yet.
 *
 * Both are fixed by the same move: deliver through a {@link UiDispatcher}, and
 * release inside the dispatched runnable, one statement before the callback.
 * A callback can still chain the next task - several flows depend on it - but
 * nothing else can get in, because everything else would have to arrive on the
 * thread that is currently inside the callback.
 */
public final class WorkerDispatchTest implements Test
{
    private static final long TIMEOUT_MS = 10000L;

    public String name() { return "app/worker-dispatch"; }

    public void run() throws Exception
    {
        theCallbackNeverRunsOnTheTaskThread();
        theWorkerStaysBusyWhileTheResultIsQueued();
        atMostOneResultIsPendingAdmission();
        failuresTravelTheSameWay();
        aThrowingCallbackStillLeavesTheWorkerUsable();
        aDispatcherThatRefusesToQueueStillReleasesTheWorker();
        resultsArriveInTheOrderTheyWereProduced();
    }

    // -------------------------------------------------------------- ownership

    /**
     * The whole point: whatever thread ran {@code Task.run()}, the callback runs
     * somewhere else - on the thread that drains the dispatcher, which on a
     * device is the display thread.
     */
    private static void theCallbackNeverRunsOnTheTaskThread() throws Exception
    {
        TestDispatcher ui = new TestDispatcher();
        Worker worker = new Worker(ui);
        final Thread[] taskThread = new Thread[1];
        WorkerTest.Recorder done = new WorkerTest.Recorder();

        Assert.isTrue("accepted", worker.submit(new Worker.Task()
        {
            public String name() { return "messages.getDialogs"; }
            public Object run() throws Exception
            {
                taskThread[0] = Thread.currentThread();
                return "page";
            }
        }, done));

        Assert.isTrue("the result is queued for the display thread",
                ui.awaitAndDrainOne(TIMEOUT_MS));
        done.awaitDone();

        Assert.equal("the callback arrived once", 1, done.successes);
        Assert.isTrue("the task did not run on the draining thread",
                taskThread[0] != Thread.currentThread());
        Assert.isTrue("and the callback did not run on the task thread",
                done.thread != taskThread[0]);
        Assert.isTrue("it ran on whoever drained the dispatcher",
                done.thread == Thread.currentThread());
    }

    /**
     * The admission gap, stated directly. Between the task returning and the
     * callback beginning the worker must still refuse - otherwise the next
     * action is admitted against state the previous result has not applied.
     */
    private static void theWorkerStaysBusyWhileTheResultIsQueued() throws Exception
    {
        TestDispatcher ui = new TestDispatcher();
        Worker worker = new Worker(ui);
        WorkerTest.Recorder done = new WorkerTest.Recorder();
        WorkerTest.Counter ran = new WorkerTest.Counter();

        Assert.isTrue("accepted",
                worker.submit(WorkerTest.counting("connect", ran), done));
        Assert.isTrue("the result is queued", ui.awaitPost(TIMEOUT_MS));
        Assert.equal("the task itself has finished", 1, ran.count);

        // Queued, not delivered. This is the window the old code left open.
        Assert.isTrue("the worker is still busy while the result waits",
                worker.isBusy());
        Assert.equal("and still names what it is finishing",
                "connect", worker.busyWith());
        Assert.equal("the callback has not run", 0, done.successes);

        WorkerTest.Counter jumped = new WorkerTest.Counter();
        Assert.isFalse("so an action arriving in the gap is refused, not"
                + " admitted",
                worker.submit(WorkerTest.counting("messages.getHistory", jumped),
                        new WorkerTest.Recorder()));
        Assert.equal("and it did not run", 0, jumped.count);

        Assert.isTrue("draining delivers the result", ui.drainOne());
        done.awaitDone();
        Assert.isFalse("and only then is the worker free", worker.isBusy());
    }

    /** One task, one result: nothing can pile up waiting to be admitted. */
    private static void atMostOneResultIsPendingAdmission() throws Exception
    {
        TestDispatcher ui = new TestDispatcher();
        Worker worker = new Worker(ui);

        Assert.isTrue("accepted", worker.submit(
                WorkerTest.immediate("first"), new WorkerTest.Recorder()));
        Assert.isTrue("queued", ui.awaitPost(TIMEOUT_MS));
        Assert.isFalse("a second submission is refused while the first result"
                + " waits",
                worker.submit(WorkerTest.immediate("second"),
                        new WorkerTest.Recorder()));
        Assert.equal("so exactly one result is ever pending", 1, ui.pending());
    }

    /** A failed task is delivered on the same thread as a successful one. */
    private static void failuresTravelTheSameWay() throws Exception
    {
        TestDispatcher ui = new TestDispatcher();
        Worker worker = new Worker(ui);
        WorkerTest.Recorder done = new WorkerTest.Recorder();

        Assert.isTrue("accepted", worker.submit(new Worker.Task()
        {
            public String name() { return "auth.signIn"; }
            public Object run() throws Exception
            {
                throw new java.io.IOException("no route to host");
            }
        }, done));

        Assert.isTrue("the failure is queued rather than delivered inline",
                ui.awaitPost(TIMEOUT_MS));
        Assert.isTrue("the worker is busy until it is", worker.isBusy());
        Assert.equal("the callback has not run", 0, done.failures);

        Assert.isTrue("drained", ui.drainOne());
        done.awaitDone();
        Assert.equal("the failure arrived", 1, done.failures);
        Assert.isTrue("on the draining thread",
                done.thread == Thread.currentThread());
        Assert.isFalse("and released the worker", worker.isBusy());
    }

    // ------------------------------------------------------------- the faults

    /**
     * A callback that throws is a second, separate fault. It must not leave the
     * worker held: on a handset that is a client that stops responding to its
     * own menu, with nothing in the log to say why.
     */
    private static void aThrowingCallbackStillLeavesTheWorkerUsable() throws Exception
    {
        TestDispatcher ui = new TestDispatcher();
        final Worker worker = new Worker(ui);
        final boolean[] freeInside = new boolean[1];

        Assert.isTrue("accepted", worker.submit(WorkerTest.immediate("connect"),
                new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                freeInside[0] = !worker.isBusy();
                throw new IllegalStateException("bad callback");
            }
            public void onFailure(Throwable error) { }
        }));

        Assert.isTrue("queued", ui.awaitAndDrainOne(TIMEOUT_MS));
        Assert.isTrue("the worker was already released before the callback ran",
                freeInside[0]);
        Assert.isFalse("and the throw did not re-hold it", worker.isBusy());

        WorkerTest.Counter after = new WorkerTest.Counter();
        WorkerTest.Recorder next = new WorkerTest.Recorder();
        Assert.isTrue("the next action is admitted",
                worker.submit(WorkerTest.counting("messages.getDialogs", after),
                        next));
        Assert.isTrue("and comes back", ui.awaitAndDrainOne(TIMEOUT_MS));
        next.awaitDone();
        Assert.equal("having actually run", 1, after.count);
        Assert.equal("with no leftover callback from the one that threw",
                1, next.successes);
    }

    /**
     * The one case where the result really is lost: the dispatcher refuses to
     * queue it. Nothing will ever run the delivery, so nothing would ever clear
     * the busy flag - and a worker that refuses for the rest of the session is
     * worse than a dropped page the user can ask for again.
     */
    private static void aDispatcherThatRefusesToQueueStillReleasesTheWorker()
            throws Exception
    {
        final int[] posts = new int[1];
        UiDispatcher broken = new UiDispatcher()
        {
            public void post(Runnable work)
            {
                posts[0]++;
                throw new IllegalStateException("no display");
            }
        };
        Worker worker = new Worker(broken);
        WorkerTest.Recorder never = new WorkerTest.Recorder();
        WorkerTest.Counter ran = new WorkerTest.Counter();

        Assert.isTrue("accepted",
                worker.submit(WorkerTest.counting("connect", ran), never));

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (worker.isBusy() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(10);
        }
        Assert.isFalse("a dispatch that throws still releases the worker",
                worker.isBusy());
        Assert.equal("the task did run", 1, ran.count);
        Assert.equal("the dispatcher was asked once", 1, posts[0]);
        Assert.equal("and the callback never arrived - the result is lost, and"
                + " that is the trade", 0, never.successes + never.failures);
    }

    /**
     * Chained work keeps its order. The connect callback submits getDialogs;
     * whatever that produces has to be applied after the connect transition,
     * not interleaved with it.
     */
    private static void resultsArriveInTheOrderTheyWereProduced() throws Exception
    {
        final TestDispatcher ui = new TestDispatcher();
        final Worker worker = new Worker(ui);
        final StringBuffer order = new StringBuffer();

        final Worker.Callback second = new Worker.Callback()
        {
            public void onSuccess(Object result) { order.append("B"); }
            public void onFailure(Throwable error) { order.append("b"); }
        };

        Assert.isTrue("accepted", worker.submit(WorkerTest.immediate("connect"),
                new Worker.Callback()
        {
            public void onSuccess(Object result)
            {
                order.append("A");
                Assert.isTrue("the chained submission is admitted",
                        worker.submit(WorkerTest.immediate("messages.getDialogs"),
                                second));
            }
            public void onFailure(Throwable error) { order.append("a"); }
        }));

        Assert.isTrue("first result", ui.awaitAndDrainOne(TIMEOUT_MS));
        Assert.isTrue("second result", ui.awaitAndDrainOne(TIMEOUT_MS));
        Assert.equal("the chained callback ran after the one that chained it",
                "AB", order.toString());
        Assert.equal("and nothing is left queued", 0, ui.pending());
    }
}
