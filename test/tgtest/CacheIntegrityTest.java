package tgtest;

import javax.microedition.rms.RecordStore;

import tg.api.Cached;
import tg.api.Dialog;
import tg.api.Message;
import tg.api.MessageEntity;
import tg.api.Peer;
import tg.api.RecordEnvelope;
import tg.plat.RmsConversationCache;

/**
 * The offline cache: how old it is, and what one damaged record costs.
 *
 * Cache loss is not failure. Everything here is a copy of something Telegram
 * still has, so the recovery for damage is to drop the entry and fetch again -
 * which is the opposite of the outbox, where a lost row is a lost message. Two
 * things are not acceptable even so.
 *
 * One damaged record must not take the rest of the cache with it. A single
 * throw part-way through a load used to abort the whole thing, so one corrupt
 * conversation hid every other cached one until the store was cleared - on a
 * handset, until the user reinstalled.
 *
 * And a cached conversation must not look freshly fetched. The timestamp was
 * being written and then dropped on the way out. On a phone that spends much of
 * its life without a usable connection that is not cosmetic: a reader who
 * cannot tell four-second-old text from four-day-old text has no way to know
 * whether the last message in a chat is the last message in it.
 */
public final class CacheIntegrityTest implements Test
{
    private static final String DIALOGS = "tgdialogcache";
    private static final String HISTORY = "tghistorycache";
    private static final int HISTORY_MAGIC = 0x54474834;

    private static final long ACCOUNT = 31337L;
    private static final long NOW = 1700000000000L;

    public String name() { return "storage/cache-integrity"; }

    public void run() throws Exception
    {
        ageIsReportedInWordsAScreenCanUse();
        aClockThatWentBackwardsSaysAgeUnknown();
        aCachedListCarriesWhenItWasWritten();
        oneDamagedConversationDoesNotHideTheOthers();
        versionOneHistoryMigratesOnRead();
        versionTwoHistoryMigratesOnRead();
        threadTranscriptsAreCachedApart();
        dialogCacheCarriesTheForumFlag();
        aDamagedRecordIsRemovedRatherThanLeftToFailAgain();
        anotherAccountCacheIsNeverShown();
        theOtherEnvironmentCacheIsNeverShown();
        aFormatThisBuildDoesNotReadIsDiscarded();
        boundsAreCheckedBeforeTheArrayIsAllocated();
        clearingRemovesEverything();
    }

    // -------------------------------------------------------------- freshness

    private static void ageIsReportedInWordsAScreenCanUse()
    {
        Assert.equal("under a minute", "cached just now",
                Cached.of(new Dialog[0], NOW).ageLabel(NOW + 30000L));
        Assert.equal("minutes", "cached 18 min old",
                Cached.of(new Dialog[0], NOW).ageLabel(NOW + 18 * 60000L));
        Assert.equal("one hour", "cached 1 hour old",
                Cached.of(new Dialog[0], NOW).ageLabel(NOW + 65 * 60000L));
        Assert.equal("hours", "cached 5 hours old",
                Cached.of(new Dialog[0], NOW).ageLabel(NOW + 5 * 3600000L));
        Assert.equal("a day", "cached 1 day old",
                Cached.of(new Dialog[0], NOW).ageLabel(NOW + 30 * 3600000L));
        Assert.equal("days", "cached 4 days old",
                Cached.of(new Dialog[0], NOW).ageLabel(NOW + 4 * 24 * 3600000L));
    }

    /**
     * One of the three handsets this has run on resets its clock to 2011 on
     * every power cycle, so "now is before it was written" happens on real
     * hardware. A negative age rendered as a number is worse than an admission,
     * and clamping it to zero would claim the data is fresh.
     */
    private static void aClockThatWentBackwardsSaysAgeUnknown()
    {
        Cached cached = Cached.of(new Dialog[0], NOW);
        Assert.equal("the clock went back", "cached, age unknown",
                cached.ageLabel(NOW - 3600000L));
        Assert.equal("and the age is not a negative number", -1L,
                cached.ageMs(NOW - 3600000L));

        Cached noStamp = Cached.of(new Dialog[0], Cached.UNKNOWN);
        Assert.equal("no timestamp at all", "cached, age unknown",
                noStamp.ageLabel(NOW));
    }

    // ------------------------------------------------------------- the store

    private static void aCachedListCarriesWhenItWasWritten() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsConversationCache cache = new RmsConversationCache();
            long before = System.currentTimeMillis();
            cache.saveDialogs(ACCOUNT, false, new Dialog[] { dialog(7) });

