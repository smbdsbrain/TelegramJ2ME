package tg.mt;

/** Bounded network diagnostics suitable for a 320x240 text screen. */
public final class ConnectionDiagnostics
{
    private static final int MAX_ATTEMPTS = 8;
    private final String[] attempts = new String[MAX_ATTEMPTS];
    private int count;
    private MtLink activeLink;

    public String state = "idle";
    public String active = "none";
    public String endpoint = "";
    public String lastError = "";
    public int dcId;
    public long connectMs;
    public long rx;
    public long tx;
    public int retrySeconds;
    public String detail = "";

    public synchronized void begin(int dc, int mode, String host, int port)
    {
        state = "connecting";
        dcId = dc;
        active = ConnectionConfig.name(mode);
        endpoint = host + ":" + port;
        lastError = "";
        activeLink = null;
    }

    public synchronized void failed(int mode, Throwable t)
    {
        lastError = t.getClass().getName() + ": " + String.valueOf(t.getMessage());
        add(ConnectionConfig.name(mode) + " FAIL " + String.valueOf(t.getMessage()));
    }

    public synchronized void connected(int mode, long ms, MtLink link)
    {
        state = "connected";
        active = link.description();
        connectMs = ms;
        rx = link.bytesRead();
        tx = link.bytesWritten();
        activeLink = link;
        add(ConnectionConfig.name(mode) + " OK " + ms + "ms");
    }

    public synchronized void closed()
    {
        refreshCounters();
        state = "closed";
        activeLink = null;
    }

    public synchronized void lifecycle(String lifecycleState, int retry, String why)
    {
        state = lifecycleState;
        retrySeconds = retry;
        detail = why == null ? "" : why;
    }

    public synchronized String[] lines()
    {
        refreshCounters();
        String[] out = new String[10 + count];
        out[0] = "state: " + state;
        out[1] = "route: " + active;
        out[2] = "endpoint: " + endpoint;
        out[3] = "dc: " + dcId;
        out[4] = "connect: " + connectMs + " ms";
        out[5] = "rx/tx: " + rx + "/" + tx;
        out[6] = "last error: " + lastError;
        out[7] = "retry in: " + retrySeconds + " s";
        out[8] = "detail: " + detail;
        out[9] = "-- attempts --";
        for (int i = 0; i < count; i++) { out[10 + i] = attempts[i]; }
        return out;
    }

    private void refreshCounters()
    {
        if (activeLink != null)
        {
            rx = activeLink.bytesRead();
            tx = activeLink.bytesWritten();
        }
    }

    private void add(String line)
    {
        if (count < MAX_ATTEMPTS)
        {
            attempts[count++] = line;
        }
        else
        {
            for (int i = 1; i < MAX_ATTEMPTS; i++) { attempts[i - 1] = attempts[i]; }
            attempts[MAX_ATTEMPTS - 1] = line;
        }
    }
}
