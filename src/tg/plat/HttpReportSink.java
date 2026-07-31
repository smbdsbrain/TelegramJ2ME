package tg.plat;

import tg.app.DevSink;
import tg.diag.Diag;
import tg.io.HttpExecutor;
import tg.io.HttpResponse;
import tg.tl.Utf8;

/**
 * Uploads a {@link Report} to the development collector over plain HTTP.
 *
 * Development only, like {@link TcpLogSink}, and subject to the same rule: it
 * carries formatted diagnostic text and nothing else - never Telegram protocol
 * traffic. The collector (tools/ingest-server.py) implements no protocol and
 * stores what it is given.
 *
 * HTTP rather than the existing TCP sink because this has to work from a
 * handset on a carrier APN, where an arbitrary high port is the first thing to
 * be blocked and port 80 is the last. It reuses {@link tg.io.HttpExecutor}, so
 * a desktop test can drive the whole path with a fake and never open a socket.
 *
 * Blocking: MIDP's HttpConnection has no timeout control, so a send can sit for
 * as long as the runtime decides. Never call this from a lcdui callback.
 */
public final class HttpReportSink
{
    /** The collector answers "ok <path>"; anything larger is not for us. */
    private static final int MAX_RESPONSE = 1024;

    /**
     * Smallest POST worth attempting.
     *
     * Below this the per-request overhead dominates on GPRS and a report would
     * take dozens of round trips. A handset that cannot manage 512 bytes in one
     * POST cannot usefully report anything.
     */
    private static final int MIN_CHUNK_BYTES = 512;

    /**
     * Ceiling on halvings per report.
     *
     * 4 KiB down to 512 B is three steps; anything beyond that means the size
     * is not what is actually wrong, and a loop that keeps trying would burn
     * the user's data allowance looking for an answer that is not there.
     */
    private static final int MAX_SHRINKS = 6;

    /**
     * Attempts per piece before giving up on it.
     *
     * Measured need, not caution. On a GT-C3592 over GPRS six of eight sections
     * went through and two did not, and the two that failed were 482 and 473
     * byte single-part uploads sitting between successful ones of 1081 and 964
     * bytes - so it was neither size nor the collector. Individual POSTs simply
     * fail on this link, and the first version had no retry at all.
     */
    private static final int MAX_ATTEMPTS = 3;

    /**
     * Pause between attempts.
     *
     * Long enough for a GPRS radio to recover from whatever dropped the last
     * connection; short enough that eight sections do not turn into a coffee
     * break. Retries are cheap here - a report piece is under 4 KB.
     */
    private static final long RETRY_PAUSE_MS = 2000;

    private final HttpExecutor http;
    private final String base;
    private final String device;
    private final long retryPauseMs;

    private String lastError;

    /**
     * Largest body this handset has actually accepted, discovered as we go.
     *
     * The GT-C3590 took six sections and refused the two that needed more than
     * one 8 KiB piece, failing inside the request body rather than returning a
     * status - the signature of a carrier gateway or an HTTP stack with its own
     * POST ceiling, not of anything the collector said. That ceiling is not
     * discoverable in advance and differs per handset and per network, so it is
     * measured here: halve on refusal, and remember what worked so the next
     * section does not repeat the search.
     */
    private int chunkBudget = Report.MAX_CHUNK_BYTES;

    /** Smallest body that was refused, or 0. Reported as a device finding. */
    private int refusedAt;

    /** Largest body the handset actually accepted. The other half of the bracket. */
    private int acceptedAt;

    public HttpReportSink(HttpExecutor http, String base, String device)
    {
        this(http, base, device, RETRY_PAUSE_MS);
    }

    /**
     * @param retryPauseMs pause between attempts; 0 in tests, which would
     *                     otherwise spend most of their runtime asleep
     */
    public HttpReportSink(HttpExecutor http, String base, String device, long retryPauseMs)
    {
        this.http = http;
        this.base = base;
        this.device = (device == null || device.length() == 0) ? "unknown" : device;
        this.retryPauseMs = retryPauseMs;
    }

    /**
     * Sink described by the build, or null when this build has none.
     *
     * Null is the normal answer for a public artifact: no secrets/dev-sink.yaml
     * at build time means no endpoint is compiled in. Callers are expected to
     * tell the user that rather than fail silently.
     */
    public static HttpReportSink createDefault()
    {
        if (!DevSink.CONFIGURED) { return null; }
        return new HttpReportSink(new MidpHttpExecutor(), DevSink.HTTP_BASE, DevSink.DEVICE);
    }

    /** Reason the last send failed, or null. Safe to show on screen. */
    public String lastError()
    {
        return lastError;
    }

