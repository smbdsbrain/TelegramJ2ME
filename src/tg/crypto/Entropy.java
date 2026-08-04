package tg.crypto;

/**
 * Entropy collection for {@link Rng}.
 *
 * <h3>The honest status of this file</h3>
 * A 2011 feature phone has no hardware RNG, no {@code /dev/urandom}, and CLDC
 * exposes no {@code java.security.SecureRandom}. Everything below is a
 * best-effort scrape of quantities that are hard for a remote party to predict.
 * The MTProto security guidelines are explicit that a weak pool produces a weak
 * auth_key.
 *
 * <h3>What one handset actually yielded</h3>
 * Measured on an Alcatel One Touch 810D, 2026-07-31, by
 * {@code tg.plat.EntropyProbe} - see {@code docs/hardware/alcatel-ot810d.md}:
 * <ul>
 *   <li><b>about 58 bits per {@link #gather()} call</b>, from jitter alone;</li>
 *   <li>seven launches produced no repeated seed, six of them cold boots
 *       started from an identical hand-set clock - two of those to the same
 *       millisecond, and their digests still diverged;</li>
 *   <li>the wall clock resets at boot on that handset and so contributes
 *       <b>nothing</b> across cold boots;</li>
 *   <li>{@code new Object().hashCode()} is not a sequential counter there and
 *       stays distinct across allocate-and-discard, so it does contribute -
 *       at least 8 bits per call, uncounted in the 58.</li>
 * </ul>
 *
 * <blockquote>
 * 58 bits is roughly a fifth of what a 2048-bit DH secret needs, so a single
 * {@code gather()} is not a sufficient seed for an auth_key. Folding in several
 * is what {@link AuthKeySeeding} does, and {@code tg.mt.Handshake} is the only
 * caller that needs to. <b>Open item:</b> one handset is one handset - quantify
 * these sources on every supported physical runtime before declaring the
 * auth_key path secure, and note that nothing here shows consecutive gathers to
 * be independent. Until both are settled, treat generated keys as development
 * keys.
 * </blockquote>
 *
 * <h3>What is collected</h3>
 * <ul>
 *   <li>wall clock, at whatever resolution the device offers;</li>
 *   <li>heap free/total, which drift with allocation history;</li>
 *   <li>identity hash codes, which usually encode an allocation address;</li>
 *   <li>scheduler and clock jitter, timed over a busy loop - the only source
 *       here that is genuinely physical rather than merely obscure, and the
 *       only one the 58-bit figure above counts;</li>
 *   <li>device identity properties, which are constant per device and add
 *       uniqueness between devices but no per-run unpredictability.</li>
 * </ul>
 *
 * <h3>The user is the most defensible source, not the cheapest one</h3>
 * {@link #fromUserInput(int, long)} exists because key-press timing carries
 * human motor noise, which is unpredictable in a way that is easy to argue for -
 * unlike jitter, whose unpredictability rests on scheduler behaviour a
 * determined attacker might model.
 *
 * This file used to say the user was simply the best source, and to plan on
 * collecting a few seconds of keyboard interaction before generating an
 * auth_key. Measurement on the OT-810D says otherwise about the economics:
 * <b>3 bits per press</b>, so 256 bits costs 86 presses and the user's
 * attention, against five {@link #gather()} calls - about 600 ms of blocking
 * and no user involvement at all. Fold key timing in when it is free; do not
 * build the seeding around it.
 *
 * Note that the entropy is in the intervals, not specifically in their low
 * bits: an event clock quantised at <i>q</i> ms makes every interval a multiple
 * of <i>q</i>, so the low bits are a function of the quotient and must not be
 * counted as a second source. {@code tg.plat.KeyTimingProbe} reports the gcd of
 * the observed intervals for exactly this reason - though see its notes for
 * what that test cannot see.
 */
public final class Entropy
{
    /** Milliseconds of busy-loop jitter sampling. Kept short: this blocks. */
    private static final int JITTER_MS = 120;

    /** Device-identifying properties: unique per handset, constant per run. */
    private static final String[] DEVICE_PROPERTIES = {
        "microedition.platform",
        "microedition.encoding",
        "microedition.locale",
        "microedition.profiles",
        "microedition.configuration"
    };

    /**
     * Iteration cap for the inner spin loop.
     *
     * Not a time limit, deliberately: the failure this guards against is a
     * clock that does not advance, and no deadline expressed in
     * {@code currentTimeMillis()} can ever fire when time is frozen. Without
     * this cap {@link #collectJitter} never returns on such a handset, and
     * because {@link Rng#Rng()} calls {@link #gather()}, the MIDlet hangs the
     * first time it needs a nonce.
     */
    private static final long SPIN_CAP = 4000000L;

    /**
     * Observer for the raw jitter samples, for {@code tg.plat.EntropyProbe}.
     *
     * Push-only on purpose. The measurement code needs the unhashed spin counts
     * to estimate min-entropy, but handing back an array of them would create
     * exactly the object nobody should have: a block of raw, unconditioned
     * entropy samples that some later caller feeds into {@code addEntropy}.
     * Retaining these values requires the receiver to write the accumulation
     * itself, and the only receiver that does aggregates them into frequency
     * counts and discards the values.
     *
     * The point of routing measurement through the real loop rather than a copy
     * of it is that the probe and {@code Rng()} then execute the same bytecode.
     * A reimplementation would measure something merely similar, and any later
     * edit here would silently invalidate every published figure.
     */
    public interface JitterSink
    {
        void sample(long spins, long nowMillis);
    }

    private Entropy() { }

