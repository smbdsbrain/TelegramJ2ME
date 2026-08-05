package tg.plat;

import javax.microedition.rms.RecordStore;

import tg.tl.Utf8;

/**
 * Whether non-ASCII text survives every layer between a String and the
 * collector.
 *
 * Every handset measured reports {@code microedition.encoding = ISO8859-1}, so
 * {@code String.getBytes()} and {@code new String(byte[])} silently destroy
 * anything outside Latin-1. The project carries {@link Utf8} precisely to avoid
 * them, and a GT-C3592 crash entry did once come back as {@code ?????} because
 * one path had not been converted. That makes "our own layers never fall back
 * to the platform default" a claim worth checking on each new handset rather
 * than assuming, and worth checking per layer rather than in aggregate: the
 * useful output is which stage failed, not that something did.
 *
 * The sample deliberately mixes three ranges, because they fail differently:
 * Latin-1-representable accents survive a platform round trip and prove
 * nothing; Cyrillic is two UTF-8 bytes and outside Latin-1; the emoji is a
 * surrogate pair and four UTF-8 bytes, which is where a hand-written encoder
 * that forgets to recombine surrogates goes wrong.
 */
public final class TextProbe
{
    /** "cafe" with an acute e, Cyrillic "privet", and a waving hand emoji. */
    private static final String SAMPLE =
            "café привет 👋";

    /** Its own store, deleted after use; never touches the client's records. */
    private static final String STORE = "tgtextprobe";

    private TextProbe() { }

