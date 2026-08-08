package tg.api;

import tg.app.Secrets;
import tg.mt.Layer;
import tg.mt.Srp;
import tg.tl.TlWriter;

/**
 * Builders for the Telegram API calls this client makes.
 *
 * Requests are hand-built rather than generated. Generating serialisers as well
 * as the parse table would double the generator and most of the value is on the
 * reading side: a response can be any of hundreds of types and has to be walked
 * blind, whereas we send exactly the dozen calls below and know each one's
 * shape. Every builder cites its TL declaration so it can be checked against
 * the schema by eye.
 *
 * Field ids come from {@link Api}, which is generated - so a constructor id
 * that changes between layers is caught at build time rather than by the server
 * ignoring us.
 */
public final class Requests
{
    private Requests() { }

    // ------------------------------------------------------------ helpers

    /** inputPeerEmpty#7f3b18ea = InputPeer */
    public static void writeInputPeerEmpty(TlWriter w)
    {
        w.writeInt(Api.INPUT_PEER_EMPTY);
    }

    /** inputPeerSelf#7da07ec9 = InputPeer */
    public static void writeInputPeerSelf(TlWriter w)
    {
        w.writeInt(Api.INPUT_PEER_SELF);
    }

    /** inputPeerUser#dde8a54c user_id:long access_hash:long = InputPeer */
    public static void writeInputPeerUser(TlWriter w, long userId, long accessHash)
    {
        w.writeInt(Api.INPUT_PEER_USER);
        w.writeLong(userId);
        w.writeLong(accessHash);
    }

    /** inputPeerChat#35a95cb9 chat_id:long = InputPeer */
    public static void writeInputPeerChat(TlWriter w, long chatId)
    {
        w.writeInt(Api.INPUT_PEER_CHAT);
        w.writeLong(chatId);
    }

    /** inputPeerChannel#27bcbbfc channel_id:long access_hash:long = InputPeer */
    public static void writeInputPeerChannel(TlWriter w, long channelId, long accessHash)
    {
        w.writeInt(Api.INPUT_PEER_CHANNEL);
        w.writeLong(channelId);
        w.writeLong(accessHash);
    }

    /**
     * Write whichever InputPeer a {@link Peer} corresponds to.
     *
     * A peer reference is not enough on its own: users and channels also need
     * an access_hash, which only appears in the User/Chat objects that came
     * with the dialog list. That is why {@link PeerCache} exists.
     */
    public static void writeInputPeer(TlWriter w, Peer peer)
    {
        if (peer == null)
        {
            writeInputPeerEmpty(w);
            return;
        }
        switch (peer.kind)
        {
            case Peer.USER:
                writeInputPeerUser(w, peer.id, peer.accessHash);
                break;
            case Peer.CHAT:
                writeInputPeerChat(w, peer.id);
                break;
            case Peer.CHANNEL:
                writeInputPeerChannel(w, peer.id, peer.accessHash);
                break;
            default:
                writeInputPeerEmpty(w);
                break;
        }
    }

    public static void writeInputUserSelf(TlWriter w)
    {
        w.writeInt(Api.INPUT_USER_SELF);
    }

    public static void writeInputUser(TlWriter w, Peer user)
    {
        if (user == null || user.self)
        {
            writeInputUserSelf(w);
            return;
        }
        w.writeInt(Api.INPUT_USER);
        w.writeLong(user.id);
        w.writeLong(user.accessHash);
    }

    // --------------------------------------------------------------- login

    /**
     * auth.sendCode#a677244f phone_number:string api_id:int api_hash:string
     *                        settings:CodeSettings = auth.SentCode
     *
     * codeSettings flags are all zero: this client cannot receive a flash call,
     * read an SMS itself, or use Firebase, and claiming otherwise would make
     * Telegram choose a delivery method that never arrives.
     */
    public static byte[] sendCode(String phoneNumber)
    {
        TlWriter w = new TlWriter(128);
        w.writeInt(Api.AUTH_SEND_CODE);
        w.writeString(phoneNumber);
        w.writeInt(Secrets.API_ID);
        w.writeString(Secrets.API_HASH);
        w.writeInt(Api.CODE_SETTINGS);
        w.writeInt(0);                      // flags: nothing we can support
        return w.toByteArray();
    }

