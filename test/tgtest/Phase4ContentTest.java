package tgtest;

import tg.api.Api;
import tg.api.AvatarRef;
import tg.api.DcDirectory;
import tg.api.Media;
import tg.api.MediaAuthorization;
import tg.api.Message;
import tg.api.Peer;
import tg.api.PeerCache;
import tg.api.PhotoRef;
import tg.api.PhotoSizeRef;
import tg.api.ReactionSummary;
import tg.api.ReactionCatalog;
import tg.api.Requests;
import tg.mem.MemoryBudget;
import tg.mt.DcEndpoint;
import tg.mt.AuthKey;
import tg.tl.TlObj;
import tg.tl.TlReader;
import tg.ui.EmojiText;

/** Pure model and wire checks for Phase 4 content. */
public final class Phase4ContentTest implements Test
{
    public String name() { return "content/media-reactions-wire"; }

    public void run() throws Exception
    {
        photoSelection();
        mediaClassification();
        reactionParsing();
        emojiPolicy();
        requestWire();
        dcDirectory();
        mediaAuthorization();
    }

    private void photoSelection() throws Exception
    {
        PhotoRef photo = new PhotoRef();
        PhotoSizeRef tiny = size(PhotoSizeRef.REMOTE, "s", 100, 75, 4000);
        PhotoSizeRef screen = size(PhotoSizeRef.PROGRESSIVE, "m", 320, 240, 30000);
        PhotoSizeRef huge = size(PhotoSizeRef.REMOTE, "x", 1280, 960, 700000);
        photo.sizes = new PhotoSizeRef[] { tiny, huge, screen };
        Assert.isTrue("screen size selected", photo.choose(320, 240) == screen);

        screen.size = MemoryBudget.photoCompressedBytes() + 1;
        Assert.isTrue("bounded fallback", photo.choose(320, 240) == tiny);

        // A smaller measured heap must change which size the server is asked
        // for, not merely fail later during the decode. 100 KB is comfortably
        // inside the reference budget and outside the one a 512 KB heap allows.
        screen.size = 100000;
        Assert.isTrue("the reference heap still takes the covering size",
                photo.choose(320, 240) == screen);
        MemoryBudget.init(512 * 1024, 0, MemoryBudget.SOURCE_MEASURED);
        try
        {
            Assert.isTrue("a small heap falls back to a smaller size",
                    photo.choose(320, 240) == tiny);
        }
        finally { MemoryBudget.reset(); }
    }

