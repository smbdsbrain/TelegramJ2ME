package tgtest;

import java.util.ArrayList;
import java.util.List;

import tg.crypto.AuthKeySeeding;
import tg.crypto.Entropy;
import tg.crypto.Rng;
import tg.crypto.Sha256;

/**
 * The auth-key seeding barrier.
 *
 * The interesting failures here are all silent ones: a barrier that reports six
 * gathers and folds three, one that folds them in the wrong order, one that keeps
 * the samples alive afterwards, or one that a degraded source turns into a no-op
 * while still reporting a duration. None of those show up as a broken handshake -
 * they show up later as a key someone could have guessed. So the tests drive the
 * fold with a scripted source and check the resulting pool against an
 * independently rebuilt one, rather than trusting the barrier's own report of
 * what it did.
 *
 * Since the count stopped being a constant there is a second question to ask of
 * it: does it stop in the right place? {@link #sizesItselfFromTheMeasuredYield}
 * replays the three handsets this project has measured - as sample streams with
 * their published sample counts per gather - and asserts the barrier reaches its
 * target on each, doing more work on the slow clock and less on the fast one.
 * That is the whole argument of issue #2, executable.
 */
public final class AuthKeySeedingTest implements Test
{
    /** The tag AuthKeySeeding folds in; reimplemented here, not imported. */
    private static final String DOMAIN = "tg/authkey/seed/v1";

    private static final byte[] SUBJECT_SEED = Assert.ascii("barrier-subject");

    public String name() { return "crypto/authkey-seeding"; }

    public void run() throws Exception
    {
        everyGatherIsFoldedInOrder();
        samplesAreWipedAfterFolding();
        contextSeparatesDomains();
        degradedSourceIsRefused();
        aDeadClockIsRefused();
        aShortfallIsReportedNotSilent();
        sizesItselfFromTheMeasuredYield();
        realBarrierRuns();
        boundsStayPinned();
    }

    /**
     * The whole claim of the class in one assertion: after the barrier the pool
     * is exactly what folding the context and then every gather, in order,
     * produces - so none was skipped, none was reordered, none was folded into
     * something else, and the count is the one the outcome reports rather than
     * whatever the loop happened to run.
     */
    private void everyGatherIsFoldedInOrder()
    {
        ScriptedSource source = healthy(26);
        Rng subject = Rng.forTesting(SUBJECT_SEED);
        AuthKeySeeding.Outcome o =
                AuthKeySeeding.strengthen(subject, 2, false, false, source, null);

        Assert.equal("one gather per round reported", o.gathers, source.calls);
        Assert.bytesEqual("pool matches context + g0..gN folded in that order",
                replay(2, false, false, o).nextBytes(32),
                subject.nextBytes(32));
    }

    /**
     * The samples are handed over, not lent. A source that returned a cached
     * buffer would find it zeroed, which is the contract GatherSource states.
     */
    private void samplesAreWipedAfterFolding()
    {
        ScriptedSource source = healthy(26);
        AuthKeySeeding.Outcome o = AuthKeySeeding.strengthen(
                Rng.forTesting(Assert.ascii("wipe")), 1, false, false, source, null);

        Assert.equal("every round recorded", o.gathers, source.calls);
        for (int i = 0; i < source.calls; i++)
        {
            byte[] handed = (byte[]) source.handedOut.get(i);
            for (int j = 0; j < handed.length; j++)
            {
                Assert.equal("sample " + i + " byte " + j + " wiped", 0, handed[j]);
            }
        }
    }

    /**
     * Identical samples must not produce identical pools for different keys.
     * Without this a media-DC key and a primary key generated from the same
     * scheduler state would be two views of one derivation.
     */
    private void contextSeparatesDomains()
    {
        Rng subject = Rng.forTesting(SUBJECT_SEED);
        AuthKeySeeding.Outcome o = AuthKeySeeding.strengthen(subject, 2, false,
                false, healthy(26), null);
        byte[] pool = subject.nextBytes(32);

        // Replaying with the same elapsed time and the same round count isolates
        // the context as the only variable, so a difference below cannot be
        // timing noise. The scripted stream is deterministic, so a replay of the
        // same barrier really does see the same gathers.
        Assert.bytesEqual("its own context reproduces the pool",
                replay(2, false, false, o).nextBytes(32), pool);
        Assert.isTrue("media role separates the domain",
                !same(pool, replay(2, false, true, o).nextBytes(32)));
        Assert.isTrue("dc id separates the domain",
                !same(pool, replay(4, false, false, o).nextBytes(32)));
        Assert.isTrue("environment separates the domain",
                !same(pool, replay(2, true, false, o).nextBytes(32)));
    }

