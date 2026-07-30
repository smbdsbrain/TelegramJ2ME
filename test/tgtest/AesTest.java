package tgtest;

import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import tg.crypto.Aes;

/**
 * AES against the FIPS-197 Appendix C vectors and, for volume, against the JDK.
 *
 * The published vectors prove the key schedule and the round functions for all
 * three key sizes. The differential pass then hammers AES-256 - the only size
 * MTProto uses - with random keys and blocks, which is what catches a table
 * that is subtly wrong for a value the fixed vectors never exercise.
 */
public final class AesTest implements Test
{
    public String name()
    {
        return "crypto/aes-fips197";
    }

    public void run() throws Exception
    {
        fips197();
        roundTrip();
        differentialVsJdk();
        inPlace();
    }

    /** FIPS-197 Appendix C: one plaintext, three key sizes. */
    private void fips197()
    {
        byte[] plain = Assert.unhex("00112233445566778899aabbccddeeff");

        check("AES-128",
              "000102030405060708090a0b0c0d0e0f",
              plain,
              "69c4e0d86a7b0430d8cdb78070b4c55a");

        check("AES-192",
              "000102030405060708090a0b0c0d0e0f1011121314151617",
              plain,
              "dda97ca4864cdfe06eaf70a0ec0d7191");

        check("AES-256",
              "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
              plain,
              "8ea2b7ca516745bfeafc49904b496089");
    }

    private void check(String label, String keyHex, byte[] plain, String cipherHex)
    {
        Aes aes = new Aes(Assert.unhex(keyHex));
        byte[] out = new byte[16];

        aes.encryptBlock(plain, 0, out, 0);
        Assert.bytesEqual(label + " encrypt", cipherHex, out);

        byte[] back = new byte[16];
        aes.decryptBlock(out, 0, back, 0);
        Assert.bytesEqual(label + " decrypt", plain, back);
    }

    private void roundTrip()
    {
        Random r = new Random(7);
        for (int i = 0; i < 200; i++)
        {
            byte[] key = new byte[32];
            byte[] block = new byte[16];
            r.nextBytes(key);
            r.nextBytes(block);

            Aes aes = new Aes(key);
            byte[] enc = new byte[16];
            byte[] dec = new byte[16];
            aes.encryptBlock(block, 0, enc, 0);
            aes.decryptBlock(enc, 0, dec, 0);
            Assert.bytesEqual("AES-256 round trip " + i, block, dec);
        }
    }

    /**
     * javax.crypto is the oracle here. AES-256 needs the unlimited-strength
     * policy, which has been the default since JDK 8u161 - the build already
     * requires a much later 8u.
     */
    private void differentialVsJdk() throws Exception
    {
        Random r = new Random(1234);
        Cipher enc = Cipher.getInstance("AES/ECB/NoPadding");
        Cipher dec = Cipher.getInstance("AES/ECB/NoPadding");

        for (int i = 0; i < 300; i++)
        {
            int keyLen = new int[] { 16, 24, 32 }[i % 3];
            byte[] key = new byte[keyLen];
            byte[] block = new byte[16];
            r.nextBytes(key);
            r.nextBytes(block);

            SecretKeySpec spec = new SecretKeySpec(key, "AES");
            enc.init(Cipher.ENCRYPT_MODE, spec);
            dec.init(Cipher.DECRYPT_MODE, spec);

            Aes aes = new Aes(key);
            byte[] ours = new byte[16];
            aes.encryptBlock(block, 0, ours, 0);
            Assert.bytesEqual("AES-" + (keyLen * 8) + " encrypt differential " + i,
                    enc.doFinal(block), ours);

            aes.decryptBlock(block, 0, ours, 0);
            Assert.bytesEqual("AES-" + (keyLen * 8) + " decrypt differential " + i,
                    dec.doFinal(block), ours);
        }
    }

    /**
     * MTProto decrypts message bodies in place, so encryptBlock/decryptBlock
     * must tolerate in == out. An implementation that writes the output as it
     * reads the input would silently corrupt here.
     */
    private void inPlace()
    {
        byte[] key = Assert.unhex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
        byte[] buf = Assert.unhex("00112233445566778899aabbccddeeff");

        Aes aes = new Aes(key);
        aes.encryptBlock(buf, 0, buf, 0);
        Assert.bytesEqual("AES-256 in-place encrypt", "8ea2b7ca516745bfeafc49904b496089", buf);

        aes.decryptBlock(buf, 0, buf, 0);
        Assert.bytesEqual("AES-256 in-place decrypt", "00112233445566778899aabbccddeeff", buf);

        // Offsets into a larger buffer, the way a framed MTProto packet is laid out.
        byte[] framed = new byte[48];
        byte[] plain = Assert.unhex("00112233445566778899aabbccddeeff");
        System.arraycopy(plain, 0, framed, 16, 16);
        aes.encryptBlock(framed, 16, framed, 16);
        byte[] slice = new byte[16];
        System.arraycopy(framed, 16, slice, 0, 16);
        Assert.bytesEqual("AES-256 offset encrypt", "8ea2b7ca516745bfeafc49904b496089", slice);
        for (int i = 0; i < 16; i++)
        {
            Assert.equal("byte " + i + " outside the block untouched", 0, framed[i]);
            Assert.equal("byte " + (32 + i) + " outside the block untouched", 0, framed[32 + i]);
        }
    }
}
