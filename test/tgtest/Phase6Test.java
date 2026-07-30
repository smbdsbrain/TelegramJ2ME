package tgtest;

import tg.api.Api;
import tg.api.Dialog;
import tg.api.Message;
import tg.api.OutgoingMessage;
import tg.api.PageMerge;
import tg.api.Peer;
import tg.api.PeerCache;
import tg.api.Profile;
import tg.api.Requests;
import tg.tl.TlObj;
import tg.tl.TlReader;
import tg.tl.TlWriter;
import tg.ui.ChatScreen;
import tg.ui.DateTime;

/** Phase 6 wire shapes, bounded paging, reply display and profile parsing. */
public final class Phase6Test implements Test
{
    public String name() { return "phase6/cheap-wins-profiles"; }

    public void run() throws Exception
    {
        paginationWire();
        messageActionWire();
        profileWireAndParsing();
        pagingAndFilter();
        repliesAndDates();
        outboxCompatibility();
    }

    private static void paginationWire() throws Exception
    {
        Dialog offset = dialog(Peer.USER, 10, "Ten");
        offset.date = 1234;
        offset.topMessageId = 55;
        offset.peer.accessHash = 99;
        TlReader dialogs = new TlReader(Requests.getDialogs(offset, 40));
        Assert.equal("dialogs method", Api.MESSAGES_GET_DIALOGS, dialogs.readInt());
        Assert.equal("dialogs flags", 0, dialogs.readInt());
        Assert.equal("dialogs date", 1234, dialogs.readInt());
        Assert.equal("dialogs id", 55, dialogs.readInt());
        Assert.equal("dialogs peer", Api.INPUT_PEER_USER, dialogs.readInt());
        Assert.equal("dialogs user", 10, dialogs.readLong());
        Assert.equal("dialogs hash", 99, dialogs.readLong());
        Assert.equal("dialogs limit", 40, dialogs.readInt());
        Assert.equal("dialogs request hash", 0, dialogs.readLong());

        TlReader history = new TlReader(Requests.getHistoryBefore(
                offset.peer, 77, 30));
        Assert.equal("history method", Api.MESSAGES_GET_HISTORY, history.readInt());
        history.readInt();
        history.readLong();
        history.readLong();
        Assert.equal("history offset", 77, history.readInt());
    }

    private static void messageActionWire() throws Exception
    {
        Peer from = peer(Peer.USER, 11, 22, "From");
        Peer to = peer(Peer.CHANNEL, 33, 44, "To");
        TlReader reply = new TlReader(Requests.sendMessage(
                from, "hello", 123L, 77));
        Assert.equal("reply method", Api.MESSAGES_SEND_MESSAGE, reply.readInt());
        Assert.equal("reply flags", 1, reply.readInt());
        Assert.equal("reply peer", Api.INPUT_PEER_USER, reply.readInt());
        reply.readLong();
        reply.readLong();
        Assert.equal("reply ctor", Api.INPUT_REPLY_TO_MESSAGE, reply.readInt());
        Assert.equal("reply ctor flags", 0, reply.readInt());
        Assert.equal("reply id", 77, reply.readInt());
        Assert.equal("reply text", "hello", reply.readString());
        Assert.equal("reply random", 123L, reply.readLong());

        TlReader forward = new TlReader(Requests.forwardMessage(
                from, 88, to, 999L));
        Assert.equal("forward method", Api.MESSAGES_FORWARD_MESSAGES,
                forward.readInt());
        Assert.equal("forward flags", 0, forward.readInt());
        Assert.equal("forward source", Api.INPUT_PEER_USER, forward.readInt());
        forward.readLong();
        forward.readLong();
        Assert.equal("forward ids vector", 0x1cb5c415, forward.readInt());
        Assert.equal("forward ids count", 1, forward.readInt());
        Assert.equal("forward message", 88, forward.readInt());
        Assert.equal("forward random vector", 0x1cb5c415, forward.readInt());
        Assert.equal("forward random count", 1, forward.readInt());
        Assert.equal("forward random", 999L, forward.readLong());
        Assert.equal("forward target", Api.INPUT_PEER_CHANNEL, forward.readInt());

        TlReader local = new TlReader(Requests.deleteMessages(5, false));
        Assert.equal("delete method", Api.MESSAGES_DELETE_MESSAGES, local.readInt());
        Assert.equal("delete local flags", 0, local.readInt());
        TlReader revoke = new TlReader(Requests.deleteMessages(5, true));
        revoke.readInt();
        Assert.equal("delete revoke flags", 1, revoke.readInt());

        TlReader channel = new TlReader(Requests.deleteChannelMessage(to, 6));
        Assert.equal("channel delete method", Api.CHANNELS_DELETE_MESSAGES,
                channel.readInt());
        Assert.equal("input channel", Api.INPUT_CHANNEL, channel.readInt());
    }