    /**
     * {@code Rng.addEntropy} ignores an empty array, so without an explicit
     * check a degraded source would produce a barrier that folds nothing and
     * still reports a duration.
     */
    private void degradedSourceIsRefused()
    {
        int before = AuthKeySeeding.completedBarriers();

        try
        {
            AuthKeySeeding.strengthen(Rng.forTesting(SUBJECT_SEED), 1, false, false,
                    new AuthKeySeeding.GatherSource()
                    {
                        public byte[] gather(Entropy.JitterSink sink) { return null; }
                    }, null);
            Assert.fail("a null gather was accepted");
        }
        catch (IllegalStateException expected) { }

        try
        {
            AuthKeySeeding.strengthen(Rng.forTesting(SUBJECT_SEED), 1, false, false,
                    new AuthKeySeeding.GatherSource()
                    {
                        public byte[] gather(Entropy.JitterSink sink)
                        {
                            return new byte[4];
                        }
                    }, null);
            Assert.fail("a short gather was accepted");
        }
        catch (IllegalStateException expected) { }

        Assert.equal("a refused barrier does not count as completed",
                before, AuthKeySeeding.completedBarriers());
    }

    /**
     * A handset whose clock does not advance produces gathers that are perfectly
     * well-formed digests of nothing surprising. That is the catastrophic case -
     * the probe calls it "seeding is NOT SAFE" - and it has to be refused, not
     * reported as a shortfall.
     *
     * Two shapes of it: a source that observes no samples at all, and one whose
     * samples are all identical. Both credit zero bits.
     */
    private void aDeadClockIsRefused() throws Exception
    {
        int before = AuthKeySeeding.completedBarriers();

        try
        {
            AuthKeySeeding.strengthen(Rng.forTesting(SUBJECT_SEED), 1, false, false,
                    new ScriptedSource(new int[0]), null);
            Assert.fail("a barrier that measured nothing was accepted");
        }
        catch (IllegalStateException expected)
        {
            Assert.isTrue("says it credited nothing: " + expected.getMessage(),
                    expected.getMessage().indexOf("0 bits") >= 0);
        }

        try
        {
            AuthKeySeeding.strengthen(Rng.forTesting(SUBJECT_SEED), 1, false, false,
                    new ScriptedSource(repeated(8, 4242)), null);
            Assert.fail("a frozen sample stream was accepted");
        }
        catch (IllegalStateException expected) { }

        Assert.equal("neither counts as a completed barrier",
                before, AuthKeySeeding.completedBarriers());
    }

    /**
     * A slow clock is not a dead one.
     *
     * Two samples per gather, one bit apiece: the cap arrives long before 256
     * credited bits do. Refusing there would lock a working handset out of
     * signing in over a bound the client cannot improve, so the barrier returns
     * and says it fell short - and the caller is the one that decides what to do
     * about it. What must never happen is this case passing for a full barrier.
     */
    private void aShortfallIsReportedNotSilent()
    {
        ScriptedSource weak = new ScriptedSource(2, 2, 7);
        AuthKeySeeding.Outcome o = AuthKeySeeding.strengthen(
                Rng.forTesting(SUBJECT_SEED), 2, false, false, weak, null);

        Assert.equal("ran to the cap", AuthKeySeeding.MAX_GATHERS, o.gathers);
        Assert.isTrue("credited something, or it should have been refused",
                o.bits > 0);
        Assert.isTrue("but not the target: " + o.describe(),
                o.bits < AuthKeySeeding.TARGET_BITS);
        Assert.isTrue("and says so", o.shortOfTarget);
    }

