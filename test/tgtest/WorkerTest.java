package tgtest;

import tg.app.Worker;

/**
 * A refused task is not a failed task, and it is not a started one either.
 *
 * {@link Worker} runs one network operation at a time and answers {@code false}
 * rather than queueing, because Telegram operations share one connection and one
 * session: two of them running at once would interleave their {@code msg_id}s
 * and {@code seq_no}s. That refusal is an ordinary outcome of pressing a key at
 * the wrong moment, not an error, so nothing about it may look like one - the
 * failure callback in particular, which every caller uses to tell the user their
 * action did not happen.
 *
 * The defect this exists for is what the eighteen callers that ignored the
 * answer did with it: a busy screen that never came down, a status line stuck at
 * "reacting...", a password box cleared for a submission that was never made.
 * None of that is reachable from a desktop suite - it is lcdui on a screen stack
 * inside a MIDlet - so what is pinned here is the contract underneath it, and
 * {@code SourceGuardTest} pins the other half: that every caller reads the
 * answer at all.
 *
 * {@code busyWith()} is the one piece of the fix that lives on the worker. The
 * message a refusal shows names the task that is holding it, and the task can
 * finish between the refusal and the message being built - so a caller reading
 * {@code currentTask()} directly shows "Finishing null first" to whoever is
 * unlucky with the timing.
 */
public final class WorkerTest implements Test
{
    /** Long enough that a loaded CI box does not fail it; short enough to notice. */
    private static final long TIMEOUT_MS = 10000L;

    public String name() { return "app/worker-refusal"; }

    public void run() throws Exception
    {
        aSecondTaskIsRefusedWhileTheFirstRuns();
        theRefusalCanNameWhatIsHoldingIt();
        anIdleWorkerNamesNothingInParticular();
        theWorkerIsFreeInsideItsOwnCallback();
        aFailedTaskReleasesTheWorkerToo();
    }

    // -------------------------------------------------------------- the defect

    /**
     * The admission contract. A second submission while the first is on the
     * network must be refused whole: no thread, no {@code run()}, and neither
     * callback - the failure one least of all, because a caller that treats a
     * refusal as a failure tells the user the server rejected them.
     */
    private static void aSecondTaskIsRefusedWhileTheFirstRuns() throws Exception
    {
        Worker worker = new Worker();
        Gate started = new Gate();
        Gate release = new Gate();

        Recorder first = new Recorder();
        Assert.isTrue("the first submission is accepted",
                worker.submit(blocking("messages.getHistory", started, release),
                        first));
        started.await();

        Recorder second = new Recorder();
        Counter refused = new Counter();
        Assert.isFalse("a second submission is refused while the first runs",
                worker.submit(counting("outbox.enqueue", refused), second));

        Assert.equal("the refused task never ran", 0, refused.count);
        Assert.equal("the refused task got no success callback",
                0, second.successes);
        Assert.equal("and no failure callback either - a refusal is not a"
                + " failure", 0, second.failures);

        release.open();
        first.awaitDone();
        Assert.equal("the accepted task still finished normally",
                1, first.successes);
        Assert.equal("and the refused one never ran after the fact",
                0, refused.count);
        Assert.equal("nor did its callback arrive late",
                0, second.successes + second.failures);
    }

    // ------------------------------------------------------- what to say to the user

    /**
     * A refusal message names the operation the user is waiting on. The name is
     * the task's own, which is what makes "Finishing messages.getHistory first"
     * something a device report can be read against.
     */
    private static void theRefusalCanNameWhatIsHoldingIt() throws Exception
    {
        Worker worker = new Worker();
        Gate started = new Gate();
        Gate release = new Gate();
        Recorder held = new Recorder();

        Assert.isTrue("accepted", worker.submit(
                blocking("messages.getHistory", started, release), held));
        started.await();

        Assert.isTrue("the worker reports itself busy", worker.isBusy());
        Assert.equal("the refusal can name what is holding it",
                "messages.getHistory", worker.busyWith());
        Assert.equal("currentTask agrees with it",
                "messages.getHistory", worker.currentTask());

        release.open();
        held.awaitDone();
        Assert.isFalse("free again once the callback has run", worker.isBusy());
    }

    /**
     * The window this closes: {@code submit} answers false, the running task
     * finishes, and only then does the caller build its message. Reading
     * {@code currentTask()} at that point gives null, and the alert the user
     * gets reads "Finishing null first".
     */
    private static void anIdleWorkerNamesNothingInParticular()
    {
        Worker worker = new Worker();

        Assert.isFalse("a fresh worker is not busy", worker.isBusy());
        Assert.isTrue("and has no current task",
                worker.currentTask() == null);
        Assert.equal("an idle worker still describes itself in words a"
                + " refusal message can use",
                "another operation", worker.busyWith());
    }

