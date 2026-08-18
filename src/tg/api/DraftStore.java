package tg.api;

import java.io.IOException;

/**
 * Per-conversation local draft persistence, keyed by {@code (peer, thread)};
 * a thread of 0 is the peer's own transcript.
 */
public interface DraftStore extends AccountStore
{
    String load(Peer peer, int thread) throws IOException;
    void save(Peer peer, int thread, String text) throws IOException;
}