    private void mediaClassification()
    {
        mediaLabel("photo", Api.MESSAGE_MEDIA_PHOTO, "[photo]");
        mediaLabel("geo", Api.MESSAGE_MEDIA_GEO, "[location]");
        mediaLabel("geo live", Api.MESSAGE_MEDIA_GEO_LIVE, "[location]");
        mediaLabel("venue", Api.MESSAGE_MEDIA_VENUE, "[location]");
        mediaLabel("contact", Api.MESSAGE_MEDIA_CONTACT, "[contact]");
        mediaLabel("poll", Api.MESSAGE_MEDIA_POLL, "[poll]");
        mediaLabel("dice", Api.MESSAGE_MEDIA_DICE, "[dice]");
        mediaLabel("game", Api.MESSAGE_MEDIA_GAME, "[game]");
        mediaLabel("invoice", Api.MESSAGE_MEDIA_INVOICE, "[invoice]");
        mediaLabel("story", Api.MESSAGE_MEDIA_STORY, "[story]");
        mediaLabel("web page", Api.MESSAGE_MEDIA_WEB_PAGE, "[link]");

        TlObj video = obj(Api.MESSAGE_MEDIA_DOCUMENT,
                Api.F_MESSAGE_MEDIA_DOCUMENT__VOICE + 1);
        video.nums[Api.F_MESSAGE_MEDIA_DOCUMENT__VIDEO] = 1;
        Assert.equal("video label", "[video]", Media.from(video).label);
        TlObj round = obj(Api.MESSAGE_MEDIA_DOCUMENT,
                Api.F_MESSAGE_MEDIA_DOCUMENT__VOICE + 1);
        round.nums[Api.F_MESSAGE_MEDIA_DOCUMENT__ROUND] = 1;
        Assert.equal("round label", "[round video]", Media.from(round).label);
        TlObj voice = obj(Api.MESSAGE_MEDIA_DOCUMENT,
                Api.F_MESSAGE_MEDIA_DOCUMENT__VOICE + 1);
        voice.nums[Api.F_MESSAGE_MEDIA_DOCUMENT__VOICE] = 1;
        Assert.equal("voice label", "[voice]", Media.from(voice).label);

        TlObj sticker = new TlObj(Api.DOCUMENT_ATTRIBUTE_STICKER, 5);
        TlObj document = obj(Api.DOCUMENT, Api.F_DOCUMENT__ATTRIBUTES + 1);
        document.refs[Api.F_DOCUMENT__MIME_TYPE] = "image/webp";
        document.refs[Api.F_DOCUMENT__ATTRIBUTES] = new TlObj[] { sticker };
        TlObj media = obj(Api.MESSAGE_MEDIA_DOCUMENT,
                Api.F_MESSAGE_MEDIA_DOCUMENT__ALT_DOCUMENTS + 1);
        media.refs[Api.F_MESSAGE_MEDIA_DOCUMENT__DOCUMENT] = document;
        Assert.equal("sticker label", "[sticker]", Media.from(media).label);

        TlObj animated = new TlObj(Api.DOCUMENT_ATTRIBUTE_ANIMATED, 0);
        document.refs[Api.F_DOCUMENT__ATTRIBUTES] = new TlObj[] { animated };
        Assert.equal("animation label", "[animation]", Media.from(media).label);
        TlObj audio = obj(Api.DOCUMENT_ATTRIBUTE_AUDIO,
                Api.F_DOCUMENT_ATTRIBUTE_AUDIO__VOICE + 1);
        document.refs[Api.F_DOCUMENT__ATTRIBUTES] = new TlObj[] { audio };
        Assert.equal("audio label", "[audio]", Media.from(media).label);
        document.refs[Api.F_DOCUMENT__ATTRIBUTES] = new TlObj[0];
        document.refs[Api.F_DOCUMENT__MIME_TYPE] = "application/pdf";
        Assert.equal("file label", "[file]", Media.from(media).label);

        TlObj videoAttribute = obj(Api.DOCUMENT_ATTRIBUTE_VIDEO,
                Api.F_DOCUMENT_ATTRIBUTE_VIDEO__ROUND_MESSAGE + 1);
        document.refs[Api.F_DOCUMENT__ATTRIBUTES] =
                new TlObj[] { videoAttribute };
        Assert.equal("video attribute label", "[video]",
                Media.from(media).label);
        media.refs[Api.F_MESSAGE_MEDIA_DOCUMENT__DOCUMENT] = null;
        media.refs[Api.F_MESSAGE_MEDIA_DOCUMENT__ALT_DOCUMENTS] =
                new TlObj[] { document };
        Assert.equal("forwarded alternative video label", "[video]",
                Media.from(media).label);

        Message caption = new Message();
        caption.text = "caption";
        caption.media = Media.from(new TlObj(Api.MESSAGE_MEDIA_PHOTO, 1));
        Assert.equal("caption plus placeholder", "caption [photo]",
                caption.summaryText());

        PeerCache peers = new PeerCache();
        Peer channel = new Peer(Peer.CHANNEL, 55);
        channel.title = "Public source";
        channel.username = "public_source";
        channel.accessHash = 66;
        peers.put(channel);
        TlObj fwd = obj(Api.MESSAGE_FWD_HEADER,
                Api.F_MESSAGE_FWD_HEADER__SAVED_FROM_MSG_ID + 1);
        TlObj source = obj(Api.PEER_CHANNEL,
                Api.F_PEER_CHANNEL__CHANNEL_ID + 1);
        source.nums[Api.F_PEER_CHANNEL__CHANNEL_ID] = 55;
        fwd.refs[Api.F_MESSAGE_FWD_HEADER__FROM_ID] = source;
        fwd.nums[Api.F_MESSAGE_FWD_HEADER__CHANNEL_POST] = 77;
        TlObj forwarded = obj(Api.MESSAGE, Api.F_MESSAGE__REACTIONS + 1);
        forwarded.refs[Api.F_MESSAGE__FWD_FROM] = fwd;
        forwarded.refs[Api.F_MESSAGE__MESSAGE] = "";
        forwarded.refs[Api.F_MESSAGE__PEER_ID] = source;
        Message parsedForward = Message.from(forwarded, peers);
        Assert.equal("forwarded label", "Forwarded from Public source",
                parsedForward.forwarded.label);
        Assert.equal("forwarded message id", 77,
                parsedForward.forwarded.messageId);
        Assert.isTrue("forwarded source can open",
                parsedForward.forwarded.canOpen(peers));
    }