    // ------------------------------------------------------------ the release

    /**
     * {@code Worker} clears its busy flag <em>before</em> running the callback,
     * and several paths depend on it: the connect callback submits
     * {@code messages.getDialogs}, sign-in does the same, and a chat open
     * submits the history fetch from inside the one that preceded it. If the
     * flag outlived the callback every one of those would refuse itself.
     */
    private static void theWorkerIsFreeInsideItsOwnCallback() throws Exception
    {
        final Worker worker = new Worker();
        final Counter nested = new Counter();
        final Recorder chained = new Recorder();
        final boolean[] accepted = new boolean[1];

        Recorder outer = new Recorder()
        {
            public void onSuccess(Object result)
            {
                accepted[0] = worker.submit(counting("messages.getDialogs",
                        nested), chained);
                super.onSuccess(result);
            }
        };

        Assert.isTrue("accepted", worker.submit(immediate("connect"), outer));
        outer.awaitDone();

        // Asserted before the wait: a false here means the chained callback
        // never comes, and a ten-second timeout says far less than this line.
        Assert.isTrue("a task submitted from inside a callback is accepted",
                accepted[0]);
        chained.awaitDone();
        Assert.equal("and it actually ran", 1, nested.count);
    }

    /**
     * The same release, on the path that reaches it through a catch block. A
     * worker left busy by a failed task would refuse everything for the rest of
     * the session, which on a handset presents as a client that stopped
     * responding to its own menu.
     */
    private static void aFailedTaskReleasesTheWorkerToo() throws Exception
    {
        Worker worker = new Worker();
        Recorder failing = new Recorder();

        Assert.isTrue("accepted", worker.submit(new Worker.Task()
        {
            public String name() { return "auth.signIn"; }
            public Object run() throws Exception
            {
                throw new java.io.IOException("no route to host");
            }
        }, failing));
        failing.awaitDone();

        Assert.equal("the failure reached the callback", 1, failing.failures);
        Assert.isFalse("and the worker is free again", worker.isBusy());
        Assert.equal("with nothing left holding it",
                "another operation", worker.busyWith());

        Counter after = new Counter();
        Recorder next = new Recorder();
        Assert.isTrue("so the next action is admitted",
                worker.submit(counting("messages.getDialogs", after), next));
        next.awaitDone();
        Assert.equal("and it ran", 1, after.count);
    }

    // ------------------------------------------------------------- helpers

    /** Blocks until {@code release} opens, announcing itself on {@code started}. */
    private static Worker.Task blocking(final String name, final Gate started,
                                        final Gate release)
    {
        return new Worker.Task()
        {
            public String name() { return name; }
            public Object run() throws Exception
            {
                started.open();
                release.await();
                return name;
            }
        };
    }

    /** Counts its own executions, so "never ran" is an assertion and not a hope. */
    private static Worker.Task counting(final String name, final Counter counter)
    {
        return new Worker.Task()
        {
            public String name() { return name; }
            public Object run() throws Exception
            {
                counter.hit();
                return name;
            }
        };
    }

    private static Worker.Task immediate(final String name)
    {
        return counting(name, new Counter());
    }

    /**
     * One-shot latch.
     *
     * The suite has no {@code CountDownLatch} habit and this needs a timeout on
     * every wait: a contract test that hangs is worse than one that fails,
     * because CI reports it as nothing at all.
     */
    private static final class Gate
    {
        private boolean open;

        synchronized void open()
        {
            open = true;
            notifyAll();
        }

        synchronized void await() throws InterruptedException
        {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!open)
            {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0)
                {
                    throw new IllegalStateException("timed out waiting for the"
                            + " worker thread");
                }
                wait(left);
            }
        }
    }

    private static final class Counter
    {
        volatile int count;

        synchronized void hit() { count++; }
    }

    /** Records which callback arrived, and lets the test wait for it. */
    private static class Recorder implements Worker.Callback
    {
        volatile int successes;
        volatile int failures;
        private final Gate done = new Gate();

        public void onSuccess(Object result)
        {
            successes++;
            done.open();
        }

        public void onFailure(Throwable error)
        {
            failures++;
            done.open();
        }

        void awaitDone() throws InterruptedException { done.await(); }
    }
}
