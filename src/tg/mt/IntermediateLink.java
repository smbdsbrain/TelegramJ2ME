package tg.mt;

import java.io.IOException;

import tg.crypto.Rng;
import tg.io.Transport;

/** Packet link using intermediate or padded-intermediate framing. */
public final class IntermediateLink implements MtLink
{
    private final Transport transport;
    private final Intermediate frame;
    private final String description;
    private final boolean padded;

    public IntermediateLink(Transport transport, Rng rng, boolean padded,
                            boolean tagEmbedded, String description)
    {
        this.transport = transport;
        this.frame = new Intermediate(transport, rng, padded, tagEmbedded);
        this.description = description;
        this.padded = padded;
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

    public int hiddenPaddingBlocks()
    {
        return padded ? Intermediate.MAX_HIDDEN_BLOCKS : 0;
    }
}
