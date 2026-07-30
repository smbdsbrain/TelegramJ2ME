package tgtest;

import java.io.IOException;
import java.util.Vector;

import tg.api.Api;
import tg.api.DownloadToken;
import tg.api.PhotoInputStream;
import tg.api.PhotoRef;
import tg.api.PhotoSizeRef;
import tg.tl.TlReader;
import tg.tl.TlObj;
import tg.tl.TlParser;
import tg.tl.TlWriter;

/** Scripted upload.getFile chunk, bound, EOF, CDN and cancellation tests. */
public final class PhotoStreamTest implements Test
{
    public String name() { return "phase4/photo-stream-chunks"; }

    public void run() throws Exception
    {
        chunkedEof();
        emptyEof();
        cancellation();
        oversizedChunk();
        advertisedBound();
        cdnRedirect();
    }

    private static void chunkedEof() throws Exception
    {
        byte[] content = new byte[70000];
        for (int i = 0; i < content.length; i++) { content[i] = (byte) i; }
        Script source = new Script(content);
        PhotoInputStream in = stream(source, content.length, new DownloadToken());
        byte[] read = new byte[content.length];
        int at = 0;
        while (at < read.length)
        {
            int count = in.read(read, at, Math.min(7777, read.length - at));
            if (count < 0) { break; }
            at += count;
        }
        Assert.equal("stream length", content.length, at);
        Assert.bytesEqual("stream bytes", content, read);
        Assert.equal("chunk RPC count", 3, source.offsets.size());
        Assert.equal("offset 0", 0L,
                ((Long) source.offsets.elementAt(0)).longValue());
        Assert.equal("offset 32K", PhotoInputStream.CHUNK,
                ((Long) source.offsets.elementAt(1)).longValue());
        Assert.equal("offset 64K", PhotoInputStream.CHUNK * 2L,
                ((Long) source.offsets.elementAt(2)).longValue());
        Assert.equal("bytesRead", content.length, in.bytesRead());
        in.close();
        Assert.isTrue("source closed", source.closed);
    }

    private static void emptyEof() throws Exception
    {
        Script source = new Script(new byte[0]);
        PhotoInputStream in = stream(source, 0, new DownloadToken());
        Assert.equal("empty EOF", -1, in.read());
        Assert.equal("one empty request", 1, source.offsets.size());
        in.close();
    }

    private static void cancellation() throws Exception
    {
        Script source = new Script(new byte[10]);
        DownloadToken token = new DownloadToken();
        PhotoInputStream in = stream(source, 10, token);
        token.cancel();
        try
        {
            in.read();
            Assert.fail("cancelled stream read");
        }
        catch (IOException expected)
        {
            Assert.isTrue("cancel message",
                    expected.getMessage().indexOf("cancel") >= 0);
        }
        Assert.isTrue("cancel closes source", source.closed);
        Assert.equal("cancel before RPC", 0, source.offsets.size());
    }

    private static void oversizedChunk() throws Exception
    {
        Script source = new Script(new byte[PhotoInputStream.CHUNK + 1]);
        source.ignoreLimit = true;
        PhotoInputStream in = stream(source, PhotoInputStream.CHUNK + 1,
                new DownloadToken());
        expectReadFailure("oversized chunk", in, "oversized");
        in.close();
    }

    private static void advertisedBound() throws Exception
    {
        Script source = new Script(new byte[101]);
        PhotoInputStream in = stream(source, 100, new DownloadToken());
        expectReadFailure("advertised bound", in, "advertised");
        in.close();
    }

    private static void cdnRedirect() throws Exception
    {
        Script source = new Script(new byte[0]);
        source.cdn = true;
        PhotoInputStream in = stream(source, 100, new DownloadToken());
        expectReadFailure("CDN redirect", in, "CDN");
        in.close();
    }

    private static void expectReadFailure(String name, PhotoInputStream in,
                                          String text) throws Exception
    {
        try
        {
            in.read();
            Assert.fail(name + " accepted");
        }
        catch (IOException expected)
        {
            Assert.isTrue(name + " message",
                    expected.getMessage().indexOf(text) >= 0);
        }
    }

    private static PhotoInputStream stream(Script source, int expected,
                                           DownloadToken token)
    {
        PhotoRef photo = new PhotoRef();
        photo.id = 11;
        photo.accessHash = 22;
        photo.fileReference = new byte[] { 1 };
        PhotoSizeRef size = new PhotoSizeRef();
        size.kind = PhotoSizeRef.REMOTE;
        size.type = "m";
        size.width = 320;
        size.height = 240;
        size.size = expected;
        return new PhotoInputStream(source, photo, size, token);
    }

    private static final class Script implements PhotoInputStream.Source
    {
        final byte[] content;
        final Vector offsets = new Vector();
        boolean cdn;
        boolean ignoreLimit;
        boolean closed;

        Script(byte[] content) { this.content = content; }

        public byte[] invoke(byte[] query) throws IOException
        {
            TlReader reader = new TlReader(query);
            Assert.equal("getFile method", Api.UPLOAD_GET_FILE, reader.readInt());
            reader.readInt();
            reader.readInt();
            reader.readLong();
            reader.readLong();
            reader.readBytes();
            reader.readString();
            long offset = reader.readLong();
            int limit = reader.readInt();
            Assert.isTrue("4K-aligned offset", (offset & 4095) == 0);
            Assert.isTrue("4K-aligned limit", (limit & 4095) == 0);
            offsets.addElement(new Long(offset));
            if (cdn)
            {
                TlWriter redirect = new TlWriter(4);
                redirect.writeInt(Api.UPLOAD_FILE_CDN_REDIRECT);
                return redirect.toByteArray();
            }
            int count = ignoreLimit ? content.length : (int) Math.min(limit,
                    Math.max(0L, content.length - offset));
            byte[] part = new byte[count];
            if (count > 0) { System.arraycopy(content, (int) offset, part, 0, count); }
            TlWriter result = new TlWriter(count + 16);
            result.writeInt(Api.UPLOAD_FILE);
            result.writeInt(Api.STORAGE_FILE_JPEG);
            result.writeInt(0);
            result.writeBytes(part);
            byte[] raw = result.toByteArray();
            TlObj parsed = TlParser.parse(new TlReader(raw));
            byte[] parsedBytes = parsed.bytes(Api.F_UPLOAD_FILE__BYTES);
            Assert.equal("raw bytes parser", count,
                    parsedBytes == null ? -1 : parsedBytes.length);
            return raw;
        }

        public void close() { closed = true; }
    }
}