    public static String[] run()
    {
        String[] out = new String[14];
        int w = 0;

        out[w++] = "non-ASCII round trip";
        out[w++] = "encoding=" + prop("microedition.encoding");
        out[w++] = "sample: " + SAMPLE.length() + " chars, "
                + codePoints(SAMPLE) + " code points";
        out[w++] = "expect: " + expectedHex();
        out[w++] = "";

        // 1. The project's own converter. If this fails nothing downstream can
        //    be right, and every TL string on the wire is already wrong.
        out[w++] = stage("Utf8 encode/decode", utf8RoundTrip());

        // 2. What the platform would do if anyone used getBytes(). Not a defect
        //    when it fails - it is the reason Utf8 exists - but the comparison
        //    is what tells a reader whether this handset is one of the lossy
        //    ones, and it names the encoding that did it.
        out[w++] = stage("String.getBytes (platform)", platformRoundTrip());

        // 3. RMS. The key store writes name=value records; if this loses
        //    characters then any non-ASCII setting - a proxy host, a cached
        //    display name - comes back wrong after a restart.
        out[w++] = stage("RMS record round trip", rmsRoundTrip());

        // 4. What actually leaves the handset. Report.redact must not eat
        //    non-Latin text, and the chunker must not split a character in
        //    half: it counts UTF-8 bytes while cutting on char boundaries.
        out[w++] = stage("Report compose/redact", reportRoundTrip());

        out[w++] = "";
        out[w++] = "PASS = bytes identical to expect.";
        out[w++] = "the platform line is expected to be";
        out[w++] = "LOSSY; the other three must not be.";

        // Trimmed rather than sized by hand: a trailing null reaches the
        // collector as a literal "null" line, and the report is the product.
        String[] trimmed = new String[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    // ---------------------------------------------------------------- stages

    private static String utf8RoundTrip()
    {
        byte[] encoded = Utf8.encode(SAMPLE);
        String back = Utf8.decode(encoded);
        if (!SAMPLE.equals(back))
        {
            return "decode differs: " + groupedHex(Utf8.encode(back));
        }
        return verifyBytes(encoded);
    }

    private static String platformRoundTrip()
    {
        try
        {
            byte[] raw = SAMPLE.getBytes();
            String back = new String(raw);
            // Report what the platform produced either way: a handset where
            // this passes is a handset where the historical bugs could not
            // have happened, which is itself worth knowing.
            return SAMPLE.equals(back)
                    ? "lossless (" + raw.length + " B)"
                    : "LOSSY: " + raw.length + " B, back as "
                      + groupedHex(Utf8.encode(back));
        }
        catch (Throwable t)
        {
            return "threw " + t.getClass().getName();
        }
    }

    /**
     * A record written and read back the way the key store writes settings.
     *
     * Done with RecordStore directly rather than through
     * {@code tg.plat.RmsAuthKeyStore}, which would drag {@code tg.mt.AuthKey}
     * and its SHA-1 into probe.jar - the build that exists specifically to
     * carry no crypto and no Telegram code. The bytes and the layout are the
     * same, which is what is under test.
     */
    private static String rmsRoundTrip()
    {
        RecordStore rs = null;
        try
        {
            rs = RecordStore.openRecordStore(STORE, true);
            byte[] data = Utf8.encode("probe.text=" + SAMPLE);
            int id = rs.addRecord(data, 0, data.length);
            byte[] raw = rs.getRecord(id);
            rs.deleteRecord(id);

            if (raw == null) { return "read back nothing"; }
            String line = Utf8.decode(raw);
            int eq = line.indexOf('=');
            String back = eq < 0 ? line : line.substring(eq + 1);
            if (!SAMPLE.equals(back))
            {
                return "differs: " + groupedHex(Utf8.encode(back));
            }
            return verifyBytes(Utf8.encode(back));
        }
        catch (Throwable t)
        {
            return "threw " + t.getClass().getName() + " "
                    + String.valueOf(t.getMessage());
        }
        finally
        {
            if (rs != null)
            {
                try { rs.closeRecordStore(); } catch (Throwable ignored) { }
            }
            try { RecordStore.deleteRecordStore(STORE); }
            catch (Throwable ignored) { }
        }
    }

    /**
     * The whole upload path: header, redaction, and the chunker.
     *
     * Split at a deliberately small budget so the sample straddles a piece
     * boundary - the chunker counts UTF-8 bytes while cutting on char
     * boundaries, and getting that wrong halves a multi-byte character.
     */
    private static String reportRoundTrip()
    {
        try
        {
            String composed = Report.compose("probe", "text-probe",
                    new String[] { "sample=" + SAMPLE });
            String[] pieces = Report.splitRaw(composed, 64);

            StringBuffer joined = new StringBuffer();
            for (int i = 0; i < pieces.length; i++)
            {
                joined.append(pieces[i]);
            }
            String all = joined.toString();
            if (all.indexOf(SAMPLE) < 0)
            {
                int at = all.indexOf("sample=");
                String got = at < 0 ? "(line missing)"
                        : all.substring(at + 7,
                                Math.min(all.length(), at + 7 + 24));
                return "sample altered, got " + groupedHex(Utf8.encode(got));
            }
            return "survives (" + pieces.length + " piece(s))";
        }
        catch (Throwable t)
        {
            return "threw " + t.getClass().getName() + " "
                    + String.valueOf(t.getMessage());
        }
    }

    // --------------------------------------------------------------- helpers

    private static String verifyBytes(byte[] actual)
    {
        byte[] expected = Utf8.encode(SAMPLE);
        if (actual.length != expected.length)
        {
            return "length " + actual.length + ", expected " + expected.length;
        }
        for (int i = 0; i < expected.length; i++)
        {
            if (actual[i] != expected[i])
            {
                return "byte " + i + " differs: " + groupedHex(actual);
            }
        }
        return "PASS (" + actual.length + " B)";
    }

    private static String stage(String label, String result)
    {
        return label + ": " + result;
    }

    private static String expectedHex()
    {
        return groupedHex(Utf8.encode(SAMPLE));
    }

    /**
     * Hex in dotted groups, because the report redacts long hex runs.
     *
     * {@code Report.redact} replaces any run of 32 or more hex digits with
     * {@code <hex:NN>}, and treats space, ':' and '-' as continuing the run.
     * That is right for key material and wrong here: this probe's entire output
     * is hex, and the first run it produced arrived at the collector as
     * {@code expect: <hex:46>} - a probe that could not report what it measured.
     * A '.' between groups ends the run without hiding anything.
     */
    private static String groupedHex(byte[] data)
    {
        StringBuffer sb = new StringBuffer(data.length * 2 + data.length / 4);
        for (int i = 0; i < data.length; i++)
        {
            if (i > 0 && (i % 4) == 0) { sb.append('.'); }
            int b = data[i] & 0xff;
            sb.append(HEX.charAt(b >> 4)).append(HEX.charAt(b & 0x0f));
        }
        return sb.toString();
    }

    private static final String HEX = "0123456789abcdef";

    /** Surrogate pairs count once; that is the number a decoder must produce. */
    private static int codePoints(String s)
    {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length()) { i++; }
            n++;
        }
        return n;
    }

    private static String prop(String name)
    {
        try
        {
            String v = System.getProperty(name);
            return v == null ? "(absent)" : v;
        }
        catch (Throwable t)
        {
            return "(threw)";
        }
    }
}
