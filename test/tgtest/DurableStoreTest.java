package tgtest;

import java.io.IOException;

import javax.microedition.rms.RecordStore;

import tg.api.OutgoingMessage;
import tg.api.Peer;
import tg.api.RecordEnvelope;
import tg.api.UpdateState;
import tg.api.UpdateStateCodec;
import tg.io.Crc32;
import tg.plat.RmsDraftStore;
import tg.plat.RmsOutgoingStore;
import tg.plat.RmsUpdateStateStore;

/**
 * The outbox, the drafts and the update cursor, held to the restart rule.
 *
 * <b>After any interruption a store shows the old value or the new one, never
 * neither and never half of either.</b> Every case below is that sentence with
 * one primitive failed at one point, through {@link FaultyRecords}.
 *
 * The outbox is the store that matters most. Everything else in RMS is a copy
 * of something Telegram still has; the outbox is the one whose contents exist
 * nowhere else, and a row lost is a message the user believes they sent. Its
 * three specific hazards get their own cases: a {@code random_id} must never
 * change - it is Telegram's deduplication key, and a new one is a second copy
 * in the conversation - a row left {@code SENDING} by a power cut must come
 * back as {@code QUEUED}, and damaged rows must be removed rather than skipped,
 * because a skipped one consumed a slot out of sixty-four for the life of the
 * installation.
 */
public final class DurableStoreTest implements Test
{
    private static final String OUTBOX = "tgoutbox";
    private static final String DRAFTS = "tgdrafts";
    private static final String UPDATES = "tgupdates";

    private static final long ACCOUNT = 4242L;

    public String name() { return "storage/durable-stores"; }

    public void run() throws Exception
    {
        theChecksumCatchesASingleFlippedBit();
        theEnvelopeTellsTheFourCasesApart();

        // Outbox.
        aQueuedMessageSurvivesARestart();
        aRefusedAddIsNotReportedAsQueued();
        anInterruptedStateChangeKeepsTheMessageOnce();
        randomIdSurvivesEveryRecovery();
        sendingBecomesQueuedAfterARestart();
        damagedRowsDoNotConsumeCapacityForEver();
        aFullOutboxRefusesTheNextMessageCleanly();
        aRemoveThatDidNotRemoveIsReported();
        legacyRowsMigrateWithoutLosingAnything();
        anotherAccountsRowsAreNotVisible();

        // Drafts.
        aDraftSurvivesReplacementFailure();
        clearingADraftIsVerified();
        draftsAreIsolatedPerPeer();
        legacyDraftsAreStillReadable();

        // Update state.
        theUpdateCursorSurvivesARestart();
        aDamagedCursorResetsRatherThanPretendingToBeAbsent();
        anotherAccountsCursorIsNotAdopted();
        aLegacyCursorIsStillRead();
    }

    // ================================================================ format

    private static void theChecksumCatchesASingleFlippedBit() throws Exception
    {
        byte[] payload = new byte[] { 1, 2, 3, 4, 5 };
        int before = Crc32.of(payload);
        payload[2] ^= 1;
        Assert.isTrue("one bit changes the checksum", Crc32.of(payload) != before);

        // The published check value, so a rewrite of Crc32 cannot quietly
        // become a different function that is merely self-consistent.
        byte[] check = new byte[9];
        for (int i = 0; i < 9; i++) { check[i] = (byte) ('1' + i); }
        Assert.equal("CRC-32 of \"123456789\"", 0xCBF43926, Crc32.of(check));
    }

