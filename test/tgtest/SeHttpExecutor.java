package tgtest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

import tg.io.HttpExecutor;
import tg.io.HttpResponse;

/** Desktop oracle/adapter for the same packet-oriented MTProto HTTP link. */
public final class SeHttpExecutor implements HttpExecutor
{
    public HttpResponse post(String url, byte[] body, int maxResponse) throws IOException
    {
        URL parsed = new URL(url);
        if ("http".equals(parsed.getProtocol()))
        {
            return postPlain(parsed, body, maxResponse);
        }
        HttpURLConnection connection = null;
        InputStream in = null;
        OutputStream out = null;
        try
        {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setRequestProperty("Connection", "keep-alive");
            connection.setFixedLengthStreamingMode(body.length);
            out = connection.getOutputStream();
            out.write(body);
            out.flush();

            int status = connection.getResponseCode();
            int declared = connection.getContentLength();
            if (declared > maxResponse)
            {
                throw new IOException("HTTP response of " + declared
                                      + " exceeds " + maxResponse);
            }
            in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream bytes =
                    new ByteArrayOutputStream(declared > 0 ? declared : 512);
            if (in != null)
            {
                byte[] chunk = new byte[4096];
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
            }
            return new HttpResponse(status, bytes.toByteArray());
        }
        finally
        {
            if (in != null) { try { in.close(); } catch (IOException ignored) { } }
            if (out != null) { try { out.close(); } catch (IOException ignored) { } }
            if (connection != null) { connection.disconnect(); }
        }
    }

    /**
     * Telegram's DC IP currently emits "HTTP/1.1  200" (two spaces). JDK 8's
     * HttpURLConnection rejects that otherwise harmless response, while Qt and
     * many handset HTTP stacks accept it. Keep the live oracle deliberately
     * lenient so it exercises Telegram rather than the desktop JDK parser.
     */
    private HttpResponse postPlain(URL url, byte[] body, int maxResponse)
            throws IOException
    {
        int port = url.getPort() < 0 ? 80 : url.getPort();
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(url.getHost(), port), 30000);
        socket.setSoTimeout(60000);
        InputStream in = null;
        OutputStream out = null;
        try
        {
            in = socket.getInputStream();
            out = socket.getOutputStream();
            String request = "POST " + url.getFile() + " HTTP/1.1\r\n"
                    + "Host: " + url.getHost() + "\r\n"
                    + "Content-Type: application/x-www-form-urlencoded\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(request.getBytes("ISO-8859-1"));
            out.write(body);
            out.flush();

            String statusLine = readLine(in);
            String[] parts = statusLine.trim().split("\\s+");
            if (parts.length < 2) { throw new IOException("bad HTTP status: " + statusLine); }
            int status;
            try { status = Integer.parseInt(parts[1]); }
            catch (NumberFormatException e) { throw new IOException("bad HTTP status: " + statusLine); }

            int declared = -1;
            while (true)
            {
                String line = readLine(in);
                if (line.length() == 0) { break; }
                int colon = line.indexOf(':');
                if (colon > 0 && "content-length".equals(
                        line.substring(0, colon).trim().toLowerCase()))
                {
                    declared = Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            if (declared > maxResponse)
            {
                throw new IOException("HTTP response of " + declared
                                      + " exceeds " + maxResponse);
            }
            ByteArrayOutputStream bytes =
                    new ByteArrayOutputStream(declared > 0 ? declared : 512);
            byte[] chunk = new byte[4096];
            int total = 0;
            while (declared < 0 || total < declared)
            {
                int want = declared < 0 ? chunk.length
                        : Math.min(chunk.length, declared - total);
                int n = in.read(chunk, 0, want);
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
        finally
        {
            if (in != null) { try { in.close(); } catch (IOException ignored) { } }
            if (out != null) { try { out.close(); } catch (IOException ignored) { } }
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static String readLine(InputStream in) throws IOException
    {
        StringBuffer line = new StringBuffer();
        while (line.length() < 8192)
        {
            int c = in.read();
            if (c < 0)
            {
                if (line.length() == 0) { throw new IOException("HTTP eof"); }
                break;
            }
            if (c == '\n') { break; }
            if (c != '\r') { line.append((char) c); }
        }
        if (line.length() >= 8192) { throw new IOException("HTTP header line too long"); }
        return line.toString();
    }
}
