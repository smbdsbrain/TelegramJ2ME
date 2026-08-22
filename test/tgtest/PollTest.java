package tgtest;

import javax.microedition.lcdui.Canvas;

import org.microemu.device.DeviceFactory;

import tg.api.Api;
import tg.api.Cached;
import tg.api.Media;
import tg.api.Message;
import tg.api.Peer;
import tg.api.Poll;
import tg.api.PollOption;
import tg.api.Requests;
import tg.plat.RmsConversationCache;
import tg.tl.TlObj;
import tg.tl.TlReader;
import tg.ui.ChatScreen;
import tg.ui.PollScreen;

/** Poll model, sendVote wire, picker, inline layout and cache-v5 tests. */
public final class PollTest implements Test
{
    public String name() { return "poll/model-wire-ui-cache"; }

    public void run() throws Exception
    {
        parseFlagsResultsAndBounds();
        mergeByOpaqueTokenAndPreserveMinChoice();
        sendVoteWire();
        pickerModesAndLiveReorder();
        inlineLayoutReflows();
        cacheV5RoundTrip();
    }

    private static void parseFlagsResultsAndBounds()
    {
        TlObj[] answers = new TlObj[14];
        for (int i = 0; i < answers.length; i++)
        {
            answers[i] = answer("answer " + i, token(i));
        }
        int flags = 1 | 2 | 4 | 8 | (1 << 4) | (1 << 5)
                | (1 << 6) | (1 << 7) | (1 << 8) | (1 << 9)
                | (1 << 10) | (1 << 11);
        TlObj definition = pollDefinition(9001L, flags, "Question?", answers);
        definition.nums[Api.F_POLL__CLOSE_PERIOD] = 60;
        definition.nums[Api.F_POLL__CLOSE_DATE] = 123456;
        TlObj results = pollResults(false, 10, "Because.", new TlObj[] {
                voters(token(2), 3, false, true),
                voters(token(0), 7, true, false)
        });

        Poll poll = Poll.from(definition, results);
        Assert.equal("poll id", 9001L, poll.id);
        Assert.equal("question", "Question?", poll.question);
        Assert.equal("options bounded to Telegram UI maximum", 12,
                poll.options.length);
        Assert.isTrue("closed", poll.closed);
        Assert.isTrue("public voters", poll.publicVoters);
        Assert.isTrue("multiple choice", poll.multipleChoice);
        Assert.isTrue("quiz", poll.quiz);
        Assert.isTrue("open answers", poll.openAnswers);
        Assert.isTrue("revoting disabled", poll.revotingDisabled);
        Assert.isTrue("shuffle", poll.shuffleAnswers);
        Assert.isTrue("hidden until close", poll.hideResultsUntilClose);
        Assert.isTrue("creator", poll.creator);
        Assert.isTrue("subscribers only", poll.subscribersOnly);
        Assert.equal("close period", 60, poll.closePeriod);
        Assert.equal("close date", 123456, poll.closeDate);
        Assert.equal("total voters", 10, poll.totalVoters);
        Assert.equal("solution", "Because.", poll.solution);
        Assert.isTrue("chosen mapped by token", poll.options[0].chosen);
        Assert.equal("chosen voters", 7, poll.options[0].voters);
        Assert.isTrue("correct mapped by token", poll.options[2].correct);
        Assert.equal("unordered voters", 3, poll.options[2].voters);
        Assert.equal("rounded percent", 70, poll.percent(poll.options[0]));
        Assert.equal("missing result stays hidden", -1, poll.options[1].voters);

        TlObj hidden = pollResults(false, 12, null, null);
        Poll noBreakdown = Poll.from(pollDefinition(2, 0, "Hidden",
                new TlObj[] { answer("A", token(0)) }), hidden);
        Assert.equal("known total without breakdown", 12,
                noBreakdown.totalVoters);
        Assert.isFalse("breakdown remains hidden", noBreakdown.hasOptionResults());

        TlObj media = obj(Api.MESSAGE_MEDIA_POLL,
                Api.F_MESSAGE_MEDIA_POLL__RESULTS + 1);
        media.refs[Api.F_MESSAGE_MEDIA_POLL__POLL] = definition;
        media.refs[Api.F_MESSAGE_MEDIA_POLL__RESULTS] = results;
        Assert.equal("media poll parsed", 9001L, Media.from(media).poll.id);
    }