    private void emojiPolicy()
    {
        int[] palette = {
            0x1f44d, 0x2764, 0x1f923, 0x1f631, 0x1f622, 0x1f64f,
            0x1f525, 0x1f44e, 0x1f389, 0x1f914, 0x1f60d, 0x1f92f
        };
        for (int i = 0; i < palette.length; i++)
        {
            Assert.isTrue("reaction sprite " + Integer.toHexString(palette[i]),
                    EmojiText.hasSprite(palette[i]));
        }
        Assert.equal("canonical laugh", "\ud83e\udd23",
                ReactionCatalog.EMOJI[2]);
        Assert.equal("canonical wow", "\ud83d\ude31",
                ReactionCatalog.EMOJI[3]);
        String[] filtered = ReactionCatalog.filter(
                ReactionCatalog.EMOJI,
                new String[] { "\ud83d\udc4d", "\ud83e\udd23" });
        Assert.equal("peer reaction filter count", 2, filtered.length);
        Assert.equal("peer reaction filter laugh", "\ud83e\udd23",
                filtered[1]);
        Assert.equal("camera semantic fallback", "[camera]",
                EmojiText.fallbackFor(0x1f4f7));
        Assert.equal("fire semantic fallback", "[fire]",
                EmojiText.fallbackFor(0x1f525));
        Assert.equal("unknown emoji fallback", "[emoji]",
                EmojiText.fallbackFor(0x1fae0));
        String family = "\ud83d\udc69\u200d\ud83d\udcbb";
        Assert.equal("ZWJ is one renderer token", family.length(),
                EmojiText.nextBoundary(family, 0));
    }

    private void reactionParsing()
    {
        TlObj emoji = obj(Api.REACTION_EMOJI,
                Api.F_REACTION_EMOJI__EMOTICON + 1);
        emoji.refs[Api.F_REACTION_EMOJI__EMOTICON] = "\ud83d\udc4d";
        TlObj count = obj(Api.REACTION_COUNT, Api.F_REACTION_COUNT__COUNT + 1);
        count.flags = 1;
        count.hasFlags = true;
        count.refs[Api.F_REACTION_COUNT__REACTION] = emoji;
        count.nums[Api.F_REACTION_COUNT__COUNT] = 7;
        TlObj all = obj(Api.MESSAGE_REACTIONS,
                Api.F_MESSAGE_REACTIONS__RESULTS + 1);
        all.refs[Api.F_MESSAGE_REACTIONS__RESULTS] = new TlObj[] { count };

        ReactionSummary[] parsed = invokeReactionParser(all);
        Assert.equal("reaction count", 1, parsed.length);
        Assert.equal("reaction emoji", "\ud83d\udc4d", parsed[0].emoji);
        Assert.equal("reaction aggregate", 7, parsed[0].count);
        Assert.isTrue("reaction chosen", parsed[0].chosen);

        ReactionSummary newer = new ReactionSummary();
        newer.emoji = "\ud83e\udd23";
        newer.chosen = true;
        newer.chosenOrder = 2;
        ReactionSummary older = new ReactionSummary();
        older.emoji = "\ud83d\udc4d";
        older.chosen = true;
        older.chosenOrder = 1;
        String[] ordered = ReactionSummary.chosenEmoji(
                new ReactionSummary[] { newer, older });
        Assert.equal("chosen order older first", older.emoji, ordered[0]);
        Assert.equal("chosen order newer last", newer.emoji, ordered[1]);
    }

