package tg.api;

import java.io.IOException;

import tg.tl.TlObj;

/** Stable fields needed by inputPhotoFileLocation and the photo viewer. */
public final class PhotoRef
{
    public static final int MAX_COMPRESSED_BYTES = 512 * 1024;
    public static final int MAX_DECODED_PIXELS = 307200;

    public long id;
    public long accessHash;
    public byte[] fileReference;
    public int dcId;
    public PhotoSizeRef[] sizes = new PhotoSizeRef[0];

    static PhotoRef from(TlObj photo)
    {
        if (photo == null || photo.id != Api.PHOTO) { return null; }
        PhotoRef out = new PhotoRef();
        out.id = photo.num(Api.F_PHOTO__ID);
        out.accessHash = photo.num(Api.F_PHOTO__ACCESS_HASH);
        out.fileReference = photo.bytes(Api.F_PHOTO__FILE_REFERENCE);
        out.dcId = photo.intAt(Api.F_PHOTO__DC_ID);
        TlObj[] raw = photo.vec(Api.F_PHOTO__SIZES);
        PhotoSizeRef[] all = new PhotoSizeRef[raw.length];
        int count = 0;
        for (int i = 0; i < raw.length; i++)
        {
            PhotoSizeRef size = PhotoSizeRef.from(raw[i]);
            if (size != null) { all[count++] = size; }
        }
        out.sizes = new PhotoSizeRef[count];
        System.arraycopy(all, 0, out.sizes, 0, count);
        return out;
    }

    public PhotoSizeRef stripped()
    {
        for (int i = 0; i < sizes.length; i++)
        {
            if (sizes[i].kind == PhotoSizeRef.STRIPPED) { return sizes[i]; }
        }
        return null;
    }

    /**
     * Smallest usable image that covers the viewport; otherwise the largest
     * bounded image. Cached bytes win when they already cover the screen.
     */
    public PhotoSizeRef choose(int width, int height) throws IOException
    {
        PhotoSizeRef covering = null;
        PhotoSizeRef fallback = null;
        for (int i = 0; i < sizes.length; i++)
        {
            PhotoSizeRef s = sizes[i];
            if (s.kind == PhotoSizeRef.STRIPPED || s.width <= 0 || s.height <= 0)
            {
                continue;
            }
            if (s.size <= 0 || s.size > MAX_COMPRESSED_BYTES
                    || area(s) > MAX_DECODED_PIXELS)
            {
                continue;
            }
            if (fallback == null || area(s) > area(fallback)) { fallback = s; }
            if (s.width >= width || s.height >= height)
            {
                if (covering == null || area(s) < area(covering)
                        || (area(s) == area(covering)
                                && s.kind == PhotoSizeRef.CACHED))
                {
                    covering = s;
                }
            }
        }
        PhotoSizeRef chosen = covering == null ? fallback : covering;
        if (chosen == null)
        {
            throw new IOException("photo has no bounded display size");
        }
        return chosen;
    }

    private static long area(PhotoSizeRef s)
    {
        return (long) s.width * (long) s.height;
    }
}
