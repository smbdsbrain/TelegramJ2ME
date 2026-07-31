package tg.plat;

import java.util.Vector;

import javax.microedition.rms.RecordStore;

import tg.crypto.Entropy;
import tg.diag.Diag;
import tg.io.Hex;

/**
 * Cross-restart determinism check for {@link Entropy#gather()}.
 *
 * <h3>The failure this exists to catch</h3>
 * Everything {@code gather()} folds in could be identical on two cold boots. A
 * feature phone with no battery-backed RTC and no network time starts at a fixed
 * epoch; a KVM that allocates deterministically hands out the same hash code;
 * the heap readings at MIDlet startup are the same every launch because nothing
 * has happened yet. If all of that holds, two cold boots produce <b>the same
 * seed</b>, and therefore the same auth_key, and therefore the same DH secret.
 * That is not a weak key, it is a published one.
 *
 * No amount of desktop testing can find this. It needs a physical power cycle,
 * so the record has to survive one - hence RMS, written on every launch and
 * compared against every launch before it. Modelled on
 * {@link RmsCheck#checkPersistenceMarker()}, which establishes the same thing
 * about RMS itself.
 *
 * <h3>Two things a tester has to know</h3>
 * <ul>
 *   <li>An app restart proves nothing. The phone must be powered fully off and
 *       on, because a warm restart inherits the running clock.</li>
 *   <li>MIDP record stores are scoped to the MIDlet suite, so {@code probe.jar}
 *       and {@code crypto.jar} keep <em>separate</em> histories and their launch
 *       numbers are not comparable. The report says so on screen; this is also
 *       true of {@code RmsCheck}'s marker and was documented nowhere.</li>
 * </ul>
 *
 * Every RMS call is wrapped: a probe that throws during startup would take the
 * MIDlet with it, and the entropy question is not worth that.
 */
public final class EntropyLog
{
    private static final String STORE = "tgentropy";

    /** Ring size. 20 records of ~70 bytes; enough cold boots to see a pattern. */
    private static final int MAX_LAUNCHES = 20;

    private static final char SEP = '|';

    private EntropyLog() { }

    /**
     * Fold this launch into the history. Call once from {@code startApp()}.
     *
     * @param tag           which MIDlet suite is writing, for the report
     * @param startupMillis {@code System.currentTimeMillis()} captured
     *                      <b>synchronously on the first line of startApp</b>.
     *                      It is the value that detects a clock reset, so it
     *                      must not be taken after 240 ms of jitter collection
     *                      has already advanced it.
     * @return a one-line summary for the diagnostic log; never throws
     */
    public static String recordLaunch(String tag, long startupMillis)
    {
        RecordStore rs = null;
        try
        {
            String digest = Hex.encode(Entropy.gather(), 0, 8);
            String second = Hex.encode(Entropy.gather(), 0, 4);

            rs = RecordStore.openRecordStore(STORE, true);
            int seq = rs.getNextRecordID();
            String record = "" + seq + SEP + tag + SEP + startupMillis
                    + SEP + digest + SEP + second;
            byte[] blob = record.getBytes();
            rs.addRecord(blob, 0, blob.length);

            trim(rs);
            return "launch " + seq + " t=" + startupMillis + " " + digest;
        }
        catch (Throwable t)
        {
            return "unavailable: " + Diag.className(t);
        }
        finally
        {
            close(rs);
        }
    }

    /**
     * The launch table and the two verdicts that come out of it: whether any
     * seed has ever repeated, and whether the wall clock survives a power cycle.
     */
    public static String[] report()
    {
        Vector v = new Vector(30);
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);
            int count = rs.getNumRecords();
            if (count == 0)
            {
                v.addElement("no launches recorded yet.");
                appendInstructions(v);
                return toArray(v);
            }

            String[] records = new String[count];
            int w = 0;
            int next = rs.getNextRecordID();
            for (int id = 1; id < next && w < count; id++)
            {
                try
                {
                    byte[] b = rs.getRecord(id);
                    if (b != null) { records[w++] = new String(b); }
                }
                catch (Throwable ignored) { /* deleted by the ring; expected */ }
            }

            v.addElement("launches recorded = " + w);
            String tag = field(records[0], 1);
            v.addElement("suite = " + (tag.length() == 0 ? "?" : tag));

            long[] times = new long[w];
            String[] digests = new String[w];
            String[] seqs = new String[w];
            for (int i = 0; i < w; i++)
            {
                seqs[i] = field(records[i], 0);
                times[i] = parseLong(field(records[i], 2));
                digests[i] = field(records[i], 3);
                if (i < 8)
                {
                    // Compare on the full 64-bit digest, display 32 bits of it:
                    // a 13-digit epoch plus 16 hex characters does not fit on a
                    // 34-column screen, and the eye only needs enough to tell
                    // two rows apart.
                    v.addElement("#" + seqs[i] + " t=" + times[i]
                            + " " + shorten(digests[i]));
                }
            }
            if (w > 8) { v.addElement("... " + (w - 8) + " more"); }

