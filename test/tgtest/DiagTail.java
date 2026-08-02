package tgtest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import tg.diag.Diag;

/**
 * The whole diagnostic log of a driven run, not just the last hundred lines.
 *
 * <h3>Why this exists</h3>
 * {@link Diag} is a hundred-line ring, sized for a handset with no console. That
 * is the right size on the device and the wrong size for a measurement: the runs
 * worth measuring are the ones that go wrong, and a run that throws thirty
 * errors has overwritten everything that came before them. The sweep behind
 * issue #5 reported {@code avatars=0} at one heap and could not say whether the
 * decoder had given up or the successes had been evicted - "did not happen" and
 * "was not logged" are different facts and the ring cannot tell them apart.
 *
 * So the driver reads the ring faster than it wraps. This is desktop-side:
 * nothing here is linked into a MIDlet and no device code changed to support it.
 *
 * <h3>The instrument shares the heap it is measuring, so it is built to weigh
 * nothing</h3>
 * There is one JVM. Anything this class retains is heap the client does not get,
 * and at the bottom of the ladder - a 3.5 MB heap with a megabyte of ballast
 * held - that is not a rounding error. The first version of this class kept
 * every line in an ArrayList and rescanned it four times to build one verdict
 * line; the run died with {@code GC overhead limit exceeded} before the client
 * had finished connecting, which is a measurement of the instrument rather than
 * of the client.
 *
 * So: lines go straight to a file and are never retained; the counts the driver
 * reports are accumulated as each line arrives rather than by rescanning; and
 * the poll allocates one array per tick and nothing else. What is left is a
 * String[] copy of the ring every {@link #POLL_MS}, which is the smallest
 * footprint {@code Diag}'s public surface allows without changing device code.
 *
 * Set {@code -Dtg.driver.tail=off} to remove even that - the control that says
 * whether an observation belongs to the client or to the observer.
 *
 * <h3>Why not a DiagSink</h3>
 * {@link Diag#setSink} looks like the obvious answer and is a trap.
 * {@code TgMidlet.configureLogging()} calls {@code Diag.setSink(null)} during
 * startup and again on every settings save, so a sink installed by the driver
 * would be dropped before the first interesting line and again every time a
 * scenario visits Settings.
 *
 * <h3>How the merge stays honest</h3>
 * {@code snapshot()} and {@code droppedLines()} are separately synchronized, so
 * a naive pair of reads can straddle a write. The counter is read either side of
 * the snapshot and the pair is retried when it moved, which makes the total -
 * {@code dropped + held} - a number that describes the array actually in hand.
 * Whatever that total has advanced by since the last poll is what is new, taken
 * from the end of the ring. If it advanced by more than the ring holds, the poll
 * was too slow and the gap is recorded as a line of its own: a measurement may
 * be incomplete, but it must never look complete when it is not.
 */
public final class DiagTail
{
    /**
     * The ring holds a hundred lines and the busiest burst observed - twelve
     * thumbnail decodes reporting one line each - is well under twenty lines a
     * second, so four polls a second leaves the ring an order of magnitude of
     * slack. Faster would cost garbage on the heap under measurement for no
     * coverage; a gap, if one ever happens, is reported rather than hidden.
     */
    private static final int POLL_MS = 250;

    /** A malformed line cannot be allowed to grow the attribution map forever. */
    private static final int MAX_KINDS = 32;

    private static final DiagTail INSTANCE = new DiagTail();

    private final Map<String, int[]> markers = new LinkedHashMap<String, int[]>();
    private final Map<String, int[]> oomKinds = new LinkedHashMap<String, int[]>();
    private OutputStream out;
    private File file;
    private long seen;
    private int lost;
    private int written;
    private boolean died;
    private volatile boolean running;

    private DiagTail() { }

    /**
     * Ask for a running count of lines containing {@code marker}.
     *
     * Registered up front and counted as lines arrive, because the alternative -
     * rescanning the log per question at the end - is what made the first
     * version of this class expensive enough to change the answer.
     */
    public static void watch(String marker)
    {
        synchronized (INSTANCE)
        {
            if (!INSTANCE.markers.containsKey(marker))
            {
                INSTANCE.markers.put(marker, new int[1]);
            }
        }
    }

