package tgtest;

import java.util.Base64;

import tg.mt.ProxySecret;

/** MTProxy secret and deep-link parsing. */
public final class ProxySecretTest implements Test
{
    public String name() { return "mt/proxy-secret"; }

    public void run()
    {
        byte[] key = Assert.unhex("00112233445566778899aabbccddeeff");
        ProxySecret normal = ProxySecret.parse(Assert.hex(key));
        Assert.isFalse("normal not padded", normal.padded());
        Assert.bytesEqual("normal key", key, normal.key());

        ProxySecret b64 = ProxySecret.parse(Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key));
        Assert.bytesEqual("base64url key", key, b64.key());

        ProxySecret dd = ProxySecret.parse("dd" + Assert.hex(key));
        Assert.isTrue("dd padded", dd.padded());
        Assert.isFalse("dd not fake tls", dd.fakeTls());

        String domainHex = Assert.hex(Assert.ascii("example.com"));
        ProxySecret ee = ProxySecret.parse("ee" + Assert.hex(key) + domainHex);
        Assert.isTrue("ee fake tls", ee.fakeTls());
        Assert.equal("ee domain", "example.com", ee.domain());

        ProxySecret.ParsedLink link = ProxySecret.parseLink(
                "tg://proxy?server=proxy.example&port=443&secret="
                + "dd" + Assert.hex(key));
        Assert.equal("link host", "proxy.example", link.host);
        Assert.equal("link port", 443, link.port);
        Assert.isTrue("link secret", link.secret.padded());

        rejects("short", "0011");
        rejects("dd without key", "dd0011");
        rejects("ee without domain", "ee" + Assert.hex(key));
        try
        {
            ProxySecret.parseLink("tg://proxy?server=x&port=0&secret=" + Assert.hex(key));
            Assert.fail("bad port accepted");
        }
        catch (IllegalArgumentException expected) { }
    }

    private static void rejects(String label, String secret)
    {
        try
        {
            ProxySecret.parse(secret);
            Assert.fail(label + " accepted");
        }
        catch (IllegalArgumentException expected) { }
    }
}
