package tg.api;

import tg.tl.TlObj;

/**
 * A chat the user can talk to: a person, a basic group, or a channel/supergroup.
 *
 * <h3>Why access_hash lives here</h3>
 * Telegram identifies a peer by id, but to *address* one you also need its
 * access_hash - a per-account token proving you are entitled to reference it.
 * The hash never appears in a {@code Peer} constructor; it only comes with the
 * {@code User} or {@code Chat} objects that accompany a dialog list or message
 * batch. A client that keeps the id and drops the hash cannot send anything.
 *
 * So this type carries both, and {@link PeerCache} is what fills in the hash.
 */
public final class Peer
{
    public static final int USER = 0;
    public static final int CHAT = 1;
    public static final int CHANNEL = 2;

    public int kind;
    public long id;
    public long accessHash;

    /** Display name: full name for a user, title for a group or channel. */
    public String title = "";

    /** Kept separately so the self-profile editor can preserve either part. */
    public String firstName = "";
    public String lastName = "";

    /** @username without the @, or null. */
    public String username;

    public boolean self;
    public boolean premium;

    /**
     * A supergroup with topics enabled. Read from {@code channel.forum} on
     * every construction from a full {@code channel} object, so
     * {@link PeerCache#absorb} keeps it fresh; the reference form and
     * {@code channelForbidden} leave it false, which is the safe direction -
     * a forum mistaken for a plain chat opens flat, a plain chat mistaken for
     * a forum asks the server a question it refuses.
     */
    public boolean forum;

    /** Current compact avatar reference, or null when there is no photo. */
    public AvatarRef avatar;

    public Peer(int kind, long id)
    {
        this.kind = kind;
        this.id = id;
    }

    /** Read a {@code Peer} constructor - the reference form, with no hash. */
    public static Peer fromPeerObj(TlObj obj)
    {
        if (obj == null)
        {
            return null;
        }
        if (obj.id == Api.PEER_USER)
        {
            return new Peer(USER, obj.num(Api.F_PEER_USER__USER_ID));
        }
        if (obj.id == Api.PEER_CHAT)
        {
            return new Peer(CHAT, obj.num(Api.F_PEER_CHAT__CHAT_ID));
        }
        if (obj.id == Api.PEER_CHANNEL)
        {
            return new Peer(CHANNEL, obj.num(Api.F_PEER_CHANNEL__CHANNEL_ID));
        }
        return null;
    }

    /** Read a {@code User} constructor, which does carry the access_hash. */
    public static Peer fromUser(TlObj obj)
    {
        if (obj == null || obj.id != Api.USER)
        {
            return null;
        }
        Peer p = new Peer(USER, obj.num(Api.F_USER__ID));
        p.accessHash = obj.num(Api.F_USER__ACCESS_HASH);
        p.username = obj.str(Api.F_USER__USERNAME);
        p.self = obj.num(Api.F_USER__SELF) != 0;
        p.premium = obj.num(Api.F_USER__PREMIUM) != 0;
        p.avatar = AvatarRef.from(obj.obj(Api.F_USER__PHOTO));

        String first = obj.strOrEmpty(Api.F_USER__FIRST_NAME);
        String last = obj.strOrEmpty(Api.F_USER__LAST_NAME);
        p.firstName = first;
        p.lastName = last;
        StringBuffer sb = new StringBuffer(first.length() + last.length() + 1);
        sb.append(first);
        if (last.length() > 0)
        {
            if (sb.length() > 0) { sb.append(' '); }
            sb.append(last);
        }
        if (sb.length() == 0)
        {
            // Deleted accounts have no name at all; showing a blank row would
            // look like a bug rather than like a deleted account.
            sb.append(obj.num(Api.F_USER__DELETED) != 0 ? "Deleted account" : "User");
        }
        p.title = sb.toString();
        return p;
    }

    /**
     * Read a {@code Chat} constructor. Covers all four forms - basic groups,
     * channels, and the "forbidden" variants the server sends for chats the
     * user was removed from, which still appear in a dialog list.
     */
    public static Peer fromChat(TlObj obj)
    {
        if (obj == null)
        {
            return null;
        }
        if (obj.id == Api.CHAT)
        {
            Peer p = new Peer(CHAT, obj.num(Api.F_CHAT__ID));
            p.title = obj.strOrEmpty(Api.F_CHAT__TITLE);
            p.avatar = AvatarRef.from(obj.obj(Api.F_CHAT__PHOTO));
            return p;
        }
        if (obj.id == Api.CHAT_FORBIDDEN)
        {
            Peer p = new Peer(CHAT, obj.num(Api.F_CHAT_FORBIDDEN__ID));
            p.title = obj.strOrEmpty(Api.F_CHAT_FORBIDDEN__TITLE);
            return p;
        }
        if (obj.id == Api.CHANNEL)
        {
            Peer p = new Peer(CHANNEL, obj.num(Api.F_CHANNEL__ID));
            p.accessHash = obj.num(Api.F_CHANNEL__ACCESS_HASH);
            p.title = obj.strOrEmpty(Api.F_CHANNEL__TITLE);
            p.username = obj.str(Api.F_CHANNEL__USERNAME);
            p.avatar = AvatarRef.from(obj.obj(Api.F_CHANNEL__PHOTO));
            p.forum = obj.num(Api.F_CHANNEL__FORUM) != 0;
            return p;
        }
        if (obj.id == Api.CHANNEL_FORBIDDEN)
        {
            Peer p = new Peer(CHANNEL, obj.num(Api.F_CHANNEL_FORBIDDEN__ID));
            p.accessHash = obj.num(Api.F_CHANNEL_FORBIDDEN__ACCESS_HASH);
            p.title = obj.strOrEmpty(Api.F_CHANNEL_FORBIDDEN__TITLE);
            return p;
        }
        return null;
    }

    /** Stable identity for the cache: kind and id together. */
    public String key()
    {
        return kind + ":" + id;
    }

    public static String key(int kind, long id)
    {
        return kind + ":" + id;
    }

    public String toString()
    {
        return title + " (" + key() + ")";
    }
}
