package tg.mt;

import java.io.IOException;

import tg.crypto.AesIge;
import tg.crypto.Pq;
import tg.crypto.Rng;
import tg.crypto.Sha1;
import tg.crypto.bigint.BigInteger;
import tg.diag.Diag;
import tg.io.Hex;
import tg.tl.Tl;
import tg.tl.TlException;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * The MTProto authorization-key exchange.
 *
 * Implements core.telegram.org/mtproto/auth_key end to end: req_pq_multi,
 * proof-of-work factorisation, RSA_PAD, Diffie-Hellman, and verification of the
 * server's dh_gen_ok. The output is an {@link AuthKey} plus the initial server
 * salt.
 *
 * <h3>The checks are not optional</h3>
 * Every nonce is compared on every message, the server's DH parameters are
 * validated by {@link DhPrime}, and new_nonce_hash1 is recomputed and matched
 * before the key is accepted. Skipping any of them yields a handshake that
 * completes and a key that proves nothing - which is worse than failing.
 *
 * <h3>Cost</h3>
 * Two 2048-bit modular exponentiations (g^b and g_a^b) plus one RSA
 * exponentiation plus the pq factorisation. On the desktop this is milliseconds;
 * on a 208 MHz handset expect seconds to tens of seconds. That is acceptable
 * precisely because the result is persisted and never regenerated - see
 * {@link AuthKeyStore}.
 *
 * Run this off the UI thread.
 */
public final class Handshake
{
    /** dh_gen_retry means the server wants a different b; bounded to avoid looping. */
    private static final int MAX_DH_ATTEMPTS = 5;

    /** Result of a successful exchange. */
    public static final class Result
    {
        public AuthKey authKey;
        public long serverSalt;
        public int serverTimeSeconds;
        public long elapsedMillis;
        public boolean usedKnownGoodPrime;
    }

    private final MtPlain plain;
    private final Rng rng;
    private final int dcId;
    private final boolean testEnvironment;

    private byte[] nonce;          // 16
    private byte[] serverNonce;    // 16
    private byte[] newNonce;       // 32

    private byte[] tmpAesKey;      // 32
    private byte[] tmpAesIv;       // 32

    public Handshake(MtPlain plain, Rng rng, int dcId, boolean testEnvironment)
    {
        this.plain = plain;
        this.rng = rng;
        this.dcId = dcId;
        this.testEnvironment = testEnvironment;
    }

    public Result run() throws IOException
    {
        long t0 = System.currentTimeMillis();
        Result result = new Result();

        ResPq res = reqPq();
        Pq.Factors factors = factorPq(res.pq);
        ServerDh serverDh = reqDhParams(res, factors);
        result.usedKnownGoodPrime = serverDh.usedKnownGoodPrime;
        result.serverTimeSeconds = serverDh.serverTime;

        setClientDhParams(serverDh, result);

        result.elapsedMillis = System.currentTimeMillis() - t0;
        Diag.info("handshake complete in " + result.elapsedMillis + " ms, "
                  + result.authKey.describe());
        return result;
    }

    // ---------------------------------------------------------------- step 1-2

    private static final class ResPq
    {
        byte[] pq;
        long[] fingerprints;
    }

    private ResPq reqPq() throws IOException
    {
        nonce = rng.nextBytes(16);

        TlWriter w = new TlWriter(32);
        w.writeInt(Tl.REQ_PQ_MULTI);
        w.writeRaw(nonce);
        plain.send(w.toByteArray());
        Diag.info("-> req_pq_multi nonce=" + Hex.encode(nonce, 0, 8) + "..");

        TlReader r = new TlReader(plain.receive());
        r.expect(Tl.RES_PQ, "resPQ");

        byte[] echoedNonce = r.readRaw(16);
        requireEqual("resPQ.nonce", nonce, echoedNonce);
        serverNonce = r.readRaw(16);

        ResPq out = new ResPq();
        out.pq = r.readBytes();
        out.fingerprints = r.readLongVector();

        Diag.info("<- resPQ pq=" + Hex.encode(out.pq) + " keys=" + out.fingerprints.length);
        return out;
    }

    // ---------------------------------------------------------------- step 3