    /**
     * auth.signIn#8d52a951 flags:# phone_number:string phone_code_hash:string
     *                      phone_code:flags.0?string
     *                      email_verification:flags.1?EmailVerification
     *                      = auth.Authorization
     */
    public static byte[] signIn(String phoneNumber, String phoneCodeHash, String code)
    {
        TlWriter w = new TlWriter(128);
        w.writeInt(Api.AUTH_SIGN_IN);
        w.writeInt(1);                      // flags.0: phone_code present
        w.writeString(phoneNumber);
        w.writeString(phoneCodeHash);
        w.writeString(code);
        return w.toByteArray();
    }

    /**
     * auth.signUp#aac7b717 flags:# no_joined_notifications:flags.0?true
     *     phone_number:string phone_code_hash:string first_name:string
     *     last_name:string = auth.Authorization
     *
     * Reached when signIn answers PHONE_NUMBER_UNOCCUPIED, which on the test
     * data centres is the normal path: a test number has no account until one
     * is created.
     */
    public static byte[] signUp(String phoneNumber, String phoneCodeHash,
                                String firstName, String lastName)
    {
        TlWriter w = new TlWriter(160);
        w.writeInt(Api.AUTH_SIGN_UP);
        w.writeInt(0);                      // flags
        w.writeString(phoneNumber);
        w.writeString(phoneCodeHash);
        w.writeString(firstName);
        w.writeString(lastName);
        return w.toByteArray();
    }

    /**
     * auth.resendCode#cae47523 flags:# phone_number:string
     *     phone_code_hash:string reason:flags.0?string = auth.SentCode
     */
    public static byte[] resendCode(String phoneNumber, String phoneCodeHash)
    {
        TlWriter w = new TlWriter(128);
        w.writeInt(Api.AUTH_RESEND_CODE);
        w.writeInt(0);                      // no Firebase/device-integrity reason
        w.writeString(phoneNumber);
        w.writeString(phoneCodeHash);
        return w.toByteArray();
    }

    /** auth.cancelCode#1f040578 phone_number:string phone_code_hash:string = Bool */
    public static byte[] cancelCode(String phoneNumber, String phoneCodeHash)
    {
        TlWriter w = new TlWriter(128);
        w.writeInt(Api.AUTH_CANCEL_CODE);
        w.writeString(phoneNumber);
        w.writeString(phoneCodeHash);
        return w.toByteArray();
    }

    /** account.getPassword#548a30f5 = account.Password */
    public static byte[] getPassword()
    {
        TlWriter w = new TlWriter(8);
        w.writeInt(Api.ACCOUNT_GET_PASSWORD);
        return w.toByteArray();
    }

    /**
     * auth.checkPassword#d18b4d16
     *     password:inputCheckPasswordSRP = auth.Authorization
     */
    public static byte[] checkPassword(Srp.Check check)
    {
        TlWriter w = new TlWriter(320);
        w.writeInt(Api.AUTH_CHECK_PASSWORD);
        w.writeInt(Api.INPUT_CHECK_PASSWORD_S_R_P);
        w.writeLong(check.id);
        w.writeBytes(check.a);
        w.writeBytes(check.m1);
        return w.toByteArray();
    }

    /** auth.logOut#3e72ba19 = auth.LoggedOut */
    public static byte[] logOut()
    {
        TlWriter w = new TlWriter(8);
        w.writeInt(Api.AUTH_LOG_OUT);
        return w.toByteArray();
    }

    /** auth.resetAuthorizations#9fab0d1a = Bool */
    public static byte[] resetAuthorizations()
    {
        TlWriter w = new TlWriter(8);
        w.writeInt(Api.AUTH_RESET_AUTHORIZATIONS);
        return w.toByteArray();
    }

    /** auth.exportAuthorization#e5bfffcd dc_id:int = auth.ExportedAuthorization */
    public static byte[] exportAuthorization(int dcId)
    {
        TlWriter w = new TlWriter(12);
        w.writeInt(Api.AUTH_EXPORT_AUTHORIZATION);
        w.writeInt(dcId);
        return w.toByteArray();
    }