    private void requestWire() throws Exception
    {
        PhotoRef photo = new PhotoRef();
        photo.id = 11;
        photo.accessHash = 22;
        photo.fileReference = new byte[] { 1, 2, 3 };
        PhotoSizeRef size = size(PhotoSizeRef.REMOTE, "m", 320, 240, 99);
        TlReader file = new TlReader(Requests.getPhotoFile(photo, size, 32768, 32768));
        Assert.equal("getFile id", Api.UPLOAD_GET_FILE, file.readInt());
        Assert.equal("getFile flags", 0, file.readInt());
        Assert.equal("photo location", Api.INPUT_PHOTO_FILE_LOCATION, file.readInt());
        Assert.equal("photo id", 11L, file.readLong());
        Assert.equal("access hash", 22L, file.readLong());
        Assert.bytesEqual("file reference", photo.fileReference, file.readBytes());
        Assert.equal("thumb type", "m", file.readString());
        Assert.equal("offset", 32768L, file.readLong());
        Assert.equal("limit", 32768, file.readInt());

        Peer avatarPeer = new Peer(Peer.USER, 77);
        avatarPeer.accessHash = 88;
        AvatarRef avatar = new AvatarRef();
        avatar.photoId = 99;
        avatar.dcId = 2;
        TlReader avatarFile = new TlReader(Requests.getAvatarFile(
                avatarPeer, avatar, 0, 32768));
        Assert.equal("avatar getFile id", Api.UPLOAD_GET_FILE,
                avatarFile.readInt());
        Assert.equal("avatar getFile flags", 0, avatarFile.readInt());
        Assert.equal("peer photo location",
                Api.INPUT_PEER_PHOTO_FILE_LOCATION, avatarFile.readInt());
        Assert.equal("peer photo flags", 0, avatarFile.readInt());
        Assert.equal("avatar input peer", Api.INPUT_PEER_USER,
                avatarFile.readInt());
        Assert.equal("avatar peer id", 77L, avatarFile.readLong());
        Assert.equal("avatar peer hash", 88L, avatarFile.readLong());
        Assert.equal("avatar photo id", 99L, avatarFile.readLong());
        Assert.equal("avatar offset", 0L, avatarFile.readLong());
        Assert.equal("avatar limit", 32768, avatarFile.readInt());

        TlReader reaction = new TlReader(Requests.sendReactions(null, 77,
                ReactionCatalog.EMOJI));
        Assert.equal("sendReaction id", Api.MESSAGES_SEND_REACTION,
                reaction.readInt());
        Assert.equal("sendReaction flags", 1, reaction.readInt());
        Assert.equal("empty peer", Api.INPUT_PEER_EMPTY, reaction.readInt());
        Assert.equal("message id", 77, reaction.readInt());
        Assert.equal("vector id", 0x1cb5c415, reaction.readInt());
        Assert.equal("reaction vector count", ReactionCatalog.EMOJI.length,
                reaction.readInt());
        for (int i = 0; i < ReactionCatalog.EMOJI.length; i++)
        {
            Assert.equal("reaction ctor " + i, Api.REACTION_EMOJI,
                    reaction.readInt());
            Assert.equal("reaction value " + i, ReactionCatalog.EMOJI[i],
                    reaction.readString());
        }

        TlReader remove = new TlReader(Requests.sendReactions(null, 77,
                new String[0]));
        remove.readInt();
        Assert.equal("remove flags", 1, remove.readInt());
        remove.readInt();
        remove.readInt();
        Assert.equal("remove vector", 0x1cb5c415, remove.readInt());
        Assert.equal("remove count", 0, remove.readInt());

        TlReader viewers = new TlReader(
                Requests.getMessageReactions(null, 77, 100));
        Assert.equal("reaction viewers method",
                Api.MESSAGES_GET_MESSAGE_REACTIONS_LIST, viewers.readInt());
        Assert.equal("reaction viewers flags", 0, viewers.readInt());
        Assert.equal("reaction viewers peer", Api.INPUT_PEER_EMPTY,
                viewers.readInt());
        Assert.equal("reaction viewers message", 77, viewers.readInt());
        Assert.equal("reaction viewers limit", 100, viewers.readInt());

        TlReader available = new TlReader(Requests.getAvailableReactions());
        Assert.equal("available reactions method",
                Api.MESSAGES_GET_AVAILABLE_REACTIONS, available.readInt());
        Assert.equal("available reactions hash", 0, available.readInt());

        Peer basic = new Peer(Peer.CHAT, 123);
        TlReader fullChat = new TlReader(Requests.getFullChat(basic));
        Assert.equal("full chat method", Api.MESSAGES_GET_FULL_CHAT,
                fullChat.readInt());
        Assert.equal("full chat id", 123L, fullChat.readLong());

        Peer channel = new Peer(Peer.CHANNEL, 456);
        channel.accessHash = 789;
        TlReader fullChannel = new TlReader(Requests.getFullChannel(channel));
        Assert.equal("full channel method", Api.CHANNELS_GET_FULL_CHANNEL,
                fullChannel.readInt());
        Assert.equal("full channel input", Api.INPUT_CHANNEL,
                fullChannel.readInt());
        Assert.equal("full channel id", 456L, fullChannel.readLong());
        Assert.equal("full channel hash", 789L, fullChannel.readLong());

        TlReader around = new TlReader(
                Requests.getHistoryAround(null, 77, 30));
        Assert.equal("history around method", Api.MESSAGES_GET_HISTORY,
                around.readInt());
        Assert.equal("history around peer", Api.INPUT_PEER_EMPTY,
                around.readInt());
        Assert.equal("history around id", 77, around.readInt());
        Assert.equal("history around date", 0, around.readInt());
        Assert.equal("history around offset", -15, around.readInt());
        Assert.equal("history around limit", 30, around.readInt());

        TlReader resolve = new TlReader(
                Requests.resolveUsername("public_source"));
        Assert.equal("resolve username method", Api.CONTACTS_RESOLVE_USERNAME,
                resolve.readInt());
        Assert.equal("resolve username flags", 0, resolve.readInt());
        Assert.equal("resolve username", "public_source",
                resolve.readString());

        TlReader export = new TlReader(Requests.exportAuthorization(4));
        Assert.equal("export method", Api.AUTH_EXPORT_AUTHORIZATION,
                export.readInt());
        Assert.equal("export dc", 4, export.readInt());
        TlReader imported = new TlReader(Requests.importAuthorization(
                123456789L, new byte[] { 9, 8, 7 }));
        Assert.equal("import method", Api.AUTH_IMPORT_AUTHORIZATION,
                imported.readInt());
        Assert.equal("import id", 123456789L, imported.readLong());
        Assert.bytesEqual("import bytes", new byte[] { 9, 8, 7 },
                imported.readBytes());
    }

