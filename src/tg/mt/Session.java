package tg.mt;

import java.io.IOException;

import tg.crypto.AesIge;
import tg.crypto.Rng;
import tg.crypto.Sha256;
import tg.io.Hex;
import tg.tl.TlException;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * MTProto 2.0 encrypted message envelope.
 *
 * <pre>
 *   on the wire:   auth_key_id:int64  msg_key:int128  encrypted_data
 *
 *   plaintext:     salt:int64  session_id:int64  msg_id:int64
 *                  seq_no:int32  length:int32  body  padding(12..1024)
 * </pre>
 *
 * Key derivation, with {@code x = 0} for messages we send and {@code x = 8} for
 * messages we receive:
 *
 * <pre>
 *   msg_key_large = SHA256(auth_key[88+x .. 120+x] + plaintext)
 *   msg_key       = msg_key_large[8 .. 24]
 *
 *   a = SHA256(msg_key + auth_key[x .. 36+x])
 *   b = SHA256(auth_key[40+x .. 76+x] + msg_key)
 *   aes_key = a[0..8]  + b[8..24] + a[24..32]
 *   aes_iv  = b[0..8]  + a[8..24] + b[24..32]
 * </pre>
 *
 * <h3>msg_key verification is mandatory</h3>
 * On receive the msg_key is recomputed from the decrypted plaintext and
 * compared. Without that check AES-IGE decryption produces plausible-looking
 * garbage for any ciphertext, and a tampered message would be parsed as if it
 * were genuine. The comparison is the authentication half of the protocol, not
 * a sanity check - see {@link #decrypt}.
 *
 * The send and receive directions have independent crypto workspaces, so one
 * writer and one reader may use the session concurrently.
 */
public final class Session
{
    /** Header before the body: salt, session_id, msg_id, seq_no, length. */
    private static final int HEADER = 8 + 8 + 8 + 4 + 4;

    /** Minimum padding required by MTProto 2.0. */
    private static final int MIN_PADDING = 12;

    private final AuthKey authKey;
    private final Rng rng;
    private final MsgIdGen ids;

    private final long sessionId;
    private long salt;

    private final CryptoState tx = new CryptoState();
    private final CryptoState rx = new CryptoState();

    private static final class CryptoState
    {
        final AesIge cipher = new AesIge(new byte[32]);
        final Sha256 sha = new Sha256();
        final byte[] msgKey = new byte[16];
        final byte[] aesKey = new byte[32];
        final byte[] aesIv = new byte[32];
    }

    public Session(AuthKey authKey, Rng rng, MsgIdGen ids, long salt)
    {
        this.authKey = authKey;
        this.rng = rng;
        this.ids = ids;
        this.salt = salt;
        this.sessionId = rng.nextLong();
        ids.resetSession();
    }

    public long sessionId()
    {
        return sessionId;
    }

    public synchronized long salt()
    {
        return salt;
    }

    /** Called on bad_server_salt, which carries the correct value. */
    public synchronized void setSalt(long newSalt)
    {
        this.salt = newSalt;
    }

    public AuthKey authKey()
    {
        return authKey;
    }

    /** A decrypted incoming message. */
    public static final class Incoming
    {
        public long salt;
        public long sessionId;
        public long msgId;
        public int seqNo;
        public byte[] body;
    }

    /**
     * Wrap and encrypt one message.
     *
     * @param contentRelated true for RPC calls, which advance seq_no and
     *                       require acknowledgement; false for acks and pings
     * @param outMsgId       receives the generated msg_id at index 0, so the
     *                       caller can match the eventual rpc_result
     */
    public byte[] encrypt(byte[] body, int off, int len, boolean contentRelated,
                          long[] outMsgId)
    {
        // All messages sent by the client, including service messages, must
        // have msg_id divisible by four. Content-relatedness is carried by the
        // seq_no, not by different low msg_id bits.
        long msgId = ids.next();
        int seqNo = ids.nextSeqNo(contentRelated);
        if (outMsgId != null && outMsgId.length > 0)
        {
            outMsgId[0] = msgId;
        }

        // Padding: at least 12 bytes, total a multiple of 16. Minimal padding
        // is used deliberately - on GPRS every byte is billed.
        int unpadded = HEADER + len;
        int padding = MIN_PADDING + ((16 - ((unpadded + MIN_PADDING) & 15)) & 15);

        TlWriter w = new TlWriter(unpadded + padding);
        w.writeLong(salt());
        w.writeLong(sessionId);
        w.writeLong(msgId);
        w.writeInt(seqNo);
        w.writeInt(len);
        w.writeRaw(body, off, len);
        byte[] pad = new byte[padding];
        rng.nextBytes(pad, 0, padding);
        w.writeRaw(pad);

        byte[] plaintext = w.buffer();
        int plainLen = w.size();

        computeMsgKey(tx, plaintext, plainLen, 0, tx.msgKey);
        deriveKeys(tx, tx.msgKey, 0, 0);           // x = 0: message from the client

        byte[] packet = new byte[8 + 16 + plainLen];
        writeLong(packet, 0, authKey.keyId());
        System.arraycopy(tx.msgKey, 0, packet, 8, 16);

        tx.cipher.encrypt(tx.aesIv, 0, plaintext, 0, packet, 24, plainLen);
        return packet;
    }

    public byte[] encryptRpc(byte[] body, long[] outMsgId)
    {
        return encrypt(body, 0, body.length, true, outMsgId);
    }

    /**
     * Decrypt and validate one incoming packet.
     *
     * Every failure mode here means the packet is not from the server holding
     * our key, so all of them throw rather than returning a partial result.
     */
    public Incoming decrypt(byte[] packet, int len) throws IOException
    {
        if (len < 24 + 16)
        {
            throw new TlException("encrypted packet is only " + len + " bytes");
        }

        long keyId = readLong(packet, 0);
        if (keyId != authKey.keyId())
        {
            throw new TlException("auth_key_id mismatch: message is for " + keyId
                                  + ", we hold " + authKey.keyId());
        }

        int cipherLen = len - 24;
        if ((cipherLen & 15) != 0)
        {
            throw new TlException("encrypted body is not a multiple of 16: " + cipherLen);
        }

        deriveKeys(rx, packet, 8, 8);       // msg_key at offset 8; x = 8: from the server

        byte[] plaintext = new byte[cipherLen];
        rx.cipher.decrypt(rx.aesIv, 0, packet, 24, plaintext, 0, cipherLen);

        // THE check: recompute msg_key over the decrypted plaintext with x=8
        // and compare. AES-IGE will happily "decrypt" anything, so this is what
        // authenticates the message.
        byte[] expected = new byte[16];
        computeMsgKey(rx, plaintext, cipherLen, 8, expected);
        if (!Hex.equals(expected, 0, packet, 8, 16))
        {
            throw new TlException("msg_key mismatch - message is forged, corrupted, "
                                  + "or encrypted with a different key");
        }

        TlReader r = new TlReader(plaintext);
        Incoming in = new Incoming();
        in.salt = r.readLong();
        in.sessionId = r.readLong();
        in.msgId = r.readLong();
        in.seqNo = r.readInt();
        int bodyLen = r.readInt();

        if (in.sessionId != sessionId)
        {
            throw new TlException("session_id mismatch: got " + in.sessionId
                                  + ", expected " + sessionId);
        }
        if (bodyLen < 0 || bodyLen > r.remaining())
        {
            throw new TlException("declared body length " + bodyLen
                                  + " exceeds the " + r.remaining() + " bytes present");
        }

        // The padding must be within spec; a server-supplied length that leaves
        // too little padding indicates tampering the msg_key check might miss
        // if the attacker also controls the body.
        int padding = r.remaining() - bodyLen;
        if (padding < MIN_PADDING || padding > 1024)
        {
            throw new TlException("padding of " + padding + " bytes is outside 12..1024");
        }

        in.body = r.readRaw(bodyLen);
        return in;
    }

    // ------------------------------------------------------------ internal

    /**
     * msg_key = SHA256(auth_key[88+x .. 120+x] + plaintext)[8 .. 24]
     */
    private void computeMsgKey(CryptoState state, byte[] plaintext, int len,
                               int x, byte[] out)
    {
        state.sha.reset();
        state.sha.update(authKey.bytes(), 88 + x, 32);
        state.sha.update(plaintext, 0, len);
        byte[] large = state.sha.digest();
        System.arraycopy(large, 8, out, 0, 16);
    }

    /**
     * Fill aesKey and aesIv from a msg_key.
     *
     * @param x 0 for messages we send, 8 for messages we receive. The two
     *          directions use disjoint slices of the auth_key, which is what
     *          stops a message being replayed back at its sender.
     */
    private void deriveKeys(CryptoState state, byte[] msgKey, int msgKeyOff, int x)
    {
        byte[] key = authKey.bytes();

        state.sha.reset();
        state.sha.update(msgKey, msgKeyOff, 16);
        state.sha.update(key, x, 36);
        byte[] a = state.sha.digest();

        state.sha.reset();
        state.sha.update(key, 40 + x, 36);
        state.sha.update(msgKey, msgKeyOff, 16);
        byte[] b = state.sha.digest();

        System.arraycopy(a, 0, state.aesKey, 0, 8);
        System.arraycopy(b, 8, state.aesKey, 8, 16);
        System.arraycopy(a, 24, state.aesKey, 24, 8);

        System.arraycopy(b, 0, state.aesIv, 0, 8);
        System.arraycopy(a, 8, state.aesIv, 8, 16);
        System.arraycopy(b, 24, state.aesIv, 24, 8);

        state.cipher.rekey(state.aesKey);
    }

    private static void writeLong(byte[] out, int off, long v)
    {
        for (int i = 0; i < 8; i++)
        {
            out[off + i] = (byte) (v >>> (i * 8));
        }
    }

    private static long readLong(byte[] in, int off)
    {
        long v = 0;
        for (int i = 7; i >= 0; i--)
        {
            v = (v << 8) | (in[off + i] & 0xffL);
        }
        return v;
    }

    public String describe()
    {
        return "session " + sessionId + " salt=" + salt() + " " + ids.describe();
    }
}