    /** auth.importAuthorization#a57a7dad id:long bytes:bytes = auth.Authorization */
    public static byte[] importAuthorization(long id, byte[] bytes)
    {
        TlWriter w = new TlWriter((bytes == null ? 0 : bytes.length) + 24);
        w.writeInt(Api.AUTH_IMPORT_AUTHORIZATION);
        w.writeLong(id);
        w.writeBytes(bytes == null ? new byte[0] : bytes);
        return w.toByteArray();
    }

    // ------------------------------------------------------------- dialogs

    /**
     * messages.getDialogs#a0f4cb4f flags:# exclude_pinned:flags.0?true
     *     folder_id:flags.1?int offset_date:int offset_id:int
     *     offset_peer:InputPeer limit:int hash:long = messages.Dialogs
     */
    public static byte[] getDialogs(int limit)
    {
        return getDialogs(null, limit, 0);
    }

    public static byte[] getDialogs(Dialog offset, int limit)
    {
        return getDialogs(offset, limit, 0);
    }

    /**
     * @param hash of the list already held, or 0 to force a full response. Only
     *             ever non-zero with a null offset: a hash describes a list
     *             from its start, and every later page is asked for by
     *             {@code (offset_date, offset_id, offset_peer)}.
     *
     * <p>The client sends 0, and that is a measurement rather than a shrug.
     * Production DC2, 2026-08-01, one page of thirty against a real account:
     * the control with hash 0 came back full, and so did all three candidate
     * vectors - the top messages, the peer ids, and the per-dialog
     * {@code (pinned, peer, top, unread)} tuple - each folded with the
     * documented algorithm. Not one produced {@code messages.dialogsNotModified}.
     * Consistent with the official clients, which never send a non-zero hash
     * here either. The parameter and {@link PageHash} stay so the next attempt
     * starts from that evidence instead of repeating it; see
     * {@code tgtest.LiveDialogHashTest} to re-run it.
     */
    public static byte[] getDialogs(Dialog offset, int limit, long hash)
    {
        TlWriter w = new TlWriter(64);
        w.writeInt(Api.MESSAGES_GET_DIALOGS);
        w.writeInt(0);                      // flags
        w.writeInt(offset == null ? 0 : offset.date);
        w.writeInt(offset == null ? 0 : offset.topMessageId);
        if (offset == null) { writeInputPeerEmpty(w); }
        else { writeInputPeer(w, offset.peer); }
        w.writeInt(limit);
        w.writeLong(offset == null ? hash : 0);
        return w.toByteArray();
    }

    /**
     * messages.getHistory#4423e6c5 peer:InputPeer offset_id:int offset_date:int
     *     add_offset:int limit:int max_id:int min_id:int hash:long
     *     = messages.Messages
     */
    public static byte[] getHistory(Peer peer, int limit)
    {
        return getHistoryBefore(peer, 0, limit);
    }

    public static byte[] getHistoryBefore(Peer peer, int offsetId, int limit)
    {
        TlWriter w = new TlWriter(64);
        w.writeInt(Api.MESSAGES_GET_HISTORY);
        writeInputPeer(w, peer);
        w.writeInt(offsetId);
        w.writeInt(0);                      // offset_date
        w.writeInt(0);                      // add_offset
        w.writeInt(limit);
        w.writeInt(0);                      // max_id
        w.writeInt(0);                      // min_id
        w.writeLong(0);                     // hash
        return w.toByteArray();
    }

    /**
     * The page immediately newer than {@code offsetId}.
     *
     * A negative {@code add_offset} walks back up the result the server would
     * have returned, which is the only way to ask for newer messages without
     * knowing their ids. Needed because a reader who has scrolled far enough
     * back has pushed the newest messages out of the retained window, and
     * scrolling forward again has to be able to fetch them a second time.
     */
    public static byte[] getHistoryAfter(Peer peer, int offsetId, int limit)
    {
        TlWriter w = new TlWriter(64);
        w.writeInt(Api.MESSAGES_GET_HISTORY);
        writeInputPeer(w, peer);
        w.writeInt(offsetId);
        w.writeInt(0);                      // offset_date
        w.writeInt(-limit);                 // add_offset
        w.writeInt(limit);
        w.writeInt(0);                      // max_id
        w.writeInt(0);                      // min_id
        w.writeLong(0);                     // hash
        return w.toByteArray();
    }

