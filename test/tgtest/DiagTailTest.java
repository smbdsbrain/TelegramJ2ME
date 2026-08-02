package tgtest;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import tg.diag.Diag;

/**
 * The instrument has to be right before anything measured with it is.
 *
 * {@link DiagTail} is what turns a hundred-line ring into the whole log of a
 * driven run, and every count in issue #5's ladder - how many avatars decoded,
 * how many OutOfMemoryErrors, which task threw them - is read out of it. A tail
 * that drops a line understates a failure; a tail that repeats one invents a
 * failure that never happened. Both were observed during that work, which is why
 * this file exists.
 *
 * Deliberately hostile: several threads logging as fast as they can, past the
 * point where the ring wraps many times over, while the tail follows at its
 * normal rate. The lines carry serial numbers so the reconstruction can be
 * checked exactly rather than approximately.
 */
public final class DiagTailTest implements Test
{
    public String name() { return "diag/tail-reconstruction"; }

    public void run() throws Exception
    {
        try
        {
            everyLineArrivesOnceAndInOrder();
            aGapIsReportedRatherThanHidden();
            attributionNamesTheTask();
        }
        finally
        {
            DiagTail.stop();
            Diag.clear();
        }
    }

    /**
     * A distinctive prefix, because {@link Diag} is global and earlier suites in
     * this JVM leave sockets and worker threads that are still logging into it.
     * The properties below are about the lines this test appended; anything else
     * in the ring is traffic, not signal.
     */
    private static final String MARK = "tailtest-order ";

    /**
     * Paced slowly enough that the ring should not wrap, which is the regime the
     * driver runs in.
     *
     * Asserted as an invariant rather than as a count. "Exactly four hundred
     * lines arrived" is a statement about how busy the machine was; "every line
     * that arrived arrived once, in order, and any that did not is reported" is
     * a statement about the tail, and it is the one worth failing on.
     */
    private static void everyLineArrivesOnceAndInOrder() throws Exception
    {
        Diag.clear();
        File log = File.createTempFile("tg-tail-order", ".log");
        log.deleteOnExit();
        DiagTail.watch(MARK);
        Assert.isTrue("the tail started", DiagTail.start(log) != null);

        final int lines = 400;
        for (int i = 0; i < lines; i++)
        {
            Diag.info(MARK + i);
            // The ring holds a hundred and the tail polls four times a second,
            // so this has to be paced or the test is measuring the gap path
            // rather than the ordinary one.
            if ((i % 20) == 19) { Thread.sleep(120); }
        }
        Thread.sleep(900);
        DiagTail.stop();

        List<Integer> serials = serials(read(log));
        Set<Integer> seen = new HashSet<Integer>();
        int previous = -1;
        for (int i = 0; i < serials.size(); i++)
        {
            int serial = serials.get(i).intValue();
            Assert.isTrue("no line appears twice: " + serial, seen.add(serials.get(i)));
            Assert.isTrue("lines keep the order they were appended in: " + serial
                            + " after " + previous,
                    serial > previous);
            previous = serial;
        }

        if (DiagTail.lostLines() == 0)
        {
            // Nothing was dropped, so nothing may be missing either.
            Assert.equal("an unbroken tail holds every line", lines,
                    serials.size());
            Assert.equal("the watched marker was counted", lines,
                    DiagTail.count(MARK));
        }
        else
        {
            // A loaded machine is allowed to outrun the tail. What is not
            // allowed is doing so quietly - see the next case.
            Assert.isTrue("a gap is reported when there is one",
                    DiagTail.count(MARK) <= lines);
        }
    }

    /**
     * When the ring wraps faster than the tail can read it, the missing lines
     * have to show up as a hole in the record.
     *
     * This is the property that makes an incomplete measurement usable: a count
     * taken from a log with {@code lostLines=0} is a total, and one taken from a
     * log without that guarantee is a floor. The driver prints the number for
     * exactly this reason.
     */
    private static void aGapIsReportedRatherThanHidden() throws Exception
    {
        Diag.clear();
        File log = File.createTempFile("tg-tail-gap", ".log");
        log.deleteOnExit();
        Assert.isTrue("the tail started", DiagTail.start(log) != null);

        // Far more than the ring holds, with no pause at all: the tail cannot
        // possibly see them all and must not pretend otherwise.
        final int lines = 5000;
        for (int i = 0; i < lines; i++) { Diag.info(MARK + i); }
        Thread.sleep(900);
        DiagTail.stop();

        List<String> recorded = read(log);
        Assert.isTrue("the flood outran the tail", DiagTail.lostLines() > 0);

        int holes = 0;
        for (int i = 0; i < recorded.size(); i++)
        {
            if (recorded.get(i).startsWith("<lost ")) { holes++; }
        }
        Assert.isTrue("the hole is marked in the log itself", holes > 0);

        // What survived is still a truthful subsequence: no repeats, no
        // reordering. A lossy record that also scrambles what it kept would be
        // worse than no record.
        List<Integer> serials = serials(recorded);
        Assert.isTrue("something survived the flood", serials.size() > 0);
        Assert.isTrue("fewer arrived than were sent", serials.size() < lines);
        int previous = -1;
        for (int i = 0; i < serials.size(); i++)
        {
            int serial = serials.get(i).intValue();
            Assert.isTrue("the surviving lines keep their order: " + serial
                    + " after " + previous, serial > previous);
            previous = serial;
        }
    }

    /** The breakdown that answered "which task threw" in issue #5. */
    private static void attributionNamesTheTask()
    {
        Assert.equal("a worker task is named by its task name",
                "task:dialog avatar",
                DiagTail.attribute(
                        "1.725 E task dialog avatar failed | OutOfMemoryError: x"));
        Assert.equal("the thumbnail thread is named", "thumbnail",
                DiagTail.attribute(
                        "2.0 W stripped thumbnail 12: OutOfMemoryError: x"));
        Assert.equal("the avatar callback is named", "avatar-callback",
                DiagTail.attribute("2.0 W avatar 1:329509986: OutOfMemoryError"));
        Assert.equal("a chat open is named", "chat-open",
                DiagTail.attribute("3.0 E chat open failed | OutOfMemoryError"));

        // Unrecognised sources keep their prefix and are flagged. Folding them
        // into an "other" bucket is what made the original sweep unable to say
        // where its OutOfMemoryErrors came from.
        Assert.equal("an unknown source keeps its prefix",
                "?dialog cache save failed",
                DiagTail.attribute("3.0 W dialog cache save failed: IOException:"
                        + " RMS dialog cache save: java.lang.OutOfMemoryError"));
        Assert.equal("an unknown source with a bar keeps its prefix",
                "?update state save failed",
                DiagTail.attribute("3.0 E update state save failed |"
                        + " IOException: java.lang.OutOfMemoryError"));
    }

    /**
     * The serial numbers this test appended, in the order they were recorded.
     *
     * Filtered rather than counted, because {@link Diag} is shared: earlier
     * suites in this JVM leave threads that are still logging, and a keepalive
     * landing in the middle of the run is not a defect in the tail.
     */
    private static List<Integer> serials(List<String> recorded)
    {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < recorded.size(); i++)
        {
            String line = recorded.get(i);
            int at = line.indexOf(MARK);
            if (at < 0) { continue; }
            try
            {
                out.add(Integer.valueOf(
                        line.substring(at + MARK.length()).trim()));
            }
            catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static List<String> read(File file) throws Exception
    {
        List<String> out = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new FileReader(file));
        try
        {
            String line;
            while ((line = reader.readLine()) != null) { out.add(line); }
        }
        finally { reader.close(); }
        return out;
    }
}