    private Pq.Factors factorPq(byte[] pqBytes) throws IOException
    {
        long pq = Pq.fromBytes(pqBytes);
        long t0 = System.currentTimeMillis();
        Pq.Factors f;
        try
        {
            f = Pq.factor(pq);
        }
        catch (ArithmeticException e)
        {
            throw new IOException("could not factor pq " + pq + ": " + e.getMessage());
        }
        Diag.info("pq " + pq + " = " + f.p + " * " + f.q
                  + " in " + (System.currentTimeMillis() - t0) + " ms");
        return f;
    }

    // ---------------------------------------------------------------- step 4-6

    private static final class ServerDh
    {
        BigInteger p;
        BigInteger gA;
        int g;
        int serverTime;
        boolean usedKnownGoodPrime;
    }

    private ServerDh reqDhParams(ResPq res, Pq.Factors factors) throws IOException
    {
        RsaKey[] keys = RsaKey.forThisBuild();
        RsaKey key = RsaKey.select(keys, res.fingerprints);
        if (key == null)
        {
            StringBuffer sb = new StringBuffer();
            sb.append("no known RSA key matches the server's fingerprints. ");
            sb.append("server offered ");
            for (int i = 0; i < res.fingerprints.length; i++)
            {
                if (i > 0) { sb.append(", "); }
                sb.append(res.fingerprints[i]);
            }
            sb.append("; we hold ");
            for (int i = 0; i < keys.length; i++)
            {
                if (i > 0) { sb.append(", "); }
                sb.append(keys[i].fingerprint());
            }
            sb.append(". Is this build pointed at the right environment? ");
            sb.append("test and production data centres use different keys.");
            throw new IOException(sb.toString());
        }

        newNonce = rng.nextBytes(32);

        byte[] pBytes = Pq.toBytes(factors.p);
        byte[] qBytes = Pq.toBytes(factors.q);

        TlWriter inner = new TlWriter(128);
        inner.writeInt(Tl.P_Q_INNER_DATA_DC);
        inner.writeBytes(res.pq);
        inner.writeBytes(pBytes);
        inner.writeBytes(qBytes);
        inner.writeRaw(nonce);
        inner.writeRaw(serverNonce);
        inner.writeRaw(newNonce);
        inner.writeInt(Dc.rawId(dcId));

        byte[] encrypted = key.encrypt(inner.toByteArray(), rng);

        TlWriter w = new TlWriter(encrypted.length + 96);
        w.writeInt(Tl.REQ_DH_PARAMS);
        w.writeRaw(nonce);
        w.writeRaw(serverNonce);
        w.writeBytes(pBytes);
        w.writeBytes(qBytes);
        w.writeLong(key.fingerprint());
        w.writeBytes(encrypted);
        plain.send(w.toByteArray());
        Diag.info("-> req_DH_params key=" + key.fingerprint() + " dc=" + Dc.rawId(dcId));

        TlReader r = new TlReader(plain.receive());
        int id = r.readInt();
        if (id != Tl.SERVER_DH_PARAMS_OK)
        {
            throw new TlException("expected server_DH_params_ok, got " + Tl.name(id));
        }
        requireEqual("server_DH_params_ok.nonce", nonce, r.readRaw(16));
        requireEqual("server_DH_params_ok.server_nonce", serverNonce, r.readRaw(16));
        byte[] encryptedAnswer = r.readBytes();

        deriveTmpAesKeys();
        return decryptServerDhInner(encryptedAnswer);
    }

    /**
     * tmp_aes_key := SHA1(new_nonce + server_nonce) + SHA1(server_nonce + new_nonce)[0:12]
     * tmp_aes_iv  := SHA1(server_nonce + new_nonce)[12:20] + SHA1(new_nonce + new_nonce)
     *                + new_nonce[0:4]
     */
    private void deriveTmpAesKeys()
    {
        byte[] nsn = sha1Of(newNonce, serverNonce);
        byte[] snn = sha1Of(serverNonce, newNonce);
        byte[] nnn = sha1Of(newNonce, newNonce);

        tmpAesKey = new byte[32];
        System.arraycopy(nsn, 0, tmpAesKey, 0, 20);
        System.arraycopy(snn, 0, tmpAesKey, 20, 12);

        tmpAesIv = new byte[32];
        System.arraycopy(snn, 12, tmpAesIv, 0, 8);
        System.arraycopy(nnn, 0, tmpAesIv, 8, 20);
        System.arraycopy(newNonce, 0, tmpAesIv, 28, 4);
    }