    private static void theEnvelopeTellsTheFourCasesApart() throws Exception
    {
        byte[] payload = new byte[] { 9, 8, 7 };
        byte[] raw = RecordEnvelope.wrap(0x41424344, 1, ACCOUNT, false, payload);

        RecordEnvelope ok = RecordEnvelope.unwrap(raw, 0x41424344, 1, 1,
                ACCOUNT, false);
        Assert.isTrue("a record this build wrote", ok.isOk());
        Assert.equal("payload length", 3, ok.payload.length);

        Assert.equal("another store's record", RecordEnvelope.FOREIGN,
                RecordEnvelope.unwrap(raw, 0x11111111, 1, 1, ACCOUNT, false).outcome);
        Assert.equal("a schema this build does not read",
                RecordEnvelope.WRONG_VERSION,
                RecordEnvelope.unwrap(raw, 0x41424344, 2, 2, ACCOUNT, false).outcome);
        Assert.equal("another account", RecordEnvelope.WRONG_OWNER,
                RecordEnvelope.unwrap(raw, 0x41424344, 1, 1, 99L, false).outcome);
        Assert.equal("the other environment", RecordEnvelope.WRONG_OWNER,
                RecordEnvelope.unwrap(raw, 0x41424344, 1, 1, ACCOUNT, true).outcome);

        byte[] damaged = new byte[raw.length];
        System.arraycopy(raw, 0, damaged, 0, raw.length);
        damaged[RecordEnvelope.HEADER] ^= 0x40;
        Assert.equal("damaged bytes", RecordEnvelope.DAMAGED,
                RecordEnvelope.unwrap(damaged, 0x41424344, 1, 1, ACCOUNT, false).outcome);

        // Zero is "unbound", and it matches rather than excluding: a message
        // queued before the client knew who it was must not disappear when it
        // finds out.
        byte[] unbound = RecordEnvelope.wrap(0x41424344, 1, 0, false, payload);
        Assert.isTrue("an unbound record reads for a known account",
                RecordEnvelope.unwrap(unbound, 0x41424344, 1, 1, ACCOUNT, false).isOk());
        Assert.isTrue("and a bound one reads for an unknown account",
                RecordEnvelope.unwrap(raw, 0x41424344, 1, 1, 0, false).isOk());
    }

    // ================================================================ outbox

    private static void aQueuedMessageSurvivesARestart() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            store.add(peer(7), "hello", 0, 111L, 1000L);

            rms.restart();
            OutgoingMessage[] after = outbox().list();

            Assert.equal("one message", 1, after.length);
            Assert.equal("with its text", "hello", after[0].text);
            Assert.equal("its random id", 111L, after[0].randomId);
            Assert.equal("and its peer", 7L, after[0].peerId);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The store must not answer "queued" for a row that is not there. The
     * caller shows the user their message is waiting on the strength of it.
     */
    private static void aRefusedAddIsNotReportedAsQueued() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            rms.failEvery(OUTBOX, FaultyRecords.ADD);
            try
            {
                store.add(peer(7), "never sent", 0, 222L, 1000L);
                Assert.isTrue("add must have thrown", false);
            }
            catch (IOException expected) { }
            rms.clearFaults();

            rms.restart();
            Assert.equal("and nothing is queued", 0, outbox().list().length);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * A state change writes a new row before removing the old one, so an
     * interruption leaves two rows for one message rather than none. The reader
     * has to resolve that to exactly one - and to the newer of the two.
     */
    private static void anInterruptedStateChangeKeepsTheMessageOnce()
            throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            OutgoingMessage queued = store.add(peer(7), "in flight", 0, 333L, 1000L);

            queued.attempts = 1;
            queued.state = OutgoingMessage.QUEUED;
            rms.failEvery(OUTBOX, FaultyRecords.DELETE);
            store.save(queued);                    // the old row cannot go
            rms.clearFaults();

            Assert.isTrue("both rows are on disk",
                    rms.recordIds(OUTBOX).length >= 2);

            rms.restart();
            OutgoingMessage[] after = outbox().list();
            Assert.equal("but the reader sees one message", 1, after.length);
            Assert.equal("the newer one", 1, after[0].attempts);
            Assert.equal("with the same random id", 333L, after[0].randomId);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * The one value nothing is allowed to regenerate. A new random_id is a
     * second copy of the message in the conversation.
     */
    private static void randomIdSurvivesEveryRecovery() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            OutgoingMessage message = store.add(peer(7), "once", 0, 987654321L, 1000L);

            message.state = OutgoingMessage.SENDING;
            store.save(message);
            rms.restart();

