package tg.plat;

import tg.app.BuildInfo;
import tg.crypto.AuthKeySeeding;
import tg.diag.Diag;

/**
 * Assembles a diagnostic report for upload, and makes it safe to send.
 *
 * Every measurement this project takes already ends up as a String[] -
 * {@link Caps#report}, {@link HeapProbe.Result#lines}, {@link RmsCheck#run},
 * {@link ImageProbe#run}, {@link EntropyProbe#run}, {@link EntropyLog#report},
 * {@code Diag.snapshot()}, {@code CrashLog.load()}. Nothing was missing except
 * a way off the handset, so this class only does three things: stamp a header
 * describing which build on which firmware produced the numbers, strip anything
 * that must not leave the device, and cut the result into pieces small enough
 * for one HTTP POST.
 *
 * The redaction is not decoration. A report may carry the tail of the
 * diagnostic ring, and that ring is written by code that also logs hex dumps -
 * on the messenger build those bytes can be session material. Diagnostics are
 * meant to be useful without ever including credentials, phone numbers or
 * message content, so anything that looks like key material is masked here
 * rather than trusted not to appear.
 *
 * No regular expressions: java.util.regex is J2SE 1.4 and absent from CLDC, so
 * the scanners below are hand-rolled on purpose.
 */
public final class Report
{
    /**
     * Starting body budget for one upload.
     *
     * Only a starting point: {@link HttpReportSink} halves it when a handset
     * refuses a POST partway through the body, because the real ceiling belongs
     * to the phone's HTTP stack and its carrier's gateway and is not knowable
     * from here.
     *
     * 4 KiB rather than 8 because 8 KiB was measured to fail on a Samsung
     * GT-C3590 over a carrier APN while the sections that fitted in one piece
     * went through. Starting under the one ceiling we have actually seen saves
     * a wasted round trip per section on a metered link; starting much lower
     * would make every report chatty for no reason.
     */
    public static final int MAX_CHUNK_BYTES = 4 * 1024;

    /** A run of this many hex digits is treated as key material, not as data. */
    private static final int HEX_RUN = 32;

    /** Digits after a '+' that make something look like a phone number. */
    private static final int PHONE_DIGITS = 7;

    /** Keys whose value is masked wherever "key=" or "key:" appears. */
    private static final String[] SENSITIVE_KEYS = {
        "api_hash", "apihash", "auth_key", "authkey", "auth-key",
        "password", "passwd", "token", "secret", "session", "phone",
        "srp", "salt", "nonce"
    };

    private Report() { }

    // ------------------------------------------------------------- assembly

    /**
     * One complete report: header, then the redacted body.
     *
     * @param target  which MIDlet produced it - "probe" or "tg"
     * @param section human-readable name of the scenario
     * @param body    the scenario's own output; null entries are skipped
     */
    public static String compose(String target, String section, String[] body)
    {
        StringBuffer sb = new StringBuffer(1024);

        sb.append("target=").append(target).append('\n');
        sb.append("section=").append(section == null ? "" : section).append('\n');
        sb.append("version=").append(BuildInfo.VERSION);
        sb.append(" build=").append(BuildInfo.BUILD);
        sb.append(" env=").append(BuildInfo.ENV);
        sb.append(" midlet=").append(BuildInfo.TARGET).append('\n');

        appendProp(sb, "platform", "microedition.platform");
        appendProp(sb, "config", "microedition.configuration");
        appendProp(sb, "profiles", "microedition.profiles");
        appendProp(sb, "encoding", "microedition.encoding");
        appendProp(sb, "locale", "microedition.locale");

        Runtime rt = Runtime.getRuntime();
        sb.append("heapTotal=").append(rt.totalMemory());
        sb.append(" heapFree=").append(rt.freeMemory()).append('\n');

        // Not a wall-clock timestamp: several of the handsets this targets lose
        // the clock across a power cycle, which is itself one of the things the
        // probe measures. The collector stamps arrival time.
        sb.append("uptimeMs=").append(System.currentTimeMillis()).append('\n');

        // The seeding barrier sizes itself from what this handset's clock
        // actually yields, so the count it chose is a measurement of the device -
        // and on a phone nobody has profiled it is the only one that comes back
        // without running the probe at all. Counts only; no pool state exists
        // outside AuthKeySeeding to leak here.
        AuthKeySeeding.Outcome seeding = AuthKeySeeding.lastOutcome();
        if (seeding != null)
        {
            sb.append("seedingBarrier=").append(seeding.describe());
            if (seeding.shortOfTarget) { sb.append(" SHORT"); }
            sb.append(" barriers=").append(AuthKeySeeding.completedBarriers());
            sb.append('\n');
        }

        sb.append("--- ").append(section == null ? "body" : section).append(" ---\n");

        if (body != null)
        {
            for (int i = 0; i < body.length; i++)
            {
                if (body[i] == null) { continue; }
                sb.append(redact(body[i])).append('\n');
            }
        }

        return sb.toString();
    }

