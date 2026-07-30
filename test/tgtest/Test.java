package tgtest;

/**
 * One test case.
 *
 * Registered explicitly in {@link AllTests} rather than discovered: CLDC has no
 * reflection worth using, and keeping the desktop harness free of it means a
 * test can later be compiled into an on-device self-test MIDlet unchanged. That
 * matters because the handoff requires the same deterministic vector to be
 * verified on the desktop, in the emulator, and on the handset.
 */
public interface Test
{
    String name();

    void run() throws Exception;
}
