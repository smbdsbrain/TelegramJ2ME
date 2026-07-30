package tg.api;

import tg.tl.TlObj;

/**
 * Compact peer-photo reference carried by User/Chat objects in dialog replies.
 *
 * Telegram supplies a peer, photo id and media DC rather than a full Photo.
 * Those fields are enough for inputPeerPhotoFileLocation.
 */
public final class AvatarRef
{
    public long photoId;
    public int dcId;
    public byte[] strippedThumb;

    static AvatarRef from(TlObj photo)
    {
        if (photo == null
                || (photo.id != Api.USER_PROFILE_PHOTO
                    && photo.id != Api.CHAT_PHOTO))
        {
            return null;
        }
        AvatarRef out = new AvatarRef();
        if (photo.id == Api.USER_PROFILE_PHOTO)
        {
            out.photoId = photo.num(Api.F_USER_PROFILE_PHOTO__PHOTO_ID);
            out.dcId = photo.intAt(Api.F_USER_PROFILE_PHOTO__DC_ID);
            out.strippedThumb = photo.bytes(
                    Api.F_USER_PROFILE_PHOTO__STRIPPED_THUMB);
        }
        else
        {
            out.photoId = photo.num(Api.F_CHAT_PHOTO__PHOTO_ID);
            out.dcId = photo.intAt(Api.F_CHAT_PHOTO__DC_ID);
            out.strippedThumb = photo.bytes(
                    Api.F_CHAT_PHOTO__STRIPPED_THUMB);
        }
        return out.photoId == 0 || out.dcId < 1 ? null : out;
    }
}