    /**
     * The reason this class stopped using a constant.
     *
     * Each stream stands in for one measured handset: {@code gather()} spends a
     * fixed 120 ms, so the samples it collects are {@code 120 / tick} and that
     * number - 10, 26, 120 - is the whole of what differs between these devices.
     * A count sized from any one of them is wrong on the other two; the barrier
     * has to arrive at a different answer for each, and the answers have to be
     * ordered the way the clocks are.
     *
     * The expected counts are what this estimator produces today, pinned so that
     * a change in the arithmetic has to be looked at rather than absorbed. They
     * are dominated by MinEntropy.MIN_SAMPLES_FOR_CONFIDENCE: on a coarse clock
     * the barrier is gathering until it has enough samples to state a bound at
     * all, which is exactly the honest place for it to stop.
     */
    private void sizesItselfFromTheMeasuredYield()
    {
        int c3592 = sizeFor(10);     // Samsung GT-C3592, 12 ms tick
        int alcatel = sizeFor(26);   // Alcatel OT-810D, 4 ms tick
        int nokia = sizeFor(120);    // Nokia C3-00, 1 ms tick

        Assert.equal("GT-C3592: 10 samples per gather", 26, c3592);
        Assert.equal("OT-810D: 26 samples per gather", 10, alcatel);
        Assert.equal("C3-00: 120 samples per gather", 3, nokia);

        Assert.isTrue("a coarser clock costs more gathers", c3592 > alcatel);
        Assert.isTrue("a finer clock costs fewer", nokia < alcatel);
        Assert.isTrue("and the old constant of five was between them",
                alcatel > 5 && nokia < 5);
    }

    /** Runs one simulated handset and returns the count the barrier chose. */
    private int sizeFor(int samplesPerGather)
    {
        AuthKeySeeding.Outcome o = AuthKeySeeding.strengthen(
                Rng.forTesting(SUBJECT_SEED), 2, false, false,
                healthy(samplesPerGather), null);

        Assert.isTrue(samplesPerGather + " samples/gather reaches the target: "
                + o.describe(), !o.shortOfTarget);
        Assert.isTrue("credited at least the target: " + o.describe(),
                o.bits >= AuthKeySeeding.TARGET_BITS);
        Assert.equal("samples add up", samplesPerGather * o.gathers, o.samples);
        return o.gathers;
    }

    /**
     * The production overload, on a real pool and real jitter. No timing bound
     * is asserted: Entropy documents handsets whose clock does not advance, and
     * a test that demanded a particular duration would fail on exactly the
     * runtime this code defends against.
     */
    private void realBarrierRuns()
    {
        int before = AuthKeySeeding.completedBarriers();
        Rng rng = new Rng();
        byte[] pre = rng.nextBytes(32);
        AuthKeySeeding.Outcome o = AuthKeySeeding.strengthen(rng, 2, false, false);

        Assert.isTrue("elapsed is not negative, got " + o.millis, o.millis >= 0);
        Assert.isTrue("at least the floor of gathers: " + o.describe(),
                o.gathers >= AuthKeySeeding.MIN_GATHERS);
        Assert.isTrue("never past the cap: " + o.describe(),
                o.gathers <= AuthKeySeeding.MAX_GATHERS);
        Assert.isTrue("credited something, or it would have thrown", o.bits > 0);
        Assert.isTrue("the shortfall flag agrees with the figures",
                o.shortOfTarget == (o.bits < AuthKeySeeding.TARGET_BITS));
        Assert.equal("one barrier completed", before + 1,
                AuthKeySeeding.completedBarriers());
        Assert.isTrue("the outcome is published for the report",
                AuthKeySeeding.lastOutcome() == o);
        Assert.isTrue("the pool moved", !same(pre, rng.nextBytes(32)));
        Assert.isFalse("a real pool is not deterministic", rng.isDeterministic());
    }