    private void dcDirectory()
    {
        TlObj media = obj(Api.DC_OPTION, Api.F_DC_OPTION__PORT + 1);
        media.flags = 2;
        media.hasFlags = true;
        media.nums[Api.F_DC_OPTION__MEDIA_ONLY] = 1;
        media.nums[Api.F_DC_OPTION__ID] = 4;
        media.nums[Api.F_DC_OPTION__PORT] = 443;
        media.refs[Api.F_DC_OPTION__IP_ADDRESS] = "10.0.0.4";
        TlObj regular = obj(Api.DC_OPTION, Api.F_DC_OPTION__PORT + 1);
        regular.nums[Api.F_DC_OPTION__ID] = 4;
        regular.nums[Api.F_DC_OPTION__PORT] = 80;
        regular.refs[Api.F_DC_OPTION__IP_ADDRESS] = "10.0.0.5";
        TlObj config = obj(Api.CONFIG, Api.F_CONFIG__DC_OPTIONS + 1);
        config.refs[Api.F_CONFIG__DC_OPTIONS] = new TlObj[] { regular, media };
        DcDirectory directory = new DcDirectory();
        directory.absorb(config);
        DcEndpoint selected = directory.endpoint(4, true);
        Assert.equal("media endpoint", "10.0.0.4", selected.host);
        Assert.equal("regular endpoint", "10.0.0.5",
                directory.endpoint(4, false).host);
    }

