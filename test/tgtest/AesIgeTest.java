package tgtest;

import java.util.Random;

import tg.crypto.Aes;
import tg.crypto.AesIge;

/**
 * AES-IGE correctness.
 *
 * No mainstream library implements IGE, so there is no oracle to diff against.
 * Instead this file contains a second, deliberately naive implementation
 * written straight from the mathematical definition - fresh arrays everywhere,
 * no aliasing tricks, no reused buffers - and checks the production one against
 * it on random data.
 *
 * That matters more than it sounds: {@link AesIge} carefully reuses four
 * scratch buffers and supports out == in, and every one of those optimisations
 * is a chance to clobber a chaining value one block early. The naive reference
 * cannot make that mistake.
 *
 * A published vector is checked too, but the reference comparison is the real
 * test - a misremembered vector would be a false alarm, whereas a disagreement
 * with the definition is always a bug.
 */
public final class AesIgeTest implements Test
{
    public String name()
    {
        return "crypto/aes-ige";
    }

    public void run() throws Exception
    {
        againstNaiveReference();
        publishedVector();
        roundTrip();
        inPlace();
        rejectsBadArguments();
    }

    // ------------------------------------------------------------ the real test

    private void againstNaiveReference()
    {
        Random r = new Random(0x19E5EEDL);
        for (int i = 0; i < 150; i++)
        {
            byte[] key = new byte[32];
            byte[] iv = new byte[32];
            int blocks = 1 + r.nextInt(8);
            byte[] plain = new byte[blocks * 16];
            r.nextBytes(key);
            r.nextBytes(iv);
            r.nextBytes(plain);

            AesIge ige = new AesIge(key);

            byte[] fast = ige.encrypt(iv, plain);
            byte[] slow = naiveEncrypt(key, iv, plain);
            Assert.bytesEqual("IGE encrypt vs reference, " + blocks + " block(s), round " + i,
                    slow, fast);

            byte[] backFast = ige.decrypt(iv, fast);
            byte[] backSlow = naiveDecrypt(key, iv, slow);
            Assert.bytesEqual("IGE decrypt vs reference, round " + i, backSlow, backFast);
            Assert.bytesEqual("IGE decrypt recovers plaintext, round " + i, plain, backFast);
        }
    }

    /** c[i] = E(m[i] XOR c[i-1]) XOR m[i-1], written as literally as possible. */
    private static byte[] naiveEncrypt(byte[] key, byte[] iv, byte[] plain)
    {
        Aes aes = new Aes(key);
        byte[] prevCipher = slice(iv, 0, 16);        // c[-1]
        byte[] prevPlain = slice(iv, 16, 16);        // m[-1]
        byte[] out = new byte[plain.length];

        for (int pos = 0; pos < plain.length; pos += 16)
        {
            byte[] m = slice(plain, pos, 16);
            byte[] t = xor(m, prevCipher);
            byte[] e = new byte[16];
            aes.encryptBlock(t, 0, e, 0);
            byte[] c = xor(e, prevPlain);

            System.arraycopy(c, 0, out, pos, 16);
            prevCipher = c;
            prevPlain = m;
        }
        return out;
    }

    /** m[i] = D(c[i] XOR m[i-1]) XOR c[i-1]. */
    private static byte[] naiveDecrypt(byte[] key, byte[] iv, byte[] cipher)
    {
        Aes aes = new Aes(key);
        byte[] prevCipher = slice(iv, 0, 16);
        byte[] prevPlain = slice(iv, 16, 16);
        byte[] out = new byte[cipher.length];

        for (int pos = 0; pos < cipher.length; pos += 16)
        {
            byte[] c = slice(cipher, pos, 16);
            byte[] t = xor(c, prevPlain);
            byte[] d = new byte[16];
            aes.decryptBlock(t, 0, d, 0);
            byte[] m = xor(d, prevCipher);

            System.arraycopy(m, 0, out, pos, 16);
            prevPlain = m;
            prevCipher = c;
        }
        return out;
    }

