package tgtest;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import tg.api.Peer;
import tg.api.Requests;
import tg.io.Hex;
import tg.tl.Utf8;
import tg.api.Telegram;
import tg.crypto.Rng;
import tg.mt.Dc;
import tg.mt.RpcError;

/**
 * Sign in and persist the session.
 *
 * On the test data centres this runs unattended: Telegram reserves numbers of
 * the form 99966XYYYY, where X is the DC id, and always accepts the DC id
 * repeated five times as the confirmation code. So the whole login flow -
 * including the sign-up branch, since a test number has no account until one is
 * created - can be exercised without a phone or a human.
 *
 *     ./tools/live.ps1 login                       test account, automatic
 *     ./tools/live.ps1 login <international-number> prompts for the code
 *
 * The session is written to secrets/live-session.properties so the dialogs and
 * send scenarios can reuse it.
 */
public final class LiveLoginTest
{
    public static void main(String[] args) throws Exception
    {
        FileAuthKeyStore store = new FileAuthKeyStore();
        SeTransport transport = new SeTransport();
        transport.setReadTimeoutMs(60000);

        Telegram tg = new Telegram(transport, new Rng(), store);
        tg.setSignUpName("J2ME", "Client");

        boolean automatic = args.length == 0 && Dc.isTest();
        String phone = automatic ? testPhoneNumber() : (args.length > 0 ? args[0] : null);

        if (phone == null)
        {
            System.out.println("A phone number is required on production.");
            System.out.println("    ./tools/live.ps1 login <international-number>");
            System.exit(2);
        }

        System.out.println("environment : " + (Dc.isTest() ? "TEST" : "PRODUCTION"));
        System.out.println("phone       : " + phone);
        if (automatic)
        {
            System.out.println("mode        : reserved test number, code is predictable");
        }
        System.out.println();

        try
        {
            tg.connect();
            System.out.println("connected to dc" + tg.dcId());

            Peer existing = tg.checkAuthorization();
            if (existing != null)
            {
                System.out.println("already signed in as " + existing.title
                                   + " (id " + existing.id + ")");
                System.out.println("delete " + store.file() + " to start over");
                return;
            }

            System.out.println("requesting a login code...");
            String hash = tg.sendCode(phone);
            System.out.println("phone_code_hash received, now on dc" + tg.dcId());

            System.out.println("delivery    : " + tg.lastSentCodeTypeName()
                               + " (0x" + Integer.toHexString(tg.lastSentCodeType()) + ")");
            System.out.println("code length : " + tg.lastSentCodeLength());
            System.out.println("hash        : \"" + hash + "\"");
            System.out.println("hash bytes  : " + Hex.encode(Utf8.encode(hash)));
            System.out.println("phone bytes : " + Hex.encode(Utf8.encode(phone)));
            System.out.println("signIn wire : "
                    + Hex.encode(Requests.signIn(phone, hash, "22222")));

            Peer me;
            try
            {
                if (args.length > 1)
                {
                    System.out.println("signing in with the supplied code...");
                    me = tg.signIn(phone, hash, args[1]);
                }
                else if (automatic)
                {
                    // Telegram documents the test code as the DC id repeated
                    // five times. Keep a six-repeat compatibility attempt.
                    me = signInTryingCodes(tg, phone, hash, new String[] {
                        repeat(Dc.BOOTSTRAP_DC_ID, 5),
                        repeat(Dc.BOOTSTRAP_DC_ID, 6)
                    });
                }
                else
                {
                    String code = prompt("enter the code Telegram sent: ");
                    System.out.println("signing in...");
                    me = tg.signIn(phone, hash, code);
                }
            }
            catch (RpcError e)
            {
                if (!e.isPasswordNeeded()) { throw e; }
                System.out.println("two-factor authentication is enabled");
                System.out.println("hint        : " + tg.passwordHint());
                String password = promptPassword();
                System.out.println("computing the SRP proof...");
                me = tg.checkPassword(password, null);
            }
            System.out.println();
            System.out.println("=== SIGNED IN ===");
            System.out.println("user        : " + (me == null ? "?" : me.title));
            System.out.println("id          : " + (me == null ? 0 : me.id));
            System.out.println("username    : " + (me == null ? null : me.username));
            System.out.println("data centre : dc" + tg.dcId());
            System.out.println("session     : " + store.file().getAbsolutePath());
        }
        catch (RpcError e)
        {
            System.out.println();
            System.out.println("=== RPC ERROR ===");
            System.out.println(e.getMessage());
            if (e.isPasswordNeeded())
            {
                System.out.println();
                System.out.println("The account requested 2FA outside the expected login step.");
            }
            else if (e.isFloodWait())
            {
                System.out.println();
                System.out.println("Wait " + e.floodWaitSeconds() + " seconds, or pick a");
                System.out.println("different YYYY suffix on the test number.");
            }
            LiveHandshakeTest.dumpLog();
            System.exit(1);
        }
        catch (Throwable t)
        {
            System.out.println();
            System.out.println("=== FAILED ===");
            System.out.println(t.getClass().getName() + ": " + t.getMessage());
            LiveHandshakeTest.dumpLog();
            t.printStackTrace(System.out);
            System.exit(1);
        }
        finally
        {
            tg.close();
        }
    }

    /**
     * 99966XYYYY: X is the data centre, YYYY is arbitrary. The suffix varies
     * per run so hitting the per-number flood limit does not block the next
     * test.
     */
    private static String testPhoneNumber()
    {
        int dc = Dc.BOOTSTRAP_DC_ID;
        int suffix = (int) (System.currentTimeMillis() % 10000);
        StringBuffer sb = new StringBuffer("99966");
        sb.append(dc);
        String digits = String.valueOf(suffix);
        for (int i = digits.length(); i < 4; i++) { sb.append('0'); }
        sb.append(digits);
        return sb.toString();
    }

    private static String repeat(int digit, int times)
    {
        StringBuffer sb = new StringBuffer(times);
        for (int i = 0; i < times; i++)
        {
            sb.append((char) ('0' + digit));
        }
        return sb.toString();
    }

    private static Peer signInTryingCodes(Telegram tg, String phone, String hash,
                                          String[] codes) throws Exception
    {
        RpcError last = null;
        for (int i = 0; i < codes.length; i++)
        {
            System.out.println("signing in with code \"" + codes[i] + "\"...");
            try
            {
                return tg.signIn(phone, hash, codes[i]);
            }
            catch (RpcError e)
            {
                if (!"PHONE_CODE_INVALID".equals(e.type()))
                {
                    throw e;
                }
                System.out.println("  rejected: " + e.getMessage());
                last = e;
            }
        }
        throw last;
    }

    private static String promptPassword() throws Exception
    {
        java.io.Console console = System.console();
        if (console != null)
        {
            char[] chars = console.readPassword("enter the 2FA password: ");
            if (chars == null) { return ""; }
            String value = new String(chars);
            for (int i = 0; i < chars.length; i++) { chars[i] = 0; }
            return value;
        }
        System.out.println("warning: this console cannot mask input");
        return prompt("enter the 2FA password: ");
    }

    private static String prompt(String text) throws Exception
    {
        System.out.print(text);
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line = in.readLine();
        return line == null ? "" : line.trim();
    }
}
