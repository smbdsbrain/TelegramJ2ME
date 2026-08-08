package tg.api;

import java.io.IOException;

import tg.io.Crc32;

/**
 * The header every durable RMS record carries, and the questions it answers.
 *
 * A record read back from a feature phone's flash can be four different things,
 * and the code that reads it has to tell them apart before it acts:
 *
 * <ul>
 * <li>a record this build wrote - use it;</li>
 * <li>a record an <em>older</em> build wrote - migrate it, or refuse it, but
 *     know which;</li>
 * <li>a record belonging to another account or the other environment - a test
 *     build and a production build share one RMS, and a key or a draft from one
 *     is not the other's;</li>
 * <li>damaged bytes - drop them, and say so.</li>
 * </ul>
 *
 * Without a header the last two are indistinguishable from a valid record whose
 * fields happen to decode, which is how a truncated draft becomes a message
 * sent to the wrong chat.
 *
 * <h3>Layout</h3>
 * <pre>
 *   int   magic        which store wrote this
 *   int   version      schema of the payload
 *   long  accountId    0 when the writer did not know it yet
 *   int   flags        bit 0: written by a test-environment build
 *   int   crc          CRC-32 of the payload
 *   byte[]             payload, to the end of the record
 * </pre>
 * Twenty-four bytes. On a 64-entry outbox that is 1.5 KB, against records that
 * are hundreds of bytes each.
 *
 * No length field: the payload runs to the end of the record, and a short read
 * shows up as a checksum that does not match rather than as a length that
 * disagrees with itself.
 *
 * <h3>accountId 0</h3>
 * Means "unbound", and is readable by any account rather than by none. A
 * message queued before the client knows who it is signed in as must not
 * disappear the moment it finds out. Binding is defence in depth behind the
 * logout wipe, not the mechanism that separates accounts.
 *
 * <h3>The checksum</h3>
 * Accidental damage only - see {@link Crc32}. Anyone able to edit a record can
 * recompute it in the same edit.
 */
public final class RecordEnvelope
{
    /** Bytes before the payload. */
    public static final int HEADER = 24;

    private static final int FLAG_TEST_ENVIRONMENT = 1;

    /** The record is this build's, and intact. */
    public static final int OK = 0;
    /** Not a record of this store at all, or too short to be one. */
    public static final int FOREIGN = 1;
    /** This store's, from a schema this build does not read. */
    public static final int WRONG_VERSION = 2;
    /** This store's, another account's or the other environment's. */
    public static final int WRONG_OWNER = 3;
    /** This store's, and damaged. */
    public static final int DAMAGED = 4;

    /** One of the constants above. */
    public final int outcome;
    /** The payload, non-null exactly when {@link #outcome} is {@link #OK}. */
    public final byte[] payload;
    public final int version;
    public final long accountId;
    public final boolean testEnvironment;

    private RecordEnvelope(int outcome, byte[] payload, int version,
                           long accountId, boolean testEnvironment)
    {
        this.outcome = outcome;
        this.payload = payload;
        this.version = version;
        this.accountId = accountId;
        this.testEnvironment = testEnvironment;
    }

    public boolean isOk() { return outcome == OK; }

    /**
     * Is this record damaged or unreadable, as distinct from someone else's?
     *
     * The two need different answers: a damaged record of ours should be
     * removed so it stops consuming capacity, and a record we do not recognise
     * should be left exactly where it is - it may belong to a build that is
     * about to run next.
     */
    public boolean isOurs()
    {
        return outcome == OK || outcome == WRONG_VERSION || outcome == DAMAGED;
    }

    public String describe()
    {
        if (outcome == OK)            { return "ok"; }
        if (outcome == FOREIGN)       { return "not this store's record"; }
        if (outcome == WRONG_VERSION) { return "schema version " + version; }
        if (outcome == WRONG_OWNER)   { return "another account or environment"; }
        return "damaged";
    }

    /** Header plus payload, ready for {@code addRecord}. */
    public static byte[] wrap(int magic, int version, long accountId,
                              boolean testEnvironment, byte[] payload)
            throws IOException
    {
        if (payload == null) { throw new IOException("no payload"); }
        byte[] out = new byte[HEADER + payload.length];
        putInt(out, 0, magic);
        putInt(out, 4, version);
        putLong(out, 8, accountId);
        putInt(out, 16, testEnvironment ? FLAG_TEST_ENVIRONMENT : 0);
        // Over the payload only. Including the header would need a second pass
        // and buys nothing: a damaged header fails its own checks first.
        putInt(out, 20, Crc32.of(payload));
        System.arraycopy(payload, 0, out, HEADER, payload.length);
        return out;
    }

    /**
     * Read a record back, saying which of the four things it is.
     *
     * @param accountId       who is asking; 0 to accept any owner
     * @param testEnvironment which environment is asking
     */
    public static RecordEnvelope unwrap(byte[] raw, int magic, int minVersion,
                                        int maxVersion, long accountId,
                                        boolean testEnvironment)
    {
        if (raw == null || raw.length < HEADER)
        {
            return new RecordEnvelope(FOREIGN, null, 0, 0, false);
        }
        if (getInt(raw, 0) != magic)
        {
            return new RecordEnvelope(FOREIGN, null, 0, 0, false);
        }

        int version = getInt(raw, 4);
        long owner = getLong(raw, 8);
        boolean test = (getInt(raw, 16) & FLAG_TEST_ENVIRONMENT) != 0;

        if (version < minVersion || version > maxVersion)
        {
            return new RecordEnvelope(WRONG_VERSION, null, version, owner, test);
        }

        byte[] payload = new byte[raw.length - HEADER];
        System.arraycopy(raw, HEADER, payload, 0, payload.length);
        if (Crc32.of(payload) != getInt(raw, 20))
        {
            return new RecordEnvelope(DAMAGED, null, version, owner, test);
        }

        // Owner last: a damaged record is worth removing whoever it belonged
        // to, and reporting it as someone else's would leave it in place.
        if (test != testEnvironment
                || (owner != 0 && accountId != 0 && owner != accountId))
        {
            return new RecordEnvelope(WRONG_OWNER, null, version, owner, test);
        }
        return new RecordEnvelope(OK, payload, version, owner, test);
    }

    /**
     * The same read, without asking who it belongs to.
     *
     * For maintenance rather than for use: eviction has to see - and be able to
     * reclaim - space held by records belonging to an account that is no longer
     * signed in or to the other environment. Reading the payload of one of
     * those and acting on it would be the bug this class exists to prevent, so
     * the only caller is the one that needs a timestamp to evict by.
     */
    public static RecordEnvelope unwrapAnyOwner(byte[] raw, int magic,
                                                int minVersion, int maxVersion)
    {
        RecordEnvelope envelope = unwrap(raw, magic, minVersion, maxVersion, 0,
                raw != null && raw.length >= HEADER
                        && (getInt(raw, 16) & FLAG_TEST_ENVIRONMENT) != 0);
        return envelope;
    }

    private static void putInt(byte[] out, int at, int value)
    {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    private static int getInt(byte[] in, int at)
    {
        return ((in[at] & 0xff) << 24) | ((in[at + 1] & 0xff) << 16)
                | ((in[at + 2] & 0xff) << 8) | (in[at + 3] & 0xff);
    }

    private static void putLong(byte[] out, int at, long value)
    {
        putInt(out, at, (int) (value >>> 32));
        putInt(out, at + 4, (int) value);
    }

    private static long getLong(byte[] in, int at)
    {
        return ((long) getInt(in, at) << 32) | (getInt(in, at + 4) & 0xffffffffL);
    }
}
