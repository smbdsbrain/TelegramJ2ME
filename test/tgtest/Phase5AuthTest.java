package tgtest;

import java.io.IOException;

import tg.api.Api;
import tg.api.Requests;
import tg.mt.Srp;
import tg.tl.TlReader;

/** Deterministic Telegram SRP proof and Phase 5 request wire shapes. */
public final class Phase5AuthTest implements Test
{
    private static final String P =
        "c71caeb9c6b1c9048e6c522f70f13f73980d40238e3e21c14934d037563d930f" +
        "48198a0aa7c14058229493d22530f4dbfa336f6e0ac925139543aed44cce7c37" +
        "20fd51f69458705ac68cd4fe6b6b13abdc9746512969328454f18faf8c595f64" +
        "2477fe96bb2a941d5bcd1d4ac8cc49880708fa9b378e3c4f3a9060bee67cf9a4" +
        "a4a695811051907e162753b56b0f6b410dba74d8a84b2a14b3144e0ef1284754" +
        "fd17ed950d5965b4b9dd46582db1178d169c6bc465b0d6ff9ca3928fef5b9ae4" +
        "e418fc15e83ebea0f87fa9ff5eed70050ded2849f47bf959d956850ce929851f" +
        "0d8115f635b105ee2e4e15d04b2454bf6f4fadf034b10403119cd8e3b92fcc5b";

    private static final String B =
        "1f248632dc6175a509a5bd784ff01658f6db1fd4c313f4f618233e5613df8c64" +
        "5caaad2e05e8fa11a35285e8097910121427cf2d8a6d4b1b64a66a2c31656e5f" +
        "f3e24d258972d98679c5f957e47921e8fa36c91a2646a889044a7a1cd0d53151" +
        "ff2bf9d95a314a187ddc12ba937602d3c75e1c30f0189f56fb73ccfcb8ac8775" +
        "0e45091ab622950730c6147e1df122770daecc19a33c29846813ec05a546afdb2" +
        "e81871b539c4b1ae071880a9c7cf51d16ffb8d495e28afe315fabbba25b8f9eb" +
        "63d6a5775bdde1d93c092f4fd0ff5cf323c77123f8223dc3b7ada607af7ce1fe" +
        "04b90c7819ebfe4b710a42b078ce02ec0b913a11d3043180767a3d400add227";

    public String name() { return "phase5/authorization"; }

    public void run() throws Exception
    {
        Srp.Parameters params = new Srp.Parameters();
        params.salt1 = Assert.unhex("00112233445566778899aabbccddeeff");
        params.salt2 = Assert.unhex("102132435465768798a9bacbdcedfe0f");
        params.g = 3;
        params.p = Assert.unhex(P);
        params.b = Assert.unhex(B);
        params.id = 0x0102030405060708L;

        byte[] secret = new byte[256];
        for (int i = 0; i < secret.length; i++)
        {
            secret[i] = (byte) (i * 73 + 19);
        }
        Srp.Check check = Srp.compute("correct horse \ud83d\udd10", params,
                                      secret, null);
        Assert.equal("srp id", params.id, check.id);
        Assert.bytesEqual("SRP A",
            "9dc80c0bed00301224113172e6c0521a4de8b6645dd7c8b2a612488298152090" +
            "f3f7bc1d8e8c561e2c12670cd0d748b221908b2ed6d6198a16f3869bd01e2c0a" +
            "157269bf858fbdda3ab8e79038d0c08352ab433466e213b172231a3c4685ca60a" +
            "93162383764345e60b172cfcaf4ac5a5b48161d613757e0178aae050b9caf3040" +
            "bd9620b5aa5a15e45b8f28a776588a09318a4ec391f0cd3e06fa17a1d2c4c54" +
            "b5b5d1f9cb20fea6594ec50266dd470a421e634aaa9f05b39269b4cd7c3a1857" +
            "9f4d41db7f31a3c474be6b3a20f86335169008f7f57beed8f4766d1b0013f365" +
            "9b8bceb8b053ec9e1dd3e620c850eda912dc4b36abcce22525bb7ed984b3735",
            check.a);
        Assert.bytesEqual("SRP M1",
            "48ffd1439967b4352cc0b94f216fe3f3c74fa43f04e35a8b8158ca5e1c6624e9",
            check.m1);

        checkPasswordWire(check);
        codeFlowWire();
        noArgWire("getPassword", Api.ACCOUNT_GET_PASSWORD,
                  Requests.getPassword());
        noArgWire("resetAuthorizations", Api.AUTH_RESET_AUTHORIZATIONS,
                  Requests.resetAuthorizations());
        rejectsUnsafeB(params, secret);
    }

    private static void checkPasswordWire(Srp.Check check) throws Exception
    {
        TlReader r = new TlReader(Requests.checkPassword(check));
        r.expect(Api.AUTH_CHECK_PASSWORD, "auth.checkPassword");
        r.expect(Api.INPUT_CHECK_PASSWORD_S_R_P, "inputCheckPasswordSRP");
        Assert.equal("wire srp id", check.id, r.readLong());
        Assert.bytesEqual("wire A", check.a, r.readBytes());
        Assert.bytesEqual("wire M1", check.m1, r.readBytes());
        Assert.equal("wire exhausted", 0, r.remaining());
    }

    private static void codeFlowWire() throws Exception
    {
        TlReader resend = new TlReader(Requests.resendCode("+12345", "hash"));
        resend.expect(Api.AUTH_RESEND_CODE, "auth.resendCode");
        Assert.equal("resend flags", 0, resend.readInt());
        Assert.equal("resend phone", "+12345", resend.readString());
        Assert.equal("resend hash", "hash", resend.readString());
        Assert.equal("resend exhausted", 0, resend.remaining());

        TlReader cancel = new TlReader(Requests.cancelCode("+12345", "hash"));
        cancel.expect(Api.AUTH_CANCEL_CODE, "auth.cancelCode");
        Assert.equal("cancel phone", "+12345", cancel.readString());
        Assert.equal("cancel hash", "hash", cancel.readString());
        Assert.equal("cancel exhausted", 0, cancel.remaining());
    }

    private static void noArgWire(String what, int constructor, byte[] wire)
            throws Exception
    {
        TlReader r = new TlReader(wire);
        r.expect(constructor, what);
        Assert.equal(what + " exhausted", 0, r.remaining());
    }

    private static void rejectsUnsafeB(Srp.Parameters params, byte[] secret)
            throws Exception
    {
        byte[] good = params.b;
        params.b = new byte[] { 1 };
        try
        {
            Srp.compute("password", params, secret, null);
            Assert.fail("unsafe B accepted");
        }
        catch (IOException expected)
        {
            Assert.isTrue("unsafe B explained",
                    expected.getMessage().indexOf("unsafe SRP parameters") >= 0);
        }
        finally
        {
            params.b = good;
        }
    }
}