    private static void profileWireAndParsing() throws Exception
    {
        Peer requested = peer(Peer.USER, 42, 84, "Old");
        TlReader fullWire = new TlReader(Requests.getFullUser(requested));
        Assert.equal("full user method", Api.USERS_GET_FULL_USER,
                fullWire.readInt());
        Assert.equal("full user input", Api.INPUT_USER, fullWire.readInt());
        Assert.equal("full user id", 42, fullWire.readLong());
        Assert.equal("full user hash", 84, fullWire.readLong());

        TlReader update = new TlReader(Requests.updateProfile(
                "Ada", "Lovelace", "Math"));
        Assert.equal("update profile method", Api.ACCOUNT_UPDATE_PROFILE,
                update.readInt());
        Assert.equal("update profile flags", 7, update.readInt());
        Assert.equal("first name", "Ada", update.readString());
        Assert.equal("last name", "Lovelace", update.readString());
        Assert.equal("bio", "Math", update.readString());

        TlObj user = obj(Api.USER, Api.F_USER__BOT_ACTIVE_USERS + 1);
        user.nums[Api.F_USER__ID] = 42;
        user.nums[Api.F_USER__ACCESS_HASH] = 84;
        user.refs[Api.F_USER__FIRST_NAME] = "Ada";
        user.refs[Api.F_USER__LAST_NAME] = "Lovelace";
        TlObj avatar = obj(Api.USER_PROFILE_PHOTO,
                Api.F_USER_PROFILE_PHOTO__DC_ID + 1);
        avatar.nums[Api.F_USER_PROFILE_PHOTO__PHOTO_ID] = 700;
        avatar.nums[Api.F_USER_PROFILE_PHOTO__DC_ID] = 4;
        avatar.refs[Api.F_USER_PROFILE_PHOTO__STRIPPED_THUMB] =
                new byte[] { 1, 2, 3, 4 };
        user.refs[Api.F_USER__PHOTO] = avatar;

        TlObj size = obj(Api.PHOTO_SIZE, Api.F_PHOTO_SIZE__SIZE + 1);
        size.refs[Api.F_PHOTO_SIZE__TYPE] = "m";
        size.nums[Api.F_PHOTO_SIZE__W] = 320;
        size.nums[Api.F_PHOTO_SIZE__H] = 240;
        size.nums[Api.F_PHOTO_SIZE__SIZE] = 1000;
        TlObj photo = obj(Api.PHOTO, Api.F_PHOTO__DC_ID + 1);
        photo.nums[Api.F_PHOTO__ID] = 7;
        photo.nums[Api.F_PHOTO__ACCESS_HASH] = 8;
        photo.refs[Api.F_PHOTO__FILE_REFERENCE] = new byte[] { 1 };
        photo.refs[Api.F_PHOTO__SIZES] = new TlObj[] { size };
        photo.nums[Api.F_PHOTO__DC_ID] = 2;
        TlObj full = obj(Api.USER_FULL, Api.F_USER_FULL__PROFILE_PHOTO + 1);
        full.refs[Api.F_USER_FULL__ABOUT] = "Analytical engine";
        full.refs[Api.F_USER_FULL__PROFILE_PHOTO] = photo;
        TlObj reply = obj(Api.USERS_USER_FULL,
                Api.F_USERS_USER_FULL__USERS + 1);
        reply.refs[Api.F_USERS_USER_FULL__FULL_USER] = full;
        reply.refs[Api.F_USERS_USER_FULL__CHATS] = new TlObj[0];
        reply.refs[Api.F_USERS_USER_FULL__USERS] = new TlObj[] { user };
        Profile parsed = Profile.from(reply, requested, new PeerCache());
        Assert.equal("parsed profile name", "Ada Lovelace", parsed.peer.title);
        Assert.equal("parsed bio", "Analytical engine", parsed.about);
        Assert.equal("static profile photo", 7L, parsed.photo.id);
        Assert.equal("static profile photo dc", 2, parsed.photo.dcId);
        Assert.equal("dialog avatar id", 700L, parsed.peer.avatar.photoId);
        Assert.equal("dialog avatar dc", 4, parsed.peer.avatar.dcId);
    }

