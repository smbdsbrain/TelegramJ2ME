package tgtest;

/**
 * Assertions and hex helpers for the desktop test harness.
 *
 * Deliberately not JUnit: adding a dependency and an annotation processor to a
 * project whose device code cannot use annotations at all buys nothing, and the
 * whole runner is a few hundred lines. Everything here is desktop-only and must
 * never be referenced from src/.
 *
 * Crypto and TL work lives or dies on byte-for-byte equality, so the failure
 * output shows the full expected/actual hex rather than "arrays differ".
 */
public final class Assert
{
    private Assert() { }

    public static void isTrue(String what, boolean cond)
    {
        if (!cond)
        {
            throw new AssertionError(what + ": expected true");
        }
    }

    public static void isFalse(String what, boolean cond)
    {
        if (cond)
        {
            throw new AssertionError(what + ": expected false");
        }
    }

    public static void equal(String what, long expected, long actual)
    {
        if (expected != actual)
        {
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
        }
    }

    public static void equal(String what, String expected, String actual)
    {
        if (expected == null ? actual != null : !expected.equals(actual))
        {
            throw new AssertionError(what + ":\n  expected " + expected + "\n  got      " + actual);
        }
    }

    public static void bytesEqual(String what, byte[] expected, byte[] actual)
    {
        if (expected == null || actual == null)
        {
            if (expected != actual)
            {
                throw new AssertionError(what + ": one side is null");
            }
            return;
        }
        if (expected.length != actual.length || !sameContent(expected, actual))
        {
            throw new AssertionError(what
                    + ":\n  expected (" + expected.length + ") " + hex(expected)
                    + "\n  got      (" + actual.length + ") " + hex(actual)
                    + "\n  " + firstDifference(expected, actual));
        }
    }

    /** Compare against a hex literal, which is how published test vectors read. */
    public static void bytesEqual(String what, String expectedHex, byte[] actual)
    {
        bytesEqual(what, unhex(expectedHex), actual);
    }

    public static void fail(String what)
    {
        throw new AssertionError(what);
    }

    // ---------------------------------------------------------------- hex

    /** Parse hex, ignoring whitespace and ':' so vectors can be pasted as printed. */
    public static byte[] unhex(String s)
    {
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ':' || c == '_')
            {
                continue;
            }
            clean.append(c);
        }
        if ((clean.length() & 1) != 0)
        {
            throw new IllegalArgumentException("odd hex length: " + clean.length());
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static String hex(byte[] b)
    {
        return hex(b, 0, b.length);
    }

    public static String hex(byte[] b, int off, int len)
    {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++)
        {
            int v = b[off + i] & 0xff;
            if (v < 16) { sb.append('0'); }
            sb.append(Integer.toHexString(v));
        }
        return sb.toString();
    }

    /** Repeat a byte, for the long-message digest vectors. */
    public static byte[] repeat(byte value, int count)
    {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) { out[i] = value; }
        return out;
    }

    public static byte[] ascii(String s)
    {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++)
        {
            out[i] = (byte) s.charAt(i);
        }
        return out;
    }

    // ----------------------------------------------------------- internal

    private static boolean sameContent(byte[] a, byte[] b)
    {
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i]) { return false; }
        }
        return true;
    }

    private static String firstDifference(byte[] a, byte[] b)
    {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++)
        {
            if (a[i] != b[i])
            {
                return "first difference at offset " + i
                        + ": " + String.format("%02x", a[i] & 0xff)
                        + " vs " + String.format("%02x", b[i] & 0xff);
            }
        }
        return "identical up to offset " + n + ", lengths differ";
    }
}
