package tgtest;

import java.util.HashMap;
import java.util.Map;

import tg.mt.AuthKey;
import tg.mt.AuthKeyStore;
import tg.mt.ConnectionConfig;

/** Sticky Auto order and persistence of reachability settings. */
public final class ConnectionConfigTest implements Test
{
    public String name() { return "mt/connection-config"; }

    public void run()
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

    private static final class MemoryStore implements AuthKeyStore
    {
        private final Map<String, String> values = new HashMap<String, String>();
        public AuthKey load(int dc, boolean test) { return null; }
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
