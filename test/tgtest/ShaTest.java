package tgtest;

import java.security.MessageDigest;
import java.util.Random;

import tg.crypto.Sha1;
import tg.crypto.Sha256;

/**
 * SHA-1 and SHA-256 against the published FIPS 180-4 vectors, plus a
 * differential pass against the JDK.
 *
 * MTProto derives every message key and every AES key/IV from SHA-256, and the
 * auth_key handshake depends on SHA-1. A digest that is wrong only for, say,
 * inputs that straddle a block boundary would produce a handshake that fails
 * with no diagnosable error - so the incremental path is tested separately from
 * the one-shot path, with deliberately awkward chunk sizes.
 */
public final class ShaTest implements Test
{
    public String name()
    {
        return "crypto/sha1-sha256-vectors";
    }

    public void run() throws Exception
    {
        sha1Vectors();
        sha256Vectors();
        incrementalMatchesOneShot();
        differentialVsJdk();
    }

    private void sha1Vectors()
    {
        Assert.bytesEqual("SHA-1 empty",
                "da39a3ee5e6b4b0d3255bfef95601890afd80709",
                Sha1.hash(new byte[0]));

        Assert.bytesEqual("SHA-1 abc",
                "a9993e364706816aba3e25717850c26c9cd0d89d",
                Sha1.hash(Assert.ascii("abc")));

        Assert.bytesEqual("SHA-1 two-block",
                "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
                Sha1.hash(Assert.ascii(
                        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")));

        // One million 'a': the vector that catches a broken 64-bit length field.
        Assert.bytesEqual("SHA-1 1e6 x 'a'",
                "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
                Sha1.hash(Assert.repeat((byte) 'a', 1000000)));
    }

    private void sha256Vectors()
    {
        Assert.bytesEqual("SHA-256 empty",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Sha256.hash(new byte[0]));

        Assert.bytesEqual("SHA-256 abc",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256.hash(Assert.ascii("abc")));

        Assert.bytesEqual("SHA-256 two-block",
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                Sha256.hash(Assert.ascii(
                        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")));

        Assert.bytesEqual("SHA-256 1e6 x 'a'",
                "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
                Sha256.hash(Assert.repeat((byte) 'a', 1000000)));
    }

    /**
     * The streaming path is what MTProto actually uses - it hashes
     * msg_key || auth_key slices without joining them - so it must agree with
     * the one-shot path for every chunking, especially ones that land inside a
     * 64-byte block.
     */
    private void incrementalMatchesOneShot()
    {
        Random r = new Random(99);
        byte[] data = new byte[1000];
        r.nextBytes(data);

        int[] chunkSizes = { 1, 3, 17, 31, 32, 63, 64, 65, 127, 128, 200 };
        for (int c = 0; c < chunkSizes.length; c++)
        {
            int chunk = chunkSizes[c];

            Sha256 d256 = new Sha256();
            Sha1 d1 = new Sha1();
            for (int off = 0; off < data.length; off += chunk)
            {
                int n = Math.min(chunk, data.length - off);
                d256.update(data, off, n);
                d1.update(data, off, n);
            }
            Assert.bytesEqual("SHA-256 chunked by " + chunk,
                    Sha256.hash(data), d256.digest());
            Assert.bytesEqual("SHA-1 chunked by " + chunk,
                    Sha1.hash(data), d1.digest());
        }

        // digest() must reset, so the same instance can be reused for the next
        // message without a stale state leaking in.
        Sha256 reused = new Sha256();
        reused.update(Assert.ascii("abc"));
        reused.digest();
        reused.update(Assert.ascii("abc"));
        Assert.bytesEqual("SHA-256 reset after digest",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                reused.digest());

        // The two-argument helper must equal hashing the concatenation.
        byte[] a = Assert.ascii("msg_key-part");
        byte[] b = Assert.ascii("auth_key-part");
        byte[] joined = new byte[a.length + b.length];
        System.arraycopy(a, 0, joined, 0, a.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        Assert.bytesEqual("SHA-256 two-part helper",
                Sha256.hash(joined), Sha256.hash(a, b));
    }

    private void differentialVsJdk() throws Exception
    {
        MessageDigest jdk256 = MessageDigest.getInstance("SHA-256");
        MessageDigest jdk1 = MessageDigest.getInstance("SHA-1");
        Random r = new Random(4242);

        for (int i = 0; i < 400; i++)
        {
            byte[] data = new byte[r.nextInt(300)];
            r.nextBytes(data);
            Assert.bytesEqual("SHA-256 differential len " + data.length,
                    jdk256.digest(data), Sha256.hash(data));
            Assert.bytesEqual("SHA-1 differential len " + data.length,
                    jdk1.digest(data), Sha1.hash(data));
        }
    }
}
