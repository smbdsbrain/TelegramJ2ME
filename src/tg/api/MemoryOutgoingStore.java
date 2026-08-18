package tg.api;

import java.io.IOException;
import java.util.Vector;

/** Deterministic in-memory OutgoingStore used by desktop tests. */
public final class MemoryOutgoingStore implements OutgoingStore
{
    private static final int MAX_ITEMS = 64;
    private final Vector items = new Vector();
    private int nextId = 1;

    public synchronized OutgoingMessage add(Peer peer, String text,
            long randomId, long createdAt) throws IOException
    {
        return add(peer, text, 0, 0, randomId, createdAt);
    }

    public synchronized OutgoingMessage add(Peer peer, String text,
            int replyToMessageId, int threadRootId, long randomId,
            long createdAt) throws IOException
    {
        if (items.size() >= MAX_ITEMS) { throw new IOException("outbox is full"); }
        OutgoingMessage message = new OutgoingMessage();
        message.localId = nextId++;
        message.peerKind = peer.kind;
        message.peerId = peer.id;
        message.accessHash = peer.accessHash;
        message.peerTitle = peer.title == null ? "" : peer.title;
        message.text = text;
        message.replyToMessageId = replyToMessageId;
        message.threadRootId = threadRootId;
        message.randomId = randomId;
        message.createdAt = createdAt;
        items.addElement(copy(message));
        return copy(message);
    }

    public synchronized OutgoingMessage[] list() throws IOException
    {
        OutgoingMessage[] out = new OutgoingMessage[items.size()];
        for (int i = 0; i < out.length; i++)
        {
            out[i] = copy((OutgoingMessage) items.elementAt(i));
        }
        return out;
    }

    public synchronized void save(OutgoingMessage message) throws IOException
    {
        for (int i = 0; i < items.size(); i++)
        {
            if (((OutgoingMessage) items.elementAt(i)).localId == message.localId)
            {
                items.setElementAt(copy(message), i);
                return;
            }
        }
        throw new IOException("outbox record " + message.localId + " not found");
    }

    public synchronized void remove(int localId) throws IOException
    {
        for (int i = 0; i < items.size(); i++)
        {
            if (((OutgoingMessage) items.elementAt(i)).localId == localId)
            {
                items.removeElementAt(i);
                return;
            }
        }
    }

    public synchronized void clear() { items.removeAllElements(); }

    private static OutgoingMessage copy(OutgoingMessage value) throws IOException
    {
        return OutgoingMessage.decode(value.localId, OutgoingMessage.encode(value));
    }
}