    /**
     * Begin following the ring, writing to {@code target}.
     *
     * @return the file being written, or null when the tail is switched off
     */
    public static File start(File target)
    {
        synchronized (INSTANCE)
        {
            if (INSTANCE.running) { return INSTANCE.file; }
            if ("off".equals(System.getProperty("tg.driver.tail")))
            {
                System.out.println("diagnostic tail disabled by"
                        + " -Dtg.driver.tail=off");
                return null;
            }
            try
            {
                if (target.getParentFile() != null)
                {
                    target.getParentFile().mkdirs();
                }
                INSTANCE.out = new FileOutputStream(target);
                INSTANCE.file = target;
                // A fresh recording. The driver starts one tail per process, so
                // this only matters to the tests - but a second run that
                // inherited the first one's counters would report a total that
                // belonged to neither.
                INSTANCE.reset();
            }
            catch (Throwable t)
            {
                System.out.println("diagnostic tail could not open "
                        + target + ": " + t);
                return null;
            }
            INSTANCE.running = true;
        }
        Thread t = new Thread(new Runnable()
        {
            public void run()
            {
                while (INSTANCE.running)
                {
                    // A tail that dies quietly is worse than no tail: every
                    // count after it would be a floor presented as a total.
                    try { INSTANCE.poll(); }
                    catch (Throwable error)
                    {
                        System.out.println("diagnostic tail failed: " + error);
                        // Recorded, not just printed. Every count taken after
                        // this point is a floor rather than a total, and the
                        // report has to say which of the two it is holding.
                        synchronized (INSTANCE) { INSTANCE.died = true; }
                        INSTANCE.running = false;
                        return;
                    }
                    try { Thread.sleep(POLL_MS); }
                    catch (InterruptedException e) { return; }
                }
            }
        }, "diag-tail");
        t.setDaemon(true);
        t.start();
        return INSTANCE.file;
    }

    /** Stop following. One last poll runs first, so the tail is complete. */
    public static void stop()
    {
        try { INSTANCE.poll(); } catch (Throwable ignored) { }
        INSTANCE.running = false;
        synchronized (INSTANCE)
        {
            if (INSTANCE.out != null)
            {
                try { INSTANCE.out.flush(); } catch (Throwable ignored) { }
                try { INSTANCE.out.close(); } catch (Throwable ignored) { }
                INSTANCE.out = null;
            }
        }
    }

    /** How many lines contained {@code marker}. Zero if it was never watched. */
    public static int count(String marker)
    {
        synchronized (INSTANCE)
        {
            int[] n = INSTANCE.markers.get(marker);
            return n == null ? 0 : n[0];
        }
    }

    /** Lines the tail knows it missed. Zero unless a poll fell behind. */
    public static int lostLines()
    {
        synchronized (INSTANCE) { return INSTANCE.lost; }
    }

    /** Lines recorded. */
    public static int writtenLines()
    {
        synchronized (INSTANCE) { return INSTANCE.written; }
    }

    /**
     * False once the tail has stopped following for a reason other than being
     * asked to. Every count is a floor after that, not a total.
     */
    public static boolean healthy()
    {
        synchronized (INSTANCE) { return !INSTANCE.died; }
    }

    public static File file()
    {
        synchronized (INSTANCE) { return INSTANCE.file; }
    }

    /**
     * Which task threw each OutOfMemoryError, as
     * {@code [task:dialog avatar:12 thumbnail:4]}.
     *
     * Issue #5 blocks its own fix on this question: the sweep that produced its
     * ladder counted OOMs and attributed them to the image workers by inference,
     * and one of them appears with pictures off, where neither worker runs. The
     * attribution was always in the lines themselves - {@code Worker} logs the
     * task name - it was only being lost to the ring.
     */
    public static String oomBreakdown()
    {
        synchronized (INSTANCE)
        {
            if (INSTANCE.oomKinds.isEmpty()) { return "[]"; }
            StringBuffer sb = new StringBuffer("[");
            for (Iterator<Map.Entry<String, int[]>> it =
                    INSTANCE.oomKinds.entrySet().iterator(); it.hasNext(); )
            {
                Map.Entry<String, int[]> e = it.next();
                sb.append(e.getKey()).append(':').append(e.getValue()[0]);
                if (it.hasNext()) { sb.append(' '); }
            }
            return sb.append(']').toString();
        }
    }

