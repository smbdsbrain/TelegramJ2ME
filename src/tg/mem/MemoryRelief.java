package tg.mem;

/**
 * What the client is willing to give back when it is about to run out.
 *
 * Implemented by the MIDlet, because it is the only object that knows every
 * cache. Registered with {@link MemoryPressure#setRelief}.
 *
 * <h3>Contract</h3>
 * {@link #release} may be called from any thread - typically a worker about to
 * decode a photo - so an implementation must only touch state that is safe to
 * change underneath the UI thread. Dropping a reference to a cache is; trimming
 * a list a live screen has already laid out is not. Anything in that second
 * category belongs in the failure path on the UI thread, not here.
 *
 * Levels are tried in order and must be ordered by how much they actually free,
 * measured rather than assumed. Each level should be individually harmless: the
 * cost of a shed that turns out to be unnecessary is a redraw.
 */
public interface MemoryRelief
{
    /** How many levels {@link #release} understands, counting from 1. */
    int levels();

    /**
     * Give back whatever this level covers. Must not throw; must not block on
     * the UI thread; must be safe to call twice.
     */
    void release(int level);
}