            OutgoingMessage[] recovered = outbox().list();
            Assert.equal("still one message", 1, recovered.length);
            Assert.equal("with the id Telegram deduplicates on",
                    987654321L, recovered[0].randomId);

            recovered[0].attempts = 3;
            outbox().save(recovered[0]);
            rms.restart();
            Assert.equal("and it is unchanged by a retry", 987654321L,
                    outbox().list()[0].randomId);
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void sendingBecomesQueuedAfterARestart() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            OutgoingMessage message = store.add(peer(7), "mid-flight", 0, 444L, 1000L);
            message.state = OutgoingMessage.SENDING;
            store.save(message);

            rms.restart();
            OutgoingMessage[] after = outbox().list();

            Assert.equal("one message", 1, after.length);
            Assert.equal("no request is on the wire after a restart, so it is"
                    + " queued again", OutgoingMessage.QUEUED, after[0].state);
            Assert.equal("under the same random id", 444L, after[0].randomId);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * A damaged row used to be skipped, which left it holding one of the
     * sixty-four slots until the installation was deleted.
     */
    private static void damagedRowsDoNotConsumeCapacityForEver() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            store.add(peer(7), "good", 0, 555L, 1000L);
            OutgoingMessage doomed = store.add(peer(7), "damaged", 0, 556L, 1001L);

            rms.flipBit(OUTBOX, doomed.localId, RecordEnvelope.HEADER + 4, 3);
            rms.restart();

            RmsOutgoingStore reopened = outbox();
            OutgoingMessage[] after = reopened.list();
            Assert.equal("the readable row is still there", 1, after.length);
            Assert.equal("and it is the good one", "good", after[0].text);
            Assert.equal("the damaged row was removed rather than skipped",
                    1, rms.recordIds(OUTBOX).length);
            Assert.equal("and counted", 1, reopened.damagedRemoved());
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void aFullOutboxRefusesTheNextMessageCleanly() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            for (int i = 0; i < 64; i++)
            {
                store.add(peer(7), "m" + i, 0, 1000L + i, 2000L + i);
            }
            int before = rms.recordIds(OUTBOX).length;
            try
            {
                store.add(peer(7), "one too many", 0, 9999L, 3000L);
                Assert.isTrue("the sixty-fifth must be refused", false);
            }
            catch (IOException expected)
            {
                Assert.isTrue("and say why: " + expected.getMessage(),
                        expected.getMessage().indexOf("full") >= 0);
            }
            Assert.equal("with no partial record left behind", before,
                    rms.recordIds(OUTBOX).length);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * A remove that quietly did nothing means the message is delivered and
     * still queued, and the next drain sends it again under a random_id
     * Telegram has already retired.
     */
    private static void aRemoveThatDidNotRemoveIsReported() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore store = outbox();
            OutgoingMessage message = store.add(peer(7), "sent", 0, 666L, 1000L);

            rms.failEvery(OUTBOX, FaultyRecords.DELETE);
            try
            {
                store.remove(message.localId);
                Assert.isTrue("remove must have thrown", false);
            }
            catch (IOException expected) { }
            rms.clearFaults();

            Assert.equal("and the row is still there to try again",
                    1, rms.recordIds(OUTBOX).length);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * An upgrade must not drop the queue. The rows a previous build wrote have
     * no envelope, and they are the user's unsent messages.
     */
    private static void legacyRowsMigrateWithoutLosingAnything() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            OutgoingMessage old = new OutgoingMessage();
            old.peerKind = Peer.USER;
            old.peerId = 7;
            old.peerTitle = "Someone";
            old.text = "written by the previous build";
            old.randomId = 777L;
            old.createdAt = 500L;
            old.attempts = 2;

            RecordStore raw = RecordStore.openRecordStore(OUTBOX, true);
            byte[] bare = OutgoingMessage.encode(old);
            raw.addRecord(bare, 0, bare.length);
            raw.closeRecordStore();

