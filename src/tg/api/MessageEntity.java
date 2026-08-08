package tg.api;

import tg.tl.TlObj;

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
}
