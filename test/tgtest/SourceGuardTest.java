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
 */
public final class SourceGuardTest implements Test
{
    /**
     * The call form, not the name.
     *
     * Production code is allowed to talk <em>about</em> the deterministic
     * factory - Handshake's refusal message names it, and a comment that
     * explains the rule should not trip it. What is forbidden is invoking it.
     */
    private static final String FORBIDDEN_CALL = "Rng.forTesting(";

    /** Paths allowed to call it, repo-relative. */
    private static final String[] ALLOWED = {
        "src/tg/crypto/Rng.java",           // the declaration itself
        "src/tg/crypto/SelfTest.java"       // reproducible vectors, no traffic
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

        List offenders = new ArrayList();
        for (int i = 0; i < sources.size(); i++)
        {
            File f = (File) sources.get(i);
            String path = relative(f);
            if (allowed(path)) { continue; }
            if (read(f).indexOf(FORBIDDEN_CALL) >= 0) { offenders.add(path); }
        }

        Assert.isTrue(FORBIDDEN_CALL
                + " must stay out of production paths; found in " + offenders,
                offenders.isEmpty());

        // The allow-list is only meaningful while its entries still exist and
        // still contain what it excuses them for.
        for (int i = 0; i < ALLOWED.length; i++)
        {
            File f = new File(ALLOWED[i]);
            Assert.isTrue("allow-listed file is missing: " + ALLOWED[i], f.isFile());
            Assert.isTrue("allow-list entry no longer needs an exemption: "
                    + ALLOWED[i], read(f).indexOf("forTesting") >= 0);
        }
    }

    private static boolean allowed(String path)
    {
        for (int i = 0; i < ALLOWED.length; i++)
        {
            if (ALLOWED[i].equals(path)) { return true; }
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
