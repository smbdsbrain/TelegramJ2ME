package tgtest;

import java.io.IOException;

import tg.crypto.Rng;
import tg.io.FakeTlsTransport;
import tg.io.Transport;
import tg.plat.ClockProbe;
import tg.plat.DisplayProbe;
import tg.plat.TextProbe;

/**
 * The measurements added for the Nokia C3-00 session, and the error message
 * that session showed was unreadable.
 *
 * A probe is only worth installing if it terminates and says something. These
 * cannot assert handset figures - a desktop clock is not a handset clock, which
 * is the whole reason the probes exist - so they assert the properties that
 * would make a probe useless on the device: that it finishes, that it reports
 * every stage it claims to, and that a failure inside it is reported rather
 * than thrown into the sweep.
 */
public final class ProbeExtrasTest implements Test
{
    public String name() { return "probe/clock-text-display"; }

    public void run() throws Exception
    {
        // The text probe exercises an RMS round trip; without a record store
        // that stage degrades to "threw NullPointerException" and proves
        // nothing. It writes and deletes its own store.
        EmulatorHarness.installRecordStore();

        theClockProbeTerminatesAndReports();
        theTextProbeCoversEveryLayer();
        theDisplayProbeWorksWithoutADisplay();
        theProbeMenuAgreesWithItsIndices();
        aTlsAlertNamesItself();
        anIllegalParameterAlertNamesTheClock();
        aTruncatedAlertStillReports();
        aTruncatedRecordIsNotCalledAnAlert();
    }

    /** A rejected timestamp must not send the user looking at signal bars. */
    private static void anIllegalParameterAlertNamesTheClock()
    {
        byte[] alert = { 0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x2f };
        String message = handshakeFailure(alert);

        Assert.isTrue("illegal_parameter is named (" + message + ")",
                message.indexOf("illegal_parameter(47)") >= 0);
        Assert.isTrue("the phone clock is actionable (" + message + ")",
                message.indexOf("phone date, time") >= 0);
        Assert.isTrue("an obsolete time zone is actionable (" + message + ")",
                message.indexOf("time zone") >= 0);
    }

    /**
     * The menu is a String[] and a parallel set of int constants, dispatched
     * through a switch on the selected index. Inserting an entry without moving
     * every constant below it silently reassigns menu items to the wrong
     * measurement - and the place that would be discovered is a handset, in the
     * middle of a session, with the wrong result already uploaded.
     */
    private static void theProbeMenuAgreesWithItsIndices() throws Exception
    {
        Class midlet = Class.forName("tg.app.ProbeMidlet");
        String[] items = (String[]) field(midlet, "MENU_ITEMS");

        // name -> the label that index must carry.
        String[][] expected = {
            { "ITEM_PLATFORM",   "Platform & build" },
            { "ITEM_HEAP",       "Heap probe" },
            { "ITEM_RMS",        "RMS test" },
            { "ITEM_ENTROPY",    "Entropy measure" },
            { "ITEM_BARRIER",    "Seeding barrier" },
            { "ITEM_VECTORS",    "Crypto vectors" },
            { "ITEM_BENCH",      "Crypto benchmarks" },
            { "ITEM_PBKDF2",     "PBKDF2 x100000" },
            { "ITEM_CLOCK",      "Clock & timers" },
            { "ITEM_TEXT",       "Text round trip" },
            { "ITEM_DISPLAY",    "Display caps" },
            { "ITEM_KEYS",       "Keys" },
            { "ITEM_KEYTIME",    "Key timing" },
            { "ITEM_CANVAS",     "Display size" },
            { "ITEM_NET",        "Public TCP echo" },
            { "ITEM_TG_80",      "Telegram DC socket :80" },
            { "ITEM_TG_443",     "Telegram DC socket :443" },
            { "ITEM_TG_5222",    "Telegram DC socket :5222" },
            { "ITEM_TG_8443",    "Telegram DC socket :8443" },
            { "ITEM_TWO_SOCK",   "Two sockets at once" },
            { "ITEM_IMAGE",      "PNG / JPEG decode" },
            { "ITEM_EMOJI",      "Emoji sheet cost" },
            { "ITEM_BG",         "Background socket" },
            { "ITEM_LOG",        "Diagnostic log" },
            { "ITEM_CRASH",      "Crash log" },
            { "ITEM_UPLOAD_ALL", "Upload all" }
        };

        Assert.equal("every menu entry has a constant", expected.length,
                items.length);

        for (int i = 0; i < expected.length; i++)
        {
            int index = ((Integer) field(midlet, expected[i][0])).intValue();
            Assert.equal(expected[i][0] + " points at its own menu entry",
                    expected[i][1], items[index]);
        }
    }