    /** Load a bounded window centred around one known message id. */
    public static byte[] getHistoryAround(Peer peer, int messageId, int limit)
    {
        TlWriter w = new TlWriter(64);
        w.writeInt(Api.MESSAGES_GET_HISTORY);
        writeInputPeer(w, peer);
        w.writeInt(messageId);
        w.writeInt(0);
        w.writeInt(-(limit / 2));
        w.writeInt(limit);
        w.writeInt(0);
        w.writeInt(0);
        w.writeLong(0);
        return w.toByteArray();
    }

    /**
     * messages.sendMessage#545cd15a flags:# ... peer:InputPeer
     *     reply_to:flags.0?InputReplyTo message:string random_id:long ...
     *     = Updates
     *
     * random_id deduplicates: if the reply is lost and the client retries with
     * the same value, Telegram will not deliver the message twice. On a
     * connection that drops as often as GPRS this is not a nicety.
     */
    public static byte[] sendMessage(Peer peer, String text, long randomId)
    {
        return sendMessage(peer, text, randomId, 0);
    }

    public static byte[] sendMessage(Peer peer, String text, long randomId,
                                     int replyToMessageId)
    {
        TlWriter w = new TlWriter(text.length() * 2 + 64);
        w.writeInt(Api.MESSAGES_SEND_MESSAGE);
        w.writeInt(replyToMessageId > 0 ? 1 : 0);
        writeInputPeer(w, peer);
        if (replyToMessageId > 0)
        {
            w.writeInt(Api.INPUT_REPLY_TO_MESSAGE);
            w.writeInt(0);
            w.writeInt(replyToMessageId);
        }
        w.writeString(text);
        w.writeLong(randomId);
        return w.toByteArray();
    }

    public static byte[] forwardMessage(Peer from, int messageId,
                                        Peer to, long randomId)
    {
        TlWriter w = new TlWriter(96);
        w.writeInt(Api.MESSAGES_FORWARD_MESSAGES);
        w.writeInt(0);
        writeInputPeer(w, from);
        w.writeVectorHeader(1);
        w.writeInt(messageId);
        w.writeVectorHeader(1);
        w.writeLong(randomId);
        writeInputPeer(w, to);
        return w.toByteArray();
    }

    public static byte[] deleteMessages(int messageId, boolean revoke)
    {
        TlWriter w = new TlWriter(32);
        w.writeInt(Api.MESSAGES_DELETE_MESSAGES);
        w.writeInt(revoke ? 1 : 0);
        w.writeVectorHeader(1);
        w.writeInt(messageId);
        return w.toByteArray();
    }

    public static byte[] deleteChannelMessage(Peer channel, int messageId)
    {
        TlWriter w = new TlWriter(48);
        w.writeInt(Api.CHANNELS_DELETE_MESSAGES);
        w.writeInt(Api.INPUT_CHANNEL);
        w.writeLong(channel.id);
        w.writeLong(channel.accessHash);
        w.writeVectorHeader(1);
        w.writeInt(messageId);
        return w.toByteArray();
    }

    /** messages.readHistory#e306d3a peer:InputPeer max_id:int = messages.AffectedMessages */
    public static byte[] readHistory(Peer peer, int maxId)
    {
        TlWriter w = new TlWriter(48);
        w.writeInt(Api.MESSAGES_READ_HISTORY);
        writeInputPeer(w, peer);
        w.writeInt(maxId);
        return w.toByteArray();
    }

    /** channels.readHistory#cc104937 channel:InputChannel max_id:int = Bool */
    public static byte[] readChannelHistory(Peer channel, int maxId)
    {
        TlWriter w = new TlWriter(48);
        w.writeInt(Api.CHANNELS_READ_HISTORY);
        w.writeInt(Api.INPUT_CHANNEL);
        w.writeLong(channel.id);
        w.writeLong(channel.accessHash);
        w.writeInt(maxId);
        return w.toByteArray();
    }

