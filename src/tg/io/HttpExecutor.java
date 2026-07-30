package tg.io;

import java.io.IOException;

/** Platform adapter for one bounded HTTP POST. */
public interface HttpExecutor
{
    HttpResponse post(String url, byte[] body, int maxResponse) throws IOException;
}
