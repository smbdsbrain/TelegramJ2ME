package tg.api;

import tg.tl.TlObj;

/**
 * Bounded display/vote model for a Telegram poll.
 *
 * The option byte strings are deliberately retained. They are opaque stable
 * identifiers, not array indices, and are the values messages.sendVote needs.
 */
public final class Poll
{
    public static final int MAX_OPTIONS = 12;
    private static final int MAX_QUESTION = 300;
    private static final int MAX_ANSWER = 100;
    private static final int MAX_SOLUTION = 200;

    public long id;
    public String question = "";
    public PollOption[] options = new PollOption[0];
    public int totalVoters = -1;
    public String solution = "";

    public boolean closed;
    public boolean publicVoters;
    public boolean multipleChoice;
    public boolean quiz;
    public boolean openAnswers;
    public boolean revotingDisabled;
    public boolean shuffleAnswers;
    public boolean hideResultsUntilClose;
    public boolean creator;
    public boolean subscribersOnly;
    public int closePeriod;
    public int closeDate;

    /** True only when this object carried a Poll definition, not results alone. */
    boolean definitionPresent;
    private PollOption[] resultOptions = new PollOption[0];
    private boolean resultVectorPresent;
    private boolean resultsMin;
    private boolean totalPresent;
    private boolean solutionPresent;

    public static Poll from(TlObj poll, TlObj results)
    {
        Poll out = partial(poll == null ? 0
                : poll.num(Api.F_POLL__ID), poll, results);
        return out.definitionPresent ? out : null;
    }

    /** Build the partial shape carried by updateMessagePoll. */
    static Poll partial(long pollId, TlObj poll, TlObj results)
    {
        Poll out = new Poll();
        out.id = pollId;
        if (poll != null && poll.id == Api.POLL)
        {
            out.definitionPresent = true;
            out.id = poll.num(Api.F_POLL__ID);
            out.closed = poll.flag(0);
            out.publicVoters = poll.flag(1);
            out.multipleChoice = poll.flag(2);
            out.quiz = poll.flag(3);
            out.openAnswers = poll.flag(6);
            out.revotingDisabled = poll.flag(7);
            out.shuffleAnswers = poll.flag(8);
            out.hideResultsUntilClose = poll.flag(9);
            out.creator = poll.flag(10);
            out.subscribersOnly = poll.flag(11);
            out.question = text(poll.obj(Api.F_POLL__QUESTION), MAX_QUESTION);
            TlObj[] answers = poll.vec(Api.F_POLL__ANSWERS);
            int count = Math.min(answers.length, MAX_OPTIONS);
            PollOption[] parsed = new PollOption[count];
            int kept = 0;
            for (int i = 0; i < count; i++)
            {
                TlObj answer = answers[i];
                if (answer == null || answer.id != Api.POLL_ANSWER) { continue; }
                byte[] token = answer.bytes(Api.F_POLL_ANSWER__OPTION);
                if (token == null || token.length == 0) { continue; }
                PollOption item = new PollOption();
                item.text = text(answer.obj(Api.F_POLL_ANSWER__TEXT), MAX_ANSWER);
                item.option = copyBytes(token);
                parsed[kept++] = item;
            }
            out.options = trim(parsed, kept);
            out.closePeriod = poll.intAt(Api.F_POLL__CLOSE_PERIOD);
            out.closeDate = poll.intAt(Api.F_POLL__CLOSE_DATE);
        }
        out.readResults(results);
        out.applyResults(out.resultOptions, out.resultVectorPresent,
                out.resultsMin);
        return out;
    }

    /** Merge a partial update while retaining omitted definition/user state. */
    public void merge(Poll update)
    {
        if (update == null || (id != 0 && update.id != 0 && id != update.id))
        {
            return;
        }
        if (id == 0) { id = update.id; }

        PollOption[] previous = options;
        if (update.definitionPresent)
        {
            closed = update.closed;
            publicVoters = update.publicVoters;
            multipleChoice = update.multipleChoice;
            quiz = update.quiz;
            openAnswers = update.openAnswers;
            revotingDisabled = update.revotingDisabled;
            shuffleAnswers = update.shuffleAnswers;
            hideResultsUntilClose = update.hideResultsUntilClose;
            creator = update.creator;
            subscribersOnly = update.subscribersOnly;
            question = update.question;
            closePeriod = update.closePeriod;
            closeDate = update.closeDate;
            options = copyOptions(update.options);
            // Definition refreshes and result vectors are independently
            // optional. Carry known state across the reordered definition by
            // token; any supplied result vector below overwrites what it knows.
            preserveState(previous, options);
        }

        applyResults(update.resultOptions, update.resultVectorPresent,
                update.resultsMin);
        if (update.totalPresent)
        {
            totalVoters = update.totalVoters;
        }
        if (update.solutionPresent)
        {
            solution = update.solution;
        }
    }

    public boolean hasVote()
    {
        for (int i = 0; i < options.length; i++)
        {
            if (options[i] != null && options[i].chosen) { return true; }
        }
        return false;
    }

    public boolean canVote()
    {
        return !closed && !(revotingDisabled && hasVote());
    }

