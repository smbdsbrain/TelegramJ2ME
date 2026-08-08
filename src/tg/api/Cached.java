package tg.api;

/**
 * Something read back from the offline cache, and when it was written.
 *
 * The timestamp used to be stored and then dropped on the way out, so a chat
 * restored from cache looked exactly like one just fetched. On a handset that
 * spends a lot of its life without a usable connection that is not a cosmetic
 * difference: a reader who cannot tell four-second-old from four-day-old text
 * has no way to know whether the last message in a conversation is the last
 * message in the conversation.
 *
 * Carrying it out costs one object per load rather than a copy of the data.
 *
 * <h3>Age, not expiry</h3>
 * Nothing here throws data away for being old. Stale text is still the best
 * thing available when the network is gone, and the eviction bounds that do
 * exist are about space rather than freshness. This says how old it is and lets
 * the screen say so.
 */
public final class Cached
{
    /** No usable timestamp: absent, zero, or from a clock that disagrees. */
    public static final long UNKNOWN = 0L;

    private final Object payload;

    /** When this was written, in the writing handset's clock, or {@link #UNKNOWN}. */
    public final long savedAt;

    private Cached(Object payload, long savedAt)
    {
        this.payload = payload;
        this.savedAt = savedAt;
    }

    public static Cached of(Object payload, long savedAt)
    {
        return payload == null ? null : new Cached(payload, savedAt);
    }

    public Dialog[] dialogs()
    {
        return payload instanceof Dialog[] ? (Dialog[]) payload : null;
    }

    public Message[] messages()
    {
        return payload instanceof Message[] ? (Message[]) payload : null;
    }

    /**
     * How old this is, in milliseconds, or -1 when that cannot be said.
     *
     * <h3>Clocks</h3>
     * One of the three handsets this has run on resets its clock to 2011 on
     * every power cycle, so "now is before it was written" is a state that
     * happens on real hardware rather than a hypothetical. It answers -1 -
     * "age unknown" - because a negative age rendered as a number is worse than
     * an admission, and clamping it to zero would claim the data is fresh.
     */
    public long ageMs(long now)
    {
        if (savedAt == UNKNOWN || now < savedAt) { return -1; }
        return now - savedAt;
    }

    /**
     * "cached", "cached 18 min old", "cached 3 days old", "cached age unknown".
     *
     * Formatted here rather than in the screen because CLDC has no date
     * formatting worth the name and every caller would otherwise write its own
     * arithmetic. Deliberately coarse: the reader wants to know whether this is
     * minutes or days behind, not the second it was written.
     */
    public String ageLabel(long now)
    {
        long age = ageMs(now);
        if (age < 0) { return "cached, age unknown"; }
        long minutes = age / 60000L;
        if (minutes < 1) { return "cached just now"; }
        if (minutes < 60) { return "cached " + minutes + " min old"; }
        long hours = minutes / 60;
        if (hours < 24) { return "cached " + hours + (hours == 1 ? " hour old" : " hours old"); }
        long days = hours / 24;
        return "cached " + days + (days == 1 ? " day old" : " days old");
    }
}
