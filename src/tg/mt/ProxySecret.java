package tg.mt;

import tg.io.Hex;

/**
 * Validated MTProxy secret.
 *
 * Supported binary forms match TDLib: 16 bytes (intermediate), 0xdd plus
 * 16 bytes (padded intermediate), and 0xee plus 16 bytes plus an SNI domain.
 */
public final class ProxySecret
{
    public static final int MAX_DOMAIN = 182;

    private final byte[] raw;

    private ProxySecret(byte[] raw)
    {
        this.raw = raw;
    }

    public static ProxySecret parse(String encoded)
    {
        if (encoded == null) { throw new IllegalArgumentException("proxy secret is empty"); }
        String s = encoded.trim();
        if (s.length() == 0) { throw new IllegalArgumentException("proxy secret is empty"); }

        byte[] decoded = null;
        if (looksHex(s))
        {
            try { decoded = Hex.decode(s); } catch (Throwable ignored) { }
        }
        if (decoded == null)
        {
            decoded = decodeBase64(s);
        }
        return fromBytes(decoded);
    }

    public static ProxySecret fromBytes(byte[] data)
    {
        if (data == null) { throw new IllegalArgumentException("proxy secret is empty"); }
        boolean normal = data.length == 16;
        boolean padded = data.length == 17 && (data[0] & 0xff) == 0xdd;
        boolean fakeTls = data.length >= 18
                && data.length <= 17 + MAX_DOMAIN
                && (data[0] & 0xff) == 0xee;
        if (!normal && !padded && !fakeTls)
        {
            throw new IllegalArgumentException("unsupported proxy secret length/type");
        }
        if (fakeTls)
        {
            for (int i = 17; i < data.length; i++)
            {
                int c = data[i] & 0xff;
                if (c < 0x21 || c > 0x7e)
                {
                    throw new IllegalArgumentException("FakeTLS domain must be printable ASCII");
                }
            }
        }
        byte[] copy = new byte[data.length];
        System.arraycopy(data, 0, copy, 0, data.length);
        return new ProxySecret(copy);
    }

    /** Parse tg://proxy?... and return host, port and secret. */
    public static ParsedLink parseLink(String uri)
    {
        if (uri == null || !uri.startsWith("tg://proxy?"))
        {
            throw new IllegalArgumentException("expected tg://proxy URI");
        }
        String host = null;
        String secret = null;
        int port = 0;
        String query = uri.substring("tg://proxy?".length());
        int at = 0;
        while (at <= query.length())
        {
            int amp = query.indexOf('&', at);
            if (amp < 0) { amp = query.length(); }
            String item = query.substring(at, amp);
            int eq = item.indexOf('=');
            if (eq > 0)
            {
                String name = item.substring(0, eq);
                String value = percentDecode(item.substring(eq + 1));
                if ("server".equals(name)) { host = value; }
                else if ("port".equals(name))
                {
                    try { port = Integer.parseInt(value); }
                    catch (Throwable t) { throw new IllegalArgumentException("invalid proxy port"); }
                }
                else if ("secret".equals(name)) { secret = value; }
            }
            if (amp == query.length()) { break; }
            at = amp + 1;
        }
        if (host == null || host.length() == 0 || port < 1 || port > 65535 || secret == null)
        {
            throw new IllegalArgumentException("proxy URI needs server, port and secret");
        }
        return new ParsedLink(host, port, parse(secret));
    }

    public byte[] key()
    {
        byte[] out = new byte[16];
        System.arraycopy(raw, raw.length == 16 ? 0 : 1, out, 0, 16);
        return out;
    }

    public boolean padded()
    {
        return raw.length >= 17;
    }

    public boolean fakeTls()
    {
        return raw.length >= 18 && (raw[0] & 0xff) == 0xee;
    }

    public String domain()
    {
        if (!fakeTls()) { return ""; }
        return new String(raw, 17, raw.length - 17);
    }

    public String encode()
    {
        return Hex.encode(raw);
    }

    public static final class ParsedLink
    {
        public final String host;
        public final int port;
        public final ProxySecret secret;

        private ParsedLink(String host, int port, ProxySecret secret)
        {
            this.host = host;
            this.port = port;
            this.secret = secret;
        }
    }

    private static boolean looksHex(String s)
    {
        if ((s.length() & 1) != 0) { return false; }
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F'))) { return false; }
        }
        return true;
    }

    private static byte[] decodeBase64(String s)
    {
        int useful = s.length();
        while (useful > 0 && s.charAt(useful - 1) == '=') { useful--; }
        int outLen = useful * 6 / 8;
        byte[] out = new byte[outLen];
        int bits = 0;
        int count = 0;
        int w = 0;
        for (int i = 0; i < useful; i++)
        {
            int v = base64Value(s.charAt(i));
            if (v < 0) { throw new IllegalArgumentException("invalid Base64 proxy secret"); }
            bits = (bits << 6) | v;
            count += 6;
            if (count >= 8)
            {
                count -= 8;
                if (w < out.length) { out[w++] = (byte) (bits >>> count); }
                bits &= (1 << count) - 1;
            }
        }
        return out;
    }

    private static int base64Value(char c)
    {
        if (c >= 'A' && c <= 'Z') { return c - 'A'; }
        if (c >= 'a' && c <= 'z') { return c - 'a' + 26; }
        if (c >= '0' && c <= '9') { return c - '0' + 52; }
        if (c == '+' || c == '-') { return 62; }
        if (c == '/' || c == '_') { return 63; }
        return -1;
    }

    private static String percentDecode(String s)
    {
        StringBuffer out = new StringBuffer(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length())
            {
                out.append((char) ((hex(s.charAt(i + 1)) << 4) | hex(s.charAt(i + 2))));
                i += 2;
            }
            else
            {
                out.append(c == '+' ? ' ' : c);
            }
        }
        return out.toString();
    }

    private static int hex(char c)
    {
        if (c >= '0' && c <= '9') { return c - '0'; }
        if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
        if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
        throw new IllegalArgumentException("bad percent escape");
    }
}