    /**
     * messages.sendReaction: send the complete ordinary-emoji reaction set.
     * An explicitly present empty vector removes all our reactions.
     */
    public static byte[] sendReactions(Peer peer, int messageId, String[] emoji)
    {
        TlWriter w = new TlWriter(64 + (emoji == null ? 0 : emoji.length * 16));
        w.writeInt(Api.MESSAGES_SEND_REACTION);
        w.writeInt(1);                      // flags.0: reaction vector present
        writeInputPeer(w, peer);
        w.writeInt(messageId);
        int count = emoji == null ? 0 : emoji.length;
        w.writeVectorHeader(count);
        for (int i = 0; i < count; i++)
        {
            w.writeInt(Api.REACTION_EMOJI);
            w.writeString(emoji[i]);
        }
        return w.toByteArray();
    }

    /** First bounded page of peers who reacted to one message. */
    public static byte[] getMessageReactions(Peer peer, int messageId, int limit)
    {
        TlWriter w = new TlWriter(64);
        w.writeInt(Api.MESSAGES_GET_MESSAGE_REACTIONS_LIST);
        w.writeInt(0);
        writeInputPeer(w, peer);
        w.writeInt(messageId);
        w.writeInt(limit);
        return w.toByteArray();
    }

    /** Current globally active normal reactions; hash=0 forces a full reply. */
    public static byte[] getAvailableReactions()
    {
        TlWriter w = new TlWriter(8);
        w.writeInt(Api.MESSAGES_GET_AVAILABLE_REACTIONS);
        w.writeInt(0);
        return w.toByteArray();
    }

    public static byte[] getFullChat(Peer chat)
    {
        if (chat == null || chat.kind != Peer.CHAT)
        {
            throw new IllegalArgumentException("basic chat is required");
        }
        TlWriter w = new TlWriter(16);
        w.writeInt(Api.MESSAGES_GET_FULL_CHAT);
        w.writeLong(chat.id);
        return w.toByteArray();
    }

    public static byte[] getFullChannel(Peer channel)
    {
        if (channel == null || channel.kind != Peer.CHANNEL)
        {
            throw new IllegalArgumentException("channel is required");
        }
        TlWriter w = new TlWriter(24);
        w.writeInt(Api.CHANNELS_GET_FULL_CHANNEL);
        w.writeInt(Api.INPUT_CHANNEL);
        w.writeLong(channel.id);
        w.writeLong(channel.accessHash);
        return w.toByteArray();
    }

    /** Resolve a public @username without a referral context. */
    /**
     * Ask Telegram for peers matching {@code query}.
     *
     * Not a message search: this answers over the account and the public
     * directory, which is the whole point - a chat the reader has not scrolled
     * to is not in the retained window and cannot be found there.
     *
     * The limit is small on purpose. Every result carries a User or Chat that
     * has to be absorbed into the peer cache, and the cache is bounded.
     */
    public static byte[] searchPeers(String query, int limit)
    {
        TlWriter w = new TlWriter(32 + query.length() * 3);
        w.writeInt(Api.CONTACTS_SEARCH);
        w.writeString(query);
        w.writeInt(limit);
        return w.toByteArray();
    }

    public static byte[] resolveUsername(String username)
    {
        TlWriter w = new TlWriter(32 + username.length() * 2);
        w.writeInt(Api.CONTACTS_RESOLVE_USERNAME);
        w.writeInt(0);
        w.writeString(username);
        return w.toByteArray();
    }

    /**
     * upload.getFile with a modern inputPhotoFileLocation.
     *
     * Flags stay zero: offsets/limits obey the ordinary 4 KiB alignment and
     * cdn_supported is deliberately not advertised by this small client.
     */
    public static byte[] getPhotoFile(PhotoRef photo, PhotoSizeRef size,
                                      long offset, int limit)
    {
        if (photo == null || size == null)
        {
            throw new IllegalArgumentException("photo and size are required");
        }
        TlWriter w = new TlWriter(96
                + (photo.fileReference == null ? 0 : photo.fileReference.length));
        w.writeInt(Api.UPLOAD_GET_FILE);
        w.writeInt(0);                      // precise=false, cdn_supported=false
        w.writeInt(Api.INPUT_PHOTO_FILE_LOCATION);
        w.writeLong(photo.id);
        w.writeLong(photo.accessHash);
        w.writeBytes(photo.fileReference == null
                ? new byte[0] : photo.fileReference);
        w.writeString(size.type);
        w.writeLong(offset);
        w.writeInt(limit);
        return w.toByteArray();
    }

