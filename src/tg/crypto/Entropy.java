package tg.crypto;

/**
 * Entropy collection for {@link Rng}.
 *
 * <h3>The honest status of this file</h3>
 * A 2011 feature phone has no hardware RNG, no {@code /dev/urandom}, and CLDC
 * exposes no {@code java.security.SecureRandom}. Everything below is a
 * best-effort scrape of quantities that are hard for a remote party to predict.
 * How much real entropy each one carries <b>on the target handset</b> has not
 * been measured, and the MTProto security guidelines are explicit that a weak
 * pool produces a weak auth_key.
 *
 * <blockquote>
 * <b>Open item, blocking production use:</b> quantify these sources on every
 * supported physical runtime before declaring the auth_key path secure. Until
 * that is done, treat generated keys as development keys.
 * </blockquote>
 *
 * <h3>What is collected</h3>
 * <ul>
 *   <li>wall clock, at whatever resolution the device offers;</li>
 *   <li>heap free/total, which drift with allocation history;</li>
 *   <li>identity hash codes, which usually encode an allocation address;</li>
 *   <li>scheduler and clock jitter, timed over a busy loop - the only source
 *       here that is genuinely physical rather than merely obscure;</li>
 *   <li>device identity properties, which are constant per device and add
 *       uniqueness between devices but no per-run unpredictability.</li>
 * </ul>
 *
 * <h3>The best source is the user</h3>
 * {@link #fromUserInput(int, long)} exists because key-press timing on a slow
 * handset carries real, physically unpredictable entropy in its low bits. The
 * intended flow before generating an auth_key is to collect a few seconds of
 * keyboard interaction and fold every event into the pool.
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
        Sha256 d = new Sha256();
        long deadline = System.currentTimeMillis() + millis;
        long previous = System.currentTimeMillis();
        int samples = 0;

        while (System.currentTimeMillis() < deadline)
        {
            long spins = 0;
            long now = previous;
            // Count how many clock reads fit inside one clock tick. The count
            // is where the jitter lives; the timestamp itself is predictable.
            while (now == previous)
            {
                now = System.currentTimeMillis();
                spins++;
            }
            previous = now;
            appendLong(d, spins);
            appendLong(d, now);
            samples++;
        }

        appendLong(d, samples);
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
     * A rough, deliberately conservative estimate of the entropy bits gathered
     * per {@link #gather()} call, for display on the diagnostics screen.
     *
     * It is a placeholder. Replace it with a measured figure once the handset
     * results exist; do not let it justify anything on its own.
     */
    public static int estimatedBitsPerGather()
    {
        return 0;   // unmeasured - claiming a number here would be dishonest
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