    /**
     * Snapshot of everything cheaply available. Called once when an {@link Rng}
     * is constructed; call again and feed the result to
     * {@link Rng#addEntropy(byte[])} whenever more is wanted.
     */
    public static byte[] gather()
    {
        Sha256 d = new Sha256();

        appendLong(d, System.currentTimeMillis());

        Runtime rt = Runtime.getRuntime();
        appendLong(d, rt.freeMemory());
        appendLong(d, rt.totalMemory());

        // Identity hash codes typically derive from an allocation address, so
        // they vary with the heap layout of this particular run.
        appendLong(d, new Object().hashCode());
        appendLong(d, d.hashCode());
        appendLong(d, Thread.currentThread().hashCode());

        for (int i = 0; i < DEVICE_PROPERTIES.length; i++)
        {
            String v = null;
            try { v = System.getProperty(DEVICE_PROPERTIES[i]); }
            catch (Throwable ignored) { }
            if (v != null)
            {
                byte[] b = v.getBytes();
                d.update(b, 0, b.length);
            }
        }

        byte[] jitter = collectJitter(JITTER_MS);
        d.update(jitter, 0, jitter.length);

        return d.digest();
    }

    /**
     * Time a busy loop against the clock and keep the low bits of each reading.
     *
     * This is the only source here whose unpredictability is physical rather
     * than merely obscure: it depends on interrupt timing, cache state and
     * whatever else the phone is doing. Its yield on a single-core 208 MHz CPU
     * with a coarse clock is exactly what still needs measuring.
     *
     * @param millis how long to sample; blocks for that long
     */
    public static byte[] collectJitter(int millis)
    {
        return collectJitter(millis, null);
    }

    /**
     * As {@link #collectJitter(int)}, additionally reporting each raw sample to
     * {@code sink}.
     *
     * The digest returned is identical either way - the sink observes, it does
     * not participate. Pass null from production code; {@code Rng()} does.
     *
     * @param sink may be null; see {@link JitterSink} for why it is push-only
     */
    public static byte[] collectJitter(int millis, JitterSink sink)
    {
        Sha256 d = new Sha256();
        long deadline = System.currentTimeMillis() + millis;
        long previous = System.currentTimeMillis();
        int samples = 0;
        boolean clockStalled = false;

        while (System.currentTimeMillis() < deadline)
        {
            long spins = 0;
            long now = previous;
            // Count how many clock reads fit inside one clock tick. The count
            // is where the jitter lives; the timestamp itself is predictable.
            while (now == previous && spins < SPIN_CAP)
            {
                now = System.currentTimeMillis();
                spins++;
            }
            if (now == previous)
            {
                // SPIN_CAP reached without the clock moving. Both this loop and
                // the enclosing one are conditioned on time advancing, so
                // continuing would never terminate.
                clockStalled = true;
                break;
            }
            previous = now;
            appendLong(d, spins);
            appendLong(d, now);
            samples++;
            if (sink != null) { sink.sample(spins, now); }
        }

        appendLong(d, samples);
        if (clockStalled)
        {
            // Distinguish a stalled run from a zero-length one in the digest,
            // so the two do not silently produce the same seed contribution.
            appendLong(d, -1L);
        }
        return d.digest();
    }

    /**
     * Fold a user input event into the pool.
     *
     * Call from the key handler with the raw key code and
     * {@code System.currentTimeMillis()}. The inter-key interval is the part
     * that carries entropy, so the caller should keep feeding events rather
     * than sampling once.
     */
    public static byte[] fromUserInput(int keyCode, long timestampMillis)
    {
        Sha256 d = new Sha256();
        appendLong(d, keyCode);
        appendLong(d, timestampMillis);
        appendLong(d, Runtime.getRuntime().freeMemory());
        return d.digest();
    }

    /**
     * A conservative lower bound on the entropy bits gathered per
     * {@link #gather()} call.
     *
     * <b>58 bits, measured on one handset</b> - an Alcatel One Touch 810D on
     * 2026-07-31, by {@code tg.plat.EntropyProbe}. See
     * {@code docs/hardware/alcatel-ot810d.md} for the raw figures and the
     * conditions.
     *
     * This is not a claim about any other device, and the number is a floor
     * rather than an estimate: it counts jitter only, at a 99% confidence bound,
     * after a serial-correlation discount, with identity hash codes and heap
     * readings charged at zero even though the same handset showed the former
     * contributing at least 8 bits per call. A different runtime could be far
     * worse - a clock that does not advance would make it zero.
     *
     * Do not use it to justify a single-call seed: 58 bits is about a fifth of
     * what a 2048-bit DH secret needs, which is why {@link AuthKeySeeding}
     * exists. Nor to justify multiplying it by a gather count - see that class
     * on why the totals are not summed.
     *
     * <b>A second handset has since been measured and is far worse.</b> A
     * Samsung GT-C3592 yields about 21 bits per gather, because its clock ticks
     * at 12 ms rather than 4 and the fixed 120 ms jitter window therefore holds
     * 10 samples instead of 26 - see {@code docs/hardware/samsung-gt-c3592.md}.
     * The number below is deliberately still the alcatel's: it is one device's
     * measurement, this method has never claimed to be a fleet minimum, and
     * changing it silently reshapes {@link AuthKeySeeding#GATHERS}. Making the
     * seeding size itself against the slowest supported clock is issue #2.
     */
    public static int estimatedBitsPerGather()
    {
        return 58;
    }

    private static void appendLong(Sha256 d, long v)
    {
        d.update((byte) (v >>> 56));
        d.update((byte) (v >>> 48));
        d.update((byte) (v >>> 40));
        d.update((byte) (v >>> 32));
        d.update((byte) (v >>> 24));
        d.update((byte) (v >>> 16));
        d.update((byte) (v >>> 8));
        d.update((byte) v);
    }
}