    /**
     * Small current peer avatar through inputPeerPhotoFileLocation.
     *
     * big=false deliberately asks Telegram for the compact list thumbnail.
     */
    public static byte[] getAvatarFile(Peer peer, AvatarRef avatar,
                                       long offset, int limit)
    {
        if (peer == null || avatar == null)
        {
            throw new IllegalArgumentException("peer and avatar are required");
        }
        TlWriter w = new TlWriter(80);
        w.writeInt(Api.UPLOAD_GET_FILE);
        w.writeInt(0);                      // precise=false, cdn_supported=false
        w.writeInt(Api.INPUT_PEER_PHOTO_FILE_LOCATION);
        w.writeInt(0);                      // big=false
        writeInputPeer(w, peer);
        w.writeLong(avatar.photoId);
        w.writeLong(offset);
        w.writeInt(limit);
        return w.toByteArray();
    }

    /** updates.getState#edd4882a = updates.State */
    public static byte[] getUpdateState()
    {
        TlWriter w = new TlWriter(8);
        w.writeInt(Api.UPDATES_GET_STATE);
        return w.toByteArray();
    }

    /**
     * updates.getDifference#19c2f763 flags:# pts:int
     *     pts_limit:flags.1?int pts_total_limit:flags.0?int
     *     date:int qts:int qts_limit:flags.2?int = updates.Difference
     */
    public static byte[] getDifference(UpdateState state)
    {
        TlWriter w = new TlWriter(40);
        w.writeInt(Api.UPDATES_GET_DIFFERENCE);
        w.writeInt(7);                      // all three bounded limits present
        w.writeInt(state.pts);
        w.writeInt(100);                    // pts_limit: bound peak heap
        w.writeInt(1000);                   // abandon huge deltas for snapshots
        w.writeInt(state.date);
        w.writeInt(state.qts);
        w.writeInt(100);                    // qts_limit (payload is ignored)
        return w.toByteArray();
    }

    /**
     * updates.getChannelDifference#03173d78 flags:# force:flags.0?true
     *     channel:InputChannel filter:ChannelMessagesFilter pts:int limit:int
     */
    public static byte[] getChannelDifference(Peer channel, int pts)
    {
        TlWriter w = new TlWriter(48);
        w.writeInt(Api.UPDATES_GET_CHANNEL_DIFFERENCE);
        w.writeInt(0);                      // do not skip possibly useful updates
        w.writeInt(Api.INPUT_CHANNEL);
        w.writeLong(channel.id);
        w.writeLong(channel.accessHash);
        w.writeInt(Api.CHANNEL_MESSAGES_FILTER_EMPTY);
        w.writeInt(pts);
        w.writeInt(50);                     // ordinary-user recommended range
        return w.toByteArray();
    }

    /** users.getUsers#d91a548 id:Vector<InputUser> = Vector<User> */
    public static byte[] getSelf()
    {
        TlWriter w = new TlWriter(32);
        w.writeInt(Api.USERS_GET_USERS);
        w.writeVectorHeader(1);
        w.writeInt(Api.INPUT_USER_SELF);
        return w.toByteArray();
    }

    public static byte[] getFullUser(Peer user)
    {
        TlWriter w = new TlWriter(32);
        w.writeInt(Api.USERS_GET_FULL_USER);
        writeInputUser(w, user);
        return w.toByteArray();
    }

    public static byte[] updateProfile(String firstName, String lastName,
                                       String about)
    {
        TlWriter w = new TlWriter(64 + (firstName.length()
                + lastName.length() + about.length()) * 2);
        w.writeInt(Api.ACCOUNT_UPDATE_PROFILE);
        w.writeInt(7);
        w.writeString(firstName);
        w.writeString(lastName);
        w.writeString(about);
        return w.toByteArray();
    }

    /** help.getConfig#c4f9186b = Config */
    public static byte[] getConfig()
    {
        TlWriter w = new TlWriter(8);
        w.writeInt(Api.HELP_GET_CONFIG);
        return w.toByteArray();
    }

    /** The layer this client declares; see {@link Layer}. */
    public static int layer()
    {
        return Layer.LAYER;
    }
}
