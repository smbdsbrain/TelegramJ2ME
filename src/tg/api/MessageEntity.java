package tg.api;

import tg.tl.TlObj;
import tg.tl.TlWriter;

/** Compact actionable subset of Telegram MessageEntity. */
public final class MessageEntity
{
    public static final int MENTION = 1;
    public static final int URL = 2;
    public static final int EMAIL = 3;
    public static final int TEXT_URL = 4;
    public static final int MENTION_NAME = 5;
    public static final int PHONE = 6;

    public int type;
    public int offset;
    public int length;
    /** Only textUrl retains a separate string payload. */
    public String value;
    /** Only mentionName retains a user id. */
    public long userId;

    public String text(String message)
    {
        if (!validRange(message, offset, length)) { return ""; }
        return message.substring(offset, offset + length);
    }

    /**
     * Detect the small actionable subset that this client can safely send.
     * Offsets are Java String offsets, which are the UTF-16 code-unit offsets
     * required by Telegram. Detection is deliberately token based and bounded:
     * the server remains responsible for every richer entity type.
     */
    public static MessageEntity[] detect(String text, int limit)
    {
        if (text == null || text.length() == 0 || limit < 1)
        {
            return new MessageEntity[0];
        }
        MessageEntity[] out = new MessageEntity[limit];
        int count = 0;
        int at = 0;
        while (at < text.length() && count < limit)
        {
            while (at < text.length() && isWhitespace(text.charAt(at)))
            {
                at++;
            }
            int start = at;
            while (at < text.length() && !isWhitespace(text.charAt(at)))
            {
                at++;
            }
            int end = at;
            while (start < end && isLeadingPunctuation(text.charAt(start)))
            {
                start++;
            }
            while (end > start && isTrailingPunctuation(text.charAt(end - 1)))
            {
                end--;
            }
            if (start >= end) { continue; }

            int type = detectedType(text, start, end);
            if (type != 0)
            {
                MessageEntity entity = new MessageEntity();
                entity.type = type;
                entity.offset = start;
                entity.length = end - start;
                out[count++] = entity;
            }
        }
        if (count == out.length) { return out; }
        MessageEntity[] trimmed = new MessageEntity[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Write one supported outbound MessageEntity constructor. */
    public void writeTo(TlWriter writer)
    {
        int constructor;
        if (type == URL) { constructor = Api.MESSAGE_ENTITY_URL; }
        else if (type == EMAIL) { constructor = Api.MESSAGE_ENTITY_EMAIL; }
        else if (type == PHONE) { constructor = Api.MESSAGE_ENTITY_PHONE; }
        else if (type == MENTION) { constructor = Api.MESSAGE_ENTITY_MENTION; }
        else { throw new IllegalArgumentException("unsupported outbound entity"); }
        writer.writeInt(constructor);
        writer.writeInt(offset);
        writer.writeInt(length);
    }

    public static MessageEntity[] from(TlObj[] raw, String text, int limit)
    {
        if (raw == null || text == null || limit < 1)
        {
            return new MessageEntity[0];
        }
        MessageEntity[] out = new MessageEntity[Math.min(raw.length, limit)];
        int count = 0;
        for (int i = 0; i < raw.length && count < out.length; i++)
        {
            MessageEntity entity = one(raw[i]);
            if (entity == null
                    || !validRange(text, entity.offset, entity.length)
                    || overlaps(out, count, entity.offset, entity.length))
            {
                continue;
            }
            if (entity.value != null && entity.value.length() > 512) { continue; }
            out[count++] = entity;
        }
        if (count == out.length) { return out; }
        MessageEntity[] trimmed = new MessageEntity[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Prefer authoritative server entities, then conservatively detect text. */
    public static MessageEntity[] fromOrDetect(TlObj[] raw, String text,
                                                int limit)
    {
        MessageEntity[] parsed = from(raw, text, limit);
        return parsed.length == 0 ? detect(text, limit) : parsed;
    }

    private static MessageEntity one(TlObj obj)
    {
        if (obj == null) { return null; }
        MessageEntity entity = new MessageEntity();
        if (obj.id == Api.MESSAGE_ENTITY_MENTION)
        {
            entity.type = MENTION;
            entity.offset = obj.intAt(Api.F_MESSAGE_ENTITY_MENTION__OFFSET);
            entity.length = obj.intAt(Api.F_MESSAGE_ENTITY_MENTION__LENGTH);
        }
        else if (obj.id == Api.MESSAGE_ENTITY_URL)
        {
            entity.type = URL;
            entity.offset = obj.intAt(Api.F_MESSAGE_ENTITY_URL__OFFSET);
            entity.length = obj.intAt(Api.F_MESSAGE_ENTITY_URL__LENGTH);
        }
        else if (obj.id == Api.MESSAGE_ENTITY_EMAIL)
        {
            entity.type = EMAIL;
            entity.offset = obj.intAt(Api.F_MESSAGE_ENTITY_EMAIL__OFFSET);
            entity.length = obj.intAt(Api.F_MESSAGE_ENTITY_EMAIL__LENGTH);
        }
        else if (obj.id == Api.MESSAGE_ENTITY_TEXT_URL)
        {
            entity.type = TEXT_URL;
            entity.offset = obj.intAt(Api.F_MESSAGE_ENTITY_TEXT_URL__OFFSET);
            entity.length = obj.intAt(Api.F_MESSAGE_ENTITY_TEXT_URL__LENGTH);
            entity.value = obj.strOrEmpty(Api.F_MESSAGE_ENTITY_TEXT_URL__URL);
            if (entity.value.length() == 0) { return null; }
        }
        else if (obj.id == Api.MESSAGE_ENTITY_MENTION_NAME)
        {
            entity.type = MENTION_NAME;
            entity.offset = obj.intAt(Api.F_MESSAGE_ENTITY_MENTION_NAME__OFFSET);
            entity.length = obj.intAt(Api.F_MESSAGE_ENTITY_MENTION_NAME__LENGTH);
            entity.userId = obj.num(Api.F_MESSAGE_ENTITY_MENTION_NAME__USER_ID);
            if (entity.userId <= 0) { return null; }
        }
        else if (obj.id == Api.MESSAGE_ENTITY_PHONE)
        {
            entity.type = PHONE;
            entity.offset = obj.intAt(Api.F_MESSAGE_ENTITY_PHONE__OFFSET);
            entity.length = obj.intAt(Api.F_MESSAGE_ENTITY_PHONE__LENGTH);
        }
        else
        {
            return null;
        }
        return entity;
    }

    public static boolean validRange(String text, int offset, int length)
    {
        if (text == null || offset < 0 || length <= 0
                || offset > text.length() || length > text.length() - offset)
        {
            return false;
        }
        int end = offset + length;
        return !splitsSurrogate(text, offset) && !splitsSurrogate(text, end);
    }

    private static boolean splitsSurrogate(String text, int boundary)
    {
        if (boundary <= 0 || boundary >= text.length()) { return false; }
        char high = text.charAt(boundary - 1);
        char low = text.charAt(boundary);
        return high >= 0xd800 && high <= 0xdbff
                && low >= 0xdc00 && low <= 0xdfff;
    }

    private static boolean overlaps(MessageEntity[] values, int count,
                                    int offset, int length)
    {
        int end = offset + length;
        for (int i = 0; i < count; i++)
        {
            int otherEnd = values[i].offset + values[i].length;
            if (offset < otherEnd && values[i].offset < end) { return true; }
        }
        return false;
    }

    private static int detectedType(String text, int start, int end)
    {
        if (startsWithAsciiIgnoreCase(text, start, end, "https://")
                || startsWithAsciiIgnoreCase(text, start, end, "http://"))
        {
            int slash = text.indexOf('/', start) + 2;
            return slash < end ? URL : 0;
        }
        if (validEmail(text, start, end)) { return EMAIL; }
        if (validPhone(text, start, end)) { return PHONE; }
        if (validMention(text, start, end)) { return MENTION; }
        return 0;
    }

    private static boolean validEmail(String text, int start, int end)
    {
        int at = -1;
        int dot = -1;
        for (int i = start; i < end; i++)
        {
            char c = text.charAt(i);
            if (c == '@')
            {
                if (at >= 0) { return false; }
                at = i;
            }
            else if (c == '.' && at >= 0) { dot = i; }
            else if (!isEmailChar(c)) { return false; }
        }
        return at > start && dot > at + 1 && dot < end - 1;
    }

    private static boolean validPhone(String text, int start, int end)
    {
        if (text.charAt(start) != '+') { return false; }
        int digits = 0;
        for (int i = start + 1; i < end; i++)
        {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') { digits++; }
            else if (c != '-' && c != '(' && c != ')') { return false; }
        }
        return digits >= 7;
    }

    private static boolean validMention(String text, int start, int end)
    {
        if (text.charAt(start) != '@' || end - start < 3) { return false; }
        for (int i = start + 1; i < end; i++)
        {
            char c = text.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_')) { return false; }
        }
        return true;
    }

    private static boolean isEmailChar(char c)
    {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '.' || c == '_'
                || c == '%' || c == '+' || c == '-';
    }

    private static boolean startsWithAsciiIgnoreCase(String text, int start,
                                                       int end, String prefix)
    {
        if (end - start <= prefix.length()) { return false; }
        for (int i = 0; i < prefix.length(); i++)
        {
            char a = text.charAt(start + i);
            char b = prefix.charAt(i);
            if (a >= 'A' && a <= 'Z') { a = (char) (a + ('a' - 'A')); }
            if (a != b) { return false; }
        }
        return true;
    }

    private static boolean isLeadingPunctuation(char c)
    {
        return c == '(' || c == '[' || c == '{' || c == '<' || c == '"';
    }

    private static boolean isTrailingPunctuation(char c)
    {
        return c == '.' || c == ',' || c == ';' || c == ':' || c == '!'
                || c == '?' || c == ')' || c == ']' || c == '}' || c == '>'
                || c == '"';
    }

    /** CLDC 1.1 has no Character.isWhitespace(char). */
    private static boolean isWhitespace(char c)
    {
        return c == ' ' || (c >= '\t' && c <= '\r') || c == '\u0085'
                || c == '\u00a0' || c == '\u1680'
                || (c >= '\u2000' && c <= '\u200a')
                || c == '\u2028' || c == '\u2029' || c == '\u202f'
                || c == '\u205f' || c == '\u3000';
    }
}
