package tg.diag;

/**
 * Fixed-size diagnostic log.
 *
 * Many Java ME runtimes are black boxes with no on-device Java debugger, and
 * System.out.println() is not reliably visible anywhere. Every subsystem
 * therefore reports through here; the log is readable on-screen, persisted
 * across a crash, and optionally streamed to a development machine over TCP.
 *
 * Memory discipline, since this runs on a heap we have not measured yet:
 *   - the ring buffer is allocated once, at class init, and never grows;
 *   - every line is truncated to MAX_LINE characters;
 *   - hex dumps are capped at MAX_HEX bytes regardless of what is passed in.
 *
 * All entry points are synchronized: the network thread and the UI thread both
 * log, and CLDC gives us no concurrent collections.
 */
public final class Diag
{
    public static final int LVL_INFO  = 0;
    public static final int LVL_WARN  = 1;
    public static final int LVL_ERROR = 2;

    /** Ring capacity in lines. ~100 * 120 chars = 24 KB worst case. */
    private static final int CAPACITY = 100;
    private static final int MAX_LINE = 120;
    private static final int MAX_HEX  = 256;

    private static final char[] HEX = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private static final String[] ring = new String[CAPACITY];
    private static int head  = 0;          // next slot to write
    private static int count = 0;          // lines currently held
    private static int dropped = 0;        // lines overwritten since start

    private static final long START = System.currentTimeMillis();

    private static DiagSink sink;          // optional remote/extra destination
    private static int minimumLevel = LVL_INFO;

    private Diag() { }

    // ----------------------------------------------------------------- api

    public static void info(String msg)
    {
        append(LVL_INFO, msg);
    }

    public static void warn(String msg)
    {
        append(LVL_WARN, msg);
    }

    public static void error(String msg)
    {
        append(LVL_ERROR, msg);
    }

    /**
     * CLDC 1.1 Throwable has no getCause() and no usable stack trace on a
     * handset, so class name plus message is all there is to record.
     */
    public static void error(String msg, Throwable t)
    {
        StringBuffer sb = new StringBuffer(64);
        sb.append(msg);
        if (t != null)
        {
            sb.append(" | ").append(className(t));
            String m = t.getMessage();
            if (m != null)
            {
                sb.append(": ").append(m);
            }
        }
        append(LVL_ERROR, sb.toString());
    }

    /** Current heap state. Called at every milestone so we can spot growth. */
    public static void mem(String tag)
    {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        StringBuffer sb = new StringBuffer(48);
        sb.append("mem ").append(tag)
          .append(" used=").append((total - free) / 1024).append('k')
          .append(" free=").append(free / 1024).append('k')
          .append(" total=").append(total / 1024).append('k');
        append(LVL_INFO, sb.toString());
    }

    /** Hex dump, capped at MAX_HEX bytes so a bad length cannot flood the ring. */
    public static void hex(String tag, byte[] data, int off, int len)
    {
        if (data == null)
        {
            append(LVL_INFO, tag + " <null>");
            return;
        }
        if (off < 0) { off = 0; }
        if (len < 0) { len = 0; }
        if (off + len > data.length) { len = data.length - off; }

        boolean truncated = false;
        if (len > MAX_HEX)
        {
            len = MAX_HEX;
            truncated = true;
        }

        StringBuffer head0 = new StringBuffer(32);
        head0.append(tag).append(' ').append(len).append(" bytes");
        if (truncated) { head0.append(" (truncated)"); }
        append(LVL_INFO, head0.toString());

        StringBuffer sb = new StringBuffer(56);
        for (int i = 0; i < len; i += 16)
        {
            sb.setLength(0);
            appendHex4(sb, i);
            sb.append(' ');
            int n = len - i;
            if (n > 16) { n = 16; }
            for (int j = 0; j < n; j++)
            {
                int b = data[off + i + j] & 0xff;
                sb.append(HEX[b >> 4]).append(HEX[b & 0x0f]);
                if ((j & 3) == 3) { sb.append(' '); }
            }
            append(LVL_INFO, sb.toString());
        }
    }

    public static void hex(String tag, byte[] data)
    {
        hex(tag, data, 0, data == null ? 0 : data.length);
    }

    /** Newest-last copy of the ring, safe to hand to the UI. */
    public static synchronized String[] snapshot()
    {
        String[] out = new String[count];
        int start = (head - count + CAPACITY) % CAPACITY;
        for (int i = 0; i < count; i++)
        {
            out[i] = ring[(start + i) % CAPACITY];
        }
        return out;
    }

    public static synchronized int droppedLines()
    {
        return dropped;
    }

    public static synchronized void clear()
    {
        for (int i = 0; i < CAPACITY; i++) { ring[i] = null; }
        head = 0;
        count = 0;
        dropped = 0;
    }

    /**
     * Attach an extra destination, typically the development-only TCP log
     * collector. Never carries Telegram protocol traffic.
     */
    public static synchronized void setSink(DiagSink s)
    {
        sink = s;
    }

    public static synchronized void setMinimumLevel(int level)
    {
        if (level < LVL_INFO) { level = LVL_INFO; }
        if (level > LVL_ERROR) { level = LVL_ERROR; }
        minimumLevel = level;
    }

    public static synchronized int minimumLevel()
    {
        return minimumLevel;
    }

    /** Class name without the package, which is all that fits on a 320px screen. */
    public static String className(Object o)
    {
        if (o == null) { return "null"; }
        String n = o.getClass().getName();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }

    // ------------------------------------------------------------ internal

    private static synchronized void append(int level, String msg)
    {
        if (level < minimumLevel) { return; }
        String line = format(level, msg);

        if (count == CAPACITY) { dropped++; }
        ring[head] = line;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) { count++; }

        DiagSink s = sink;
        if (s != null)
        {
            // A failing sink must never take down the subsystem that logged.
            try { s.write(line); }
            catch (Throwable t) { sink = null; }
        }
    }

    private static String format(int level, String msg)
    {
        if (msg == null) { msg = "null"; }
        if (msg.length() > MAX_LINE) { msg = msg.substring(0, MAX_LINE - 3) + "..."; }

        StringBuffer sb = new StringBuffer(msg.length() + 12);
        long ms = System.currentTimeMillis() - START;
        sb.append(ms / 1000).append('.');
        long frac = ms % 1000;
        if (frac < 100) { sb.append('0'); }
        if (frac < 10)  { sb.append('0'); }
        sb.append(frac).append(' ');
        sb.append(level == LVL_ERROR ? 'E' : (level == LVL_WARN ? 'W' : 'I'));
        sb.append(' ').append(msg);
        return sb.toString();
    }

    private static void appendHex4(StringBuffer sb, int v)
    {
        sb.append(HEX[(v >> 12) & 0x0f]).append(HEX[(v >> 8) & 0x0f])
          .append(HEX[(v >> 4) & 0x0f]).append(HEX[v & 0x0f]);
    }
}
