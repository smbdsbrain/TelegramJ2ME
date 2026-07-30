package tg.mt;

import java.io.IOException;

/** Minimal packet framing contract shared by raw frame tests and MtLink. */
public interface PacketFrame
{
    void send(byte[] payload, int off, int len) throws IOException;
    int receive() throws IOException;
    byte[] buffer();
}