    private static void mergeByOpaqueTokenAndPreserveMinChoice()
    {
        Poll current = Poll.from(pollDefinition(77, 4, "Pick", new TlObj[] {
                answer("A", token(1)), answer("B", token(2))
        }), pollResults(false, 5, null, new TlObj[] {
                voters(token(1), 4, true, false),
                voters(token(2), 1, false, false)
        }));

        Poll reorderedMin = Poll.from(pollDefinition(77, 4, "Pick", new TlObj[] {
                answer("B renamed", token(2)), answer("A", token(1))
        }), pollResults(true, 8, null, new TlObj[] {
                voters(token(2), 3, false, false),
                voters(token(1), 5, false, false)
        }));
        current.merge(reorderedMin);
        Assert.equal("definition reorder retained", "B renamed",
                current.options[0].text);
        Assert.equal("result follows token after reorder", 3,
                current.options[0].voters);
        Assert.isFalse("other token not chosen", current.options[0].chosen);
        Assert.isTrue("min update preserves own choice by token",
                current.options[1].chosen);
        Assert.equal("min total applied", 8, current.totalVoters);

        Poll definitionOnly = Poll.from(pollDefinition(77, 4, "Renamed",
                new TlObj[] { answer("B again", token(2)),
                        answer("A again", token(1)) }),
                pollResults(false, -1, null, null));
        current.merge(definitionOnly);
        Assert.equal("definition-only update keeps known count", 3,
                current.options[0].voters);
        Assert.isTrue("definition-only update keeps known choice",
                current.options[1].chosen);
    }

    private static void sendVoteWire() throws Exception
    {
        Peer user = new Peer(Peer.USER, 11);
        user.accessHash = 12;
        assertVotePeer(Requests.sendVote(user, 42,
                new byte[][] { token(1) }), Api.INPUT_PEER_USER, 11, 12, 42,
                new byte[][] { token(1) });

        Peer chat = new Peer(Peer.CHAT, 21);
        assertVotePeer(Requests.sendVote(chat, 43,
                new byte[][] { token(2), token(3) }), Api.INPUT_PEER_CHAT,
                21, 0, 43, new byte[][] { token(2), token(3) });

        Peer channel = new Peer(Peer.CHANNEL, 31);
        channel.accessHash = 32;
        assertVotePeer(Requests.sendVote(channel, 44, new byte[0][]),
                Api.INPUT_PEER_CHANNEL, 31, 32, 44, new byte[0][]);
    }

    private static void assertVotePeer(byte[] wire, int peerCtor, long peerId,
                                       long hash, int msgId, byte[][] options)
            throws Exception
    {
        TlReader r = new TlReader(wire);
        Assert.equal("sendVote constructor", Api.MESSAGES_SEND_VOTE, r.readInt());
        Assert.equal("input peer constructor", peerCtor, r.readInt());
        Assert.equal("input peer id", peerId, r.readLong());
        if (peerCtor != Api.INPUT_PEER_CHAT)
        {
            Assert.equal("input peer hash", hash, r.readLong());
        }
        Assert.equal("vote message id", msgId, r.readInt());
        Assert.equal("vote vector count", options.length, r.readVectorCount());
        for (int i = 0; i < options.length; i++)
        {
            Assert.bytesEqual("vote option " + i, options[i], r.readBytes());
        }
        Assert.equal("exact wire length consumed", 0, r.remaining());
    }