    public boolean hasOptionResults()
    {
        for (int i = 0; i < options.length; i++)
        {
            if (options[i] != null && options[i].voters >= 0) { return true; }
        }
        return false;
    }

    /** Independently rounded whole percentage, or -1 when undisclosed. */
    public int percent(PollOption option)
    {
        if (option == null || option.voters < 0 || totalVoters <= 0) { return -1; }
        long scaled = (long) option.voters * 100L;
        return (int) ((scaled + totalVoters / 2L) / totalVoters);
    }

    public byte[][] chosenOptions()
    {
        int count = 0;
        for (int i = 0; i < options.length; i++)
        {
            if (options[i] != null && options[i].chosen) { count++; }
        }
        byte[][] out = new byte[count][];
        int at = 0;
        for (int i = 0; i < options.length; i++)
        {
            if (options[i] != null && options[i].chosen)
            {
                out[at++] = copyBytes(options[i].option);
            }
        }
        return out;
    }

    private void readResults(TlObj results)
    {
        if (results == null || results.id != Api.POLL_RESULTS) { return; }
        resultsMin = results.flag(0);
        resultVectorPresent = results.flag(1);
        totalPresent = results.flag(2);
        solutionPresent = results.flag(4);
        if (totalPresent)
        {
            totalVoters = results.intAt(Api.F_POLL_RESULTS__TOTAL_VOTERS);
        }
        if (solutionPresent)
        {
            solution = bounded(results.strOrEmpty(
                    Api.F_POLL_RESULTS__SOLUTION), MAX_SOLUTION);
        }
        if (!resultVectorPresent) { return; }
        TlObj[] raw = results.vec(Api.F_POLL_RESULTS__RESULTS);
        int count = Math.min(raw.length, MAX_OPTIONS);
        PollOption[] parsed = new PollOption[count];
        int kept = 0;
        for (int i = 0; i < count; i++)
        {
            TlObj result = raw[i];
            if (result == null || result.id != Api.POLL_ANSWER_VOTERS) { continue; }
            byte[] token = result.bytes(Api.F_POLL_ANSWER_VOTERS__OPTION);
            if (token == null || token.length == 0) { continue; }
            PollOption item = new PollOption();
            item.option = copyBytes(token);
            item.chosen = result.flag(0);
            item.correct = result.flag(1);
            item.voters = result.flag(2)
                    ? result.intAt(Api.F_POLL_ANSWER_VOTERS__VOTERS) : -1;
            parsed[kept++] = item;
        }
        resultOptions = trim(parsed, kept);
    }

    private void applyResults(PollOption[] results, boolean present,
                              boolean min)
    {
        if (!present) { return; }
        for (int i = 0; i < options.length; i++)
        {
            PollOption option = options[i];
            PollOption result = find(results, option.option);
            if (result == null)
            {
                option.voters = -1;
                option.correct = false;
                if (!min) { option.chosen = false; }
                continue;
            }
            option.voters = result.voters;
            option.correct = result.correct;
            if (!min) { option.chosen = result.chosen; }
        }
    }

    private static void preserveState(PollOption[] before, PollOption[] after)
    {
        for (int i = 0; i < after.length; i++)
        {
            PollOption old = find(before, after[i].option);
            if (old != null)
            {
                after[i].chosen = old.chosen;
                after[i].correct = old.correct;
                after[i].voters = old.voters;
            }
        }
    }

    private static PollOption find(PollOption[] values, byte[] token)
    {
        if (values == null) { return null; }
        for (int i = 0; i < values.length; i++)
        {
            if (values[i] != null && sameBytes(values[i].option, token))
            {
                return values[i];
            }
        }
        return null;
    }

    private static String text(TlObj value, int max)
    {
        if (value == null || value.id != Api.TEXT_WITH_ENTITIES) { return ""; }
        return bounded(value.strOrEmpty(Api.F_TEXT_WITH_ENTITIES__TEXT), max);
    }

    private static String bounded(String value, int max)
    {
        if (value == null) { return ""; }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static PollOption[] trim(PollOption[] values, int count)
    {
        if (count == values.length) { return values; }
        PollOption[] out = new PollOption[count];
        System.arraycopy(values, 0, out, 0, count);
        return out;
    }

    private static PollOption[] copyOptions(PollOption[] values)
    {
        if (values == null) { return new PollOption[0]; }
        PollOption[] out = new PollOption[values.length];
        for (int i = 0; i < values.length; i++)
        {
            out[i] = values[i] == null ? new PollOption() : values[i].copy();
        }
        return out;
    }

    static byte[] copyBytes(byte[] value)
    {
        if (value == null) { return new byte[0]; }
        byte[] out = new byte[value.length];
        System.arraycopy(value, 0, out, 0, value.length);
        return out;
    }

    public static boolean sameBytes(byte[] a, byte[] b)
    {
        if (a == b) { return true; }
        if (a == null || b == null || a.length != b.length) { return false; }
        for (int i = 0; i < a.length; i++)
        {
            if (a[i] != b[i]) { return false; }
        }
        return true;
    }
}
