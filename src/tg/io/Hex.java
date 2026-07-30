package tg.io;

/**
 * Hex encoding and decoding, CLDC 1.1 only.
 *
 * Needed on the device, not just in tests: the self-test carries its vectors as
 * hex literals, and the diagnostics screen has to show byte content when
 * something goes wrong on a handset with no debugger. CLDC has no
 * javax.xml.bind, no String.format and no regex, so this is written by hand.
 *
 * Decoding is strict - a malformed literal is a bug in our own source, not
 * network input, and should fail loudly.
 */
public final class Hex
{
    private static final char[] DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private Hex() { }

    public static String encode(byte[] data)
    {
        return encode(data, 0, data == null ? 0 : data.length);
    }

    public static String encode(byte[] data, int off, int len)
    {
        if (data == null) { return "null"; }
        StringBuffer sb = new StringBuffer(len * 2);
        for (int i = 0; i < len; i++)
        {
            int v = data[off + i] & 0xff;
            sb.append(DIGITS[v >> 4]).append(DIGITS[v & 0x0f]);
        }
        return sb.toString();
    }

    /** Whitespace and ':' are ignored so vectors can be pasted as published. */
    public static byte[] decode(String s)
    {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
        {
            if (!isSkippable(s.charAt(i))) { n++; }
        }
        if ((n & 1) != 0)
        {
            throw new IllegalArgumentException("odd number of hex digits: " + n);
        }

        byte[] out = new byte[n / 2];
        int hi = -1;
        int w = 0;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (isSkippable(c)) { continue; }
            int v = value(c);
            if (hi < 0)
            {
                hi = v;
            }
            else
            {
                out[w++] = (byte) ((hi << 4) | v);
                hi = -1;
            }
        }
        return out;
    }

    /** Constant-time-ish comparison. Used where a mismatch is attacker-visible. */
    public static boolean equals(byte[] a, byte[] b)
    {
        if (a == null || b == null) { return a == b; }
        if (a.length != b.length) { return false; }
        int diff = 0;
        for (int i = 0; i < a.length; i++)
        {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    public static boolean equals(byte[] a, int aOff, byte[] b, int bOff, int len)
    {
        int diff = 0;
        for (int i = 0; i < len; i++)
        {
            diff |= a[aOff + i] ^ b[bOff + i];
        }
        return diff == 0;
    }

    private static boolean isSkippable(char c)
    {
        return c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':' || c == '_';
    }

    private static int value(char c)
    {
        if (c >= '0' && c <= '9') { return c - '0'; }
        if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
        if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
        throw new IllegalArgumentException("not a hex digit: '" + c + "'");
    }
}
