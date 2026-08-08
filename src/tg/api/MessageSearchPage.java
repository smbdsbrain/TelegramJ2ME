package tg.api;

import tg.tl.TlObj;

/** One bounded page returned by {@code messages.search}. */
public final class MessageSearchPage
{
    public Message[] messages = new Message[0];
    public int totalCount;
    public boolean totalExact = true;
    public int nextOffsetId;
    public boolean exhausted;

    /** Flatten a messages.Messages reply after its peer vectors are absorbed. */
    public static MessageSearchPage from(TlObj res, PeerCache peers, int limit,
                                         int requestedOffset)
    {
        if (res == null || limit < 1) { return null; }
        TlObj[] raw;
        TlObj[] users;
        TlObj[] chats;
        int total;
        boolean exact = true;
        if (res.id == Api.MESSAGES_MESSAGES)
        {
            raw = res.vec(Api.F_MESSAGES_MESSAGES__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_MESSAGES__CHATS);
            users = res.vec(Api.F_MESSAGES_MESSAGES__USERS);
            total = raw.length;
        }
        else if (res.id == Api.MESSAGES_MESSAGES_SLICE)
        {
            raw = res.vec(Api.F_MESSAGES_MESSAGES_SLICE__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_MESSAGES_SLICE__CHATS);
            users = res.vec(Api.F_MESSAGES_MESSAGES_SLICE__USERS);
            total = res.intAt(Api.F_MESSAGES_MESSAGES_SLICE__COUNT);
            exact = res.num(Api.F_MESSAGES_MESSAGES_SLICE__INEXACT) == 0;
        }
        else if (res.id == Api.MESSAGES_CHANNEL_MESSAGES)
        {
            raw = res.vec(Api.F_MESSAGES_CHANNEL_MESSAGES__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_CHANNEL_MESSAGES__CHATS);
            users = res.vec(Api.F_MESSAGES_CHANNEL_MESSAGES__USERS);
            total = res.intAt(Api.F_MESSAGES_CHANNEL_MESSAGES__COUNT);
            exact = res.num(Api.F_MESSAGES_CHANNEL_MESSAGES__INEXACT) == 0;
        }
        else
        {
            return null;
        }

        peers.absorb(users, chats);
        int capacity = Math.min(raw.length, limit);
        Message[] out = new Message[capacity];
        int count = 0;
        int next = 0;
        for (int i = 0; i < raw.length && count < capacity; i++)
        {
            Message message = Message.from(raw[i], peers);
            if (message == null || message.id <= 0) { continue; }
            boolean duplicate = false;
            for (int j = 0; j < count; j++)
            {
                if (out[j].id == message.id) { duplicate = true; break; }
            }
            if (!duplicate)
            {
                out[count++] = message;
                next = message.id;
            }
        }
        if (count != out.length)
        {
            Message[] trimmed = new Message[count];
            System.arraycopy(out, 0, trimmed, 0, count);
            out = trimmed;
        }
        MessageSearchPage page = new MessageSearchPage();
        page.messages = out;
        page.totalCount = Math.max(total, count);
        page.totalExact = exact;
        page.nextOffsetId = next;
        page.exhausted = count == 0 || raw.length < limit || next <= 0
                || next == requestedOffset;
        return page;
    }
}
