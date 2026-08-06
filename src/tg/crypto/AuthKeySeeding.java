package tg.crypto;

/**
 * The seeding barrier a permanent MTProto auth_key must cross.
 *
 * <h3>What this is for</h3>
 * {@link Rng#Rng()} folds in one {@link Entropy#gather()}, which is the right
 * cost for a nonce, a padding block or an outgoing {@code random_id}. It is not
 * the right cost for a 2048-bit DH secret: a gather is worth between 21 and 165
 * bits depending on the handset, so a pool that has seen one of them may be far
 * short of what a permanent key needs. Rather than make every {@code Rng()} in
 * the process pay for that, the expensive seeding is named, isolated here, and
 * invoked from the one place that generates a key - {@code tg.mt.Handshake}.
 *
 * <h3>The count is measured, not compiled in</h3>
 * This class used to fold in a constant five gathers, divided out of one
 * handset's 58 bits per gather. Three handsets later that constant was wrong in
 * both directions - {@code gather()} spends a fixed wall-clock window, so its
 * sample count is {@code window / clock tick} and its yield is a property of the
 * device:
 *
 * <pre>
 *   Alcatel OT-810D    4 ms tick    26 samples    ~58 bits/gather
 *   Samsung GT-C3592  12 ms tick    10 samples    ~21 bits/gather
 *   Nokia C3-00        1 ms tick   120 samples  ~135-165 bits/gather
 * </pre>
 *
 * Five gathers are about 290 bits on the first, 105 on the second and 675 on the
 * third: short by a factor of 2.6 on one device and 2.5x more work than needed on
 * another. So the barrier now counts what it is collecting - {@link JitterYield}
 * observes the very samples being folded in - and stops when the measured total
 * reaches {@link #TARGET_BITS}. A good clock pays less; a bad one pays more; no
 * number in this file has to be right about a handset nobody has measured. See
 * issue #2 and {@code docs/hardware/}.
 *
 * <h3>What is and is not claimed</h3>
 * The credited figure counts jitter only, at a 99% confidence bound, after a
 * serial-correlation discount, with the heap readings, identity hash codes and
 * wall clock that {@code gather()} also folds in charged at zero. Samples are
 * pooled across gathers rather than a per-gather figure being multiplied, so
 * repetition between gathers lowers the estimate instead of being summed - but
 * that is a mitigation and not a demonstration that consecutive gathers are
 * independent. The one-run, one-handset caveats in {@link Entropy} are unchanged
 * by this class.
 *
 * <h3>Fail closed, and be loud when you cannot</h3>
 * {@link Rng#addEntropy(byte[])} returns quietly when handed nothing, so a
 * degraded source would produce a barrier that reports success having folded in
 * no entropy at all. Every gather is checked, and a short or absent one aborts
 * the key generation. So does a run that credits <b>zero</b> bits, which is what
 * a handset whose clock does not advance produces.
 *
 * A run that credits something but never reaches the target is different: it is
 * a slow clock, not a dead one, and refusing there would lock a working handset
 * out of signing in for the sake of a bound nobody can improve from the client.
 * That case returns with {@link Outcome#shortOfTarget} set, and the caller says
 * so at warning level.
 *
 * <h3>Diagnostics</h3>
 * Deliberately no dependency on {@code tg.diag}: the rest of {@code tg.crypto}
 * reaches outside the package only for {@code tg.io.Hex}, and a hashing helper
 * should not decide what gets logged. The caller receives an {@link Outcome} and
 * logs it - never a sample, never pool state.
 */
public final class AuthKeySeeding
{
    /** The seeding target: what a 2048-bit DH secret is conventionally given. */
    public static final int TARGET_BITS = 256;

    /**
     * Gathers folded in before the measurement is even consulted.
     *
     * Two, not one, because the narrow claim this class can defend is that the
     * pool behind a permanent key has seen several <em>separated</em> physical
     * samples rather than one continuous stretch of the same scheduler state. On
     * every handset measured the estimator asks for more than this anyway; the
     * floor exists for the device nobody has measured, whose clock might be fine
     * enough to reach 256 credited bits inside a single window.
     */
    public static final int MIN_GATHERS = 2;

    /**
     * Hard ceiling on rounds, and on wall-clock time.
     *
     * A cap is not a target. On the worst handset measured the estimator settles
     * around 26 gathers (~3.4 s against a 24-second handshake); these bounds sit
     * well above that so they bind only on a runtime whose jitter is worse than
     * anything measured. Both exist because {@code gather()} blocks: a barrier
     * that kept going until an unreachable target would hang the sign-in it is
     * supposed to protect.
     */
    public static final int MAX_GATHERS = 64;