    private static void pickerModesAndLiveReorder()
    {
        DeviceFactory.setDevice(new UiTestDevice("poll", 320, 240));
        Poll multiple = model(true);
        ExposedPoll picker = new ExposedPoll();
        picker.setPoll(multiple);
        picker.press(Canvas.KEY_NUM5);
        picker.press(Canvas.KEY_NUM8);
        picker.press(Canvas.KEY_NUM5);
        Assert.isTrue("multiple first checked", picker.isSelected(0));
        Assert.isTrue("multiple second checked", picker.isSelected(1));
        Assert.equal("multiple sends complete set", 2,
                picker.selectedTokens().length);

        Poll reordered = model(true);
        PollOption swap = reordered.options[0];
        reordered.options[0] = reordered.options[1];
        reordered.options[1] = swap;
        picker.setPoll(reordered);
        Assert.isTrue("dirty choice follows first token after reorder",
                picker.isSelected(1));
        Assert.isTrue("dirty choice follows second token after reorder",
                picker.isSelected(0));

        Poll single = model(false);
        picker = new ExposedPoll();
        picker.setPoll(single);
        picker.press(Canvas.KEY_NUM5);
        picker.press(Canvas.KEY_NUM8);
        picker.press(Canvas.KEY_NUM5);
        Assert.isFalse("radio clears previous", picker.isSelected(0));
        Assert.isTrue("radio selects focused", picker.isSelected(1));

        single.closed = true;
        picker.setPoll(single);
        boolean before = picker.isSelected(1);
        picker.press(Canvas.KEY_NUM5);
        Assert.equal("closed picker read-only", before ? 1 : 0,
                picker.isSelected(1) ? 1 : 0);

        single.closed = false;
        single.revotingDisabled = true;
        single.options[1].chosen = true;
        picker.setPoll(single);
        Assert.isFalse("non-revotable picker has no submit", picker.canSubmit());
    }

    private static void inlineLayoutReflows()
    {
        DeviceFactory.setDevice(new UiTestDevice("poll-inline", 320, 240));
        Message message = new Message();
        message.id = 1;
        message.text = "";
        message.media = new Media();
        message.media.kind = Media.POLL;
        message.media.poll = model(true);
        ChatScreen chat = new ChatScreen();
        chat.resetMessages(new Message[] { message });
        int before = chat.transcriptLineCount();
        message.media.poll.options[0].voters = 3;
        message.media.poll.options[1].voters = 7;
        message.media.poll.totalVoters = 10;
        message.media.poll.solution = "Explanation";
        chat.setMessages(new Message[] { message });
        Assert.isTrue("authoritative results cause live reflow",
                chat.transcriptLineCount() > before);
    }

    private static void cacheV5RoundTrip() throws Exception
    {
        FaultyRecords rms = new FaultyRecords();
        EmulatorRecords.swapIn(rms);
        try
        {
            Peer peer = new Peer(Peer.USER, 700);
            peer.title = "Poll peer";
            Message message = new Message();
            message.id = 9;
            message.peer = peer;
            message.media = new Media();
            message.media.kind = Media.POLL;
            message.media.label = "[poll]";
            message.media.poll = model(true);
            message.media.poll.options[1].chosen = true;
            message.media.poll.options[1].correct = true;
            message.media.poll.options[1].voters = 7;
            message.media.poll.totalVoters = 9;
            message.media.poll.solution = "why";

            RmsConversationCache cache = new RmsConversationCache();
            cache.saveHistory(999, false, peer, 0,
                    new Message[] { message });
            rms.restart();
            Cached loaded = new RmsConversationCache().loadHistory(
                    999, false, peer, 0);
            Assert.isTrue("v5 poll cache loads", loaded != null);
            Poll poll = loaded.messages()[0].media.poll;
            Assert.equal("cached poll id", 1234L, poll.id);
            Assert.equal("cached option count", 2, poll.options.length);
            Assert.bytesEqual("cached opaque token", token(2),
                    poll.options[1].option);
            Assert.isTrue("cached chosen", poll.options[1].chosen);
            Assert.isTrue("cached correct", poll.options[1].correct);
            Assert.equal("cached voters", 7, poll.options[1].voters);
            Assert.equal("cached solution", "why", poll.solution);
        }
        finally { EmulatorRecords.restore(); }
    }

