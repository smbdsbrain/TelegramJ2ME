package tg.api;

import tg.tl.TlObj;

/** Displayable and, when possible, navigable origin of a forwarded message. */
public final class ForwardInfo
{
    public String label = "Forwarded";
    public Peer source;
    public int messageId;

    public boolean canOpen(PeerCache peers)
    {
        if (source == null || messageId <= 0) { return false; }
        return peers.isAddressable(source)
                || (source.username != null && source.username.length() > 0);
    }

    static ForwardInfo from(TlObj header, PeerCache peers)
    {
        if (header == null || header.id != Api.MESSAGE_FWD_HEADER)
        {
            return null;
        }
        ForwardInfo out = new ForwardInfo();
        Peer origin = resolve(header.obj(Api.F_MESSAGE_FWD_HEADER__FROM_ID), peers);
        String name = header.strOrEmpty(Api.F_MESSAGE_FWD_HEADER__FROM_NAME);
        if (origin != null && origin.title != null && origin.title.length() > 0)
        {
            name = origin.title;
        }
        if (name.length() > 0) { out.label = "Forwarded from " + name; }

        Peer saved = resolve(
                header.obj(Api.F_MESSAGE_FWD_HEADER__SAVED_FROM_PEER), peers);
        int savedId = header.intAt(
                Api.F_MESSAGE_FWD_HEADER__SAVED_FROM_MSG_ID);
        if (saved != null && savedId > 0)
        {
            out.source = saved;
            out.messageId = savedId;
        }
        else
        {
            int channelPost = header.intAt(
                    Api.F_MESSAGE_FWD_HEADER__CHANNEL_POST);
            if (origin != null && origin.kind == Peer.CHANNEL
                    && channelPost > 0)
            {
                out.source = origin;
                out.messageId = channelPost;
            }
        }
        return out;
    }

    private static Peer resolve(TlObj obj, PeerCache peers)
    {
        Peer reference = Peer.fromPeerObj(obj);
        if (reference == null) { return null; }
        return peers == null ? reference : peers.resolve(reference);
    }
}
