package tgtest;

import java.io.IOException;
import java.util.Vector;

import tg.io.HttpExecutor;
import tg.io.HttpResponse;
import tg.plat.HttpReportSink;
import tg.plat.Report;
import tg.tl.Utf8;

/**
 * Report assembly, redaction, chunking and upload.
 *
 * The redaction cases are the point of this suite. A diagnostic report is the
 * one thing in this project that deliberately leaves the handset, so anything
 * that could carry account material off it has to be proven to be masked here,
 * on the desktop, rather than discovered later in a collector file.
 *
 * The transport cases all come from measurements on a Samsung GT-C3592; each
 * one names the behaviour it was written against.
 */
public final class ReportTest implements Test
{
    public String name() { return "diag/report-upload"; }

    public void run() throws Exception
    {
        redactsHexRuns();
        redactsSensitiveKeys();
        redactsPhoneNumbers();
        keepsOrdinaryMeasurements();
        composeCarriesHeaderAndBody();
        splitsOnLineBoundaries();
        utf8LengthMatchesEncoder();
        sinkPostsEveryPart();
        sinkSurvivesTransportFailure();
        sinkReportsHttpError();
        sinkShrinksWhenTheHandsetRefusesTheSize();
        sinkDoesNotShrinkOnADeadNetwork();
        sinkRetriesAFlakyLink();
        sinkDoesNotRetryAStaleEndpoint();
        crashTextSurvivesNonLatinRoundTrip();
    }

    // ----------------------------------------------------------- redaction

    private void redactsHexRuns()
    {
        // An auth_key fragment as Diag.hex would render it.
        String dump = "rx 0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f6071 tail";
        String out = Report.redact(dump);
        Assert.isTrue("long hex run must not survive", out.indexOf("0a1b2c3d") < 0);
        Assert.isTrue("length is kept as a diagnostic", out.indexOf("<hex:") >= 0);
        Assert.isTrue("surrounding text is kept", out.indexOf("tail") >= 0);

        // Space-separated dumps are the common shape and must not slip through
        // just because the digits are not contiguous.
        String spaced = "0a 1b 2c 3d 4e 5f 60 71 82 93 a4 b5 c6 d7 e8 f9 0a 1b";
        Assert.isTrue("spaced hex dump is masked",
                Report.redact(spaced).indexOf("<hex:") >= 0);
    }

    private void redactsSensitiveKeys()
    {
        Assert.isTrue("api_hash value masked",
                Report.redact("api_hash=deadbeefcafe").indexOf("deadbeef") < 0);
        Assert.isTrue("auth_key value masked",
                Report.redact("auth_key: 12345678").indexOf("12345678") < 0);
        Assert.isTrue("password value masked",
                Report.redact("password=hunter2 next").indexOf("hunter2") < 0);
        Assert.isTrue("text after the value is kept",
                Report.redact("password=hunter2 next").indexOf("next") >= 0);
        Assert.isTrue("key name survives so the line still reads",
                Report.redact("api_hash=deadbeefcafe").indexOf("api_hash") >= 0);

        // Case-insensitive: log lines are not written to a house style.
        Assert.isTrue("uppercase key masked",
                Report.redact("API_HASH=deadbeefcafe").indexOf("deadbeef") < 0);
    }

    private void redactsPhoneNumbers()
    {
        String out = Report.redact("sign-in for +79161234567 started");
        Assert.isTrue("subscriber number masked", out.indexOf("79161234567") < 0);
        Assert.isTrue("marker present", out.indexOf("<phone>") >= 0);
        Assert.isTrue("context kept", out.indexOf("started") >= 0);
        // The separator scan must not eat the space that ends the number.
        Assert.equal("word boundary after the number survives",
                "sign-in for <phone> started", out);

        // Grouped numbers are still one number.
        Assert.isTrue("grouped number masked",
                Report.redact("+7 916 123 45 67 ok").indexOf("916") < 0);

        // A short "+" run is not a phone number; masking it would eat real data.
        Assert.equal("short plus-number untouched",
                "delta +42 ms", Report.redact("delta +42 ms"));
    }