    /** @see #MAX_GATHERS */
    public static final int MAX_MILLIS = 8000;

    /**
     * Pause between gathers.
     *
     * {@link Entropy#collectJitter} spins against the clock, so back-to-back
     * gathers sample one uninterrupted stretch of the same scheduler state.
     * Yielding the CPU in between shifts the phase and lets other activity
     * intervene. It is an attempt at decorrelation, not a proof of it, and it
     * also stops the barrier pinning a single-core handset for its whole
     * duration.
     */
    private static final int SPACING_MS = 10;

    /**
     * Domain separation for this use of the pool.
     *
     * Folding a constant in does not add entropy. What it does is make the
     * auth-key pool state diverge from every other pool state derived from the
     * same samples, so a key, a nonce stream and a media-DC key can never be
     * different views of one derivation.
     */
    private static final String DOMAIN = "tg/authkey/seed/v1";

    private static final Object COUNTER_LOCK = new Object();
    private static int completed;
    private static Outcome last;

    private AuthKeySeeding() { }

    /**
     * Observer-free injection point for the gather source.
     *
     * The same shape as {@link Entropy.JitterSink} and for the same reason: a
     * test needs to drive the fold order and the sizing deterministically, and
     * the only way to do that without a seam is to reimplement the loop - which
     * then measures something merely similar to what ships. Production passes
     * null and never constructs an implementation, so the device build carries
     * the interface and nothing else.
     *
     * An implementation must pass its samples to {@code sink} - that is what the
     * barrier sizes itself from - and must return a freshly allocated array on
     * every call: {@link #strengthen} zeroes what it is handed.
     */
    public interface GatherSource
    {
        byte[] gather(Entropy.JitterSink sink);
    }

    /**
     * Narration for a barrier that can now run for seconds.
     *
     * Same shape as {@link Pbkdf2.Progress} minus the cancel return: a handshake
     * has already committed to generating a key by the time this runs, and there
     * is nothing to cancel back to.
     */
    public interface Progress
    {
        void update(int gathers, int bits, int targetBits);
    }

    /** What one barrier did. Counts only - no sample, no pool state. */
    public static final class Outcome
    {
        public final int gathers;
        public final int samples;
        public final int bits;
        public final int targetBits;
        public final long millis;
        public final boolean shortOfTarget;

        Outcome(int gathers, int samples, int bits, int targetBits, long millis)
        {
            this.gathers = gathers;
            this.samples = samples;
            this.bits = bits;
            this.targetBits = targetBits;
            this.millis = millis;
            this.shortOfTarget = bits < targetBits;
        }

        /** One line for a log or a report: what it cost and what it bought. */
        public String describe()
        {
            return gathers + " gathers, " + bits + "/" + targetBits + " bits from "
                    + samples + " samples in " + millis + " ms";
        }
    }

    /**
     * Prepare {@code rng} for generating one permanent auth_key.
     *
     * Blocks until the measured yield reaches {@link #TARGET_BITS} or a cap is
     * hit - between about 0.4 s and 3.5 s on the handsets measured, up to
     * {@link #MAX_MILLIS} on one that is worse. Must not be called on the display
     * thread. Call it once per key, not once per connection.
     *
     * @param dcId           the data centre the key will belong to
     * @param testEnvironment true for the Telegram test environment
     * @param media          true for an auxiliary/media connection
     * @throws IllegalStateException if a gather comes back empty or short, or if
     *         the run credits no entropy at all
     */
    public static Outcome strengthen(Rng rng, int dcId, boolean testEnvironment,
                                     boolean media)
    {
        return strengthen(rng, dcId, testEnvironment, media, null, null);
    }

    /**
     * As {@link #strengthen(Rng, int, boolean, boolean)}, narrating each round.
     *
     * @param progress may be null
     */
    public static Outcome strengthen(Rng rng, int dcId, boolean testEnvironment,
                                     boolean media, Progress progress)
    {
        return strengthen(rng, dcId, testEnvironment, media, null, progress);
    }

