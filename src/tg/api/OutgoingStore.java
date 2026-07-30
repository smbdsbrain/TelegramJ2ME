package tg.api;

import java.io.IOException;

/** Durable storage for user-authored messages awaiting confirmed delivery. */
public interface OutgoingStore
{
    OutgoingMessage add(Peer peer, String text, long randomId, long createdAt)
            throws IOException;
    OutgoingMessage add(Peer peer, String text, int replyToMessageId,
                        long randomId, long createdAt) throws IOException;
    OutgoingMessage[] list() throws IOException;
    void save(OutgoingMessage message) throws IOException;
    void remove(int localId) throws IOException;
    void clear() throws IOException;
}