    private void keepsOrdinaryMeasurements()
    {
        // The whole exercise is worthless if the numbers we came for get eaten.
        String[] intact = {
            "startTotal = 2097152",
            "largestSingleAlloc = 262144 (256 KB)",
            "heapCost=163840 (160 KB)",
            "modPow 2048-bit: 18342 ms",
            "decoded 256x160 in 412ms",
            "hitOutOfMemory = true"
        };
        for (int i = 0; i < intact.length; i++)
        {
            Assert.equal("measurement survives redaction", intact[i],
                    Report.redact(intact[i]));
        }
    }

    // ------------------------------------------------------------- compose

    private void composeCarriesHeaderAndBody()
    {
        String text = Report.compose("probe", "Heap probe", new String[] {
            "startTotal = 2097152",
            null,
            "hitOutOfMemory = true"
        });

        Assert.isTrue("target recorded", text.indexOf("target=probe") >= 0);
        Assert.isTrue("section recorded", text.indexOf("section=Heap probe") >= 0);
        Assert.isTrue("build recorded", text.indexOf("version=") >= 0);
        Assert.isTrue("heap recorded", text.indexOf("heapTotal=") >= 0);
        Assert.isTrue("body present", text.indexOf("hitOutOfMemory = true") >= 0);
        Assert.isTrue("null entries skipped", text.indexOf("null") < 0);
    }

    // ------------------------------------------------------------ chunking

    private void splitsOnLineBoundaries()
    {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < 400; i++)
        {
            sb.append("line ").append(i).append(" of the transcript\n");
        }
        String text = sb.toString();

        String[] parts = Report.splitRaw(text, 1024);
        Assert.isTrue("split into several parts", parts.length > 1);

        StringBuffer rejoined = new StringBuffer();
        for (int i = 0; i < parts.length; i++)
        {
            Assert.isTrue("part respects the budget",
                    Report.utf8Length(parts[i]) <= 1024);
            rejoined.append(parts[i]);
        }
        // The strongest statement available: concatenation is the original,
        // so nothing was lost, duplicated or reordered.
        Assert.equal("parts rejoin to exactly the input", text, rejoined.toString());

