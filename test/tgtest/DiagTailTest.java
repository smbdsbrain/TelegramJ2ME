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
     * Slow enough that the ring cannot wrap between polls, which is the regime
     * the driver actually runs in.
     */
    private static void everyLineArrivesOnceAndInOrder() throws Exception
    {
        Diag.clear();
        File log = File.createTempFile("tg-tail-order", ".log");
        log.deleteOnExit();
        DiagTail.watch("marker");
        Assert.isTrue("the tail started", DiagTail.start(log) != null);

        final int lines = 400;
        for (int i = 0; i < lines; i++)
        {
            Diag.info("marker " + i);
            // The ring holds a hundred and the tail polls four times a second,
            // so this has to be paced or the test is measuring the gap path
            // rather than the ordinary one.
            if ((i % 20) == 19) { Thread.sleep(120); }
        }
        Thread.sleep(600);
        DiagTail.stop();

        List<String> recorded = read(log);
        Assert.equal("no line was lost", 0, DiagTail.lostLines());
        Assert.equal("every line was recorded", lines, recorded.size());
        Assert.equal("the counter agrees with the file", lines,
                DiagTail.writtenLines());
        Assert.equal("the watched marker was counted", lines,
                DiagTail.count("marker"));

        Set<String> seen = new HashSet<String>();
        for (int i = 0; i < recorded.size(); i++)
        {
            String line = recorded.get(i);
            Assert.isTrue("no line appears twice: " + line, seen.add(line));
            Assert.isTrue("line " + i + " is the one appended there: " + line,
                    line.endsWith("marker " + i));
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
        for (int i = 0; i < lines; i++) { Diag.info("flood " + i); }
        Thread.sleep(600);
        DiagTail.stop();

        List<String> recorded = read(log);
        int lost = DiagTail.lostLines();
        Assert.isTrue("the flood outran the tail", lost > 0);

        int markers = 0;
        for (int i = 0; i < recorded.size(); i++)
        {
            if (recorded.get(i).startsWith("<lost ")) { markers++; }
        }
        Assert.isTrue("the hole is marked in the log itself", markers > 0);
        Assert.equal("recorded plus lost accounts for every line", lines,
                recorded.size() - markers + lost);
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
