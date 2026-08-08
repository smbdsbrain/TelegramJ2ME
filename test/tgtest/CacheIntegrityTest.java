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
            cache.saveHistory(ACCOUNT, false, peer(7), new Message[] { message(1, "seven") });
            Message withEntity = message(2, "eight@example.test");
            MessageEntity entity = new MessageEntity();
            entity.type = MessageEntity.EMAIL;
            entity.offset = 0;
            entity.length = withEntity.text.length();
            withEntity.entities = new MessageEntity[] { entity };
            cache.saveHistory(ACCOUNT, false, peer(8),
                    new Message[] { withEntity });

            int[] ids = rms.recordIds(HISTORY);
            Assert.equal("two conversations cached", 2, ids.length);
            rms.flipBit(HISTORY, ids[0], RecordEnvelope.HEADER + 2, 5);
            rms.restart();

            RmsConversationCache reopened = new RmsConversationCache();
            Cached eight = reopened.loadHistory(ACCOUNT, false, peer(8));
            Assert.isTrue("the intact conversation still loads", eight != null);
            Assert.equal("with its message", "eight@example.test",
                    eight.messages()[0].text);
            Assert.equal("entity survives cache round trip", 1,
                    eight.messages()[0].entities.length);
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
            RmsConversationCache cache = new RmsConversationCache();
            cache.saveHistory(ACCOUNT, false, peer(7),
                    new Message[] { message(3, "old cache") });
            int id = rms.recordIds(HISTORY)[0];
            byte[] raw = rms.peek(HISTORY, id);
            RecordEnvelope v2 = RecordEnvelope.unwrap(raw, HISTORY_MAGIC,
                    2, 2, ACCOUNT, false);
            Assert.isTrue("fixture starts as v2", v2.isOk());
            byte[] oldPayload = new byte[v2.payload.length - 4];
            System.arraycopy(v2.payload, 0, oldPayload, 0,
                    oldPayload.length);
            rms.poke(HISTORY, id, RecordEnvelope.wrap(HISTORY_MAGIC, 1,
                    ACCOUNT, false, oldPayload));
            rms.restart();

            Cached loaded = new RmsConversationCache().loadHistory(
                    ACCOUNT, false, peer(7));
            Assert.isTrue("v1 history remains readable", loaded != null);
            Assert.equal("v1 text", "old cache", loaded.messages()[0].text);
            Assert.equal("v1 entities default empty", 0,
                    loaded.messages()[0].entities.length);
        }
        finally { EmulatorRecords.restore(); }
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
            cache.saveHistory(ACCOUNT, false, peer(7),
                    new Message[] { message(1, "private") });
            rms.restart();

            RmsConversationCache other = new RmsConversationCache();
            Assert.isTrue("the dialog list is not shown",
                    other.loadDialogs(999999L, false) == null);
            Assert.isTrue("nor the conversation",
                    other.loadHistory(999999L, false, peer(7)) == null);
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
            cache.saveHistory(ACCOUNT, false, peer(7),
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
