package tg.api;

import java.io.IOException;

/** Per-chat local draft persistence. */
public interface DraftStore extends AccountStore
{
    String load(Peer peer) throws IOException;
    void save(Peer peer, String text) throws IOException;
}