    /** Copy the recorded log to {@code to} without holding it in memory. */
    public static void dumpTo(java.io.PrintStream to)
    {
        File f = file();
        if (f == null) { to.println("(no diagnostic tail was recorded)"); return; }
        InputStream in = null;
        try
        {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) { to.write(buf, 0, n); }
            to.flush();
        }
        catch (Throwable t) { to.println("(could not read " + f + ": " + t + ")"); }
        finally
        {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
        }
    }

    private void reset()
    {
        // The ring may already hold lines from before this recording began -
        // the client logs its version and its heap measurement before anything
        // asks to follow along. Starting from what is there now, rather than
        // from zero, is what keeps those out of a run they do not belong to.
        seen = (long) Diag.droppedLines() + Diag.snapshot().length;
        lost = 0;
        written = 0;
        oomKinds.clear();
        for (Iterator<Map.Entry<String, int[]>> it = markers.entrySet().iterator();
             it.hasNext(); )
        {
            it.next().getValue()[0] = 0;
        }
    }

    private synchronized void poll()
    {
        if (out == null) { return; }
        for (int attempt = 0; attempt < 8; attempt++)
        {
            int before = Diag.droppedLines();
            String[] ring = Diag.snapshot();
            int after = Diag.droppedLines();
            // The ring moved between the two reads, so the total would describe
            // neither the array in hand nor the one that replaced it.
            if (before != after) { continue; }

            long total = (long) after + ring.length;
            if (total < seen)
            {
                // Diag.clear() resets both counters. Only ProbeMidlet does that
                // today, but a tail that silently stopped recording afterwards
                // would be the worst possible failure of this class.
                record("<diagnostic log was cleared; following the new one>");
                seen = 0;
            }

            long fresh = total - seen;
            if (fresh > ring.length)
            {
                int gap = (int) (fresh - ring.length);
                lost += gap;
                record("<lost " + gap + " diagnostic lines: the tail fell behind>");
                fresh = ring.length;
            }
            for (int i = ring.length - (int) fresh; i < ring.length; i++)
            {
                record(ring[i]);
            }
            seen = total;
            return;
        }
        // Eight disagreements in a row means the ring is being written faster
        // than it can be read at all. The next poll catches up and reports the
        // gap; nothing is claimed here.
    }

    private void record(String line)
    {
        if (line == null) { return; }
        written++;
        tally(line);
        try
        {
            out.write(line.getBytes("UTF-8"));
            out.write('\n');
        }
        catch (Throwable ignored)
        {
            // A full disk must not take down the run being measured.
        }
    }

    private void tally(String line)
    {
        for (Iterator<Map.Entry<String, int[]>> it = markers.entrySet().iterator();
             it.hasNext(); )
        {
            Map.Entry<String, int[]> e = it.next();
            if (line.indexOf(e.getKey()) >= 0) { e.getValue()[0]++; }
        }
        if (line.indexOf("OutOfMemory") < 0) { return; }
        String kind = attribute(line);
        int[] n = oomKinds.get(kind);
        if (n != null) { n[0]++; }
        else if (oomKinds.size() < MAX_KINDS) { oomKinds.put(kind, new int[] { 1 }); }
    }

    /**
     * Name the source of one OutOfMemory line.
     *
     * The shapes come from the loggers themselves:
     * <pre>
     *   task &lt;name&gt; failed | OutOfMemoryError      tg.app.Worker
     *   stripped thumbnail &lt;id&gt;: OutOfMemoryError  the thumbnail thread
     *   avatar &lt;key&gt;: OutOfMemoryError            the avatar callback
     * </pre>
     * A message id or a peer key is per-run noise and is dropped, so twelve
     * failures of the same kind read as one entry with a twelve beside it.
     * Anything unrecognised keeps its whole prefix and is marked with a
     * {@code ?}: an unattributed OOM is the interesting one and must not be
     * quietly folded into "other".
     */
    static String attribute(String line)
    {
        String text = strip(line);
        if (text.startsWith("task ") && text.indexOf(" failed") > 0)
        {
            return "task:" + text.substring(5, text.indexOf(" failed"));
        }
        if (text.startsWith("stripped thumbnail ")) { return "thumbnail"; }
        if (text.startsWith("avatar ")) { return "avatar-callback"; }
        if (text.startsWith("chat open failed")) { return "chat-open"; }
        if (text.startsWith("heap: probe failed")) { return "heap-probe"; }

        // Everything else keeps whatever it said before it started quoting the
        // exception - " | " is what Diag.error appends, ": " is what the
        // hand-written warnings use. The "?" is deliberate and load-bearing: it
        // marks a source this parser does not recognise, which is precisely the
        // kind of OutOfMemory that has to be looked at rather than tallied.
        int cut = text.indexOf(" | ");
        if (cut < 0) { cut = text.indexOf(": "); }
        return "?" + (cut > 0 ? text.substring(0, cut) : text);
    }

    /** Drop the "12.345 E " prefix Diag.format writes. */
    private static String strip(String line)
    {
        int space = line.indexOf(' ');
        if (space < 0 || space + 3 > line.length()) { return line; }
        return line.substring(space + 3);
    }
}
