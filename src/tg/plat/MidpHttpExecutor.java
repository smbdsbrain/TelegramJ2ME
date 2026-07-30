package tg.plat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

import tg.io.HttpExecutor;
import tg.io.HttpResponse;

/** MIDP HttpConnection adapter used when raw sockets are unavailable. */
public final class MidpHttpExecutor implements HttpExecutor
{
    public HttpResponse post(String url, byte[] body, int maxResponse) throws IOException
    {
        HttpConnection conn = null;
        OutputStream out = null;
        InputStream in = null;
        String stage = "open";
        try
        {
            conn = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
            stage = "setup";
            conn.setRequestMethod(HttpConnection.POST);
            // Matches Telegram Desktop's official HTTP carrier. Some current
            // web.telegram.org frontends reject application/octet-stream even
            // though the body is still the raw MTProto payload.
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            stage = "request-body";
            out = conn.openOutputStream();
            out.write(body);
            out.flush();
            // MIDP specifies that closing the request stream commits the
            // request.  Some WSP implementations do not start the transaction
            // after flush alone, even when Content-Length is present.
            out.close();
            out = null;

            stage = "response-status";
            int status = conn.getResponseCode();
            long declared = conn.getLength();
            if (declared > maxResponse)
            {
                throw new IOException("HTTP response of " + declared + " exceeds " + maxResponse);
            }
            stage = "response-body";
            in = conn.openInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                    declared > 0 ? (int) declared : 512);
            byte[] chunk = new byte[512];
            int total = 0;
            while (true)
            {
                int n = in.read(chunk);
                if (n < 0) { break; }
                total += n;
                if (total > maxResponse)
                {
                    throw new IOException("HTTP response exceeds " + maxResponse);
                }
                bytes.write(chunk, 0, n);
            }
            return new HttpResponse(status, bytes.toByteArray());
        }
        catch (IOException e)
        {
            throw new IOException("HTTP " + stage + ": " + e.toString());
        }
        finally
        {
            if (in != null) { try { in.close(); } catch (Throwable ignored) { } }
            if (out != null) { try { out.close(); } catch (Throwable ignored) { } }
            if (conn != null) { try { conn.close(); } catch (Throwable ignored) { } }
        }
    }
}
