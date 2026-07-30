package tg.mt;

/**
 * MTProto message identifiers and sequence numbers.
 *
 * <h3>msg_id</h3>
 * The high 32 bits are the server's unix time and the low 32 bits are a
 * fractional part. The server rejects a message whose id is more than 300
 * seconds in its future or 30 seconds in its past, so a handset with a wrong
 * clock - which a 2011 phone with a dead battery certainly has - cannot talk to
 * Telegram until the offset is corrected. That correction is what
 * {@link #applyServerTime} exists for: the first server response carries a
 * trustworthy time, and every id after it is generated against that.
 *
 * The low two bits are meaningful:
 * <ul>
 *   <li>{@code % 4 == 0} - a message from the client that expects a response</li>
 *   <li>all client messages, including service messages, use {@code % 4 == 0}</li>
 * </ul>
 * Ids must also be strictly increasing within a session, which matters more
 * than it sounds on a device whose clock resolution may be coarse: two messages
 * sent in the same millisecond would otherwise collide.
 *
 * <h3>seq_no</h3>
 * Content-related messages (RPC calls) get odd numbers and advance the counter;
 * everything else gets an even number and does not. Getting this wrong makes
 * the server respond with bad_msg_notification 32/33 and is one of the harder
 * things to diagnose from a handset.
 */
public final class MsgIdGen
{
    /** Server time minus local time, in milliseconds. */
    private long timeOffsetMs;
    private boolean timeSynced;

    private long lastMsgId;
    private int contentMessages;

    /** Reset the per-session counters. The time offset survives - it is a property
     *  of the device's clock, not of the session. */
    public synchronized void resetSession()
    {
        lastMsgId = 0;
        contentMessages = 0;
    }

    /**
     * Adopt the server's clock.
     *
     * @param serverMsgId any msg_id received from the server
     */
    public synchronized void applyServerTime(long serverMsgId)
    {
        long serverSeconds = serverMsgId >>> 32;
        long localSeconds = System.currentTimeMillis() / 1000L;
        timeOffsetMs = (serverSeconds - localSeconds) * 1000L;
        timeSynced = true;
    }

    public synchronized boolean isTimeSynced()
    {
        return timeSynced;
    }

    public synchronized long timeOffsetSeconds()
    {
        return timeOffsetMs / 1000L;
    }

    /** Current server time in seconds, for TL fields that carry a date. */
    public synchronized int serverTimeSeconds()
    {
        return (int) ((System.currentTimeMillis() + timeOffsetMs) / 1000L);
    }

    /**
     * Next msg_id for a message that expects a response.
     */
    public synchronized long next()
    {
        long now = System.currentTimeMillis() + timeOffsetMs;
        long seconds = now / 1000L;
        long millis = now % 1000L;

        // The low 32 bits are nominally nanoseconds. Milliseconds are all a
        // handset offers, so scale up and leave the low two bits for the type.
        long fraction = (millis * 4294967L) & 0xFFFFFFFCL;
        long id = (seconds << 32) | fraction;

        if (id <= lastMsgId)
        {
            id = lastMsgId + 4;
        }
        lastMsgId = id;
        return id;
    }

    /**
     * Compatibility alias for service messages. MTProto uses the same
     * divisible-by-four msg_id rule; only seq_no encodes content-relatedness.
     */
    public synchronized long nextService()
    {
        return next();
    }

    /**
     * @param contentRelated true for RPC calls, false for acks and other
     *                       service messages
     */
    public synchronized int nextSeqNo(boolean contentRelated)
    {
        if (contentRelated)
        {
            int seq = contentMessages * 2 + 1;
            contentMessages++;
            return seq;
        }
        return contentMessages * 2;
    }

    /**
     * True if a received msg_id is plausible. The server's own ids should be
     * close to its clock; a wild value means we are misparsing the stream.
     */
    public synchronized boolean isPlausible(long msgId)
    {
        long seconds = msgId >>> 32;
        long nowSeconds = (System.currentTimeMillis() + timeOffsetMs) / 1000L;
        long delta = seconds - nowSeconds;
        if (delta < 0) { delta = -delta; }
        return delta < 24 * 3600;
    }

    public synchronized String describe()
    {
        return "msgId last=" + lastMsgId
               + " content=" + contentMessages
               + " offset=" + timeOffsetSeconds() + "s"
               + (timeSynced ? "" : " (clock NOT synced)");
    }
}