    private ServerDh decryptServerDhInner(byte[] encryptedAnswer) throws IOException
    {
        if ((encryptedAnswer.length & 15) != 0)
        {
            throw new TlException("encrypted_answer is not a multiple of 16: "
                                  + encryptedAnswer.length);
        }

        AesIge ige = new AesIge(tmpAesKey);
        byte[] answerWithHash = new byte[encryptedAnswer.length];
        ige.decrypt(tmpAesIv, 0, encryptedAnswer, 0, answerWithHash, 0, encryptedAnswer.length);
        ige.wipe();

        // SHA1(answer) + answer + 0-15 random bytes. The answer length is not
        // transmitted, so it is recovered by parsing and then checked against
        // the hash - which is also what proves the decryption was correct.
        TlReader r = new TlReader(answerWithHash, 20, answerWithHash.length - 20);
        r.expect(Tl.SERVER_DH_INNER_DATA, "server_DH_inner_data");
        requireEqual("server_DH_inner_data.nonce", nonce, r.readRaw(16));
        requireEqual("server_DH_inner_data.server_nonce", serverNonce, r.readRaw(16));

        ServerDh out = new ServerDh();
        out.g = r.readInt();
        byte[] dhPrime = r.readBytes();
        byte[] gA = r.readBytes();
        out.serverTime = r.readInt();

        int answerLen = r.position();
        byte[] expectedSha = Sha1.hash(answerWithHash, 20, answerLen);
        if (!Hex.equals(expectedSha, 0, answerWithHash, 0, 20))
        {
            throw new TlException("server_DH_inner_data SHA1 mismatch - "
                                  + "decryption failed or the answer was tampered with");
        }

        out.p = new BigInteger(1, dhPrime);
        out.gA = new BigInteger(1, gA);

        DhPrime.Result v = DhPrime.validate(out.p, out.g, out.gA);
        if (!v.ok)
        {
            throw new IOException("DH parameter validation failed: " + v.failure);
        }
        out.usedKnownGoodPrime = v.usedKnownGoodFastPath;
        Diag.info("<- server_DH_inner_data g=" + out.g
                  + " validated in " + v.millis + " ms"
                  + (v.usedKnownGoodFastPath ? " (known-good prime)" : " (full check)"));
        return out;
    }

    // ---------------------------------------------------------------- step 7-9

    private void setClientDhParams(ServerDh dh, Result result) throws IOException
    {
        long retryId = 0;

        for (int attempt = 0; attempt < MAX_DH_ATTEMPTS; attempt++)
        {
            // b must be freshly random on every attempt. Reusing it after a
            // dh_gen_retry would hand the server two exponentiations of the
            // same secret.
            BigInteger b = new BigInteger(2048, rng);
            BigInteger g = BigInteger.valueOf(dh.g);

            long t0 = System.currentTimeMillis();
            BigInteger gB = g.modPow(b, dh.p);
            String problem = DhPrime.checkPublicValue("g_b", dh.p, gB);
            if (problem != null)
            {
                Diag.warn("generated g_b rejected by our own check (" + problem + "), retrying");
                continue;
            }

            BigInteger authKeyValue = dh.gA.modPow(b, dh.p);
            Diag.info("two 2048-bit modPow in " + (System.currentTimeMillis() - t0) + " ms");

            byte[] authKeyBytes = RsaKey.toFixed(authKeyValue, AuthKey.KEY_SIZE);
            AuthKey candidate = new AuthKey(authKeyBytes, dcId, testEnvironment);

            sendClientDhInner(gB, retryId);

            TlReader r = new TlReader(plain.receive());
            int id = r.readInt();
            requireEqual("dh_gen.nonce", nonce, r.readRaw(16));
            requireEqual("dh_gen.server_nonce", serverNonce, r.readRaw(16));
            byte[] hash = r.readRaw(16);

            if (id == Tl.DH_GEN_OK)
            {
                byte[] expected = newNonceHash(1, candidate.auxHash());
                if (!Hex.equals(expected, hash))
                {
                    throw new TlException("dh_gen_ok new_nonce_hash1 mismatch - "
                                          + "the server did not prove it holds the same key");
                }
                result.authKey = candidate;
                result.serverSalt = serverSalt();
                return;
            }

            if (id == Tl.DH_GEN_RETRY)
            {
                byte[] expected = newNonceHash(2, candidate.auxHash());
                if (!Hex.equals(expected, hash))
                {
                    throw new TlException("dh_gen_retry new_nonce_hash2 mismatch");
                }
                Diag.warn("dh_gen_retry - the server already has a key with this hash, "
                          + "regenerating b (attempt " + (attempt + 1) + ")");
                retryId = candidate.auxHash();
                continue;
            }

            if (id == Tl.DH_GEN_FAIL)
            {
                byte[] expected = newNonceHash(3, candidate.auxHash());
                if (!Hex.equals(expected, hash))
                {
                    throw new TlException("dh_gen_fail new_nonce_hash3 mismatch");
                }
                throw new IOException("server returned dh_gen_fail - the exchange was rejected");
            }

            throw new TlException("unexpected answer to set_client_DH_params: " + Tl.name(id));
        }
        throw new IOException("dh_gen_retry " + MAX_DH_ATTEMPTS + " times, giving up");
    }