            rms.restart();
            Cached loaded = new RmsConversationCache().loadDialogs(ACCOUNT, false);

            Assert.isTrue("the list is there", loaded != null);
            Assert.equal("with its one chat", 1, loaded.dialogs().length);
            Assert.isTrue("and a timestamp from around when it was written: "
                    + loaded.savedAt, loaded.savedAt >= before);
            Assert.isTrue("which reads as fresh",
                    loaded.ageLabel(loaded.savedAt + 1000L).indexOf("just now") >= 0);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The defect this exists for: one bad record used to abort the whole load.
     */
    private static void oneDamagedConversationDoesNotHideTheOthers() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsConversationCache cache = new RmsConversationCache();
            cache.saveHistory(ACCOUNT, false, peer(7), 0, new Message[] { message(1, "seven") });
            Message withEntity = message(2, "eight@example.test");
            MessageEntity entity = new MessageEntity();
            entity.type = MessageEntity.EMAIL;
            entity.offset = 0;
            entity.length = withEntity.text.length();
            withEntity.entities = new MessageEntity[] { entity };
            withEntity.editDate = 1235;
            cache.saveHistory(ACCOUNT, false, peer(8), 0,
                    new Message[] { withEntity });

            int[] ids = rms.recordIds(HISTORY);
            Assert.equal("two conversations cached", 2, ids.length);
            rms.flipBit(HISTORY, ids[0], RecordEnvelope.HEADER + 2, 5);
            rms.restart();

            RmsConversationCache reopened = new RmsConversationCache();
            Cached eight = reopened.loadHistory(ACCOUNT, false, peer(8), 0);
            Assert.isTrue("the intact conversation still loads", eight != null);
            Assert.equal("with its message", "eight@example.test",
                    eight.messages()[0].text);
            Assert.equal("entity survives cache round trip", 1,
                    eight.messages()[0].entities.length);
            Assert.equal("edit date survives cache round trip", 1235,
                    eight.messages()[0].editDate);
            Assert.equal("and the damaged one was dropped", 1,
                    reopened.droppedRecords());
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void versionOneHistoryMigratesOnRead() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            storeLegacyHistory(1, 3, "old cache", false, 0);
            rms.restart();

            Cached loaded = new RmsConversationCache().loadHistory(
                    ACCOUNT, false, peer(7), 0);
            Assert.isTrue("v1 history remains readable", loaded != null);
            Assert.equal("v1 text", "old cache", loaded.messages()[0].text);
            Assert.equal("v1 entities default empty", 0,
                    loaded.messages()[0].entities.length);
            Assert.equal("v1 thread facts default off", 0,
                    loaded.messages()[0].replyToTopId);
            Assert.isTrue("a pre-thread record belongs to no topic",
                    new RmsConversationCache().loadHistory(
                            ACCOUNT, false, peer(7), 400) == null);
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void versionTwoHistoryMigratesOnRead() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            storeLegacyHistory(2, 4, "v2@example.test", true, 0);
            rms.restart();

            Cached loaded = new RmsConversationCache().loadHistory(
                    ACCOUNT, false, peer(7), 0);
            Assert.isTrue("v2 history remains readable", loaded != null);
            Assert.equal("v2 entity survives", 1,
                    loaded.messages()[0].entities.length);
            Assert.equal("v2 edit date defaults to zero", 0,
                    loaded.messages()[0].editDate);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * A topic transcript and the peer's own are separate records: saving one
     * replaces only its own, loading asks by (peer, thread), and the thread
     * facts a topic is bucketed by survive the round trip.
     */
    private static void threadTranscriptsAreCachedApart() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsConversationCache cache = new RmsConversationCache();
            Message flat = message(3, "the peer's own");
            Message inTopic = message(600, "inside the topic");
            inTopic.replyToTopId = 400;
            inTopic.forumTopic = true;
            inTopic.hasComments = true;
            inTopic.repliesCount = 12;
            cache.saveHistory(ACCOUNT, false, peer(7), 0,
                    new Message[] { flat });
            cache.saveHistory(ACCOUNT, false, peer(7), 400,
                    new Message[] { inTopic });
            rms.restart();

