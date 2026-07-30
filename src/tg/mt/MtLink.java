package tg.mt;

import java.io.IOException;

/**
 * Packet-oriented link below MTProto messages.
 *
 * TCP links add/remove abridged or intermediate framing. HTTP links exchange
 * the MTProto payload directly. The MTProto client therefore no longer needs
 * to know what the underlying carrier looks like.
 */
public interface MtLink extends PacketFrame
{
    void connect(String host, int port, int timeoutMs) throws IOException;
    void send(byte[] payload, int off, int len) throws IOException;
    int receive() throws IOException;
    byte[] buffer();
    boolean isConnected();
    void close();
    long bytesRead();
    long bytesWritten();
    String description();

    /**
     * True for carriers such as HTTP where every outbound payload produces one
     * queued response and idle receiving requires an explicit http_wait.
     */
    boolean isRequestResponse();
}