    private void sendClientDhInner(BigInteger gB, long retryId) throws IOException
    {
        TlWriter inner = new TlWriter(320);
        inner.writeInt(Tl.CLIENT_DH_INNER_DATA);
        inner.writeRaw(nonce);
        inner.writeRaw(serverNonce);
        inner.writeLong(retryId);
        inner.writeBytes(RsaKey.toFixed(gB, 256));
        byte[] data = inner.toByteArray();

        // SHA1(data) + data + 0-15 random bytes, padded to a multiple of 16.
        byte[] sha = Sha1.hash(data);
        int unpadded = sha.length + data.length;
        int padding = (16 - (unpadded & 15)) & 15;
        byte[] withHash = new byte[unpadded + padding];
        System.arraycopy(sha, 0, withHash, 0, sha.length);
        System.arraycopy(data, 0, withHash, sha.length, data.length);
        if (padding > 0)
        {
            rng.nextBytes(withHash, unpadded, padding);
        }

        AesIge ige = new AesIge(tmpAesKey);
        byte[] encrypted = new byte[withHash.length];
        ige.encrypt(tmpAesIv, 0, withHash, 0, encrypted, 0, withHash.length);
        ige.wipe();

        TlWriter w = new TlWriter(encrypted.length + 64);
        w.writeInt(Tl.SET_CLIENT_DH_PARAMS);
        w.writeRaw(nonce);
        w.writeRaw(serverNonce);
        w.writeBytes(encrypted);
        plain.send(w.toByteArray());
        Diag.info("-> set_client_DH_params retry_id=" + retryId);
    }

    /**
     * 128 lower-order bits of SHA1(new_nonce + [i] + auth_key_aux_hash).
     * The distinct i values are what stop an intruder turning a dh_gen_ok into
     * a dh_gen_retry.
     */
    private byte[] newNonceHash(int which, long auxHash)
    {
        Sha1 sha = new Sha1();
        sha.update(newNonce, 0, 32);
        sha.update((byte) which);
        for (int i = 0; i < 8; i++)
        {
            sha.update((byte) (auxHash >>> (i * 8)));
        }
        byte[] full = sha.digest();

        byte[] low = new byte[16];
        System.arraycopy(full, full.length - 16, low, 0, 16);
        return low;
    }

    /** server_salt := substr(new_nonce, 0, 8) XOR substr(server_nonce, 0, 8) */
    private long serverSalt()
    {
        long salt = 0;
        for (int i = 7; i >= 0; i--)
        {
            salt = (salt << 8) | ((newNonce[i] ^ serverNonce[i]) & 0xffL);
        }
        return salt;
    }

    // ------------------------------------------------------------ helpers

    private static byte[] sha1Of(byte[] a, byte[] b)
    {
        Sha1 sha = new Sha1();
        sha.update(a, 0, a.length);
        sha.update(b, 0, b.length);
        return sha.digest();
    }

    private static void requireEqual(String what, byte[] expected, byte[] actual)
            throws TlException
    {
        if (!Hex.equals(expected, actual))
        {
            throw new TlException(what + " mismatch: expected "
                                  + Hex.encode(expected) + ", got " + Hex.encode(actual));
        }
    }
}
