package tg.crypto;

import tg.crypto.bigint.BigInteger;

/**
 * Generates an encoded Montgomery-u coordinate that is a real Curve25519
 * point. FakeTLS does not complete a key exchange, but current MTProxy
 * fingerprints require the key_share bytes to pass X25519 point validation.
 */
public final class X25519
{
    private static final BigInteger P =
            new BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16);
    private static final BigInteger A = BigInteger.valueOf(486662);
    private static final BigInteger EULER =
            P.subtract(BigInteger.ONE).divide(BigInteger.TWO);

    private X25519() { }

    /** Return a valid 32-byte little-endian Montgomery-u coordinate. */
    public static byte[] generateU(Rng rng)
    {
        byte[] little = new byte[32];
        do
        {
            rng.nextBytes(little, 0, little.length);
            little[31] &= 0x7f;
        }
        while (!isValidU(little));
        return little;
    }

    public static boolean isValidU(byte[] little)
    {
        if (little == null || little.length != 32) { return false; }
        BigInteger x = fromLittleEndian(little);
        if (x.signum() == 0 || x.compareTo(P) >= 0) { return false; }

        // Montgomery Curve25519: y^2 = x^3 + 486662*x^2 + x.
        BigInteger rhs = x.multiply(x).mod(P).multiply(x.add(A)).add(x).mod(P);
        if (rhs.signum() == 0) { return true; }
        return rhs.modPow(EULER, P).equals(BigInteger.ONE);
    }

    private static BigInteger fromLittleEndian(byte[] little)
    {
        byte[] big = new byte[little.length];
        for (int i = 0; i < little.length; i++)
        {
            big[big.length - 1 - i] = little[i];
        }
        return new BigInteger(1, big);
    }
}
