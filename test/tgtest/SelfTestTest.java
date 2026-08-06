package tgtest;

import tg.crypto.SelfTest;

/**
 * Runs the on-device self-test here, on the desktop.
 *
 * {@link SelfTest} is CLDC-only code that also runs from tg.app.ProbeMidlet in
 * the emulator and on the handset. Executing it in all three places is what
 * turns "the crypto is correct" into "the crypto is correct after this
 * toolchain compiled, preverified and shrank it, on that VM".
 *
 * If this passes on the desktop and fails on the phone, the bug is in the
 * build, not in the algorithm - and that is a distinction worth being able to
 * make before debugging a failed auth_key handshake over GPRS.
 */
public final class SelfTestTest implements Test
{
    public String name()
    {
        return "crypto/device-selftest";
    }

    public void run() throws Exception
    {
        SelfTest.Result r = SelfTest.run();

        for (int i = 0; i < r.lines.length; i++)
        {
            if (r.lines[i].startsWith("FAIL") || r.lines[i].startsWith("--"))
            {
                System.out.println("      " + r.lines[i]);
            }
        }

        Assert.isTrue("self-test reported " + r.failed + " failure(s) out of "
                      + (r.passed + r.failed), r.ok());
        Assert.isTrue("self-test actually ran some checks", r.passed > 10);
    }
}
