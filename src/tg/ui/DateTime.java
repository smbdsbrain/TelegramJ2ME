package tg.ui;

import java.util.Calendar;
import java.util.Date;

/** Small local-time formatter; avoids heavyweight locale/date-format classes. */
public final class DateTime
{
    private DateTime() { }

    public static String time(int unixSeconds)
    {
        if (unixSeconds <= 0) { return ""; }
        Calendar c = calendar(unixSeconds);
        return two(c.get(Calendar.HOUR_OF_DAY)) + ":"
                + two(c.get(Calendar.MINUTE));
    }

    public static String date(int unixSeconds)
    {
        if (unixSeconds <= 0) { return ""; }
        Calendar c = calendar(unixSeconds);
        return c.get(Calendar.YEAR) + "-"
                + two(c.get(Calendar.MONTH) + 1) + "-"
                + two(c.get(Calendar.DAY_OF_MONTH));
    }

    /** Compact dialog-list timestamp: local time today, otherwise YYYY-MM-DD. */
    public static String compact(int unixSeconds)
    {
        if (unixSeconds <= 0) { return ""; }
        int now = (int) (System.currentTimeMillis() / 1000L);
        return dayKey(unixSeconds) == dayKey(now)
                ? time(unixSeconds) : date(unixSeconds);
    }

    /** Stable day identity in the handset's local timezone. */
    public static int dayKey(int unixSeconds)
    {
        if (unixSeconds <= 0) { return 0; }
        Calendar c = calendar(unixSeconds);
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    private static Calendar calendar(int unixSeconds)
    {
        Calendar c = Calendar.getInstance();
        c.setTime(new Date((long) unixSeconds * 1000L));
        return c;
    }

    private static String two(int value)
    {
        return value < 10 ? ("0" + value) : String.valueOf(value);
    }
}
