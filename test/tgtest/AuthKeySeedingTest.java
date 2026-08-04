package tgtest;

import tg.crypto.AuthKeySeeding;
import tg.crypto.Entropy;
import tg.crypto.Rng;
import tg.crypto.Sha256;

/**
 * The auth-key seeding barrier.
 *
 * The interesting failures here are all silent ones: a barrier that reports
 * five gathers and folds three, one that folds them in the wrong order, one
 * that keeps the samples alive afterwards, or one that a degraded source turns
 * into a no-op while still reporting a duration. None of those show up as a
 * broken handshake - they show up later as a key someone could have guessed. So
 * the tests drive the fold with a scripted source and check the resulting pool
 * against an independently rebuilt one, rather than trusting the barrier's own
 * report of what it did.
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
        realBarrierRuns();
        countStaysSizedForTheTarget();
    }

    /**
     * The whole claim of the class in one assertion: after the barrier the pool
     * is exactly what folding the context and then every gather, in order,
     * produces - so none was skipped, none was reordered, none was folded into
     * something else, and the count is the configured one rather than whatever
     * the loop happened to run.
     */
    private void everyGatherIsFoldedInOrder()
    {
        ScriptedSource source = new ScriptedSource();
        Rng subject = Rng.forTesting(SUBJECT_SEED);
        long elapsed = AuthKeySeeding.strengthen(subject, 2, false, false, source);

        Assert.equal("one gather per configured round",
                AuthKeySeeding.GATHERS, source.calls);
        Assert.bytesEqual("pool matches context + g0..gN folded in that order",
                replay(2, false, false, elapsed).nextBytes(32),
                subject.nextBytes(32));
    }

    /**
     * The samples are handed over, not lent. A source that returned a cached
     * buffer would find it zeroed, which is the contract GatherSource states.
     */
    private void samplesAreWipedAfterFolding()
    {
        ScriptedSource source = new ScriptedSource();
        AuthKeySeeding.strengthen(Rng.forTesting(Assert.ascii("wipe")), 1, false,
                false, source);
        Assert.equal("every round recorded", AuthKeySeeding.GATHERS, source.calls);
        for (int i = 0; i < source.calls; i++)
        {
            byte[] handed = source.handedOut[i];
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
        long elapsed = AuthKeySeeding.strengthen(subject, 2, false, false,
                new ScriptedSource());
        byte[] pool = subject.nextBytes(32);

        // Replaying with the same elapsed time isolates the context as the only
        // variable, so a difference below cannot be timing noise.
        Assert.bytesEqual("its own context reproduces the pool",
                replay(2, false, false, elapsed).nextBytes(32), pool);
        Assert.isTrue("media role separates the domain",
                !same(pool, replay(2, false, true, elapsed).nextBytes(32)));
        Assert.isTrue("dc id separates the domain",
                !same(pool, replay(4, false, false, elapsed).nextBytes(32)));
        Assert.isTrue("environment separates the domain",
                !same(pool, replay(2, true, false, elapsed).nextBytes(32)));
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
                        public byte[] gather() { return null; }
                    });
            Assert.fail("a null gather was accepted");
        }
        catch (IllegalStateException expected) { }

        try
        {
            AuthKeySeeding.strengthen(Rng.forTesting(SUBJECT_SEED), 1, false, false,
                    new AuthKeySeeding.GatherSource()
                    {
                        public byte[] gather() { return new byte[4]; }
                    });
            Assert.fail("a short gather was accepted");
        }
        catch (IllegalStateException expected) { }

        Assert.equal("a refused barrier does not count as completed",
                before, AuthKeySeeding.completedBarriers());
    }

    /**
     * The production overload, on a real pool and real jitter. No timing bound
     * is asserted: Entropy documents handsets whose clock does not advance, and
     * a test that demanded a positive duration would fail on exactly the runtime
     * this code defends against.
     */
    private void realBarrierRuns()
    {
        int before = AuthKeySeeding.completedBarriers();
        Rng rng = new Rng();
        byte[] pre = rng.nextBytes(32);
        long elapsed = AuthKeySeeding.strengthen(rng, 2, false, false);

        Assert.isTrue("elapsed is not negative, got " + elapsed, elapsed >= 0);
        Assert.equal("one barrier completed", before + 1,
                AuthKeySeeding.completedBarriers());
        Assert.isTrue("the pool moved", !same(pre, rng.nextBytes(32)));
        Assert.isFalse("a real pool is not deterministic", rng.isDeterministic());
    }

    /**
     * A tripwire on the sizing rule, not on arithmetic. If a later device run
     * lowers estimatedBitsPerGather, five gathers stop covering the target, and
     * whoever lowers it has to come through here to find that out.
     */
    private void countStaysSizedForTheTarget()
    {
        Assert.equal("five gathers, per docs/hardware/alcatel-ot810d.md",
                5, AuthKeySeeding.GATHERS);
        Assert.equal("sized against a 256-bit target", 256,
                AuthKeySeeding.TARGET_BITS);
        Assert.isTrue("the configured count still covers the target: "
                + AuthKeySeeding.GATHERS + " x " + Entropy.estimatedBitsPerGather(),
                AuthKeySeeding.GATHERS * Entropy.estimatedBitsPerGather()
                        >= AuthKeySeeding.TARGET_BITS);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * The barrier's recipe, written out longhand from its documented behaviour
     * rather than borrowed from the implementation.
     *
     * The barrier folds its own duration in last, which is the one input a test
     * cannot predict - so the replay takes the value {@code strengthen} reported
     * having folded. Everything else is fixed, which is what makes the
     * comparisons above exact rather than statistical.
     */
    private static Rng replay(int dcId, boolean test, boolean media, long elapsed)
    {
        Rng r = Rng.forTesting(SUBJECT_SEED);
        r.addEntropy(context(dcId, test, media));
        for (int i = 0; i < AuthKeySeeding.GATHERS; i++)
        {
            r.addEntropy(sample(i));
        }
        r.addEntropy(elapsed);
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

    private static boolean same(byte[] a, byte[] b)
    {
        if (a.length != b.length) { return false; }
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i]) { return false; }
        }
        return true;
    }

    /** Deterministic stand-in for Entropy.gather(), recording what it handed over. */
    private static final class ScriptedSource implements AuthKeySeeding.GatherSource
    {
        int calls;
        final byte[][] handedOut = new byte[AuthKeySeeding.GATHERS][];

        public byte[] gather()
        {
            byte[] out = sample(calls);
            handedOut[calls++] = out;
            return out;
        }
    }
}
