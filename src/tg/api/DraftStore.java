package tg.api;

import java.io.IOException;

/** Per-chat local draft persistence. */
public interface DraftStore
{
    String load(Peer peer) throws IOException;
    void save(Peer peer, String text) throws IOException;
    void clear() throws IOException;
}
