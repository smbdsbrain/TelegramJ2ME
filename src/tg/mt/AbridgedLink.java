package tg.mt;

import java.io.IOException;

import tg.io.Transport;

/** Packet link using MTProto abridged framing. */
public final class AbridgedLink implements MtLink
{
    private final Transport transport;
    private final Abridged frame;
    private final String description;

    public AbridgedLink(Transport transport)
    {
        this(transport, false, "direct/abridged");
    }

    public AbridgedLink(Transport transport, boolean tagEmbedded, String description)
    {
        this.transport = transport;
        this.frame = new Abridged(transport, tagEmbedded);
        this.description = description;
    }

    public void connect(String host, int port, int timeoutMs) throws IOException
    {
        transport.connect(host, port, timeoutMs);
        frame.reset();
    }

    public void send(byte[] payload, int off, int len) throws IOException
    {
        frame.send(payload, off, len);
    }

    public int receive() throws IOException { return frame.receive(); }
    public byte[] buffer() { return frame.buffer(); }
    public boolean isConnected() { return transport.isConnected(); }
    public void close() { transport.close(); }
    public long bytesRead() { return transport.bytesRead(); }
    public long bytesWritten() { return transport.bytesWritten(); }
    public String description() { return description; }
    public boolean isRequestResponse() { return false; }
    public int hiddenPaddingBlocks() { return 0; }
}