    /**
     * As {@link #strengthen(Rng, int, boolean, boolean)}, taking gathers from
     * {@code source}.
     *
     * @param source   null for the real {@link Entropy#gather(Entropy.JitterSink)};
     *                 see {@link GatherSource} for the ownership contract
     * @param progress may be null
     */
    public static Outcome strengthen(Rng rng, int dcId, boolean testEnvironment,
                                     boolean media, GatherSource source,
                                     Progress progress)
    {
        if (rng == null) { throw new IllegalArgumentException("rng"); }

        long t0 = System.currentTimeMillis();

        byte[] context = context(dcId, testEnvironment, media);
        rng.addEntropy(context);
        wipe(context);

        // Not named "yield": that is a restricted identifier from Java 14 on, and
        // this file is also read on toolchains newer than the JDK 8 it is built
        // with.
        JitterYield measured = new JitterYield();
        int gathers = 0;

        while (true)
        {
            if (gathers > 0) { pause(); }

            measured.startRound();
            byte[] sample = source == null
                    ? Entropy.gather(measured) : source.gather(measured);
            if (sample == null || sample.length < Sha256.DIGEST_SIZE)
            {
                // Refusing here is the point of the class. A key generated from
                // a pool that silently absorbed nothing is worse than a
                // handshake that fails and says why.
                throw new IllegalStateException("entropy source returned "
                        + (sample == null ? "nothing" : sample.length + " bytes")
                        + "; refusing to generate an auth_key");
            }
            rng.addEntropy(sample);
            // Hygiene, not a guarantee: the digest that produced these bytes saw
            // them too, and the pool state derived from them is meant to persist.
            wipe(sample);
            measured.endRound();
            gathers++;

            if (progress != null)
            {
                progress.update(gathers, measured.creditedBits(), TARGET_BITS);
            }

            if (gathers >= MIN_GATHERS && measured.enough(TARGET_BITS)) { break; }
            if (gathers >= MAX_GATHERS) { break; }
            if (System.currentTimeMillis() - t0 >= MAX_MILLIS) { break; }
        }

        int bits = measured.creditedBits();
        if (bits <= 0)
        {
            // Not a slow clock - a dead one. collectJitter breaks out when the
            // clock stops advancing, and a pool seeded from a source that
            // provably yielded nothing is the failure this whole class exists to
            // prevent. The probe calls this case "seeding is NOT SAFE".
            throw new IllegalStateException("jitter credited 0 bits over "
                    + gathers + " gathers (" + measured.samples()
                    + " samples); refusing to generate an auth_key");
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (elapsed < 0) { elapsed = 0; }
        // How long the barrier actually took is itself a timing measurement.
        rng.addEntropy(elapsed);

        Outcome outcome = new Outcome(gathers, measured.samples(), bits,
                TARGET_BITS, elapsed);
        synchronized (COUNTER_LOCK) { completed++; last = outcome; }
        return outcome;
    }

    /**
     * Barriers completed since the process started.
     *
     * Diagnostics and tests only - it says nothing about pool quality. Counted
     * under a lock because the primary and media sessions can handshake on
     * separate threads, and CLDC offers no atomic int. Same shape as
     * {@code Diag.droppedLines()}.
     */
    public static int completedBarriers()
    {
        synchronized (COUNTER_LOCK) { return completed; }
    }

    /**
     * The most recent completed barrier, or null.
     *
     * Exists so {@code tg.plat.Report} can carry the measured sizing off a
     * handset without the probe MIDlet: on a device nobody has measured, the
     * gather count this run chose is the finding.
     */
    public static Outcome lastOutcome()
    {
        synchronized (COUNTER_LOCK) { return last; }
    }

    // ------------------------------------------------------------ internal

    /**
     * Non-secret context: which key this is going to be.
     *
     * The dc id and the environment are already public in the handshake, and
     * the media flag is visible as a negated dc id. They are here to separate
     * derivations, not to hide anything.
     */
    private static byte[] context(int dcId, boolean testEnvironment, boolean media)
    {
        int tagLength = DOMAIN.length();
        byte[] out = new byte[tagLength + 5];
        for (int i = 0; i < tagLength; i++)
        {
            // Not String.getBytes(): CLDC honours microedition.encoding there,
            // so the same tag would produce different bytes on different
            // handsets and the domain separation would not be one domain.
            out[i] = (byte) DOMAIN.charAt(i);
        }
        out[tagLength] = (byte) (dcId >>> 24);
        out[tagLength + 1] = (byte) (dcId >>> 16);
        out[tagLength + 2] = (byte) (dcId >>> 8);
        out[tagLength + 3] = (byte) dcId;
        out[tagLength + 4] = (byte) ((testEnvironment ? 1 : 0) | (media ? 2 : 0));
        return out;
    }

    private static void pause()
    {
        try { Thread.sleep(SPACING_MS); }
        catch (InterruptedException ignored) { }
    }

    private static void wipe(byte[] data)
    {
        for (int i = 0; i < data.length; i++) { data[i] = 0; }
    }
}
