package tgtest;

import java.io.IOException;

import tg.crypto.AuthKeySeeding;
import tg.crypto.Rng;
import tg.mt.AuthKey;
import tg.mt.Dc;
import tg.mt.MtClient;
import tg.mt.MtLink;

/**
 * The seeding barrier as seen from the only production path that generates a
 * permanent key.
 *
 * No network and no scripted server: every assertion here is about what happens
 * <em>before</em> the first byte leaves the client, which is exactly where a
 * seeding barrier has to live. A link that refuses to answer is enough - the
 * handshake gets as far as it can and then fails, and what matters is the state
 * it passed through on the way.
 */
public final class HandshakeBarrierTest implements Test
{
    public String name() { return "mt/handshake-barrier"; }

    public void run() throws Exception
    {
        deterministicPoolNeverReachesTheWire();
        newKeyCrossesTheBarrierBeforeSending();
        resumingAStoredKeyPaysNothing();
    }

    /**
     * {@code MtClient.authenticate()} is the only production construction of a
     * Handshake, and it is reached from all three new-key paths - first launch,
     * a DC migration, and a media/auxiliary DC. Covering the shared boundary is
     * what covers all three; there is no per-path branch to miss.
     *
     * The barrier has to be crossed before the first packet, because new_nonce
     * derives tmp_aes_key and the server salt as well as the DH secret. A deaf
     * link lets the assertion be about ordering rather than about a server.
     */
    private void newKeyCrossesTheBarrierBeforeSending() throws Exception
    {
        DeafLink link = new DeafLink();
        MtClient client = new MtClient(link, new Rng());
        client.connect(Dc.BOOTSTRAP_DC_ID, "fake", 443, 1);

        int before = AuthKeySeeding.completedBarriers();
        try
        {
            client.authenticate();
            Assert.fail("a deaf link produced an auth key");
        }
        catch (IOException expected) { }

        Assert.equal("exactly one barrier for one new key",
                before + 1, AuthKeySeeding.completedBarriers());
        Assert.equal("the barrier ran before req_pq_multi", 1, link.sends);
        client.close();
    }

    /**
     * The other half of the requirement, and the one a user feels: reusing a
     * stored key must not pay for seeding. {@code resume()} shares no code with
     * {@code authenticate()} beyond {@code adopt()}, so this pins that.
     */
    private void resumingAStoredKeyPaysNothing() throws Exception
    {
        DeafLink link = new DeafLink();
        MtClient client = new MtClient(link, new Rng());
        client.setListener(new MtClient.Listener()
        {
            public void onUpdate(byte[] body) { }
            public void onConnectionLost(IOException error) { }
        });
        client.connect(Dc.BOOTSTRAP_DC_ID, "fake", 443, 1);

        byte[] raw = new byte[256];
        for (int i = 0; i < raw.length; i++) { raw[i] = (byte) (i + 1); }

        int before = AuthKeySeeding.completedBarriers();
        client.resume(new AuthKey(raw, Dc.BOOTSTRAP_DC_ID, Dc.isTest()), 0);
        Assert.equal("resuming a stored key runs no barrier",
                before, AuthKeySeeding.completedBarriers());
        Assert.equal("resuming a stored key sends nothing by itself", 0, link.sends);
        client.close();
    }

    /**
     * {@code Rng.forTesting} exists so a crypto failure can be replayed. A pool
     * with a published seed must never negotiate a key with Telegram, and the
     * check has to fire before the nonce is sent, not after the server has
     * already seen it.
     */
    private void deterministicPoolNeverReachesTheWire() throws Exception
    {
        DeafLink link = new DeafLink();
        MtClient client = new MtClient(link,
                Rng.forTesting(Assert.ascii("deterministic-pool")));
        client.connect(Dc.BOOTSTRAP_DC_ID, "fake", 443, 1);
        try
        {
            client.authenticate();
            Assert.fail("authenticate() accepted a deterministic RNG");
        }
        catch (IOException expected)
        {
            Assert.isTrue("the refusal names its cause, got: " + expected.getMessage(),
                    expected.getMessage().indexOf("deterministic") >= 0);
        }
        Assert.equal("nothing was sent with a deterministic pool", 0, link.sends);
        client.close();
    }

    /** Accepts a connection, records writes, and never answers. */
    static final class DeafLink implements MtLink
    {
        int sends;
        boolean connected;

        public synchronized void connect(String host, int port, int timeoutMs)
        {
            connected = true;
        }

        public synchronized void send(byte[] payload, int off, int len)
        {
            sends++;
        }

        public synchronized int receive() throws IOException
        {
            throw new IOException("deaf link: no server");
        }

        public byte[] buffer() { return new byte[0]; }
        public synchronized boolean isConnected() { return connected; }
        public synchronized void close() { connected = false; }
        public long bytesRead() { return 0; }
        public synchronized long bytesWritten() { return sends; }
        public String description() { return "deaf"; }
        public boolean isRequestResponse() { return false; }
        public int hiddenPaddingBlocks() { return 0; }
    }
}
