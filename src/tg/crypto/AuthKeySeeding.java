package tg.crypto;

/**
 * The seeding barrier a permanent MTProto auth_key must cross.
 *
 * <h3>What this is for</h3>
 * {@link Rng#Rng()} folds in one {@link Entropy#gather()}, which is the right
 * cost for a nonce, a padding block or an outgoing {@code random_id}. It is not
 * the right cost for a 2048-bit DH secret: on the one handset measured a gather
 * is worth about {@link Entropy#estimatedBitsPerGather()} bits, so a pool that
 * has seen one of them is short of what a permanent key needs. Rather than make
 * every {@code Rng()} in the process pay for that, the expensive seeding is
 * named, isolated here, and invoked from the one place that generates a key -
 * {@code tg.mt.Handshake}.
 *
 * <h3>What is and is not claimed</h3>
 * {@link #GATHERS} gathers are folded in. That is <b>not</b> a claim of
 * {@code GATHERS x 58} bits: consecutive gathers are sampled from the same
 * scheduler on the same idle handset seconds apart, and nothing here
 * demonstrates that they are independent. What is claimed is narrower and still
 * worth having - the pool behind a permanent key has seen several separated
 * physical samples instead of one, which is the difference between a seed an
 * attacker might enumerate and one they probably cannot. The one-handset caveat
 * in {@link Entropy} is unchanged by this class.
 *
 * <h3>Fail closed</h3>
 * {@link Rng#addEntropy(byte[])} returns quietly when handed nothing, so a
 * degraded source would produce a barrier that reports success having folded in
 * no entropy at all. Every gather is checked instead, and a short or absent one
 * aborts the key generation rather than weakening it silently.
 *
 * <h3>Diagnostics</h3>
 * Deliberately no dependency on {@code tg.diag}: the rest of {@code tg.crypto}
 * reaches outside the package only for {@code tg.io.Hex}, and a hashing helper
 * should not decide what gets logged. The caller receives the elapsed time and
 * logs the count and the duration - never a sample, never pool state.
 */
public final class AuthKeySeeding
{
    /**
     * Fresh {@link Entropy#gather()} results folded in before a permanent key.
     *
     * Five, because a 2048-bit DH secret is conventionally seeded from 256 bits
     * and one gather measured about 58 on an Alcatel OT-810D - see
     * {@code docs/hardware/alcatel-ot810d.md}. The division is a sizing rule,
     * not an addition: see the class note on why the totals are not summed.
     * {@code AuthKeySeedingTest} fails if a future measurement makes five stop
     * covering {@link #TARGET_BITS}.
     */
    public static final int GATHERS = 5;

    /** The seeding target the {@link #GATHERS} count is sized against. */
    public static final int TARGET_BITS = 256;

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

    private AuthKeySeeding() { }

    /**
     * Observer-free injection point for the gather source.
     *
     * The same shape as {@link Entropy.JitterSink} and for the same reason: a
     * test needs to drive the fold order deterministically, and the only way to
     * do that without a seam is to reimplement the loop - which then measures
     * something merely similar to what ships. Production passes null and never
     * constructs an implementation, so the device build carries the interface
     * and nothing else.
     *
     * An implementation must return a freshly allocated array on every call:
     * {@link #strengthen} zeroes what it is handed.
     */
    public interface GatherSource
    {
        byte[] gather();
    }

    /**
     * Prepare {@code rng} for generating one permanent auth_key.
     *
     * Blocks for roughly {@code GATHERS * 130} ms - about 600 ms on the handset
     * measured - and must not be called on the display thread. Call it once per
     * key, not once per connection.
     *
     * @param dcId           the data centre the key will belong to
     * @param testEnvironment true for the Telegram test environment
     * @param media          true for an auxiliary/media connection
     * @return elapsed milliseconds, never negative
     * @throws IllegalStateException if a gather comes back empty or short
     */
    public static long strengthen(Rng rng, int dcId, boolean testEnvironment,
                                  boolean media)
    {
        return strengthen(rng, dcId, testEnvironment, media, null);
    }

    /**
     * As {@link #strengthen(Rng, int, boolean, boolean)}, taking gathers from
     * {@code source}.
     *
     * @param source null for the real {@link Entropy#gather()}; see
     *               {@link GatherSource} for the ownership contract
     */
    public static long strengthen(Rng rng, int dcId, boolean testEnvironment,
                                  boolean media, GatherSource source)
    {
        if (rng == null) { throw new IllegalArgumentException("rng"); }

        long t0 = System.currentTimeMillis();

        byte[] context = context(dcId, testEnvironment, media);
        rng.addEntropy(context);
        wipe(context);

        for (int i = 0; i < GATHERS; i++)
        {
            if (i > 0) { pause(); }
            byte[] sample = source == null ? Entropy.gather() : source.gather();
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
        }

        long elapsed = System.currentTimeMillis() - t0;
        if (elapsed < 0) { elapsed = 0; }
        // How long the barrier actually took is itself a timing measurement.
        rng.addEntropy(elapsed);

        synchronized (COUNTER_LOCK) { completed++; }
        return elapsed;
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