    private static void appendProp(StringBuffer sb, String label, String key)
    {
        String value = Caps.prop(key);
        sb.append(label).append('=').append(value == null ? "?" : value).append('\n');
    }

    // ------------------------------------------------------------ redaction

    /**
     * Mask anything in one line that must not leave the handset.
     *
     * Deliberately blunt. A masked measurement is a nuisance; a leaked
     * auth_key is not recoverable.
     */
    public static String redact(String line)
    {
        if (line == null) { return ""; }

        String out = maskSensitiveValues(line);
        out = maskHexRuns(out);
        out = maskPhoneNumbers(out);
        return out;
    }

    /** Replace the value after any {@link #SENSITIVE_KEYS} name with a marker. */
    private static String maskSensitiveValues(String line)
    {
        StringBuffer sb = null;
        int at = 0;
        int n = line.length();

        while (at < n)
        {
            int keyStart = -1;
            int valueStart = -1;

            for (int k = 0; k < SENSITIVE_KEYS.length; k++)
            {
                int found = indexOfIgnoreCase(line, SENSITIVE_KEYS[k], at);
                if (found < 0) { continue; }
                int after = found + SENSITIVE_KEYS[k].length();
                // Tolerate "key = value" and "key: value".
                while (after < n && line.charAt(after) == ' ') { after++; }
                if (after >= n) { continue; }
                char sep = line.charAt(after);
                if (sep != '=' && sep != ':') { continue; }
                after++;
                while (after < n && line.charAt(after) == ' ') { after++; }
                if (keyStart < 0 || found < keyStart)
                {
                    keyStart = found;
                    valueStart = after;
                }
            }

            if (keyStart < 0) { break; }

            int valueEnd = valueStart;
            while (valueEnd < n && !isValueTerminator(line.charAt(valueEnd)))
            {
                valueEnd++;
            }

            // Nothing there to hide; keep scanning past this key.
            if (valueEnd == valueStart)
            {
                at = valueStart + 1;
                continue;
            }

            if (sb == null) { sb = new StringBuffer(n); }
            sb.append(line.substring(at, valueStart));
            sb.append("<redacted>");
            at = valueEnd;
        }

        if (sb == null) { return line; }
        sb.append(line.substring(at));
        return sb.toString();
    }

    private static boolean isValueTerminator(char c)
    {
        return c == ' ' || c == ',' || c == ';' || c == ')' || c == '"' || c == '\'';
    }

    /**
     * Collapse long hex runs. Diag.hex() dumps space-separated bytes, so the
     * scan ignores the separators a dump uses and still sees the run.
     */
    private static String maskHexRuns(String line)
    {
        StringBuffer sb = null;
        int n = line.length();
        int i = 0;

        while (i < n)
        {
            if (!isHexDigit(line.charAt(i))) { i++; continue; }

            int start = i;
            int digits = 0;
            int j = i;
            while (j < n)
            {
                char c = line.charAt(j);
                if (isHexDigit(c)) { digits++; j++; }
                else if ((c == ' ' || c == ':' || c == '-') && digits > 0
                         && j + 1 < n && isHexDigit(line.charAt(j + 1))) { j++; }
                else { break; }
            }

            if (digits >= HEX_RUN)
            {
                if (sb == null) { sb = new StringBuffer(n); }
                sb.append(line.substring(0, start));
                // Keep the length: "how much" is often the diagnostic point,
                // and it discloses nothing.
                sb.append("<hex:").append(digits).append(">");
                String tail = maskHexRuns(line.substring(j));
                sb.append(tail);
                return sb.toString();
            }

            i = j > i ? j : i + 1;
        }

        return line;
    }

