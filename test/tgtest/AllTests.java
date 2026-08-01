package tgtest;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop test runner.
 *
 * Tests are registered explicitly - no reflection, no discovery - so the same
 * cases can later be linked into an on-device self-test MIDlet without change.
 * The handoff requires every crypto and TL vector to match on the desktop, in
 * the emulator and on the handset; a runner that depends on desktop reflection
 * would make the second and third impossible.
 *
 * Usage:
 *     java -cp build/desktop/classes;build/desktop/test-classes tgtest.AllTests
 *     java ... tgtest.AllTests bigint          # substring filter
 */
public final class AllTests
{
    private static Test[] registry()
    {
        return new Test[] {
            new ShaTest(),
            new Sha512Test(),
            new AesTest(),
            new AesCtrTest(),
            new AesIgeTest(),
            new RngTest(),
            new EntropyTest(),
            new BigIntTest(),
            new TlTest(),
            new InflateTest(),
            new TlParserTest(),
            new ProxySecretTest(),
            new ServerKeysTest(),
            new ConnectionConfigTest(),
            new ObfuscatedFramingTest(),
            new TransportPhase1Test(),
            new AsyncMtClientTest(),
            new Phase2PersistenceTest(),
            new UpdateSyncTest(),
            new ChatScrollStateTest(),
            new ChatScreenPhase4Test(),
            new Phase4ContentTest(),
            new Phase5AuthTest(),
            new Phase6Test(),
            new Phase7DesignTest(),
            new PhotoStreamTest(),
            new JpegDecoderTest(),
            new SelfTestTest(),
            new ReportTest(),
            new MemoryBudgetTest(),
            new HeapProbeTest()
        };
    }

    public static void main(String[] args)
    {
        String filter = args.length > 0 ? args[0] : null;

        List<Test> selected = new ArrayList<Test>();
        for (Test t : registry())
        {
            if (filter == null || t.name().contains(filter))
            {
                selected.add(t);
            }
        }

        if (selected.isEmpty())
        {
            System.out.println("no tests match filter '" + filter + "'");
            System.exit(2);
        }

        System.out.println("running " + selected.size() + " test(s)");
        System.out.println();

        int passed = 0;
        List<String> failures = new ArrayList<String>();

        for (Test t : selected)
        {
            long t0 = System.currentTimeMillis();
            System.out.print("  " + pad(t.name(), 44));
            try
            {
                t.run();
                long ms = System.currentTimeMillis() - t0;
                System.out.println("PASS  " + ms + " ms");
                passed++;
            }
            catch (Throwable e)
            {
                long ms = System.currentTimeMillis() - t0;
                System.out.println("FAIL  " + ms + " ms");
                failures.add(t.name() + "\n      " + describe(e));
                System.out.println("      " + describe(e));
            }
        }

        System.out.println();
        System.out.println("passed " + passed + "/" + selected.size());

        if (!failures.isEmpty())
        {
            System.out.println();
            System.out.println("FAILURES:");
            for (String f : failures)
            {
                System.out.println("  " + f);
            }
            System.exit(1);
        }
    }

    private static String describe(Throwable e)
    {
        String msg = e.getMessage();
        String head = e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
        // The first frame inside our own code is what the reader wants; the
        // runner frames above it are noise.
        for (StackTraceElement el : e.getStackTrace())
        {
            if (el.getClassName().startsWith("tg"))
            {
                return head + "\n      at " + el;
            }
        }
        return head;
    }

    private static String pad(String s, int width)
    {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < width) { sb.append('.'); }
        return sb.toString();
    }
}