            OutgoingMessage[] after = outbox().list();
            Assert.equal("the legacy row is read", 1, after.length);
            Assert.equal("with its text", "written by the previous build",
                    after[0].text);
            Assert.equal("its random id", 777L, after[0].randomId);
            Assert.equal("and its attempt count", 2, after[0].attempts);

            // And it is now stored in the current format, so the next launch
            // does not have to recognise the old one again.
            rms.restart();
            OutgoingMessage[] migrated = outbox().list();
            Assert.equal("still exactly one row", 1, migrated.length);
            Assert.equal("still the same random id", 777L, migrated[0].randomId);
            Assert.equal("and one record on disk", 1,
                    rms.recordIds(OUTBOX).length);
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void anotherAccountsRowsAreNotVisible() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsOutgoingStore mine = outbox();
            mine.add(peer(7), "mine", 0, 888L, 1000L);

            RmsOutgoingStore theirs = new RmsOutgoingStore();
            theirs.bindAccount(999999L);
            Assert.equal("another account sees none of it", 0,
                    theirs.list().length);

            // And has not removed them either.
            Assert.equal("the rows are still on disk", 1,
                    rms.recordIds(OUTBOX).length);
            Assert.equal("and their owner still sees them", 1,
                    outbox().list().length);
        }
        finally { EmulatorRecords.restore(); }
    }

    // ================================================================ drafts

    /**
     * Replacement writes the new row first. Interrupted before the old one is
     * removed, the chat has two drafts and must show the newer.
     */
    private static void aDraftSurvivesReplacementFailure() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsDraftStore store = drafts();
            store.save(peer(7), "first version");

            rms.failEvery(DRAFTS, FaultyRecords.DELETE);
            store.save(peer(7), "second version");
            rms.clearFaults();

            rms.restart();
            Assert.equal("the newer draft wins", "second version",
                    drafts().load(peer(7)));

            // The other direction: the add fails, and the old draft is intact.
            rms.failEvery(DRAFTS, FaultyRecords.ADD);
            try
            {
                drafts().save(peer(7), "third version");
                Assert.isTrue("the save must have thrown", false);
            }
            catch (IOException expected) { }
            rms.clearFaults();
            rms.restart();
            Assert.equal("a refused save keeps what was there",
                    "second version", drafts().load(peer(7)));
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void clearingADraftIsVerified() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsDraftStore store = drafts();
            store.save(peer(7), "typed and then sent");

            rms.failEvery(DRAFTS, FaultyRecords.DELETE);
            try
            {
                store.save(peer(7), "");
                Assert.isTrue("clearing must report that it did not happen",
                        false);
            }
            catch (IOException expected) { }
            rms.clearFaults();

            store.save(peer(7), "");
            rms.restart();
            Assert.equal("and once it works the draft is gone", "",
                    drafts().load(peer(7)));
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void draftsAreIsolatedPerPeer() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsDraftStore store = drafts();
            store.save(peer(7), "for seven");
            store.save(peer(8), "for eight");
            rms.restart();

            Assert.equal("seven", "for seven", drafts().load(peer(7)));
            Assert.equal("eight", "for eight", drafts().load(peer(8)));

            drafts().save(peer(7), "");
            Assert.equal("clearing one leaves the other", "for eight",
                    drafts().load(peer(8)));

            RmsDraftStore theirs = new RmsDraftStore();
            theirs.bindAccount(999999L);
            Assert.equal("and another account sees neither", "",
                    theirs.load(peer(8)));
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void legacyDraftsAreStillReadable() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            tg.tl.TlWriter writer = new tg.tl.TlWriter(64);
            writer.writeInt(0x54474432);          // TGD2
            writer.writeInt(Peer.USER);
            writer.writeLong(7L);
            writer.writeString("from the previous build");
            byte[] bare = writer.toByteArray();

            RecordStore raw = RecordStore.openRecordStore(DRAFTS, true);
            raw.addRecord(bare, 0, bare.length);
            raw.closeRecordStore();

            Assert.equal("an old draft is still shown",
                    "from the previous build", drafts().load(peer(7)));

            // And a save replaces it rather than leaving both.
            drafts().save(peer(7), "edited");
            rms.restart();
            Assert.equal("the edit wins", "edited", drafts().load(peer(7)));
        }
        finally { EmulatorRecords.restore(); }
    }

    // ========================================================== update state

    private static void theUpdateCursorSurvivesARestart() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            RmsUpdateStateStore store = new RmsUpdateStateStore();
            store.save(state(120, 44));

            rms.restart();
            UpdateState after = new RmsUpdateStateStore().load(ACCOUNT, false);

            Assert.isTrue("the cursor is there", after != null);
            Assert.equal("pts", 120, after.pts);
            Assert.equal("date", 44, after.date);
            Assert.equal("and only one record is kept", 1,
                    rms.recordIds(UPDATES).length);
        }
        finally { EmulatorRecords.restore(); }
    }

    /**
     * Believing a wrong cursor is worse than having none: it tells Telegram the
     * client has seen updates it has not, and the difference is never asked
     * for. So a damaged one becomes "no state", which is the path that asks for
     * a snapshot - and it says so, rather than looking like a first launch.
     */
    private static void aDamagedCursorResetsRatherThanPretendingToBeAbsent()
            throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsUpdateStateStore().save(state(500, 90));
            int id = rms.recordIds(UPDATES)[0];
            rms.flipBit(UPDATES, id, RecordEnvelope.HEADER + 6, 2);
            rms.restart();

            RmsUpdateStateStore store = new RmsUpdateStateStore();
            UpdateState after = store.load(ACCOUNT, false);

            Assert.isTrue("no cursor is returned", after == null);
            Assert.isTrue("and it is reported as a reset, not as absence",
                    store.lastLoadWasReset());
            Assert.isTrue("with a reason: " + store.lastResetReason(),
                    store.lastResetReason().length() > 0);
            Assert.isFalse("the damaged record is gone", rms.exists(UPDATES));

            // A genuinely absent cursor is not a reset.
            RmsUpdateStateStore fresh = new RmsUpdateStateStore();
            Assert.isTrue("nothing stored", fresh.load(ACCOUNT, false) == null);
            Assert.isFalse("and nothing was thrown away", fresh.lastLoadWasReset());
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void anotherAccountsCursorIsNotAdopted() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            new RmsUpdateStateStore().save(state(700, 12));
            rms.restart();

            RmsUpdateStateStore store = new RmsUpdateStateStore();
            Assert.isTrue("another account gets nothing",
                    store.load(555555L, false) == null);
            Assert.isTrue("and is told it was a reset", store.lastLoadWasReset());
        }
        finally { EmulatorRecords.restore(); }
    }

    private static void aLegacyCursorIsStillRead() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            byte[] bare = UpdateStateCodec.encode(state(321, 77));
            RecordStore raw = RecordStore.openRecordStore(UPDATES, true);
            raw.addRecord(bare, 0, bare.length);
            raw.closeRecordStore();

            RmsUpdateStateStore store = new RmsUpdateStateStore();
            UpdateState after = store.load(ACCOUNT, false);
            Assert.isTrue("the old cursor is read", after != null);
            Assert.equal("with its pts", 321, after.pts);
            Assert.isFalse("and nothing was reset", store.lastLoadWasReset());
        }
        finally { EmulatorRecords.restore(); }
    }

    // ---------------------------------------------------------------- helpers

    private static RmsOutgoingStore outbox()
    {
        RmsOutgoingStore store = new RmsOutgoingStore();
        store.bindAccount(ACCOUNT);
        return store;
    }

    private static RmsDraftStore drafts()
    {
        RmsDraftStore store = new RmsDraftStore();
        store.bindAccount(ACCOUNT);
        return store;
    }

    private static Peer peer(long id)
    {
        Peer peer = new Peer(Peer.USER, id);
        peer.title = "Peer " + id;
        return peer;
    }

    private static UpdateState state(int pts, int date)
    {
        UpdateState state = new UpdateState();
        state.accountId = ACCOUNT;
        state.testEnvironment = false;
        state.pts = pts;
        state.date = date;
        state.seq = 3;
        return state;
    }
}
