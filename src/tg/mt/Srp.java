package tg.mt;

import java.io.IOException;

import tg.crypto.Pbkdf2;
import tg.crypto.Rng;
import tg.crypto.Sha256;
import tg.crypto.bigint.BigInteger;
import tg.tl.Utf8;

/**
 * Telegram's SRP-6a password proof.
 *
 * All integer/hash concatenations use unsigned, big-endian 2048-bit values as
 * required by Telegram. The password itself never leaves this class; callers
 * receive only A and M1.
 */
public final class Srp
{
    public static final int SIZE = 256;
    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int MAX_SECRET_ATTEMPTS = 8;

    public static final class Parameters
    {
        public byte[] salt1;
        public byte[] salt2;
        public int g;
        public byte[] p;
        public byte[] b;
        public long id;
    }

    public static final class Check
    {
        public long id;
        public byte[] a;
        public byte[] m1;
    }

    private Srp() { }

    /** Production entry point: generate a fresh 2048-bit client secret. */
    public static Check compute(String password, Parameters params, Rng rng,
                                Pbkdf2.Progress progress) throws IOException
    {
        if (rng == null) { throw new IllegalArgumentException("rng is required"); }
        IOException last = null;
        for (int attempt = 0; attempt < MAX_SECRET_ATTEMPTS; attempt++)
        {
            byte[] secret = rng.nextBytes(SIZE);
            try
            {
                return compute(password, params, secret, progress);
            }
            catch (UnsafeSecretException e)
            {
                last = e;
            }
            finally
            {
                wipe(secret);
            }
        }
        throw last == null ? new IOException("could not generate safe SRP A") : last;
    }

    /**
     * Deterministic seam for byte-for-byte tests. {@code secret} is the
     * big-endian SRP private value a and must be unpredictable in production.
     */
    public static Check compute(String password, Parameters params, byte[] secret,
                                Pbkdf2.Progress progress) throws IOException
    {
        validateShape(params, secret);

        BigInteger p = new BigInteger(1, params.p);
        BigInteger b = new BigInteger(1, params.b);
        DhPrime.Result validation = DhPrime.validate(p, params.g, b);
        if (!validation.ok)
        {
            throw new IOException("unsafe SRP parameters: " + validation.failure);
        }

        BigInteger g = BigInteger.valueOf(params.g);
        BigInteger a = new BigInteger(1, secret);
        if (a.signum() == 0) { throw new UnsafeSecretException("SRP a is zero"); }

        BigInteger bigA = g.modPow(a, p);
        String aProblem = DhPrime.checkPublicValue("SRP A", p, bigA);
        if (aProblem != null) { throw new UnsafeSecretException(aProblem); }

        byte[] pPad = toFixed(p);
        byte[] gPad = toFixed(g);
        byte[] aPad = toFixed(bigA);
        byte[] bPad = toFixed(b);
        byte[] passwordBytes = Utf8.encode(password == null ? "" : password);
        byte[] ph1a = null;
        byte[] ph1 = null;
        byte[] ph2base = null;
        byte[] xBytes = null;
        byte[] sPad = null;
        try
        {
            ph1a = saltedHash(passwordBytes, params.salt1);
            ph1 = saltedHash(ph1a, params.salt2);
            ph2base = Pbkdf2.derive(ph1, params.salt1, PBKDF2_ITERATIONS, 64,
                                    progress);
            xBytes = saltedHash(ph2base, params.salt2);
            BigInteger x = new BigInteger(1, xBytes);

            byte[] kHash = hashTwo(pPad, gPad);
            BigInteger k = new BigInteger(1, kHash);
            BigInteger v = g.modPow(x, p);
            BigInteger kv = k.multiply(v).mod(p);

            byte[] uHash = hashTwo(aPad, bPad);
            BigInteger u = new BigInteger(1, uHash);
            if (u.signum() == 0) { throw new IOException("invalid SRP u=0"); }

            BigInteger t = b.subtract(kv);
            if (t.signum() < 0) { t = t.add(p); }
            if (t.signum() == 0) { throw new IOException("invalid SRP base=0"); }

            BigInteger exponent = a.add(u.multiply(x));
            BigInteger shared = t.modPow(exponent, p);
            sPad = toFixed(shared);
            byte[] key = Sha256.hash(sPad);

            byte[] hp = Sha256.hash(pPad);
            byte[] hg = Sha256.hash(gPad);
            for (int i = 0; i < hp.length; i++) { hp[i] ^= hg[i]; }
            byte[] hs1 = Sha256.hash(params.salt1);
            byte[] hs2 = Sha256.hash(params.salt2);

            Sha256 proof = new Sha256();
            proof.update(hp);
            proof.update(hs1);
            proof.update(hs2);
            proof.update(aPad);
            proof.update(bPad);
            proof.update(key);

            Check out = new Check();
            out.id = params.id;
            out.a = aPad;
            out.m1 = proof.digest();

            wipe(kHash);
            wipe(uHash);
            wipe(key);
            wipe(hp);
            wipe(hg);
            wipe(hs1);
            wipe(hs2);
            return out;
        }
        finally
        {
            wipe(passwordBytes);
            wipe(ph1a);
            wipe(ph1);
            wipe(ph2base);
            wipe(xBytes);
            wipe(sPad);
            wipe(pPad);
            wipe(gPad);
            // aPad is intentionally retained only as the returned public A.
            wipe(bPad);
        }
    }

    private static void validateShape(Parameters params, byte[] secret)
            throws IOException
    {
        if (params == null) { throw new IOException("missing SRP parameters"); }
        if (params.salt1 == null || params.salt2 == null
                || params.p == null || params.b == null)
        {
            throw new IOException("incomplete SRP parameters");
        }
        if (params.p.length > SIZE || params.b.length > SIZE)
        {
            throw new IOException("oversized SRP integer");
        }
        if (secret == null || secret.length != SIZE)
        {
            throw new IOException("SRP secret must be 256 bytes");
        }
    }

    /** SHA256(salt || data || salt). */
    private static byte[] saltedHash(byte[] data, byte[] salt)
    {
        Sha256 sha = new Sha256();
        sha.update(salt);
        sha.update(data);
        sha.update(salt);
        return sha.digest();
    }

    private static byte[] hashTwo(byte[] first, byte[] second)
    {
        Sha256 sha = new Sha256();
        sha.update(first);
        sha.update(second);
        return sha.digest();
    }

    private static byte[] toFixed(BigInteger value) throws IOException
    {
        byte[] raw = value.toByteArray();
        int off = raw.length > 0 && raw[0] == 0 ? 1 : 0;
        int len = raw.length - off;
        if (len > SIZE) { throw new IOException("SRP integer exceeds 2048 bits"); }
        byte[] out = new byte[SIZE];
        System.arraycopy(raw, off, out, SIZE - len, len);
        return out;
    }

    private static void wipe(byte[] value)
    {
        if (value == null) { return; }
        for (int i = 0; i < value.length; i++) { value[i] = 0; }
    }

    /** Causes the RNG entry point to draw a new a without hiding real errors. */
    private static final class UnsafeSecretException extends IOException
    {
        UnsafeSecretException(String message) { super(message); }
    }
}