    private void mediaAuthorization()
    {
        byte[] mainBytes = new byte[256];
        byte[] foreignBytes = new byte[256];
        for (int i = 0; i < 256; i++)
        {
            mainBytes[i] = (byte) i;
            foreignBytes[i] = (byte) (255 - i);
        }
        AuthKey primary = new AuthKey(mainBytes, 2, false);
        AuthKey foreign = new AuthKey(foreignBytes, 4, false);
        Assert.isTrue("same DC reuses primary key",
                MediaAuthorization.select(2, 2, primary, null) == primary);
        Assert.isTrue("foreign DC reuses persisted key",
                MediaAuthorization.select(2, 4, primary, foreign) == foreign);
        Assert.isTrue("missing foreign key requests handshake",
                MediaAuthorization.select(2, 4, primary, null) == null);
        Assert.isTrue("foreign key needs import",
                MediaAuthorization.needsImport(2, 4, foreign, null));
        Assert.isFalse("persisted import marker skips re-import",
                MediaAuthorization.needsImport(2, 4, foreign,
                        String.valueOf(foreign.keyId())));
        Assert.isFalse("same DC never imports",
                MediaAuthorization.needsImport(2, 2, primary, null));
        Assert.equal("marker name", "imported.prod.4",
                MediaAuthorization.markerName(4, false));
    }

    private static PhotoSizeRef size(int kind, String type, int w, int h, int bytes)
    {
        PhotoSizeRef out = new PhotoSizeRef();
        out.kind = kind;
        out.type = type;
        out.width = w;
        out.height = h;
        out.size = bytes;
        return out;
    }

    private static void mediaLabel(String name, int constructor, String expected)
    {
        Assert.equal(name + " label", expected,
                Media.from(new TlObj(constructor, 16)).label);
    }

    private static TlObj obj(int id, int fields)
    {
        TlObj out = new TlObj(id, fields);
        out.refs = new Object[fields];
        return out;
    }

    private static ReactionSummary[] invokeReactionParser(TlObj reactions)
    {
        TlObj message = obj(Api.MESSAGE, Api.F_MESSAGE__REACTIONS + 1);
        message.refs[Api.F_MESSAGE__REACTIONS] = reactions;
        message.refs[Api.F_MESSAGE__MESSAGE] = "";
        message.refs[Api.F_MESSAGE__PEER_ID] =
                new TlObj(Api.PEER_USER, Api.F_PEER_USER__USER_ID + 1);
        return tg.api.Message.from(message, null).reactions;
    }
}