        String[] single = Report.splitRaw("short\n", 1024);
        Assert.equal("small report stays whole", 1, single.length);
        Assert.equal("and is passed through untouched", "short\n", single[0]);
    }

    private void utf8LengthMatchesEncoder()
    {
        // A disagreement here would size the chunk budget wrongly for exactly
        // the emoji-bearing lines that need it most.
        String[] samples = {
            "plain ascii",
            "éè latin-1 supplement",
            "русский",
            "😀 surrogate pair",
            ""
        };
        for (int i = 0; i < samples.length; i++)
        {
            Assert.equal("utf8Length agrees with Utf8.encode",
                    Utf8.encode(samples[i]).length, Report.utf8Length(samples[i]));
        }
    }

    // ---------------------------------------------------------------- sink

    private void sinkPostsEveryPart() throws Exception
    {
        RecordingExecutor http = new RecordingExecutor(200);
        HttpReportSink sink = new HttpReportSink(http, "http://sink/r/tok", "gt-c3592", 0);

        String[] body = new String[600];
        for (int i = 0; i < body.length; i++)
        {
            body[i] = "measurement line number " + i + " with some padding text";
        }

        Assert.isTrue("send reports success", sink.send("probe", "Heap probe", body));
        Assert.isTrue("more than one POST was needed", http.urls.size() > 1);
        Assert.equal("URL carries target and device",
                "http://sink/r/tok/probe/gt-c3592", (String) http.urls.elementAt(0));
        Assert.isTrue("no error recorded", sink.lastError() == null);

        for (int i = 0; i < http.bodies.size(); i++)
        {
            byte[] sent = (byte[]) http.bodies.elementAt(i);
            Assert.isTrue("each POST respects the collector's body cap",
                    sent.length <= 64 * 1024);
        }
    }

    private void sinkSurvivesTransportFailure()
    {
        HttpReportSink sink = new HttpReportSink(new HttpExecutor()
        {
            public HttpResponse post(String url, byte[] body, int maxResponse)
                    throws IOException
            {
                throw new IOException("no data session");
            }
        }, "http://sink/r/tok", "gt-c3592", 0);

        // A dead network must never propagate out of the reporting path: the
        // handset is usually being diagnosed precisely because something else
        // is already wrong.
        Assert.isFalse("failure is reported, not thrown",
                sink.send("probe", "Platform", new String[] { "x" }));
        Assert.isTrue("reason is available for the screen", sink.lastError() != null);
    }

    private void sinkReportsHttpError()
    {
        HttpReportSink sink = new HttpReportSink(new RecordingExecutor(404),
                "http://sink/r/wrong", "gt-c3592", 0);
        Assert.isFalse("404 is a failure", sink.send("probe", "Platform",
                new String[] { "x" }));
        Assert.isTrue("404 explains the likely cause",
                sink.lastError().indexOf("stale endpoint") >= 0);
    }

    /**
     * Some handsets and carrier gateways cap a POST body and fail inside the
     * request rather than answering, so the sink has to find the ceiling itself.
     */
    private void sinkShrinksWhenTheHandsetRefusesTheSize()
    {
        final int ceiling = 1500;
        CeilingExecutor http = new CeilingExecutor(ceiling);
        HttpReportSink sink = new HttpReportSink(http, "http://sink/r/tok", "gt-c3592", 0);

        String[] body = new String[400];
        for (int i = 0; i < body.length; i++)
        {
            body[i] = "measurement line " + i + " with enough text to add up";
        }

        Assert.isTrue("report gets through despite the ceiling",
                sink.send("probe", "Diagnostic log", body));
        Assert.isTrue("something was refused before it fitted",
                sink.refusedChunkBytes() > 0);
        Assert.isTrue("every delivered body is under the handset's ceiling",
                http.largestAccepted <= ceiling);
        Assert.equal("the accepted size is the real largest POST",
                http.largestAccepted, sink.acceptedChunkBytes());
        Assert.isTrue("the refused size brackets the ceiling from above",
                sink.refusedChunkBytes() > ceiling);

        // A second section must not repeat the whole search: the discovered
        // size is remembered, so this one goes through with nothing refused.
        int refusedAfterFirst = sink.refusedChunkBytes();
        Assert.isTrue("a later section still gets through",
                sink.send("probe", "Crash log", body));
        Assert.equal("and it did not have to rediscover the ceiling",
                refusedAfterFirst, sink.refusedChunkBytes());
    }

    /**
     * A dead network must not be mistaken for a size limit: halving the payload
     * against a phone with no data session just costs the user more timeouts.
     */
    private void sinkDoesNotShrinkOnADeadNetwork()
    {
        final int[] attempts = new int[1];
        HttpReportSink sink = new HttpReportSink(new HttpExecutor()
        {
            public HttpResponse post(String url, byte[] body, int maxResponse)
                    throws IOException
            {
                attempts[0]++;
                // The stage label MidpHttpExecutor produces when it never got
                // as far as sending anything.
                throw new IOException("HTTP open: java.io.IOException");
            }
        }, "http://sink/r/tok", "gt-c3592", 0);

        String[] body = new String[400];
        for (int i = 0; i < body.length; i++) { body[i] = "line " + i + " padding padding"; }

        Assert.isFalse("send fails", sink.send("probe", "Diagnostic log", body));
        // Retried, because a dropped connection is indistinguishable from a
        // flaky one - but bounded, and with no size search on top of it.
        Assert.equal("bounded by the retry limit, not multiplied by a size search",
                3, attempts[0]);
        Assert.equal("nothing was recorded as refused for size",
                0, sink.refusedChunkBytes());
    }

    /**
     * Measured: two small single-part sections failed between larger ones that
     * succeeded, so neither size nor the collector explained it - individual
     * POSTs simply drop on that link and the transport had no retry.
     */
    private void sinkRetriesAFlakyLink()
    {
        final int[] calls = new int[1];
        HttpReportSink sink = new HttpReportSink(new HttpExecutor()
        {
            public HttpResponse post(String url, byte[] body, int maxResponse)
                    throws IOException
            {
                calls[0]++;
                if (calls[0] == 1)
                {
                    throw new IOException("HTTP request-body: java.io.IOException");
                }
                return new HttpResponse(200, Assert.ascii("ok"));
            }
        }, "http://sink/r/tok", "gt-c3592", 0);

        Assert.isTrue("a single dropped POST does not lose the section",
                sink.send("probe", "RMS", new String[] { "largest record = 65536" }));
        Assert.equal("it took exactly one retry", 2, calls[0]);
        Assert.isTrue("no stale error is left behind", sink.lastError() == null);
    }

    /** A wrong token or moved endpoint will not fix itself; retrying costs data. */
    private void sinkDoesNotRetryAStaleEndpoint()
    {
        final int[] calls = new int[1];
        HttpReportSink sink = new HttpReportSink(new HttpExecutor()
        {
            public HttpResponse post(String url, byte[] body, int maxResponse)
            {
                calls[0]++;
                return new HttpResponse(404, Assert.ascii("not found"));
            }
        }, "http://sink/r/wrong", "gt-c3592", 0);

        Assert.isFalse("send fails", sink.send("probe", "RMS", new String[] { "x" }));
        Assert.equal("and it only asked once", 1, calls[0]);
    }

    // ------------------------------------------------------------ encoding

    /**
     * A crash entry naming a Cyrillic chat came back as "?????" from a handset
     * whose microedition.encoding is ISO8859-1: String.getBytes() destroyed the
     * characters on the way into RMS. CrashLog uses Utf8 at both ends now, and
     * caps an entry on a character boundary.
     */
    private void crashTextSurvivesNonLatinRoundTrip()
    {
        String cyrillic = "рабочий чат";
        String[] samples = { cyrillic, "plain ascii", "éè latin-1" };
        for (int i = 0; i < samples.length; i++)
        {
            Assert.equal("crash text round-trips through Utf8",
                    samples[i], Utf8.decode(Utf8.encode(samples[i])));
        }

        // Every possible cut point: backing off over continuation bytes, the
        // way CrashLog caps an entry, must never leave a sequence half written.
        byte[] encoded = Utf8.encode(cyrillic);
        for (int cut = 0; cut <= encoded.length; cut++)
        {
            int end = cut;
            while (end > 0 && end < encoded.length
                   && (encoded[end] & 0xc0) == 0x80)
            {
                end--;
            }
            byte[] head = new byte[end];
            System.arraycopy(encoded, 0, head, 0, end);
            Assert.isTrue("truncated crash text decodes without U+FFFD",
                    Utf8.decode(head).indexOf('�') < 0);
        }
    }

    // -------------------------------------------------------------- helper

    /**
     * A handset that refuses anything over a fixed size, the way a carrier
     * gateway does: the failure happens while writing the body, not as a
     * status, so it is indistinguishable from a network fault except by the
     * stage label.
     */
    private static final class CeilingExecutor implements HttpExecutor
    {
        private final int ceiling;
        int largestAccepted;

        CeilingExecutor(int ceiling) { this.ceiling = ceiling; }

        public HttpResponse post(String url, byte[] body, int maxResponse)
                throws IOException
        {
            if (body.length > ceiling)
            {
                throw new IOException("HTTP request-body: java.io.IOException");
            }
            if (body.length > largestAccepted) { largestAccepted = body.length; }
            return new HttpResponse(200, Assert.ascii("ok"));
        }
    }

    /** Captures what would have gone on the wire. */
    private static final class RecordingExecutor implements HttpExecutor
    {
        final Vector urls = new Vector();
        final Vector bodies = new Vector();
        private final int status;

        RecordingExecutor(int status) { this.status = status; }

        public HttpResponse post(String url, byte[] body, int maxResponse)
        {
            urls.addElement(url);
            bodies.addElement(body);
            return new HttpResponse(status, Assert.ascii("ok"));
        }
    }
}
