package tg.ui;

/**
 * Scroll intent for a chat transcript, kept independent of MIDP Canvas so the
 * tricky replace/append behaviour can be tested on the desktop.
 */
public final class ChatScrollState
{
    private int top;
    private boolean followEnd = true;

    public int top()
    {
        return top;
    }

    public boolean followsEnd()
    {
        return followEnd;
    }

    /** A different chat always opens at its newest message. */
    public void reset(int maxTop)
    {
        followEnd = true;
        top = clamp(maxTop, maxTop);
    }

    /**
     * Preserve the first visible logical message while a transcript is
     * rebuilt. If the user was already at the end, keep following new content.
     */
    public void replace(int[] oldIds, int[] oldOffsets,
                        int[] newIds, int[] newOffsets, int maxTop)
    {
        maxTop = nonNegative(maxTop);
        if (followEnd)
        {
            top = maxTop;
            return;
        }

        int oldTop = top;
        if (oldIds != null && oldOffsets != null
                && oldTop >= 0 && oldTop < oldIds.length
                && oldTop < oldOffsets.length && oldIds[oldTop] != 0)
        {
            int id = oldIds[oldTop];
            int offset = oldOffsets[oldTop];
            int at = find(newIds, newOffsets, id, offset);
            // If the bounded history evicted the anchor, the nearest surviving
            // content is now at the start; do not silently jump further down.
            top = at >= 0 ? clamp(at, maxTop) : 0;
            return;
        }
        top = clamp(oldTop, maxTop);
    }

    /** Content appended below the viewport must not steal scroll focus. */
    public void appended(int maxTop)
    {
        maxTop = nonNegative(maxTop);
        top = followEnd ? maxTop : clamp(top, maxTop);
    }

    /** Re-clamp after status/layout height changes without changing intent. */
    public void resized(int maxTop)
    {
        appended(maxTop);
    }

    /** Only explicit user navigation changes whether the end is followed. */
    public void userScroll(int delta, int maxTop)
    {
        maxTop = nonNegative(maxTop);
        top = clamp(top + delta, maxTop);
        followEnd = top == maxTop;
    }

    private static int find(int[] ids, int[] offsets, int id, int offset)
    {
        if (ids == null || offsets == null) { return -1; }
        int count = Math.min(ids.length, offsets.length);
        for (int i = 0; i < count; i++)
        {
            if (ids[i] == id && offsets[i] == offset) { return i; }
        }
        return -1;
    }

    private static int clamp(int value, int max)
    {
        if (value < 0) { return 0; }
        if (value > max) { return max; }
        return value;
    }

    private static int nonNegative(int value)
    {
        return value < 0 ? 0 : value;
    }
}