    /**
     * A tripwire on the bounds, not on arithmetic.
     *
     * The target is what a 2048-bit DH secret is conventionally seeded from. The
     * caps are what stops a handset with worse jitter than anything measured from
     * blocking a sign-in indefinitely, and lowering either of them silently
     * weakens every key generated on a slow clock.
     */
    private void boundsStayPinned()
    {
        Assert.equal("sized against a 256-bit target", 256,
                AuthKeySeeding.TARGET_BITS);
        Assert.equal("at least two separated gathers", 2,
                AuthKeySeeding.MIN_GATHERS);
        Assert.equal("capped at 64 gathers", 64, AuthKeySeeding.MAX_GATHERS);
        Assert.equal("capped at 8 s", 8000, AuthKeySeeding.MAX_MILLIS);
        Assert.isTrue("the cap leaves room for the worst measured handset",
                AuthKeySeeding.MAX_GATHERS >= 26);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The barrier's recipe, written out longhand from its documented behaviour
     * rather than borrowed from the implementation.
     *
     * The barrier folds its own duration in last, which is the one input a test
     * cannot predict - so the replay takes the values the outcome reported. Every
     * other input is fixed, which is what makes the comparisons above exact
     * rather than statistical.
     */
    private static Rng replay(int dcId, boolean test, boolean media,
                              AuthKeySeeding.Outcome o)
    {
        Rng r = Rng.forTesting(SUBJECT_SEED);
        r.addEntropy(context(dcId, test, media));
        for (int i = 0; i < o.gathers; i++)
        {
            r.addEntropy(sample(i));
        }
        r.addEntropy(o.millis);
        return r;
    }

    /** Deterministic stand-in samples, distinct per round. */
    private static byte[] sample(int index)
    {
        Sha256 d = new Sha256();
        byte[] label = Assert.ascii("scripted-gather-" + index);
        d.update(label, 0, label.length);
        return d.digest();
    }

    private static byte[] context(int dcId, boolean testEnvironment, boolean media)
    {
        byte[] out = new byte[DOMAIN.length() + 5];
        for (int i = 0; i < DOMAIN.length(); i++)
        {
            out[i] = (byte) DOMAIN.charAt(i);
        }
        int p = DOMAIN.length();
        out[p] = (byte) (dcId >>> 24);
        out[p + 1] = (byte) (dcId >>> 16);
        out[p + 2] = (byte) (dcId >>> 8);
        out[p + 3] = (byte) dcId;
        out[p + 4] = (byte) ((testEnvironment ? 1 : 0) | (media ? 2 : 0));
        return out;
    }

    /**
     * A source whose samples are worth about 2.3 bits each - the neighbourhood
     * all three measured handsets sit in - at the given samples per gather.
     */
    private static ScriptedSource healthy(int samplesPerGather)
    {
        return new ScriptedSource(samplesPerGather, 5, 1);
    }

    private static int[] repeated(int n, int value)
    {
        int[] out = new int[n];
        for (int i = 0; i < n; i++) { out[i] = value; }
        return out;
    }

    private static boolean same(byte[] a, byte[] b)
    {
        if (a.length != b.length) { return false; }
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i]) { return false; }
        }
        return true;
    }

    /**
     * Deterministic stand-in for Entropy.gather(), recording what it handed over
     * and feeding the sink a scripted spin stream.
     *
     * The stream is what the barrier sizes itself from, so it has to look like
     * jitter rather than like a counter: values come from an LCG so that adjacent
     * samples are not a function of each other, which is what the barrier's own
     * serial-correlation check would otherwise - correctly - punish.
     */
    private static final class ScriptedSource implements AuthKeySeeding.GatherSource
    {
        private final int samplesPerGather;
        private final int distinct;
        private final int[] fixed;
        private int state;

        int calls;
        final List handedOut = new ArrayList();

        /** @param distinct how many spin values the stream draws from */
        ScriptedSource(int samplesPerGather, int distinct, int seed)
        {
            this.samplesPerGather = samplesPerGather;
            this.distinct = distinct;
            this.fixed = null;
            this.state = seed;
        }

        /** A literal stream, replayed once per gather. Empty means "no samples". */
        ScriptedSource(int[] values)
        {
            this.samplesPerGather = values.length;
            this.distinct = 0;
            this.fixed = values;
            this.state = 0;
        }

        public byte[] gather(Entropy.JitterSink sink)
        {
            for (int i = 0; i < samplesPerGather; i++)
            {
                sink.sample(fixed != null ? fixed[i] : next(), 1000L + i);
            }
            byte[] out = sample(calls);
            handedOut.add(out);
            calls++;
            return out;
        }

        /** Numerical Recipes LCG; the high bits are the ones worth taking. */
        private int next()
        {
            state = state * 1664525 + 1013904223;
            return 1100 + ((state >>> 16) & 0x7fff) % distinct;
        }
    }
}