            RmsConversationCache reopened = new RmsConversationCache();
            Cached topic = reopened.loadHistory(ACCOUNT, false, peer(7), 400);
            Assert.isTrue("the topic transcript loads", topic != null);
            Assert.equal("with its text", "inside the topic",
                    topic.messages()[0].text);
            Assert.equal("its thread root", 400,
                    topic.messages()[0].replyToTopId);
            Assert.isTrue("its forum flag", topic.messages()[0].forumTopic);
            Assert.isTrue("its comments flag", topic.messages()[0].hasComments);
            Assert.equal("and its comment count", 12,
                    topic.messages()[0].repliesCount);

            Cached own = reopened.loadHistory(ACCOUNT, false, peer(7), 0);
            Assert.isTrue("the peer's own transcript is untouched",
                    own != null);
            Assert.equal("and still its own", "the peer's own",
                    own.messages()[0].text);

            // Saving the topic again replaces the topic record only.
            reopened.saveHistory(ACCOUNT, false, peer(7), 400,
                    new Message[] { message(601, "newer topic page") });
            Assert.equal("the peer's own survives a topic save",
                    "the peer's own", new RmsConversationCache().loadHistory(
                            ACCOUNT, false, peer(7), 0).messages()[0].text);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The forum flag is what routes an offline open to the topic screen, so
     * the dialog cache has to carry it - and a v1 record without it still
     * reads, as a plain chat.
     */
    private static void dialogCacheCarriesTheForumFlag() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            Dialog forum = dialog(9);
            forum.peer = new Peer(Peer.CHANNEL, 9);
            forum.peer.title = "Topics";
            forum.peer.forum = true;
            new RmsConversationCache().saveDialogs(ACCOUNT, false,
                    new Dialog[] { forum });
            rms.restart();

            Cached loaded = new RmsConversationCache().loadDialogs(
                    ACCOUNT, false);
            Assert.isTrue("the list loads", loaded != null);
            Assert.isTrue("and the forum flag survives",
                    loaded.dialogs()[0].peer.forum);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * Write a record in one of the pre-thread formats, byte for byte as the
     * old writer produced it. Hand-built rather than derived from the current
     * writer, which emits v4 and no longer knows these shapes.
     */
    private static void storeLegacyHistory(int version, int messageId,
            String text, boolean withEntity, int editDate) throws Exception
    {
        tg.tl.TlWriter w = new tg.tl.TlWriter(256);
        Peer peer = peer(7);
        w.writeInt(peer.kind);
        w.writeLong(peer.id);
        w.writeLong(NOW);
        w.writeInt(1);
        w.writeInt(messageId);
        w.writeInt(1234);                    // date
        w.writeInt(0);                       // flags
        w.writeString(text);
        writeLegacyPeer(w, peer);            // message.peer
        w.writeInt(0);                       // no sender
        w.writeInt(0);                       // no media
        w.writeInt(0);                       // replyToMessageId
        w.writeInt(0);                       // reactions
        w.writeInt(0);                       // no forward
        if (version >= 2)
        {
            if (withEntity)
            {
                w.writeInt(1);
                w.writeInt(MessageEntity.EMAIL);
                w.writeInt(0);
                w.writeInt(text.length());
                w.writeString("");
                w.writeLong(0);
            }
            else { w.writeInt(0); }
        }
        if (version >= 3) { w.writeInt(editDate); }

        byte[] wrapped = RecordEnvelope.wrap(HISTORY_MAGIC, version, ACCOUNT,
                false, w.toByteArray());
        RecordStore rs = RecordStore.openRecordStore(HISTORY, true);
        rs.addRecord(wrapped, 0, wrapped.length);
        rs.closeRecordStore();
    }

    private static void writeLegacyPeer(tg.tl.TlWriter w, Peer peer)
    {
        w.writeInt(1);
        w.writeInt(peer.kind);
        w.writeLong(peer.id);
        w.writeLong(peer.accessHash);
        w.writeString(peer.title == null ? "" : peer.title);
        w.writeString("");
        w.writeString("");
        w.writeString("");
        w.writeInt(0);                       // not self
        w.writeInt(0);                       // no avatar
    }

    private static void aDamagedRecordIsRemovedRatherThanLeftToFailAgain()
            throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsConversationCache().saveDialogs(ACCOUNT, false,
                    new Dialog[] { dialog(7) });
            int id = rms.recordIds(DIALOGS)[0];
            rms.truncate(DIALOGS, id, RecordEnvelope.HEADER + 4);
            rms.restart();

