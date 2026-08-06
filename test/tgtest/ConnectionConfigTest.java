package tgtest;

import java.util.HashMap;
import java.util.Map;

import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
import tg.mt.AuthKeyStore;
import tg.mt.ConnectionConfig;

/** Sticky Auto order and persistence of reachability settings. */
public final class ConnectionConfigTest implements Test
{
    public String name() { return "mt/connection-config"; }

    /**
     * A synthetic link, never the build's own.
     *
     * tools/build.ps1 writes a real DevProxy from secrets/proxy.yaml when that
     * file exists and an empty one when it does not, so a case that read
     * DevProxy would assert different things on a developer machine and in CI.
     */
    private static final String LINK = "tg://proxy?server=proxy.example&port=8443"
            + "&secret=dd00112233445566778899aabbccddeeff";

    public void run()
    {
        persistenceAndStickyOrder();
        builtInProxyLeadsTheChain();
    }

    private static void persistenceAndStickyOrder()
    {
        MemoryStore store = new MemoryStore();
        ConnectionConfig saved = new ConnectionConfig();
        saved.mode = ConnectionConfig.AUTO;
        saved.lastSuccessful = ConnectionConfig.HTTP;
        saved.proxyHost = "proxy.example";
        saved.proxyPort = 8443;
        saved.proxySecret = "dd00112233445566778899aabbccddeeff";
        saved.save(store);

        ConnectionConfig loaded = new ConnectionConfig();
        loaded.load(store);
        Assert.equal("persisted mode", ConnectionConfig.AUTO, loaded.mode);
        Assert.equal("persisted sticky route", ConnectionConfig.HTTP,
                     loaded.lastSuccessful);
        Assert.equal("persisted proxy host", "proxy.example", loaded.proxyHost);
        Assert.equal("persisted proxy port", 8443, loaded.proxyPort);

        int[] order = loaded.attempts();
        int[] expected = {
            ConnectionConfig.HTTP,
            ConnectionConfig.DIRECT,
            ConnectionConfig.DIRECT_OBFUSCATED,
            ConnectionConfig.MTPROXY
        };
        Assert.equal("fallback route count", expected.length, order.length);
        for (int i = 0; i < expected.length; i++)
        {
            Assert.equal("fallback route " + i, expected[i], order[i]);
        }

        loaded.mode = ConnectionConfig.DIRECT_OBFUSCATED;
        order = loaded.attempts();
        Assert.equal("manual mode has one route", 1, order.length);
        Assert.equal("manual route", ConnectionConfig.DIRECT_OBFUSCATED, order[0]);
    }

    /**
     * A build's own proxy goes to the front of the Auto chain, not in place of
     * it.
     *
     * It used to set mode to MTPROXY, which made attempts() return that one
     * route, so a build carrying a proxy that happened to be down could not
     * connect at all - not even directly.
     */
    private static void builtInProxyLeadsTheChain()
    {
        ConnectionConfig fresh = new ConnectionConfig();
        fresh.seedProxy(LINK);
        Assert.equal("seeding leaves the mode on Auto",
                     ConnectionConfig.AUTO, fresh.mode);
        Assert.equal("seeded host", "proxy.example", fresh.proxyHost);
        Assert.equal("seeded port", 8443, fresh.proxyPort);
        Assert.isTrue("seeded proxy is usable", fresh.hasProxy());

        int[] order = fresh.attempts();
        int[] expected = {
            ConnectionConfig.MTPROXY,
            ConnectionConfig.DIRECT,
            ConnectionConfig.DIRECT_OBFUSCATED,
            ConnectionConfig.HTTP
        };
        Assert.equal("seeded route count", expected.length, order.length);
        for (int i = 0; i < expected.length; i++)
        {
            Assert.equal("seeded route " + i, expected[i], order[i]);
        }

        // A route that carried a help.getConfig on this handset outranks a
        // guess made at build time. Reached by installing a proxy-carrying
        // build over a profile written by one without a proxy.
        ConnectionConfig known = new ConnectionConfig();
        known.lastSuccessful = ConnectionConfig.DIRECT;
        known.seedProxy(LINK);
        Assert.equal("a known route is not overwritten",
                     ConnectionConfig.DIRECT, known.lastSuccessful);
        Assert.equal("known route still leads", ConnectionConfig.DIRECT,
                     known.attempts()[0]);

        // A build with a broken link must still start, and must not pretend a
        // proxy it could not parse is worth trying first.
        ConnectionConfig broken = new ConnectionConfig();
        broken.seedProxy("nonsense");
        Assert.equal("broken link leaves the mode on Auto",
                     ConnectionConfig.AUTO, broken.mode);
        Assert.equal("broken link seeds no route", 0, broken.lastSuccessful);
        Assert.isTrue("broken link seeds no proxy", !broken.hasProxy());
        Assert.equal("broken link keeps the plain chain",
                     ConnectionConfig.DIRECT, broken.attempts()[0]);

        // The seed has to survive Settings being saved before any route has
        // succeeded, otherwise opening Settings once on a fresh install would
        // silently demote the proxy to fourth.
        MemoryStore store = new MemoryStore();
        fresh.save(store);
        ConnectionConfig reloaded = new ConnectionConfig();
        reloaded.load(store);
        Assert.equal("seed survives a save before any connect",
                     ConnectionConfig.MTPROXY, reloaded.lastSuccessful);
        Assert.equal("reload stays on Auto", ConnectionConfig.AUTO, reloaded.mode);
    }

    private static final class MemoryStore implements AuthKeyStore
    {
        private final Map<String, String> values = new HashMap<String, String>();
        public AuthKeyLoad load(int dc, boolean test)
        {
            return AuthKeyLoad.notFound();
        }
        public void save(AuthKey key) { }
        public void clear(int dc, boolean test) { }
        public String loadString(String name) { return values.get(name); }
        public void saveString(String name, String value)
        {
            if (value == null) { values.remove(name); }
            else { values.put(name, value); }
        }
    }
}
