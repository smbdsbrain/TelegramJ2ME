package tg.api;

/**
 * Durable Telegram update cursor.
 *
 * Common messages share pts/qts/date/seq. Channels and supergroups each have
 * their own pts sequence, kept in a deliberately bounded table.
 */
public final class UpdateState
{
    public static final int MAX_CHANNELS = 128;

    public long accountId;
    public boolean testEnvironment;
    public int pts;
    public int qts;
    public int date;
    public int seq;

    private final long[] channelIds = new long[MAX_CHANNELS];
    private final int[] channelPts = new int[MAX_CHANNELS];
    private int channelCount;

    /** @return the channel pts, or -1 when the channel has no known cursor. */
    public synchronized int channelPts(long channelId)
    {
        int at = channelIndex(channelId);
        return at < 0 ? -1 : channelPts[at];
    }

    public synchronized void setChannelPts(long channelId, int pts)
    {
        if (channelId == 0 || pts < 0) { return; }
        int at = channelIndex(channelId);
        if (at >= 0)
        {
            channelPts[at] = pts;
            return;
        }
        if (channelCount < MAX_CHANNELS)
        {
            at = channelCount++;
        }
        else
        {
            // The dialog list continually re-seeds visible channels. Evicting
            // the oldest slot is safer than allowing unbounded heap/RMS growth.
            System.arraycopy(channelIds, 1, channelIds, 0, MAX_CHANNELS - 1);
            System.arraycopy(channelPts, 1, channelPts, 0, MAX_CHANNELS - 1);
            at = MAX_CHANNELS - 1;
        }
        channelIds[at] = channelId;
        channelPts[at] = pts;
    }

    public synchronized int channelCount()
    {
        return channelCount;
    }

    public synchronized long channelIdAt(int index)
    {
        return channelIds[index];
    }

    public synchronized int channelPtsAt(int index)
    {
        return channelPts[index];
    }

    public synchronized UpdateState copy()
    {
        UpdateState out = new UpdateState();
        out.accountId = accountId;
        out.testEnvironment = testEnvironment;
        out.pts = pts;
        out.qts = qts;
        out.date = date;
        out.seq = seq;
        out.channelCount = channelCount;
        System.arraycopy(channelIds, 0, out.channelIds, 0, channelCount);
        System.arraycopy(channelPts, 0, out.channelPts, 0, channelCount);
        return out;
    }

    private int channelIndex(long channelId)
    {
        for (int i = 0; i < channelCount; i++)
        {
            if (channelIds[i] == channelId) { return i; }
        }
        return -1;
    }
}
