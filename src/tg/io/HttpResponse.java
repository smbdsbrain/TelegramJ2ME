package tg.io;

/** Result of a platform HTTP exchange. */
public final class HttpResponse
{
    public final int status;
    public final byte[] body;

    public HttpResponse(int status, byte[] body)
    {
        this.status = status;
        this.body = body;
    }
}
