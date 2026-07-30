package tg.api;

import java.io.IOException;

/** In-memory update state, primarily for desktop tests. */
public final class MemoryUpdateStateStore implements UpdateStateStore
{
    private UpdateState state;

    public synchronized UpdateState load(long accountId, boolean testEnvironment)
    {
        if (state == null || state.accountId != accountId
                || state.testEnvironment != testEnvironment)
        {
            return null;
        }
        return state.copy();
    }

    public synchronized void save(UpdateState value)
    {
        state = value == null ? null : value.copy();
    }

    public synchronized void clear()
    {
        state = null;
    }
}