            appendCollisions(v, digests, seqs, w);
            appendClockVerdict(v, times, w);
            appendInstructions(v);
            return toArray(v);
        }
        catch (Throwable t)
        {
            v.addElement("unavailable: " + Diag.className(t));
            v.addElement(String.valueOf(t.getMessage()));
            return toArray(v);
        }
        finally
        {
            close(rs);
        }
    }

    /** Start a clean series - the tester's escape hatch after a bad run. */
    public static void reset()
    {
        try { RecordStore.deleteRecordStore(STORE); }
        catch (Throwable ignored) { /* absent is the desired state anyway */ }
    }

    // ------------------------------------------------------------- verdicts

    /**
     * Compare every digest against every earlier one. O(n^2) over at most 20
     * records, and the pairwise comparison is the point: a collision between two
     * non-adjacent launches is just as fatal as one between neighbours.
     */
    private static void appendCollisions(Vector v, String[] digests,
                                         String[] seqs, int w)
    {
        for (int i = 0; i < w; i++)
        {
            for (int j = 0; j < i; j++)
            {
                if (digests[i] != null && digests[i].length() > 0
                        && digests[i].equals(digests[j]))
                {
                    v.addElement("CATASTROPHIC: launch " + seqs[i]);
                    v.addElement("digest == launch " + seqs[j]);
                    v.addElement("the seed REPEATS. keys");
                    v.addElement("generated here are public.");
                    return;
                }
            }
        }
        v.addElement("digest collisions = 0");
    }

    /**
     * Whether the clock is worth anything across a power cycle.
     *
     * Two separate defects. A clock that goes backwards, or several launches
     * landing on the same startup millisecond, both mean the wall clock resets
     * at boot - the expected behaviour on a handset with no RTC and no network
     * time. It contributes zero bits across cold boots, which does not by itself
     * make the seed repeat but does mean jitter is carrying the pool alone.
     */
    private static void appendClockVerdict(Vector v, long[] times, int w)
    {
        if (w < 2)
        {
            v.addElement("clock: need >= 2 launches.");
            return;
        }

        int backwards = 0;
        int clustered = 0;
        for (int i = 1; i < w; i++)
        {
            if (times[i] < times[i - 1]) { backwards++; }
            for (int j = 0; j < i; j++)
            {
                long d = times[i] - times[j];
                if (d < 0) { d = -d; }
                if (d < 2000) { clustered++; break; }
            }
        }

        if (backwards > 0)
        {
            v.addElement("clock went BACKWARDS " + backwards + "x");
            v.addElement("=> CLOCK RESETS AT BOOT");
            v.addElement("0 bits from the wall clock");
            v.addElement("across cold boots.");
        }
        else if (clustered > 0)
        {
            v.addElement(clustered + " launches within 2 s of");
            v.addElement("an earlier one.");
            v.addElement("=> CLOCK RESETS AT BOOT");
            v.addElement("0 bits from the wall clock");
            v.addElement("across cold boots.");
        }
        else
        {
            v.addElement("clock advances across");
            v.addElement("launches: spread "
                    + ((times[w - 1] - times[0]) / 1000) + " s");
        }
    }

    private static void appendInstructions(Vector v)
    {
        v.addElement("run after a FULL POWER CYCLE,");
        v.addElement("not an app restart.");
        v.addElement("RMS is per-suite: probe and");
        v.addElement("crypto keep separate lists.");
    }

    // -------------------------------------------------------------- internal

    /** Drop the oldest records until the ring fits. */
    private static void trim(RecordStore rs) throws Exception
    {
        int next = rs.getNextRecordID();
        for (int id = 1; id < next && rs.getNumRecords() > MAX_LAUNCHES; id++)
        {
            try { rs.deleteRecord(id); }
            catch (Throwable ignored) { /* already gone */ }
        }
    }

    /** CLDC has no String.split and we are not shipping a regex engine. */
    private static String field(String record, int index)
    {
        if (record == null) { return ""; }
        int start = 0;
        int seen = 0;
        for (int i = 0; i <= record.length(); i++)
        {
            if (i == record.length() || record.charAt(i) == SEP)
            {
                if (seen == index) { return record.substring(start, i); }
                seen++;
                start = i + 1;
            }
        }
        return "";
    }

    private static String shorten(String digest)
    {
        if (digest == null) { return "?"; }
        return digest.length() > 8 ? digest.substring(0, 8) : digest;
    }

    private static long parseLong(String s)
    {
        try { return Long.parseLong(s); }
        catch (Throwable t) { return 0L; }
    }

    private static void close(RecordStore rs)
    {
        if (rs != null)
        {
            try { rs.closeRecordStore(); }
            catch (Throwable ignored) { }
        }
    }

    private static String[] toArray(Vector v)
    {
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }
}