    // ------------------------------------------------------------ extras

    /**
     * The OpenSSL AES_ige_encrypt test vector, which is what Telegram's own
     * implementations are validated against.
     */
    private void publishedVector()
    {
        byte[] key = Assert.unhex("000102030405060708090A0B0C0D0E0F");
        byte[] iv = Assert.unhex(
                "000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F");
        byte[] plain = new byte[32];

        byte[] cipher = new AesIge(key).encrypt(iv, plain);
        Assert.bytesEqual("OpenSSL IGE vector",
                "1a8519a6557be652e9da8e43da4ef4453cf456b4ca488aa383c79c98b34797cb",
                cipher);
    }

    private void roundTrip()
    {
        Random r = new Random(555);
        for (int i = 0; i < 100; i++)
        {
            byte[] key = new byte[32];
            byte[] iv = new byte[32];
            byte[] plain = new byte[16 * (1 + r.nextInt(40))];
            r.nextBytes(key);
            r.nextBytes(iv);
            r.nextBytes(plain);

            AesIge ige = new AesIge(key);
            Assert.bytesEqual("IGE round trip " + i,
                    plain, ige.decrypt(iv, ige.encrypt(iv, plain)));
        }
    }

    /** MTProto decrypts message bodies in place; out == in must be safe. */
    private void inPlace()
    {
        Random r = new Random(31337);
        byte[] key = new byte[32];
        byte[] iv = new byte[32];
        byte[] plain = new byte[16 * 5];
        r.nextBytes(key);
        r.nextBytes(iv);
        r.nextBytes(plain);

        byte[] expected = new AesIge(key).encrypt(iv, plain);

        byte[] buf = new byte[plain.length];
        System.arraycopy(plain, 0, buf, 0, plain.length);
        new AesIge(key).encrypt(iv, 0, buf, 0, buf, 0, buf.length);
        Assert.bytesEqual("IGE in-place encrypt", expected, buf);

        new AesIge(key).decrypt(iv, 0, buf, 0, buf, 0, buf.length);
        Assert.bytesEqual("IGE in-place decrypt", plain, buf);

        // Encrypting a slice in the middle of a larger frame must not disturb
        // its neighbours - MTProto hands us exactly that shape.
        byte[] frame = new byte[16 + plain.length + 16];
        System.arraycopy(plain, 0, frame, 16, plain.length);
        new AesIge(key).encrypt(iv, 0, frame, 16, frame, 16, plain.length);
        byte[] sliced = slice(frame, 16, plain.length);
        Assert.bytesEqual("IGE offset encrypt", expected, sliced);
        for (int i = 0; i < 16; i++)
        {
            Assert.equal("prefix byte " + i + " untouched", 0, frame[i]);
            Assert.equal("suffix byte " + i + " untouched", 0, frame[frame.length - 1 - i]);
        }
    }

    /**
     * A length that is not a multiple of 16 means a framing bug upstream. It
     * must throw, not silently process a truncated buffer.
     */
    private void rejectsBadArguments()
    {
        byte[] key = new byte[32];
        byte[] iv = new byte[32];
        AesIge ige = new AesIge(key);

        try
        {
            ige.encrypt(iv, new byte[17]);
            Assert.fail("IGE accepted a length that is not a multiple of 16");
        }
        catch (IllegalArgumentException expected) { }

        try
        {
            ige.encrypt(new byte[16], new byte[16]);
            Assert.fail("IGE accepted a 16-byte IV");
        }
        catch (IllegalArgumentException expected) { }

        try
        {
            new AesIge(new byte[20]);
            Assert.fail("AES accepted a 20-byte key");
        }
        catch (IllegalArgumentException expected) { }
    }

    // ----------------------------------------------------------- helpers

    private static byte[] slice(byte[] src, int off, int len)
    {
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b)
    {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++)
        {
            out[i] = (byte) (a[i] ^ b[i]);
        }
        return out;
    }
}
