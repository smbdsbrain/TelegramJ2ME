package tg.crypto;

import java.util.Random;

/**
 * The project's random number generator.
 *
 * <h3>Why it subclasses java.util.Random</h3>
 * The vendored {@link tg.crypto.bigint.BigInteger} takes a {@code java.util.Random}
 * to generate DH secrets, and CLDC has no {@code java.security.SecureRandom} to
 * hand it. Extending Random and overriding the generation methods means
 * {@code new BigInteger(2048, rng)} draws from this pool instead of from a
 * linear congruential generator seeded with the clock.
 *
 * <h3>Construction</h3>
 * A hash-based deterministic RNG: output block <i>i</i> is
 * {@code SHA-256(state || counter)}, and {@code addEntropy(x)} folds new input
 * in with {@code state = SHA-256(state || x)}. Backtracking resistance comes
 * from the counter never repeating and the state never being emitted.
 *
 * <h3>What the constructor's seeding is and is not for</h3>
 * The construction is sound. The <em>seeding</em> has been measured on one
 * handset and found sufficient to avoid repeating, but not sufficient on its
 * own: an Alcatel One Touch 810D yields about 58 bits per
 * {@link Entropy#gather()}, roughly a fifth of what a 2048-bit DH secret needs.
 * See {@link Entropy} and {@code docs/hardware/alcatel-ot810d.md}. Per the
 * MTProto security guidelines, an auth_key must not be generated from a weak
 * pool.
 *
 * <blockquote>
 * <b>The single {@link Entropy#gather()} in this constructor is the right cost
 * for a nonce, a padding block or an outgoing {@code random_id}, and is not
 * enough for a permanent auth_key.</b> The auth-key path therefore crosses
 * {@link AuthKeySeeding} first, which folds in several further separated
 * gathers; {@code tg.mt.Handshake} calls it, so no other caller has to
 * remember to. On any runtime other than the one measured, evaluate the sources
 * before trusting either figure. Server-provided randomness (the server_nonce,
 * new_nonce chain) must never be the sole source of DH secret entropy.
 * </blockquote>
 *
 * {@link #forTesting(byte[])} gives a fully deterministic instance so crypto
 * tests are reproducible.
 *
 * Output extraction is synchronized because primary and media sessions may
 * legitimately be active on separate threads.
 */
public class Rng extends Random
{
    private static final int BLOCK = Sha256.DIGEST_SIZE;

    private final byte[] state = new byte[BLOCK];
    private final byte[] buffer = new byte[BLOCK];
    private final byte[] counterBytes = new byte[8];
    private final Sha256 digest = new Sha256();

    private long counter;
    private int available;          // unused bytes remaining in `buffer`
    private boolean seeded;
    private final boolean deterministic;

    /**
     * Seeded from whatever entropy the platform offers - about 58 bits of it on
     * the one handset measured. Usable immediately for nonces and padding; see
     * the class note before using it for a DH secret.
     */
    public Rng()
    {
        deterministic = false;
        addEntropy(Entropy.gather());
    }

    /** Seeded only from {@code seed}; deliberately skips {@link Entropy#gather()}. */
    private Rng(byte[] seed)
    {
        deterministic = true;
        addEntropy(seed);
    }

    /**
     * Deterministic instance for tests, for the on-device self-test, and for
     * reproducing a reported failure. Never use this for anything that reaches
     * Telegram.
     */
    public static Rng forTesting(byte[] seed)
    {
        return new Rng(seed);
    }

    /**
     * Fold additional entropy into the pool. Always safe to call - it can only
     * increase uncertainty, never reduce it. Feed it key-press timings, network
     * round-trip times, anything unpredictable the app observes.
     */
    public final void addEntropy(byte[] data)
    {
        if (data == null || data.length == 0) { return; }
        synchronized (state)
        {
            digest.reset();
            digest.update(state, 0, BLOCK);
            digest.update(data, 0, data.length);
            digest.digest(state, 0);
            available = 0;              // discard output derived from the old state
            seeded = true;
        }
    }

    public final void addEntropy(long value)
    {
        byte[] b = new byte[8];
        writeLong(b, value);
        addEntropy(b);
    }

    /** True once any entropy has been folded in. */
    public final boolean isSeeded()
    {
        return seeded;
    }

    /**
     * True for an instance from {@link #forTesting(byte[])}.
     *
     * Not a quality measure - a deterministic pool that later absorbed real
     * gathers would be perfectly strong. It is there so the two guarantees this
     * class makes can be enforced rather than documented: that a {@code
     * forTesting} stream stays reproducible, and that a pool with a published
     * seed cannot negotiate a key with Telegram. {@code tg.mt.Handshake} refuses
     * one.
     */
    public final boolean isDeterministic()
    {
        return deterministic;
    }