    private static Poll model(boolean multiple)
    {
        Poll poll = new Poll();
        poll.id = 1234;
        poll.question = "Choose";
        poll.multipleChoice = multiple;
        poll.totalVoters = -1;
        poll.options = new PollOption[2];
        for (int i = 0; i < poll.options.length; i++)
        {
            PollOption option = new PollOption();
            option.text = "Option " + (i + 1);
            option.option = token(i + 1);
            poll.options[i] = option;
        }
        return poll;
    }

    private static TlObj pollDefinition(long id, int flags, String question,
                                        TlObj[] answers)
    {
        TlObj poll = obj(Api.POLL, Api.F_POLL__HASH + 1);
        poll.flags = flags;
        poll.hasFlags = true;
        poll.nums[Api.F_POLL__ID] = id;
        poll.refs[Api.F_POLL__QUESTION] = text(question);
        poll.refs[Api.F_POLL__ANSWERS] = answers;
        return poll;
    }

    private static TlObj pollResults(boolean min, int total, String solution,
                                     TlObj[] voters)
    {
        TlObj results = obj(Api.POLL_RESULTS,
                Api.F_POLL_RESULTS__SOLUTION_ENTITIES + 1);
        results.hasFlags = true;
        results.flags = (min ? 1 : 0) | (voters == null ? 0 : 2)
                | (total < 0 ? 0 : 4) | (solution == null ? 0 : 16);
        if (voters != null)
        {
            results.refs[Api.F_POLL_RESULTS__RESULTS] = voters;
        }
        if (total >= 0)
        {
            results.nums[Api.F_POLL_RESULTS__TOTAL_VOTERS] = total;
        }
        if (solution != null)
        {
            results.refs[Api.F_POLL_RESULTS__SOLUTION] = solution;
            results.refs[Api.F_POLL_RESULTS__SOLUTION_ENTITIES] = new TlObj[0];
        }
        return results;
    }

    private static TlObj voters(byte[] token, int count, boolean chosen,
                                 boolean correct)
    {
        TlObj out = obj(Api.POLL_ANSWER_VOTERS,
                Api.F_POLL_ANSWER_VOTERS__VOTERS + 1);
        out.hasFlags = true;
        out.flags = (chosen ? 1 : 0) | (correct ? 2 : 0) | 4;
        out.refs[Api.F_POLL_ANSWER_VOTERS__OPTION] = token;
        out.nums[Api.F_POLL_ANSWER_VOTERS__VOTERS] = count;
        return out;
    }

    private static TlObj answer(String label, byte[] token)
    {
        TlObj answer = obj(Api.POLL_ANSWER, Api.F_POLL_ANSWER__OPTION + 1);
        answer.refs[Api.F_POLL_ANSWER__TEXT] = text(label);
        answer.refs[Api.F_POLL_ANSWER__OPTION] = token;
        return answer;
    }

    private static TlObj text(String value)
    {
        TlObj out = obj(Api.TEXT_WITH_ENTITIES,
                Api.F_TEXT_WITH_ENTITIES__ENTITIES + 1);
        out.refs[Api.F_TEXT_WITH_ENTITIES__TEXT] = value;
        out.refs[Api.F_TEXT_WITH_ENTITIES__ENTITIES] = new TlObj[0];
        return out;
    }

    private static byte[] token(int value)
    {
        return new byte[] { (byte) value };
    }

    private static TlObj obj(int id, int fields)
    {
        TlObj out = new TlObj(id, fields);
        out.refs = new Object[fields];
        return out;
    }

    private static final class ExposedPoll extends PollScreen
    {
        void press(int keyCode) { keyPressed(keyCode); }
    }
}
