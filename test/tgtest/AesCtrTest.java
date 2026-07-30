package tgtest;

import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import tg.crypto.AesCtr;

/** AES-256-CTR vectors and streaming behaviour used by obfuscated2. */
public final class AesCtrTest implements Test
{
    public String name() { return "crypto/aes-ctr"; }

    public void run() throws Exception
    {
        nist();
        chunkedAndInPlace();
        differential();
    }

    private void nist()
    {
        byte[] key = Assert.unhex(
                "603deb1015ca71be2b73aef0857d7781"
              + "1f352c073b6108d72d9810a30914dff4");
        byte[] iv = Assert.unhex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff");
        byte[] plain = Assert.unhex(
                "6bc1bee22e409f96e93d7e117393172a"
              + "ae2d8a571e03ac9c9eb76fac45af8e51"
              + "30c81c46a35ce411e5fbc1191a0a52ef"
              + "f69f2445df4f9b17ad2b417be66c3710");
        byte[] out = new byte[plain.length];
        new AesCtr(key, iv).crypt(plain, 0, out, 0, out.length);
        Assert.bytesEqual("NIST SP800-38A AES-256-CTR",
                "601ec313775789a5b7a7f504bbf3d228"
              + "f443e3ca4d62b59aca84e990cacaf5c5"
              + "2b0930daa23de94ce87017ba2d84988d"
              + "dfc9c58db67aada613c2dd08457941a6", out);
    }

    private void chunkedAndInPlace()
    {
        byte[] key = Assert.repeat((byte) 0x5a, 32);
        byte[] iv = Assert.repeat((byte) 0xa5, 16);
        byte[] input = new byte[257];
        new Random(17).nextBytes(input);

        byte[] whole = new byte[input.length];
        new AesCtr(key, iv).crypt(input, 0, whole, 0, input.length);

        byte[] chunked = (byte[]) input.clone();
        AesCtr ctr = new AesCtr(key, iv);
        int at = 0;
        int[] sizes = { 1, 15, 16, 17, 31, 2, 64 };
        for (int i = 0; at < chunked.length; i++)
        {
            int n = Math.min(sizes[i % sizes.length], chunked.length - at);
            ctr.crypt(chunked, at, n);
            at += n;
        }
        Assert.bytesEqual("chunked in-place equals one-shot", whole, chunked);

        new AesCtr(key, iv).crypt(chunked, 0, chunked, 0, chunked.length);
        Assert.bytesEqual("CTR decrypt is encrypt", input, chunked);
    }

    private void differential() throws Exception
    {
        Random r = new Random(9921);
        for (int round = 0; round < 40; round++)
        {
            byte[] key = new byte[32];
            byte[] iv = new byte[16];
            byte[] input = new byte[round * 7 + 1];
            r.nextBytes(key); r.nextBytes(iv); r.nextBytes(input);

            Cipher oracle = Cipher.getInstance("AES/CTR/NoPadding");
            oracle.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(iv));
            byte[] expected = oracle.doFinal(input);
            byte[] actual = new byte[input.length];
            new AesCtr(key, iv).crypt(input, 0, actual, 0, actual.length);
            Assert.bytesEqual("JCE differential " + round, expected, actual);
        }
    }
}
