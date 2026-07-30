package tg.api;

import java.io.IOException;

/** Persistent storage for the common and per-channel update cursors. */
public interface UpdateStateStore
{
    UpdateState load(long accountId, boolean testEnvironment) throws IOException;
    void save(UpdateState state) throws IOException;
    void clear() throws IOException;
}
