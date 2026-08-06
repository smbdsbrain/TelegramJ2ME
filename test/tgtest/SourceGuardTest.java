package tgtest;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Invariants about the device sources that no runtime check can express.
 *
 * {@code Rng.forTesting} produces a fully predictable stream from a published
 * seed. {@code Handshake} refuses one at auth time, so a mistake cannot produce
 * a real key - but a refusal at auth time is discovered by a user whose login
 * fails. This finds the same mistake at {@code ./tools/test.sh} time instead,
 * and it also catches the case the runtime check cannot see: a production path
 * using a deterministic pool for nonces, padding or {@code random_id}, where
 * nothing throws and everything looks fine.
 *
 * The one legitimate use inside src/ is the on-device crypto self-test, which
 * needs reproducibility and never negotiates anything.
 *
 * The second rule is newer and has the same shape.
 * {@code Entropy.estimatedBitsPerGather()} is one handset's measured figure, and
 * it used to divide into 256 to give the seeding barrier a gather count. Three
 * handsets showed that a per-device yield cannot be a compiled-in constant, so
 * the barrier measures its own instead (issue #2). The number survives because
 * the on-device reports quote it, and the failure mode is that some later
 * seeding path starts multiplying by it again - which nothing at runtime can
 * notice, because the result would look entirely reasonable.
 */
public final class SourceGuardTest implements Test
{
    /**
     * The call forms, not the names.
     *
     * Production code is allowed to talk <em>about</em> either - Handshake's
     * refusal message names {@code forTesting}, and the comments that explain
     * these rules should not trip them. What is forbidden is invoking them, so
     * prose that means the method rather than the call writes the name without
     * its parentheses.
     */
    private static final String[][] RULES = {
        {
            "Rng.forTesting(",
            "src/tg/crypto/Rng.java",           // the declaration itself
            "src/tg/crypto/SelfTest.java"       // reproducible vectors, no traffic
        },
        {
            "estimatedBitsPerGather(",
            "src/tg/crypto/Entropy.java",       // the declaration itself
            "src/tg/app/CryptoMidlet.java"      // prints it in a report
        }
    };

    public String name() { return "src/source-guard"; }

    public void run() throws Exception
    {
        File root = new File("src");
        // A guard that quietly passes when it cannot find the sources is worse
        // than no guard: it reports green for a check it never ran.
        Assert.isTrue("src/ must be reachable from the working directory ("
                + new File(".").getAbsolutePath() + ")",
                root.isDirectory());

        List sources = new ArrayList();
        collect(root, sources);
        Assert.isTrue("found device sources, got " + sources.size(),
                sources.size() > 100);

        for (int r = 0; r < RULES.length; r++)
        {
            check(RULES[r], sources);
        }
    }

    private void check(String[] rule, List sources) throws IOException
    {
        String call = rule[0];

        List offenders = new ArrayList();
        for (int i = 0; i < sources.size(); i++)
        {
            File f = (File) sources.get(i);
            String path = relative(f);
            if (allowed(rule, path)) { continue; }
            if (read(f).indexOf(call) >= 0) { offenders.add(path); }
        }

        Assert.isTrue(call + " must stay out of production paths; found in "
                + offenders, offenders.isEmpty());

        // The allow-list is only meaningful while its entries still exist and
        // still contain what it excuses them for.
        for (int i = 1; i < rule.length; i++)
        {
            File f = new File(rule[i]);
            Assert.isTrue("allow-listed file is missing: " + rule[i], f.isFile());
            Assert.isTrue("allow-list entry no longer needs an exemption: "
                    + rule[i], read(f).indexOf(bareName(call)) >= 0);
        }
    }

    /** "Rng.forTesting(" -> "forTesting"; the name a file must still mention. */
    private static String bareName(String call)
    {
        String name = call.substring(0, call.length() - 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static boolean allowed(String[] rule, String path)
    {
        for (int i = 1; i < rule.length; i++)
        {
            if (rule[i].equals(path)) { return true; }
        }
        return false;
    }

    private static void collect(File dir, List out)
    {
        File[] entries = dir.listFiles();
        if (entries == null) { return; }
        for (int i = 0; i < entries.length; i++)
        {
            if (entries[i].isDirectory()) { collect(entries[i], out); }
            else if (entries[i].getName().endsWith(".java")) { out.add(entries[i]); }
        }
    }

    private static String relative(File f)
    {
        return f.getPath().replace('\\', '/');
    }

    private static String read(File f) throws IOException
    {
        FileInputStream in = new FileInputStream(f);
        try
        {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length)
            {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) { break; }
                off += n;
            }
            return new String(buf, 0, off, "ISO-8859-1");
        }
        finally { in.close(); }
    }
}