    /**
     * Fill {@code out} with generated bytes.
     *
     * On J2SE this overrides Random.nextBytes; on CLDC, whose Random has no
     * such method, it is simply an addition. Either way it is the primitive the
     * rest of the code uses.
     */
    public void nextBytes(byte[] out)
    {
        nextBytes(out, 0, out.length);
    }

    public void nextBytes(byte[] out, int off, int len)
    {
        synchronized (state)
        {
            while (len > 0)
            {
                if (available == 0) { refill(); }
                int n = available < len ? available : len;
                System.arraycopy(buffer, BLOCK - available, out, off, n);
                // Zero what we hand out, so a later peek at the buffer reveals
                // nothing already used.
                for (int i = 0; i < n; i++)
                {
                    buffer[BLOCK - available + i] = 0;
                }
                available -= n;
                off += n;
                len -= n;
            }
        }
    }

    public byte[] nextBytes(int len)
    {
        byte[] out = new byte[len];
        nextBytes(out, 0, len);
        return out;
    }

    // ------------------------------------------------- java.util.Random API

    /**
     * The hook every other Random method is built on. Overriding it is what
     * redirects BigInteger's DH secret generation into this pool.
     */
    protected int next(int bits)
    {
        if (bits <= 0) { return 0; }
        if (bits > 32) { bits = 32; }
        return nextInt() >>> (32 - bits);
    }

    public int nextInt()
    {
        synchronized (state)
        {
            if (available < 4) { refill(); }
            int p = BLOCK - available;
            int v = ((buffer[p] & 0xff) << 24)
                  | ((buffer[p + 1] & 0xff) << 16)
                  | ((buffer[p + 2] & 0xff) << 8)
                  |  (buffer[p + 3] & 0xff);
            buffer[p] = 0;
            buffer[p + 1] = 0;
            buffer[p + 2] = 0;
            buffer[p + 3] = 0;
            available -= 4;
            return v;
        }
    }

    /**
     * Uniform in [0, n) with rejection sampling - the modulo shortcut would bias
     * the low values, which matters for anything a server could observe.
     */
    public int nextInt(int n)
    {
        if (n <= 0)
        {
            throw new IllegalArgumentException("bound must be positive: " + n);
        }
        if ((n & -n) == n)
        {
            // Power of two: take the high bits directly.
            return (int) ((n * (long) (nextInt() >>> 1)) >> 31);
        }
        int bits;
        int val;
        do
        {
            bits = nextInt() >>> 1;
            val = bits % n;
        }
        while (bits - val + (n - 1) < 0);
        return val;
    }

    public long nextLong()
    {
        return ((long) nextInt() << 32) | (nextInt() & 0xffffffffL);
    }

    /**
     * Deliberately inert. java.util.Random.setSeed would reset the underlying
     * LCG and could be called by inherited code; letting it replace our pool
     * would be a silent downgrade to a predictable stream. Use
     * {@link #addEntropy(byte[])}.
     */
    public void setSeed(long seed)
    {
        // Fold it in rather than replace - strictly better than ignoring, and
        // it keeps a superclass constructor call from weakening the pool.
        if (state != null)
        {
            addEntropy(seed);
        }
    }

    /**
     * Best-effort wipe of a pool that is being discarded.
     *
     * <b>Not</b> a post-auth_key cleanup step, whatever this comment used to
     * say. The application owns one shared instance ({@code TgMidlet}), the same
     * one that supplies every subsequent nonce, {@code random_id} and padding
     * block, and the generator does not re-seed itself on demand - so wiping it
     * after a handshake would leave the rest of the session drawing from a
     * zeroed state. Call it only on an instance nothing will use again.
     */
    public void wipe()
    {
        synchronized (state)
        {
            for (int i = 0; i < BLOCK; i++)
            {
                state[i] = 0;
                buffer[i] = 0;
            }
            available = 0;
            counter = 0;
            seeded = false;
        }
    }

    // ------------------------------------------------------------ internal

    private void refill()
    {
        synchronized (state)
        {
            writeLong(counterBytes, counter++);
            digest.reset();
            digest.update(state, 0, BLOCK);
            digest.update(counterBytes, 0, 8);
            digest.digest(buffer, 0);
            available = BLOCK;
        }
    }

    private static void writeLong(byte[] out, long v)
    {
        out[0] = (byte) (v >>> 56);
        out[1] = (byte) (v >>> 48);
        out[2] = (byte) (v >>> 40);
        out[3] = (byte) (v >>> 32);
        out[4] = (byte) (v >>> 24);
        out[5] = (byte) (v >>> 16);
        out[6] = (byte) (v >>> 8);
        out[7] = (byte) v;
    }
}