            RmsConversationCache cache = new RmsConversationCache();
            Assert.isTrue("nothing usable is returned",
                    cache.loadDialogs(ACCOUNT, false) == null);
            Assert.equal("and the record is gone, not left to fail every launch",
                    0, rms.recordIds(DIALOGS).length);
            Assert.equal("counted", 1, cache.droppedRecords());
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void anotherAccountCacheIsNeverShown() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsConversationCache cache = new RmsConversationCache();
            cache.saveDialogs(ACCOUNT, false, new Dialog[] { dialog(7) });
            cache.saveHistory(ACCOUNT, false, peer(7), 0,
                    new Message[] { message(1, "private") });
            rms.restart();

            RmsConversationCache other = new RmsConversationCache();
            Assert.isTrue("the dialog list is not shown",
                    other.loadDialogs(999999L, false) == null);
            Assert.isTrue("nor the conversation",
                    other.loadHistory(999999L, false, peer(7), 0) == null);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * A test build and a production build share one RMS on the same handset,
     * and the two are different accounts on different servers.
     */
    private static void theOtherEnvironmentCacheIsNeverShown() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsConversationCache().saveDialogs(ACCOUNT, true,
                    new Dialog[] { dialog(7) });
            rms.restart();

            Assert.isTrue("a production build does not read a test cache",
                    new RmsConversationCache().loadDialogs(ACCOUNT, false) == null);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * Discard, not migration. A cache is a copy of something the server still
     * has, so re-fetching costs one request; the outbox is where an upgrade
     * that dropped records would be unacceptable.
     */
    private static void aFormatThisBuildDoesNotReadIsDiscarded() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            // A record from the pre-envelope format: magic TGDC, then fields.
            tg.tl.TlWriter w = new tg.tl.TlWriter(64);
            w.writeInt(0x54474443);
            w.writeLong(ACCOUNT);
            w.writeInt(0);
            w.writeLong(NOW);
            w.writeInt(0);
            byte[] bare = w.toByteArray();

            RecordStore raw = RecordStore.openRecordStore(DIALOGS, true);
            raw.addRecord(bare, 0, bare.length);
            raw.closeRecordStore();

            RmsConversationCache cache = new RmsConversationCache();
            Assert.isTrue("it is not read", cache.loadDialogs(ACCOUNT, false) == null);
            Assert.equal("and it does not sit there for ever", 0,
                    rms.recordIds(DIALOGS).length);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * A corrupt count field is an OutOfMemoryError during a load on the device
     * with the smallest heap, unless the bound is checked first.
     */
    private static void boundsAreCheckedBeforeTheArrayIsAllocated() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            // A well-formed envelope whose payload claims a hundred million
            // dialogs. The checksum is correct, so only the bound stops it.
            tg.tl.TlWriter w = new tg.tl.TlWriter(32);
            w.writeLong(NOW);
            w.writeInt(100000000);
            byte[] record = RecordEnvelope.wrap(0x54474434, 1, ACCOUNT, false,
                    w.toByteArray());

            RecordStore raw = RecordStore.openRecordStore(DIALOGS, true);
            raw.addRecord(record, 0, record.length);
            raw.closeRecordStore();

            RmsConversationCache cache = new RmsConversationCache();
            Assert.isTrue("the count is refused rather than allocated",
                    cache.loadDialogs(ACCOUNT, false) == null);
            Assert.equal("and the record is removed", 0,
                    rms.recordIds(DIALOGS).length);
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void clearingRemovesEverything() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsConversationCache cache = new RmsConversationCache();
            cache.saveDialogs(ACCOUNT, false, new Dialog[] { dialog(7) });
            cache.saveHistory(ACCOUNT, false, peer(7), 0,
                    new Message[] { message(1, "gone after logout") });

            cache.clear();

            Assert.isFalse("the dialog cache store is gone", rms.exists(DIALOGS));
            Assert.isFalse("and the history cache store", rms.exists(HISTORY));
            Assert.isTrue("nothing loads", new RmsConversationCache()
                    .loadDialogs(ACCOUNT, false) == null);
        }
        finally { EmulatorRecords.restore(); }
    }

    // ---------------------------------------------------------------- helpers

    private static Peer peer(long id)
    {
        Peer peer = new Peer(Peer.USER, id);
        peer.title = "Peer " + id;
        return peer;
    }

    private static Dialog dialog(long id)
    {
        Dialog dialog = new Dialog();
        dialog.peer = peer(id);
        dialog.topMessageId = 10;
        dialog.lastMessage = "last";
        dialog.date = 1234;
        return dialog;
    }

    private static Message message(int id, String text)
    {
        Message message = new Message();
        message.id = id;
        message.text = text;
        message.date = 1234;
        message.peer = peer(7);
        return message;
    }
}
