package tg.plat;

import tg.diag.Diag;

/**
 * Runs a report upload off the UI thread and narrates it back.
 *
 * All three MIDlets need the same four behaviours - resolve the sink, refuse
 * politely when the build has none, send on a worker, and say what happened -
 * and none of them should be reimplementing them. The lcdui dependency stays on
 * the caller's side of {@link Progress}, so this class is testable on the
 * desktop and drags no UI into a build that does not want it.
 *
 * Worker thread is not optional. MIDP's HttpConnection exposes no timeout, so a
 * handset with no data session can block for a long time inside
 * Connector.open; doing that in a lcdui callback freezes the display, and some
 * AMS implementations treat that as a hung MIDlet and kill it - which would end
 * the very session being diagnosed.
 */
public final class ReportUpload
{
    /** Sink for progress text; typically a TextScreen's setLines. */
    public interface Progress
    {
        void lines(String[] text);
    }

    private ReportUpload() { }

    /**
     * Upload one section. Returns immediately; progress arrives on a worker.
     *
     * @param target "probe", "crypto" or "tg"
     */
    public static void send(final String target, final String section,
                            final String[] body, final Progress progress)
    {
        final HttpReportSink sink = HttpReportSink.createDefault();
        if (sink == null)
        {
            progress.lines(noSinkMessage());
            return;
        }

        progress.lines(new String[] {
            "sending \"" + section + "\"...",
            "",
            "this can take a while on GPRS.",
            "do not exit."
        });

        new Thread(new Runnable()
        {
            public void run()
            {
                boolean ok;
                try
                {
                    ok = sink.send(target, section, body);
                }
                catch (Throwable t)
                {
                    // HttpReportSink already swallows its own failures; this
                    // catch is for the thread itself, because an uncaught
                    // Throwable on a worker is silent on most handsets.
                    Diag.error("upload " + section, t);
                    progress.lines(new String[] {
                        "FAILED: " + section, "", Diag.className(t),
                        String.valueOf(t.getMessage())
                    });
                    return;
                }

                progress.lines(ok
                        ? new String[] { "sent: " + section, "", "collector accepted it." }
                        : new String[] { "FAILED: " + section, "",
                                         String.valueOf(sink.lastError()) });
            }
        }).start();
    }

    /**
     * What to show when this build has no sink compiled in.
     *
     * Not an error: every published artifact is built without
     * secrets/dev-sink.yaml and must be, so this is the expected state for
     * anyone who did not set one up.
     */
    public static String[] noSinkMessage()
    {
        return new String[] {
            "no report sink in this build.",
            "",
            "this is normal for a published",
            "artifact. to enable uploads, build",
            "with secrets/dev-sink.yaml present.",
            "",
            "results are still on screen."
        };
    }
}
