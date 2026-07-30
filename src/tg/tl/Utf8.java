package tg.tl;

/**
 * UTF-8 conversion that does not depend on the platform.
 *
 * {@code String.getBytes()} on CLDC uses {@code microedition.encoding}, which is
 * whatever the handset vendor chose - on a Russian-market 2011 phone that could
 * well be a Cyrillic single-byte codepage. TL strings are always UTF-8, so
 * relying on the platform default would corrupt every non-ASCII message.
 *
 * {@code getBytes("UTF-8")} exists in CLDC 1.1 but throws a checked exception
 * and is not guaranteed to be supported, so the conversion is done by hand.
 *
 * Java strings are UTF-16, so a character above the BMP arrives as a surrogate
 * pair and must be recombined into one 4-byte sequence - emoji in Telegram
 * messages are exactly that case.
 */
public final class Utf8
{
    private Utf8() { }

    public static byte[] encode(String s)
    {
        if (s == null) { return new byte[0]; }

        int n = s.length();
        // Exact size first: growing a byte[] per character would allocate
        // repeatedly on a heap we are trying to be careful with.
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
            else if (isHighSurrogate(c) && i + 1 < n && isLowSurrogate(s.charAt(i + 1)))
            {
                size += 4;
                i++;
            }
            else
            {
                size += 3;
            }
        }

        byte[] out = new byte[size];
        int p = 0;
        for (int i = 0; i < n; i++)
        {
            char c = s.charAt(i);
            if (c < 0x80)
            {
                out[p++] = (byte) c;
            }
            else if (c < 0x800)
            {
                out[p++] = (byte) (0xc0 | (c >> 6));
                out[p++] = (byte) (0x80 | (c & 0x3f));
            }
            else if (isHighSurrogate(c) && i + 1 < n && isLowSurrogate(s.charAt(i + 1)))
            {
                int cp = 0x10000 + (((c & 0x3ff) << 10) | (s.charAt(i + 1) & 0x3ff));
                i++;
                out[p++] = (byte) (0xf0 | (cp >> 18));
                out[p++] = (byte) (0x80 | ((cp >> 12) & 0x3f));
                out[p++] = (byte) (0x80 | ((cp >> 6) & 0x3f));
                out[p++] = (byte) (0x80 | (cp & 0x3f));
            }
            else
            {
                out[p++] = (byte) (0xe0 | (c >> 12));
                out[p++] = (byte) (0x80 | ((c >> 6) & 0x3f));
                out[p++] = (byte) (0x80 | (c & 0x3f));
            }
        }
        return out;
    }

    /**
     * Decode UTF-8. Malformed input yields U+FFFD rather than an exception:
     * a single bad byte in one message must not take down the dialog list.
     */
    public static String decode(byte[] data)
    {
        return decode(data, 0, data.length);
    }

    public static String decode(byte[] data, int off, int len)
    {
        if (data == null || len <= 0) { return ""; }

        StringBuffer sb = new StringBuffer(len);
        int end = off + len;
        int i = off;
        while (i < end)
        {
            int b = data[i++] & 0xff;
            if (b < 0x80)
            {
                sb.append((char) b);
            }
            else if ((b & 0xe0) == 0xc0)
            {
                if (i >= end) { sb.append('�'); break; }
                int b2 = data[i++] & 0x3f;
                sb.append((char) (((b & 0x1f) << 6) | b2));
            }
            else if ((b & 0xf0) == 0xe0)
            {
                if (i + 1 >= end) { sb.append('�'); break; }
                int b2 = data[i++] & 0x3f;
                int b3 = data[i++] & 0x3f;
                sb.append((char) (((b & 0x0f) << 12) | (b2 << 6) | b3));
            }
            else if ((b & 0xf8) == 0xf0)
            {
                if (i + 2 >= end) { sb.append('�'); break; }
                int b2 = data[i++] & 0x3f;
                int b3 = data[i++] & 0x3f;
                int b4 = data[i++] & 0x3f;
                int cp = ((b & 0x07) << 18) | (b2 << 12) | (b3 << 6) | b4;
                if (cp > 0x10ffff)
                {
                    sb.append('�');
                }
                else
                {
                    // Back to a UTF-16 surrogate pair.
                    cp -= 0x10000;
                    sb.append((char) (0xd800 + (cp >> 10)));
                    sb.append((char) (0xdc00 + (cp & 0x3ff)));
                }
            }
            else
            {
                sb.append('�');
            }
        }
        return sb.toString();
    }

    private static boolean isHighSurrogate(char c)
    {
        return c >= '\ud800' && c <= '\udbff';
    }

    private static boolean isLowSurrogate(char c)
    {
        return c >= '\udc00' && c <= '\udfff';
    }
}
