package tg.diag;

/**
 * Extra destination for diagnostic lines.
 *
 * Implemented by the development-only TCP log collector in tg.plat so that the
 * diag package stays free of any networking dependency - it has to keep working
 * when the network is exactly what is broken.
 */
public interface DiagSink
{
    /**
     * Deliver one already-formatted line. Implementations must not throw for
     * ordinary failures; Diag detaches a sink that does.
     */
    void write(String line);
}
