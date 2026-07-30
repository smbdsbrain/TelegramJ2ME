package tg.tl;

import java.io.IOException;

/**
 * Malformed TL data.
 *
 * Extends IOException deliberately: a parse failure means the peer sent
 * something we cannot use, which is a connection-level problem handled the same
 * way as a socket error - drop the connection and reconnect. It is explicitly
 * NOT a RuntimeException, because that would blur the line between "the network
 * gave us garbage" and "our code has a bug", and on a handset with no debugger
 * that distinction is most of the diagnosis.
 */
public class TlException extends IOException
{
    public TlException(String message)
    {
        super(message);
    }
}
