package tg.io;

import java.io.IOException;

/**
 * Byte-stream transport, deliberately narrower than any concrete socket API.
 *
 * MTProto runs directly over TCP, and every layer above this one is pure
 * CLDC-subset Java. Keeping the socket behind this interface is what lets the
 * whole crypto/TL/MTProto stack be driven from a desktop JVM - against the real
 * Telegram test DC - long before a handset is involved:
 *
 *   tg.plat.MidpTransport   javax.microedition.io.SocketConnection  (device)
 *   tgtest.SeTransport      java.net.Socket                         (desktop)
 *
 * No proxy, TLS or HTTP variant belongs here. If Telegram traffic ever needs to
 * take another shape, that is a transport *implementation*, not a change to
 * this contract.
 */
public interface Transport
{
    /**
     * Open a connection. Implementations should apply {@code timeoutMs} to the
     * connect attempt where the platform allows it; MIDP offers no portable
     * connect timeout, so callers must not rely on it.
     */
    void connect(String host, int port, int timeoutMs) throws IOException;

    /**
     * Read at least one byte, blocking until data arrives.
     *
     * @return number of bytes read, or -1 at end of stream
     */
    int read(byte[] buf, int off, int len) throws IOException;

    /** Read exactly {@code len} bytes or throw. */
    void readFully(byte[] buf, int off, int len) throws IOException;

    void write(byte[] buf, int off, int len) throws IOException;

    void flush() throws IOException;

    boolean isConnected();

    /** Idempotent, never throws. */
    void close();

    /** Bytes received since connect - surfaced on the diagnostics screen. */
    long bytesRead();

    /** Bytes sent since connect - surfaced on the diagnostics screen. */
    long bytesWritten();
}