    private static boolean isHexDigit(char c)
    {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isDigit(char c)
    {
        return c >= '0' && c <= '9';
    }

    /** Mask "+" followed by enough digits to be a subscriber number. */
    private static String maskPhoneNumbers(String line)
    {
        int n = line.length();
        StringBuffer sb = null;
        int i = 0;
        int copied = 0;

        while (i < n)
        {
            if (line.charAt(i) != '+') { i++; continue; }

            int j = i + 1;
            int digits = 0;
            while (j < n)
            {
                char c = line.charAt(j);
                if (c >= '0' && c <= '9') { digits++; j++; }
                else if ((c == ' ' || c == '-' || c == '(' || c == ')') && digits > 0
                         && j + 1 < n && isDigit(line.charAt(j + 1)))
                {
                    // Only a separator if a digit follows. Without this check the
                    // space after a number is swallowed and "+7... started"
                    // comes out as "<phone>started".
                    j++;
                }
                else { break; }
            }

            if (digits >= PHONE_DIGITS)
            {
                if (sb == null) { sb = new StringBuffer(n); }
                sb.append(line.substring(copied, i));
                sb.append("<phone>");
                copied = j;
                i = j;
            }
            else
            {
                i = j > i ? j : i + 1;
            }
        }

        if (sb == null) { return line; }
        sb.append(line.substring(copied));
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String haystack, String needle, int from)
    {
        int n = haystack.length();
        int m = needle.length();
        for (int i = from; i + m <= n; i++)
        {
            int k = 0;
            while (k < m && lower(haystack.charAt(i + k)) == lower(needle.charAt(k))) { k++; }
            if (k == m) { return i; }
        }
        return -1;
    }

    private static char lower(char c)
    {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }

    // ------------------------------------------------------------- chunking

    /**
     * Cut a report into upload-sized pieces, never mid-line.
     *
     * A line longer than the budget is emitted alone and over budget rather
     * than split: the alternative is a torn UTF-8 sequence, and the collector's
     * own line cap will deal with it.
     *
     * The pieces are deliberately not numbered here. {@link HttpReportSink}
     * re-cuts the unsent remainder whenever a handset refuses a size, so the
     * numbering has to be applied to whatever actually goes on the wire.
     */
    public static String[] splitRaw(String text, int maxBytes)
    {
        if (text == null) { return new String[0]; }
        if (maxBytes < 256) { maxBytes = 256; }

        int total = utf8Length(text);
        if (total <= maxBytes) { return new String[] { text }; }

        // Two passes: count, then fill. Growing a Vector of Strings on a heap
        // this small costs more than walking the text twice.
        int parts = countParts(text, maxBytes);
        String[] out = new String[parts];

        int written = 0;
        int at = 0;
        int n = text.length();
        StringBuffer sb = new StringBuffer(maxBytes);
        int used = 0;

        while (at < n)
        {
            int end = text.indexOf('\n', at);
            if (end < 0) { end = n; } else { end++; }
            String line = text.substring(at, end);
            int cost = utf8Length(line);

            if (used > 0 && used + cost > maxBytes)
            {
                out[written++] = sb.toString();
                sb = new StringBuffer(maxBytes);
                used = 0;
            }

            sb.append(line);
            used += cost;
            at = end;
        }

        if (used > 0 && written < parts) { out[written++] = sb.toString(); }

        if (written == out.length) { return out; }
        String[] trimmed = new String[written];
        System.arraycopy(out, 0, trimmed, 0, written);
        return trimmed;
    }

    private static int countParts(String text, int maxBytes)
    {
        int parts = 0;
        int used = 0;
        int at = 0;
        int n = text.length();

        while (at < n)
        {
            int end = text.indexOf('\n', at);
            if (end < 0) { end = n; } else { end++; }
            int cost = utf8Length(text.substring(at, end));
            if (used > 0 && used + cost > maxBytes)
            {
                parts++;
                used = 0;
            }
            used += cost;
            at = end;
        }
        if (used > 0) { parts++; }
        return parts;
    }

    /**
     * UTF-8 byte length without building the array. Mirrors Utf8.encode exactly,
     * surrogate pairs included; a mismatch would make the chunk budget wrong for
     * precisely the emoji-bearing lines that need it most.
     */
    public static int utf8Length(String s)
    {
        if (s == null) { return 0; }
        int n = s.length();
        int size = 0;
        for (int i = 0; i < n; i++)
        {
            char c = s.charAt(i);
            if (c < 0x80)
            {
                size += 1;
            }
            else if (c < 0x800)
            {
                size += 2;
            }
            else if (c >= 0xd800 && c <= 0xdbff
                     && i + 1 < n && s.charAt(i + 1) >= 0xdc00 && s.charAt(i + 1) <= 0xdfff)
            {
                size += 4;
                i++;
            }
            else
            {
                size += 3;
            }
        }
        return size;
    }

    // --------------------------------------------------------------- helper

    /** Log a one-line summary of what was sent, without the payload. */
    public static void logSent(String section, int parts, int bytes)
    {
        Diag.info("report " + section + ": " + parts + " part(s), " + bytes + " B");
    }
}
