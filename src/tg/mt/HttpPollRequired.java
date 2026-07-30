package tg.mt;

import java.io.IOException;

/** Internal signal: the previous HTTP response contained no awaited RPC yet. */
final class HttpPollRequired extends IOException
{
    HttpPollRequired()
    {
        super("MTProto HTTP response consumed; http_wait is required");
    }
}
