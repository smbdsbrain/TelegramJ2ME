package tg.api;

import tg.tl.TlObj;

/** One downloadable or inline size of a Telegram photo. */
public final class PhotoSizeRef
{
    public static final int REMOTE = 1;
    public static final int CACHED = 2;
    public static final int STRIPPED = 3;
    public static final int PROGRESSIVE = 4;

    public int kind;
    public String type = "";
    public int width;
    public int height;
    public int size;
    public byte[] bytes;

    static PhotoSizeRef from(TlObj obj)
    {
        if (obj == null) { return null; }
        PhotoSizeRef out = new PhotoSizeRef();
        if (obj.id == Api.PHOTO_STRIPPED_SIZE)
        {
            out.kind = STRIPPED;
            out.type = obj.strOrEmpty(Api.F_PHOTO_STRIPPED_SIZE__TYPE);
            out.bytes = obj.bytes(Api.F_PHOTO_STRIPPED_SIZE__BYTES);
            if (out.bytes != null && out.bytes.length >= 3)
            {
                out.height = out.bytes[1] & 0xff;
                out.width = out.bytes[2] & 0xff;
            }
            return out;
        }
        if (obj.id == Api.PHOTO_CACHED_SIZE)
        {
            out.kind = CACHED;
            out.type = obj.strOrEmpty(Api.F_PHOTO_CACHED_SIZE__TYPE);
            out.width = obj.intAt(Api.F_PHOTO_CACHED_SIZE__W);
            out.height = obj.intAt(Api.F_PHOTO_CACHED_SIZE__H);
            out.bytes = obj.bytes(Api.F_PHOTO_CACHED_SIZE__BYTES);
            out.size = out.bytes == null ? 0 : out.bytes.length;
            return out;
        }
        if (obj.id == Api.PHOTO_SIZE)
        {
            out.kind = REMOTE;
            out.type = obj.strOrEmpty(Api.F_PHOTO_SIZE__TYPE);
            out.width = obj.intAt(Api.F_PHOTO_SIZE__W);
            out.height = obj.intAt(Api.F_PHOTO_SIZE__H);
            out.size = obj.intAt(Api.F_PHOTO_SIZE__SIZE);
            return out;
        }
        if (obj.id == Api.PHOTO_SIZE_PROGRESSIVE)
        {
            out.kind = PROGRESSIVE;
            out.type = obj.strOrEmpty(Api.F_PHOTO_SIZE_PROGRESSIVE__TYPE);
            out.width = obj.intAt(Api.F_PHOTO_SIZE_PROGRESSIVE__W);
            out.height = obj.intAt(Api.F_PHOTO_SIZE_PROGRESSIVE__H);
            long[] sizes = obj.longVec(Api.F_PHOTO_SIZE_PROGRESSIVE__SIZES);
            out.size = sizes.length == 0 ? 0 : (int) sizes[sizes.length - 1];
            return out;
        }
        return null;
    }
}
