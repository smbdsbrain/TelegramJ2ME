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

    /**
     * How many whole AES blocks of transport padding this carrier could be
     * hiding behind an encrypted packet, beyond what the framing can see.
     *
     * Zero for every carrier that states an exact length. Padded intermediate
     * only reveals the length modulo the block size, and MTProxy deployments
     * have been measured padding past the documented 15 bytes, so the reader
     * is allowed to retry a few shorter lengths - each one still has to pass
     * the msg_key check before it is believed.
     */
    int hiddenPaddingBlocks();
}