    private static Object field(Class owner, String name) throws Exception
    {
        java.lang.reflect.Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    /**
     * Bounded is the requirement. It runs inside "Upload all" on a handset that
     * may already be struggling, and a clock probe that spins waiting for a tick
     * that never comes would hang the sweep with no result at all.
     */
    private static void theClockProbeTerminatesAndReports()
    {
        long t0 = System.currentTimeMillis();
        String[] lines = ClockProbe.run();
        long elapsed = System.currentTimeMillis() - t0;

        Assert.isTrue("the clock probe returns lines", lines.length > 0);
        Assert.isTrue("every line is set", noNulls(lines));
        Assert.isTrue("it finishes in well under a minute (" + elapsed + " ms)",
                elapsed < 30000);

        Assert.isTrue("it reports the clock tick",
                contains(lines, "currentTimeMillis tick"));
        Assert.isTrue("it reports sleep accuracy", contains(lines, "min/avg/max"));
        Assert.isTrue("it reports backwards steps",
                contains(lines, "backwards steps"));
        Assert.isTrue("it states a verdict", contains(lines, "VERDICT"));

        // A desktop clock moves forwards; if this ever fails here the probe is
        // miscounting rather than the machine misbehaving.
        Assert.isTrue("no backwards step is seen on a desktop JVM",
                contains(lines, "backwards steps = 0"));
    }

    private static void theTextProbeCoversEveryLayer()
    {
        String[] lines = TextProbe.run();

        Assert.isTrue("the text probe returns lines", lines.length > 0);
        Assert.isTrue("every line is set", noNulls(lines));
        Assert.isTrue("it names the platform encoding",
                contains(lines, "encoding="));
        Assert.isTrue("it reports the Utf8 layer",
                contains(lines, "Utf8 encode/decode"));
        Assert.isTrue("it reports the platform conversion",
                contains(lines, "String.getBytes"));
        Assert.isTrue("it reports the RMS layer",
                contains(lines, "RMS record round trip"));
        Assert.isTrue("it reports the upload layer",
                contains(lines, "Report compose/redact"));

        // The project's own converter and the report path must both be correct
        // here, whatever the platform does - a desktop failure would mean the
        // handset never had a chance.
        Assert.isTrue("Utf8 round trips on this JVM",
                contains(lines, "Utf8 encode/decode: PASS"));
        Assert.isTrue("the report path preserves non-Latin text",
                contains(lines, "Report compose/redact: survives"));

        // The probe's own output is entirely hex, and the report redacts runs
        // of 32+ hex digits. The first device run came back with
        // "expect: <hex:46>" - a probe that could not report what it measured,
        // and that would have hidden the bytes of any stage that failed.
        String[] redacted = new String[lines.length];
        for (int i = 0; i < lines.length; i++)
        {
            redacted[i] = tg.plat.Report.redact(lines[i]);
        }
        Assert.isFalse("the probe's own hex survives redaction",
                contains(redacted, "<hex:"));
        Assert.isTrue("and the expected bytes are still shown",
                contains(redacted, "expect: 63616" ));
    }

    /**
     * Display is null off-device. The probe still has to produce the font half,
     * because that is the half that does not need a screen and the sweep runs
     * it either way.
     */
    private static void theDisplayProbeWorksWithoutADisplay()
    {
        String[] lines = DisplayProbe.run(null);

        Assert.isTrue("the display probe returns lines", lines.length > 0);
        Assert.isTrue("every line is set", noNulls(lines));
        Assert.isTrue("it says why colours are missing",
                contains(lines, "no Display"));
        Assert.isTrue("it still reports fonts", contains(lines, "fonts"));
        Assert.isTrue("it points at the canvas screen",
                contains(lines, "canvas size"));
    }

    /**
     * The message a Nokia C3-00 produced twice, and what was wrong with it.
     *
     * "invalid FakeTLS handshake record" named nothing: a proxy rejecting the
     * ClientHello with a TLS alert, a truncated read, and a server answering
     * something else all produced that one sentence, so the log could not be
     * acted on.
     */
    private static void aTlsAlertNamesItself()
    {
        // 0x15 = alert, TLS 1.2 record version, body = fatal(2)
        // handshake_failure(40). Exactly the shape a Nokia C3-00 received from
        // a working MTProxy.
        byte[] alert = { 0x15, 0x03, 0x03, 0x00, 0x02, 0x02, 0x28 };
        String message = handshakeFailure(alert);

        Assert.isTrue("the alert is named as an alert (" + message + ")",
                message.indexOf("alert") >= 0);
        Assert.isTrue("the record type is given", message.indexOf("0x15") >= 0);
        Assert.isTrue("the length is given", message.indexOf("length 2") >= 0);
        Assert.isTrue("the expected type is given",
                message.indexOf("expected type 0x16") >= 0);

        // The reason is in the body, and reading it is the difference between
        // "the proxy refused" and "the proxy refused because".
        Assert.isTrue("the alert level is decoded (" + message + ")",
                message.indexOf("fatal") >= 0);
        Assert.isTrue("the alert description is named",
                message.indexOf("handshake_failure") >= 0);
        Assert.isTrue("and its number is kept", message.indexOf("(40)") >= 0);
    }

    /** An alert whose body never arrives must not hang or hide the record. */
    private static void aTruncatedAlertStillReports()
    {
        byte[] headerOnly = { 0x15, 0x03, 0x03, 0x00, 0x02 };
        String message = handshakeFailure(headerOnly);

        Assert.isTrue("the record is still described (" + message + ")",
                message.indexOf("0x15") >= 0);
        Assert.isTrue("and the missing body is said so",
                message.indexOf("unreadable") >= 0);
    }

    /** A different failure has to read differently, or the message is noise. */
    private static void aTruncatedRecordIsNotCalledAnAlert()
    {
        // A plausible handshake type with a nonsense version: what a torn read
        // or a non-TLS server looks like.
        byte[] garbage = { 0x16, 0x00, 0x00, 0x00, 0x01, 0x00 };
        String message = handshakeFailure(garbage);

        Assert.isTrue("it is not reported as an alert (" + message + ")",
                message.indexOf("alert") < 0);
        Assert.isTrue("the version actually seen is reported",
                message.indexOf("version 0.0") >= 0);
    }

    /** Run the FakeTLS handshake against a canned response and take the error. */
    private static String handshakeFailure(byte[] response)
    {
        byte[] secret = new byte[16];
        for (int i = 0; i < secret.length; i++) { secret[i] = (byte) i; }

        FakeTlsTransport tls = new FakeTlsTransport(new CannedTransport(response),
                new Rng(), secret, "example.com");
        try
        {
            tls.connect("example.com", 443, 1000);
            Assert.fail("the handshake should not have completed");
            return "";
        }
        catch (IOException e)
        {
            return String.valueOf(e.getMessage());
        }
    }

    private static boolean contains(String[] lines, String needle)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] != null && lines[i].indexOf(needle) >= 0) { return true; }
        }
        return false;
    }

    private static boolean noNulls(String[] lines)
    {
        for (int i = 0; i < lines.length; i++)
        {
            if (lines[i] == null) { return false; }
        }
        return true;
    }

    /** Swallows whatever is written and replays one fixed response. */
    private static final class CannedTransport implements Transport
    {
        private final byte[] canned;
        private int at;

        CannedTransport(byte[] canned) { this.canned = canned; }

        public void connect(String host, int port, int timeoutMs) { }

        public int read(byte[] buf, int off, int len)
        {
            int n = Math.min(len, canned.length - at);
            if (n <= 0) { return -1; }
            System.arraycopy(canned, at, buf, off, n);
            at += n;
            return n;
        }

        public void readFully(byte[] buf, int off, int len) throws IOException
        {
            int got = 0;
            while (got < len)
            {
                int n = read(buf, off + got, len - got);
                if (n < 0) { throw new IOException("canned eof"); }
                got += n;
            }
        }

        public void write(byte[] buf, int off, int len) { }
        public void flush() { }
        public boolean isConnected() { return true; }
        public void close() { }
        public long bytesRead() { return at; }
        public long bytesWritten() { return 0; }
    }
}
