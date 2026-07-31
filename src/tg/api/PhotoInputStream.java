package tg.api;

import java.io.IOException;
import java.io.InputStream;

import tg.mt.MtClient;
import tg.tl.TlObj;
import tg.tl.TlParser;
import tg.tl.TlReader;

/**
 * upload.getFile exposed as a bounded stream. At most one 32 KiB TL bytes
 * field is retained, and closing the stream closes its dedicated MT session.
 */
public final class PhotoInputStream extends InputStream
{
    /** Small seam used by scripted tests; production wraps a dedicated MtClient. */
    public interface Source
    {
        byte[] invoke(byte[] query) throws IOException;
        void close();
    }

    public static final int CHUNK = 32 * 1024;

    private Source source;
    private final PhotoRef photo;
    private final PhotoSizeRef size;
    private final Peer avatarPeer;
    private final AvatarRef avatar;
    private final DownloadToken token;
    private byte[] chunk;
    private int chunkAt;
    private long offset;
    private boolean eof;

    PhotoInputStream(MtClient client, PhotoRef photo, PhotoSizeRef size,
                     DownloadToken token)
    {
        this(client == null ? null : new MtSource(client), photo, size, token);
    }

    public PhotoInputStream(Source source, PhotoRef photo, PhotoSizeRef size,
                            DownloadToken token)
    {
        this.source = source;
        this.photo = photo;
        this.size = size;
        this.avatarPeer = null;
        this.avatar = null;
        this.token = token == null ? new DownloadToken() : token;
        if (size.kind == PhotoSizeRef.CACHED)
        {
            chunk = size.bytes == null ? new byte[0] : size.bytes;
            offset = chunk.length;
            eof = true;
            this.token.progress(chunk.length, chunk.length);
        }
    }

    PhotoInputStream(MtClient client, Peer peer, AvatarRef avatar,
                     DownloadToken token)
    {
        this(client == null ? null : new MtSource(client), peer, avatar, token);
    }

    /** Avatar variant over an arbitrary source; see the photo constructor. */
    public PhotoInputStream(Source source, Peer peer, AvatarRef avatar,
                            DownloadToken token)
    {
        this.source = source;
        this.photo = null;
        this.size = null;
        this.avatarPeer = peer;
        this.avatar = avatar;
        this.token = token == null ? new DownloadToken() : token;
    }

    public int bytesRead() { return (int) (offset - availableInChunk()); }
    public int expectedBytes() { return size == null ? 0 : size.size; }

    public int read() throws IOException
    {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    public int read(byte[] out, int off, int len) throws IOException
    {
        if (out == null) { throw new NullPointerException(); }
        if (off < 0 || len < 0 || off + len > out.length)
        {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) { return 0; }
        checkCancelled();
        if (chunk == null || chunkAt >= chunk.length)
        {
            if (eof) { return -1; }
            fetch();
            if (chunk.length == 0) { return -1; }
        }
        int count = Math.min(len, chunk.length - chunkAt);
        System.arraycopy(chunk, chunkAt, out, off, count);
        chunkAt += count;
        return count;
    }

    private void fetch() throws IOException
    {
        checkCancelled();
        if (source == null) { eof = true; chunk = new byte[0]; return; }
        if (offset >= PhotoRef.MAX_COMPRESSED_BYTES)
        {
            throw new IOException("photo exceeds compressed memory policy");
        }
        byte[] query = avatar == null
                ? Requests.getPhotoFile(photo, size, offset, CHUNK)
                : Requests.getAvatarFile(avatarPeer, avatar, offset, CHUNK);
        byte[] body = source.invoke(query);
        int constructor = new TlReader(body).readInt();
        if (constructor == Api.UPLOAD_FILE_CDN_REDIRECT)
        {
            throw new IOException("server requested unsupported CDN redirect");
        }
        TlObj result = TlParser.parse(new TlReader(body));
        if (result == null) { throw new IOException("empty upload.getFile result"); }
        if (result.id != Api.UPLOAD_FILE)
        {
            throw new IOException("unexpected upload.getFile result 0x"
                    + Integer.toHexString(result.id));
        }
        byte[] bytes = result.bytes(Api.F_UPLOAD_FILE__BYTES);
        if (bytes == null) { bytes = new byte[0]; }
        if (bytes.length > CHUNK)
        {
            throw new IOException("oversized upload.getFile chunk " + bytes.length);
        }
        if (offset + bytes.length > PhotoRef.MAX_COMPRESSED_BYTES
                || (size != null && size.size > 0
                    && offset + bytes.length > size.size))
        {
            throw new IOException("photo data exceeds advertised bound");
        }
        chunk = bytes;
        chunkAt = 0;
        offset += bytes.length;
        eof = bytes.length < CHUNK || (size != null && size.size > 0
                && offset >= size.size);
        token.progress((int) offset, size == null ? 0 : size.size);
    }

    private int availableInChunk()
    {
        return chunk == null ? 0 : chunk.length - chunkAt;
    }

    private void checkCancelled() throws IOException
    {
        if (token.isCancelled())
        {
            close();
            throw new IOException("photo download cancelled");
        }
    }

    public void close()
    {
        Source open = source;
        source = null;
        if (open != null) { open.close(); }
        chunk = null;
        eof = true;
    }

    private static final class MtSource implements Source
    {
        private MtClient client;

        MtSource(MtClient client) { this.client = client; }

        public byte[] invoke(byte[] query) throws IOException
        {
            if (client == null) { throw new IOException("media session closed"); }
            return client.invokeWithSaltRetry(query);
        }

        public void close()
        {
            MtClient open = client;
            client = null;
            if (open != null) { open.close(); }
        }
    }
}