    private static void pagingAndFilter()
    {
        Dialog one = dialog(Peer.USER, 1, "Alice");
        Dialog two = dialog(Peer.USER, 2, "Bob");
        Dialog twoNew = dialog(Peer.USER, 2, "Bobby");
        Dialog three = dialog(Peer.CHAT, 3, "Team");
        Dialog[] merged = PageMerge.dialogs(new Dialog[] { one, two },
                new Dialog[] { twoNew, three }, 3);
        Assert.equal("dialog dedupe", 3, merged.length);
        Assert.equal("new page replaces duplicate", "Bobby", merged[1].title());
        Assert.equal("filter case insensitive", 1,
                PageMerge.filter(merged, "ali").length);

        Message first = message(1, "one");
        Message second = message(2, "old");
        Message secondNew = message(2, "new");
        Message third = message(3, "three");
        Message[] messages = PageMerge.messages(
                new Message[] { first, second },
                new Message[] { secondNew, third }, 3);
        Assert.equal("message dedupe", 3, messages.length);
        Assert.equal("message replacement", "new", messages[1].text);
        Assert.equal("page bound", 2, PageMerge.messages(
                new Message[] { first, second },
                new Message[] { third }, 2).length);
    }

    private static void repliesAndDates()
    {
        Message source = message(7, "A sufficiently short source");
        source.outgoing = true;
        Message reply = message(8, "answer");
        reply.replyToMessageId = 7;
        Assert.equal("reply preview",
                "Reply to You: A sufficiently short source",
                ChatScreen.replyLine(reply, new Message[] { reply, source }));
        reply.replyToMessageId = 99;
        Assert.equal("reply fallback", "Reply to #99",
                ChatScreen.replyLine(reply, new Message[] { reply, source }));
        Assert.equal("time format length", 5, DateTime.time(1722340800).length());
        Assert.isTrue("adjacent days differ",
                DateTime.dayKey(1722340800) != DateTime.dayKey(1722427200));
    }

    private static void outboxCompatibility() throws Exception
    {
        OutgoingMessage message = new OutgoingMessage();
        message.peerKind = Peer.USER;
        message.peerId = 9;
        message.accessHash = 10;
        message.peerTitle = "Peer";
        message.text = "reply";
        message.replyToMessageId = 77;
        message.randomId = 11;
        OutgoingMessage decoded = OutgoingMessage.decode(1,
                OutgoingMessage.encode(message));
        Assert.equal("v2 reply persisted", 77, decoded.replyToMessageId);

        TlWriter legacy = new TlWriter(128);
        legacy.writeInt(0x54474f32);
        legacy.writeInt(1);
        legacy.writeInt(OutgoingMessage.QUEUED);
        legacy.writeInt(Peer.USER);
        legacy.writeLong(9);
        legacy.writeLong(10);
        legacy.writeString("Peer");
        legacy.writeString("legacy");
        legacy.writeLong(12);
        legacy.writeLong(13);
        legacy.writeInt(0);
        legacy.writeLong(0);
        legacy.writeString("");
        OutgoingMessage old = OutgoingMessage.decode(2, legacy.toByteArray());
        Assert.equal("v1 reply defaults empty", 0, old.replyToMessageId);
        Assert.equal("v1 text survives", "legacy", old.text);
    }

    private static Dialog dialog(int kind, long id, String title)
    {
        Dialog out = new Dialog();
        out.peer = peer(kind, id, 0, title);
        return out;
    }

    private static Peer peer(int kind, long id, long hash, String title)
    {
        Peer out = new Peer(kind, id);
        out.accessHash = hash;
        out.title = title;
        return out;
    }

    private static Message message(int id, String text)
    {
        Message out = new Message();
        out.id = id;
        out.text = text;
        return out;
    }

    private static TlObj obj(int id, int fields)
    {
        TlObj out = new TlObj(id, fields);
        out.refs = new Object[fields];
        return out;
    }
}