    /**
     * Compose, redact, split and upload one scenario's output.
     *
     * @return true when every part was accepted
     */
    public boolean send(String target, String section, String[] body)
    {
        lastError = null;

        String text;
        String[] parts;
        try
        {
            text = Report.compose(target, section, body);
            parts = Report.splitRaw(text, chunkBudget);
        }
        catch (Throwable t)
        {
            // Assembling the report must not be what kills the MIDlet we are
            // trying to diagnose - especially when the fault under
            // investigation is that the heap is exhausted.
            lastError = "compose failed: " + Diag.className(t);
            Diag.warn("report " + section + ": " + lastError);
            return false;
        }

        String url = base + "/" + target + "/" + device;
        int index = 0;
        int sent = 0;
        int shrinks = 0;

        while (index < parts.length)
        {
            String piece = label(parts[index], index, parts.length);

            if (postWithRetry(url, piece, index + 1, parts.length, section))
            {
                sent += Report.utf8Length(piece);
                index++;
                continue;
            }

            // A handset with no data session fails at connection setup, not
            // partway through the body. Only the latter is a size problem, and
            // halving the payload against a dead network would just cost the
            // user another four timeouts on a metered link.
            if (!looksLikeSizeLimit(lastError)) { return false; }

            int size = Report.utf8Length(piece);
            int smaller = size / 2;
            if (smaller < MIN_CHUNK_BYTES || ++shrinks > MAX_SHRINKS)
            {
                return false;
            }

            if (refusedAt == 0 || size < refusedAt) { refusedAt = size; }
            chunkBudget = smaller;
            Diag.warn("report " + section + ": " + size + " B refused inside the"
                    + " request body, retrying at " + smaller + " B");

            // Re-cut only what has not been sent yet. Nothing is duplicated,
            // and the ceiling is found once for the whole report rather than
            // rediscovered by every remaining piece - which on GPRS is the
            // difference between two wasted round trips and a dozen.
            StringBuffer rest = new StringBuffer();
            for (int k = index; k < parts.length; k++) { rest.append(parts[k]); }
            String[] recut = Report.splitRaw(rest.toString(), smaller);
            if (recut.length <= 1 && Report.utf8Length(recut[0]) >= size)
            {
                // One line longer than the target: splitting further would only
                // tear a UTF-8 sequence, and the collector caps lines anyway.
                return false;
            }
            parts = recut;
            index = 0;
        }

        // Cleared on the way out, not only on the way in: a report that
        // succeeded after a shrink still has the refusal recorded here, and a
        // caller showing lastError() would report a failure that did not happen.
        lastError = null;
        Report.logSent(section, parts.length, sent);
        return true;
    }

    /**
     * Number a piece so a truncated upload is obvious in the collector.
     *
     * Applied here rather than baked into the split, because the remainder is
     * re-cut whenever the handset refuses a size and the numbering has to
     * follow the pieces that actually go on the wire.
     */
    private static String label(String part, int index, int count)
    {
        if (count < 2) { return part; }
        return "[part " + (index + 1) + "/" + count + "]\n" + part;
    }

    /**
     * POST one piece, retrying a failed attempt.
     *
     * A 404 is not retried: it means the token is wrong or the endpoint has
     * moved, and neither improves by asking again - it only costs the user
     * data. Everything else on this link is worth a second try.
     */
    private boolean postWithRetry(String url, String piece, int index, int count,
                                  String section)
    {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
        {
            if (post(url, piece, index, count, section))
            {
                if (attempt > 1)
                {
                    Diag.info("report " + section + " part " + index + "/" + count
                            + ": succeeded on attempt " + attempt);
                }
                return true;
            }

            if (lastError != null && lastError.indexOf("stale endpoint") >= 0)
            {
                return false;
            }
            if (attempt == MAX_ATTEMPTS)
            {
                return false;
            }

            if (retryPauseMs > 0)
            {
                try { Thread.sleep(retryPauseMs); }
                catch (InterruptedException ignored) { /* CLDC cannot interrupt */ }
            }
        }
        return false;
    }

    private static boolean looksLikeSizeLimit(String error)
    {
        return error != null && error.indexOf("request-body") >= 0;
    }

    /** Largest body this handset actually accepted; a device finding worth recording. */
    public int acceptedChunkBytes()
    {
        return acceptedAt;
    }

    /** Smallest body refused, or 0 if nothing was ever refused. */
    public int refusedChunkBytes()
    {
        return refusedAt;
    }

    /** Upload text already assembled elsewhere. */
    public boolean sendRaw(String target, String section, String text)
    {
        return send(target, section, new String[] { text });
    }

    private boolean post(String url, String part, int index, int count, String section)
    {
        byte[] payload;
        try
        {
            payload = Utf8.encode(part);
        }
        catch (Throwable t)
        {
            lastError = "encode failed: " + Diag.className(t);
            return false;
        }

        try
        {
            HttpResponse response = http.post(url, payload, MAX_RESPONSE);
            if (response == null)
            {
                lastError = "no response";
            }
            else if (response.status == 200 || response.status == 204)
            {
                if (payload.length > acceptedAt) { acceptedAt = payload.length; }
                return true;
            }
            else if (response.status == 404)
            {
                // The collector answers 404 for a bad token, a bad path and a
                // missing file alike, so it cannot say which. On this side the
                // overwhelmingly likely cause is a stale endpoint: the VM's
                // public address is ephemeral and the build bakes it in.
                lastError = "404 - wrong token or stale endpoint; rebuild after "
                          + "refreshing the sink";
            }
            else
            {
                lastError = "HTTP " + response.status;
            }
        }
        catch (Throwable t)
        {
            // Includes IOException. A handset with no data session, a blocked
            // port or a dead DNS all land here and none of them are worth
            // taking the MIDlet down for.
            lastError = Diag.className(t) + ": " + t.getMessage();
        }

        Diag.warn("report " + section + " part " + index + "/" + count + ": " + lastError);
        return false;
    }
}
