package tg.api;

import java.io.IOException;

import tg.crypto.Pbkdf2;
import tg.crypto.Rng;
import tg.diag.Diag;
import tg.io.DelayedWake;
import tg.io.Transport;
import tg.mt.AuthKey;
import tg.mt.AuthKeyLoad;
import tg.mt.AuthKeyStore;
import tg.mt.Dc;
import tg.mt.DcEndpoint;
import tg.mt.MsgIdGen;
import tg.mt.MtClient;
import tg.mt.MtLinkFactory;
import tg.mt.FixedLinkFactory;
import tg.mt.ConnectionConfig;
import tg.mt.ConnectionDiagnostics;
import tg.mt.LinkSpec;
import tg.mt.RpcError;
import tg.mt.Srp;
import tg.tl.TlObj;
import tg.tl.TlParser;
import tg.tl.TlReader;

/**
 * The Telegram client the UI talks to.
 *
 * Everything above this line is protocol; everything below is a messenger.
 * Methods here block on network round trips and must not be called from the
 * lcdui thread.
 *
 * <h3>Data centre migration</h3>
 * An account lives on one data centre, and a client cannot know which until it
 * asks - {@code auth.sendCode} answers with {@code PHONE_MIGRATE_X} when it
 * guessed wrong. Migrating means reconnecting, generating a *new* auth_key for
 * that DC, and retrying. That is handled transparently in {@link #sendCode}
 * because it is unavoidable on first login, not an edge case.
 */
public final class Telegram
{
    /** Bounded so a server that keeps redirecting cannot loop forever. */
    private static final int MAX_MIGRATIONS = 5;
    private static final int[] RECONNECT_DELAYS = {
        1000, 2000, 4000, 8000, 16000, 30000
    };

    public static final int IDLE = 0;
    public static final int CONNECTING = 1;
    public static final int ONLINE = 2;
    public static final int RETRYING = 3;
    public static final int OFFLINE = 4;
    public static final int PAUSED = 5;

    public interface ConnectionListener
    {
        void onConnectionState(int state, int retrySeconds, String detail);
    }

    public interface OutgoingListener
    {
        void onOutboxChanged();
    }

    public interface UpdateListener
    {
        void onUpdates(UpdateBatch batch);
    }

    private final MtLinkFactory links;
    private final Rng rng;
    private final AuthKeyStore store;
    private final ConnectionConfig connectionConfig;
    private final ConnectionDiagnostics connectionDiagnostics;
    private final PeerCache peers = new PeerCache();
    private final DcDirectory dcDirectory = new DcDirectory();
    private final UpdateSync updates;
    private final AccountWipe wipe;
    private final Object outboxLock = new Object();

    /**
     * Incremented when the account leaves this handset.
     *
     * Work already in flight belongs to the account that started it. The outbox
     * drain is the one that writes: it runs on its own thread, and without this
     * it would file a "sending" row into the store the wipe had just emptied.
     */
    private volatile int accountEpoch;
    private volatile WipeReport lastWipe;

    private MtClient client;
    private OutgoingStore outgoingStore;
    private OutgoingListener outgoingListener;
    private boolean outboxDraining;

    /**
     * The one delayed retry.
     *
     * A FLOOD_WAIT is per message, so a queue of them used to start a sleeping
     * thread each, all waking to run the same drain. This keeps the earliest
     * deadline of all of them and one waiter to serve it.
     */
    private final DelayedWake outboxRetry = new DelayedWake("outbox",
            new DelayedWake.Wake()
    {
        public void onWake() { startOutboxDrain(); }
    });
    private final Object connectLock = new Object();
    private final Object lifecycleLock = new Object();
    private ConnectionListener connectionListener;
    private int connectionState = IDLE;
    private String connectionDetail = "";
    private int reconnectToken;
    private boolean reconnectRunning;
    private boolean foreground = true;
    private boolean everConnected;

    /** True while the session is parked so a media transfer can use the socket. */
    private volatile boolean mediaParked;
    private String[] cachedAvailableReactions;

    /** Outlives individual connections; holds the server-time offset. */
    private final MsgIdGen ids = new MsgIdGen();

    private int dcId = Dc.BOOTSTRAP_DC_ID;
    private boolean authorized;

    /** Used only when the number has no account yet; see {@link #signIn}. */
    private String signUpFirstName = "J2ME";
    private String signUpLastName = "";

    public void setSignUpName(String firstName, String lastName)
    {
        this.signUpFirstName = firstName == null ? "J2ME" : firstName;
        this.signUpLastName = lastName == null ? "" : lastName;
    }

    public Telegram(Transport transport, Rng rng, AuthKeyStore store)
    {
        this(new FixedLinkFactory(transport), rng, store, directConfig(),
             new ConnectionDiagnostics());
    }

    public Telegram(MtLinkFactory links, Rng rng, AuthKeyStore store,
                    ConnectionConfig config, ConnectionDiagnostics diagnostics)
    {
        this.links = links;
        this.rng = rng;
        this.store = store;
        this.connectionConfig = config;
        this.connectionDiagnostics = diagnostics;
        this.connectionConfig.load(store);
        this.updates = new UpdateSync(new UpdateSync.Invoker()
        {
            public byte[] invoke(byte[] query) throws IOException
            {
                return Telegram.this.invoke(query);
            }
        }, peers);
        // Built here rather than handed in, so the credentials are erased on
        // every path that has a client at all - including the live tests, which
        // never assemble a MIDlet. The two stores this class owns are
        // registered once, here, rather than in their setters: an adapter reads
        // the field, so re-setting a store does not need a second slot.
        this.wipe = new AccountWipe(store, Dc.isTest());
        this.wipe.add("outbox", new AccountStore()
        {
            // Emptied under outboxLock, which the drain thread also takes to
            // write, so the two cannot interleave.
            public void clear() throws IOException
            {
                synchronized (outboxLock)
                {
                    if (outgoingStore != null) { outgoingStore.clear(); }
                }
                // Whatever it was waiting to retry is not there any more.
                outboxRetry.cancel();
            }
        });
        this.wipe.add("update state", new AccountStore()
        {
            public void clear() throws IOException { updates.clearStore(); }
        });
    }

    public PeerCache peers()
    {
        return peers;
    }

    public boolean isAuthorized()
    {
        return authorized;
    }

    public int dcId()
    {
        return dcId;
    }

    public ConnectionConfig connectionConfig()
    {
        return connectionConfig;
    }

    public ConnectionDiagnostics connectionDiagnostics()
    {
        return connectionDiagnostics;
    }

    public void setConnectionListener(ConnectionListener listener)
    {
        connectionListener = listener;
        if (listener != null)
        {
            listener.onConnectionState(connectionState, 0, connectionDetail);
        }
    }

    public void setOutgoingStore(OutgoingStore store)
    {
        // Under the lock the drain writes under, so a replacement cannot land
        // between a send and its record update. A wake pending against the
        // store being replaced has nothing left to do.
        synchronized (outboxLock) { outgoingStore = store; }
        outboxRetry.cancel();
    }

    public void setOutgoingListener(OutgoingListener listener)
    {
        outgoingListener = listener;
    }

    public void setUpdateStateStore(UpdateStateStore value)
    {
        updates.setStore(value);
    }

    public void setUpdateListener(final UpdateListener value)
    {
        updates.setListener(value == null ? null : new UpdateSync.Listener()
        {
            public void onBatch(UpdateBatch batch)
            {
                value.onUpdates(batch);
            }
        });
    }

    public void setActivePeer(Peer peer)
    {
        updates.setActivePeer(peer);
    }

    public UpdateState updateState()
    {
        return updates.snapshot();
    }

    public String updateSyncState()
    {
        return updates.syncState();
    }

    public String updateSyncDetail()
    {
        return updates.detail();
    }

    public int queuedUpdates()
    {
        return updates.queued();
    }

    public int connectionState()
    {
        synchronized (lifecycleLock) { return connectionState; }
    }

    public static String connectionStateName(int state)
    {
        switch (state)
        {
            case CONNECTING: return "connecting";
            case ONLINE: return "online";
            case RETRYING: return "retrying";
            case OFFLINE: return "offline";
            case PAUSED: return "paused";
            default: return "idle";
        }
    }

    // ------------------------------------------------------------ connection

    /**
     * Connect to a data centre and establish an encrypted session, reusing a
     * stored auth_key when one exists.
     */
    public void connect(int dc) throws IOException
    {
        synchronized (lifecycleLock)
        {
            reconnectToken++;
            lifecycleLock.notifyAll();
        }
        connectInternal(dc);
    }

    private void connectInternal(int dc) throws IOException
    {
        synchronized (connectLock)
        {
            doConnect(dc);
        }
    }

    private void doConnect(int dc) throws IOException
    {
        setConnectionState(CONNECTING, 0, "dc" + dc);
        MtClient old = client;
        if (old != null) { old.close(); }
        client = null;
        dcId = dc;
        int[] attempts = connectionConfig.attempts();
        IOException last = null;
        for (int i = 0; i < attempts.length; i++)
        {
            int mode = attempts[i];
            LinkSpec spec = null;
            long started = System.currentTimeMillis();
            try
            {
                spec = links.create(mode, dc, dcDirectory.endpoint(dc, false),
                        connectionConfig, rng);
                connectionDiagnostics.begin(dc, mode, spec.host, spec.port);
                Diag.info("route " + ConnectionConfig.name(mode) + " -> "
                          + spec.host + ":" + spec.port);

                // The same MsgIdGen is carried across reconnects so the
                // server-time offset survives.
                final MtClient candidate = new MtClient(spec.link, rng, ids);
                candidate.setListener(new MtClient.Listener()
                {
                    public void onUpdate(byte[] body)
                    {
                        updates.accept(body);
                    }

                    public void onConnectionLost(IOException error)
                    {
                        handleConnectionLost(candidate, error);
                    }
                });
                client = candidate;
                candidate.connect(dc, spec.host, spec.port, 30000, spec.media);

                AuthKeyLoad stored = store.load(dc, Dc.isTest());
                if (stored.isFound())
                {
                    candidate.resume(stored.key, 0);
                    Diag.info("resumed with stored key for dc" + dc);
                }
                else
                {
                    if (!stored.isNotFound())
                    {
                        // A damaged record or an unreadable store is not a first
                        // launch, and the handshake about to run will replace
                        // whatever is there. Naming which one it was is the only
                        // way a later report can tell "the app forgot my login"
                        // apart from "I never signed in".
                        Diag.error("stored auth_key for dc" + dc
                                   + " unusable (" + stored.describe()
                                   + "), generating a new one");
                    }
                    AuthKey fresh = candidate.authenticate();
                    store.save(fresh);
                    Diag.info("generated a new key for dc" + dc);
                }

                // A connected socket is not yet proof that this carrier reaches
                // MTProto: DPI/proxies can accept TCP and discard the protocol.
                // This idempotent preflight belongs to connection setup, so Auto
                // may safely try the next route if it fails. It also means an
                // "OK" Diagnostics row on the phone is direct evidence that
                // help.getConfig completed through that exact route.
                byte[] config = candidate.invokeWithSaltRetry(Requests.getConfig());
                TlObj parsedConfig = TlParser.parse(new TlReader(config));
                if (parsedConfig == null || parsedConfig.id != Api.CONFIG)
                {
                    throw new IOException("connection preflight returned "
                                          + describe(parsedConfig));
                }
                dcDirectory.absorb(parsedConfig);
                Diag.info("route preflight help.getConfig = " + config.length + " bytes");

                connectionConfig.lastSuccessful = mode;
                connectionConfig.save(store);
                connectionDiagnostics.connected(mode,
                        System.currentTimeMillis() - started, spec.link);
                everConnected = true;
                setConnectionState(ONLINE, 0, candidate.isConnected()
                        ? spec.link.description() : "connected");
                return;
            }
            catch (RpcError e)
            {
                connectionDiagnostics.failed(mode, e);
                if (client != null) { client.close(); client = null; }
                // API/auth errors are independent of the carrier. Trying the
                // same RPC through another route would only hide the real fault.
                throw e;
            }
            catch (SecurityException e)
            {
                // MIDP permission failures are unchecked exceptions, not
                // IOExceptions. They are nevertheless a normal reachability
                // failure for this particular route (some AMS policies reject
                // raw sockets for unsigned MIDlets), so Auto must continue to
                // HTTP instead of aborting on the first direct try.
                connectionDiagnostics.failed(mode, e);
                Diag.warn("route " + ConnectionConfig.name(mode)
                          + " denied by Java security policy: " + e.getMessage());
                last = new IOException("security policy denied "
                        + ConnectionConfig.name(mode) + ": " + e.getMessage());
                if (client != null) { client.close(); client = null; }
            }
            catch (IOException e)
            {
                last = e;
                connectionDiagnostics.failed(mode, e);
                Diag.warn("route " + ConnectionConfig.name(mode) + " failed: "
                          + e.getMessage());
                if (client != null) { client.close(); client = null; }
            }
        }
        IOException failure = last == null
                ? new IOException("no connection routes") : last;
        setConnectionState(OFFLINE, 0, failure.getMessage());
        throw failure;
    }

    /** Connect to whichever DC the last session used, or the bootstrap one. */
    public void connect() throws IOException
    {
        String saved = store.loadString("dc");
        int dc = Dc.BOOTSTRAP_DC_ID;
        if (saved != null)
        {
            try { dc = Integer.parseInt(saved); }
            catch (NumberFormatException ignored) { }
        }
        connect(dc);
    }

    public void close()
    {
        synchronized (lifecycleLock)
        {
            reconnectToken++;
            lifecycleLock.notifyAll();
        }
        MtClient old = client;
        client = null;
        if (old != null) { old.close(); }
        updates.close();
        connectionDiagnostics.closed();
        setConnectionState(IDLE, 0, "");
    }

    /** Deliberate MIDlet background transition: never reconnect while paused. */
    public void pause()
    {
        synchronized (lifecycleLock)
        {
            foreground = false;
            reconnectToken++;
            lifecycleLock.notifyAll();
        }
        MtClient old = client;
        client = null;
        if (old != null) { old.close(); }
        updates.offline();
        // Not for safety - startOutboxDrain refuses while the connection is not
        // ONLINE anyway - but a backgrounded MIDlet should not be holding a
        // thread against a deadline it cannot act on. resume() reconnects, and
        // the ONLINE transition drains and reschedules from the store.
        outboxRetry.cancel();
        connectionDiagnostics.closed();
        setConnectionState(PAUSED, 0, "MIDlet backgrounded");
    }

    /** Resume is non-blocking; the connection manager performs all network I/O. */
    public void resume()
    {
        synchronized (lifecycleLock)
        {
            foreground = true;
            reconnectToken++;
            lifecycleLock.notifyAll();
        }
        if (everConnected) { startReconnect(true); }
        else { setConnectionState(IDLE, 0, "press Connect"); }
    }

    public void reconnectNow()
    {
        synchronized (lifecycleLock)
        {
            reconnectToken++;
            // The old loop observes the token change and exits. Allow the
            // replacement loop to start immediately instead of making the
            // button merely cancel the pending retry.
            reconnectRunning = false;
            lifecycleLock.notifyAll();
        }
        startReconnect(true);
    }

    /** Diagnostics-only deterministic unexpected-drop injection. */
    public void testDrop()
    {
        MtClient dropped = client;
        if (dropped == null) { return; }
        dropped.close();
        handleConnectionLost(dropped, new IOException("diagnostic test drop"));
    }

    private void handleConnectionLost(MtClient failed, IOException error)
    {
        if (client != failed) { return; }
        client = null;
        connectionDiagnostics.failed(connectionConfig.lastSuccessful, error);
        if (connectionState() == CONNECTING)
        {
            // The route loop owns failures during preflight and may safely try
            // the next carrier. Starting a second reconnect loop here would
            // race it with the same session and MsgIdGen.
            return;
        }
        setConnectionState(OFFLINE, 0, error.getMessage());
        startReconnect(false);
    }

    private void startReconnect(final boolean immediate)
    {
        final int token;
        synchronized (lifecycleLock)
        {
            if (!foreground || reconnectRunning) { return; }
            reconnectRunning = true;
            token = reconnectToken;
        }
        new Thread(new Runnable()
        {
            public void run()
            {
                int attempt = 0;
                try
                {
                    while (reconnectAllowed(token))
                    {
                        if (!immediate || attempt > 0)
                        {
                            int delay = RECONNECT_DELAYS[Math.min(
                                    attempt, RECONNECT_DELAYS.length - 1)];
                            setConnectionState(RETRYING, delay / 1000,
                                    "retry " + (attempt + 1));
                            if (!waitReconnect(token, delay)) { return; }
                        }
                        try
                        {
                            connectInternal(savedDc());
                            return;
                        }
                        catch (IOException e)
                        {
                            Diag.warn("reconnect failed: " + e.getMessage());
                            attempt++;
                        }
                    }
                }
                finally
                {
                    synchronized (lifecycleLock)
                    {
                        if (token == reconnectToken) { reconnectRunning = false; }
                    }
                }
            }
        }).start();
    }

    /**
     * The data centre this account's key belongs to, connected or not.
     *
     * Kept current across a migration, so it is what a caller wants before
     * anything has connected - the UI asks it to find the stored key whose
     * seeding version it wants to report on the start screen.
     */
    public int accountDc()
    {
        return savedDc();
    }

    private int savedDc()
    {
        String saved = store.loadString("dc");
        if (saved != null)
        {
            try { return Integer.parseInt(saved); }
            catch (NumberFormatException ignored) { }
        }
        return dcId > 0 ? dcId : Dc.BOOTSTRAP_DC_ID;
    }

    private boolean reconnectAllowed(int token)
    {
        synchronized (lifecycleLock)
        {
            return foreground && token == reconnectToken;
        }
    }

    private boolean waitReconnect(int token, int delay)
    {
        synchronized (lifecycleLock)
        {
            if (!foreground || token != reconnectToken) { return false; }
            try { lifecycleLock.wait(delay); }
            catch (InterruptedException ignored) { }
            return foreground && token == reconnectToken;
        }
    }

    private void setConnectionState(int state, int retrySeconds, String detail)
    {
        ConnectionListener callback;
        synchronized (lifecycleLock)
        {
            connectionState = state;
            connectionDetail = detail == null ? "" : detail;
            callback = connectionListener;
        }
        connectionDiagnostics.lifecycle(connectionStateName(state),
                retrySeconds, connectionDetail);
        if (callback != null)
        {
            try { callback.onConnectionState(state, retrySeconds, connectionDetail); }
            catch (Throwable t) { Diag.error("connection state listener failed", t); }
        }
        if (state == ONLINE) { updates.online(); }
        else if (state == OFFLINE || state == PAUSED || state == IDLE)
        {
            updates.offline();
        }
        if (state == ONLINE) { startOutboxDrain(); }
    }

    private static ConnectionConfig directConfig()
    {
        ConnectionConfig config = new ConnectionConfig();
        config.mode = ConnectionConfig.DIRECT;
        return config;
    }

    // ------------------------------------------------------------ login

    /**
     * Ask Telegram to send a login code.
     *
     * @return the phone_code_hash to pass back to {@link #signIn}
     */
    public String sendCode(String phoneNumber) throws IOException
    {
        for (int attempt = 0; attempt < MAX_MIGRATIONS; attempt++)
        {
            try
            {
                byte[] result = invoke(Requests.sendCode(phoneNumber));
                return parseSentCode(result, "auth.sendCode");
            }
            catch (RpcError e)
            {
                int target = e.migrateDc();
                if (!e.isMigrate() || target <= 0)
                {
                    throw e;
                }
                // The account is on another DC. Reconnecting means a fresh
                // handshake there - keys are not portable between DCs.
                Diag.info("account lives on dc" + target + ", migrating");
                store.saveString("dc", String.valueOf(target));
                connect(target);
            }
        }
        throw new IOException("migrated " + MAX_MIGRATIONS + " times without settling");
    }

    /** Ask Telegram to send the next available code delivery type. */
    public String resendCode(String phoneNumber, String phoneCodeHash)
            throws IOException
    {
        byte[] result = invoke(Requests.resendCode(phoneNumber, phoneCodeHash));
        return parseSentCode(result, "auth.resendCode");
    }

    /** Cancel an unfinished code flow before the user changes phone number. */
    public void cancelCode(String phoneNumber, String phoneCodeHash)
            throws IOException
    {
        requireTrue(invoke(Requests.cancelCode(phoneNumber, phoneCodeHash)),
                    "auth.cancelCode");
    }

    /**
     * Complete the login.
     *
     * @return the signed-in user
     * @throws RpcError with {@link RpcError#isPasswordNeeded()} when the account
     *         has two-factor authentication enabled - that needs SRP, which
     *         this client does not implement yet
     */
    public Peer signIn(String phoneNumber, String phoneCodeHash, String code)
            throws IOException
    {
        byte[] result;
        try
        {
            result = invoke(Requests.signIn(phoneNumber, phoneCodeHash, code));
        }
        catch (RpcError e)
        {
            if (!"PHONE_NUMBER_UNOCCUPIED".equals(e.type()))
            {
                throw e;
            }
            // No account on this number yet. On the test data centres that is
            // the normal path; on production it means the user has to register,
            // which we do with the name they gave us.
            Diag.info("no account for this number, registering");
            result = invoke(Requests.signUp(phoneNumber, phoneCodeHash,
                                            signUpFirstName, signUpLastName));
        }

        return acceptAuthorization(result, "auth.signIn");
    }

    /** Password hint for the 2FA input screen. Empty when no hint was set. */
    public String passwordHint() throws IOException
    {
        TlObj password = getPasswordObject();
        return password.strOrEmpty(Api.F_ACCOUNT_PASSWORD__HINT);
    }

    /**
     * Complete a 2FA login using Telegram's SRP proof.
     *
     * A fresh account.getPassword call is intentional: srp_B/srp_id belong to
     * one proof attempt, so a wrong password must never reuse stale parameters.
     */
    public Peer checkPassword(String password, Pbkdf2.Progress progress)
            throws IOException
    {
        TlObj accountPassword = getPasswordObject();
        Srp.Parameters params = srpParameters(accountPassword);
        long started = System.currentTimeMillis();
        Srp.Check check = Srp.compute(password, params, rng, progress);
        Diag.info("2FA SRP proof generated in "
                + (System.currentTimeMillis() - started) + " ms");
        byte[] result = invoke(Requests.checkPassword(check));
        return acceptAuthorization(result, "auth.checkPassword");
    }

    private String parseSentCode(byte[] result, String operation)
            throws IOException
    {
        TlObj sent = TlParser.parse(new TlReader(result));
        if (sent == null || sent.id != Api.AUTH_SENT_CODE)
        {
            throw new IOException("unexpected reply to " + operation + ": "
                                  + describe(sent));
        }
        String hash = sent.str(Api.F_AUTH_SENT_CODE__PHONE_CODE_HASH);
        TlObj type = sent.obj(Api.F_AUTH_SENT_CODE__TYPE);
        // The delivery type decides where the user should look for the code -
        // on a client that cannot read SMS itself, the distinction matters.
        lastSentCodeType = type == null ? 0 : type.id;
        // Every SentCodeType variant that carries a numeric length stores it
        // last. Constructors without a length leave this at zero.
        lastSentCodeLength = 0;
        if (type != null && type.nums.length > 0)
        {
            lastSentCodeLength = (int) type.nums[type.nums.length - 1];
        }
        Diag.info("code sent via " + describeSentCodeType(lastSentCodeType)
                  + ", hash length " + (hash == null ? 0 : hash.length()));
        return hash;
    }

    private TlObj getPasswordObject() throws IOException
    {
        TlObj password = TlParser.parse(new TlReader(invoke(Requests.getPassword())));
        if (password == null || password.id != Api.ACCOUNT_PASSWORD)
        {
            throw new IOException("unexpected reply to account.getPassword: "
                                  + describe(password));
        }
        byte[] secureRandom =
                password.bytes(Api.F_ACCOUNT_PASSWORD__SECURE_RANDOM);
        if (secureRandom != null) { rng.addEntropy(secureRandom); }
        return password;
    }

    private static Srp.Parameters srpParameters(TlObj password)
            throws IOException
    {
        if (!password.flag(2))
        {
            throw new IOException("account.getPassword says no 2FA password is set");
        }
        TlObj algo = password.obj(Api.F_ACCOUNT_PASSWORD__CURRENT_ALGO);
        if (algo == null || algo.id !=
                Api.PASSWORD_KDF_ALGO_S_H_A256_S_H_A256_P_B_K_D_F2_H_M_A_C_S_H_A512ITER100000_S_H_A256_MOD_POW)
        {
            throw new IOException("unsupported Telegram password KDF: "
                                  + describe(algo));
        }
        Srp.Parameters params = new Srp.Parameters();
        params.salt1 = algo.bytes(
                Api.F_PASSWORD_KDF_ALGO_S_H_A256_S_H_A256_P_B_K_D_F2_H_M_A_C_S_H_A512ITER100000_S_H_A256_MOD_POW__SALT1);
        params.salt2 = algo.bytes(
                Api.F_PASSWORD_KDF_ALGO_S_H_A256_S_H_A256_P_B_K_D_F2_H_M_A_C_S_H_A512ITER100000_S_H_A256_MOD_POW__SALT2);
        params.g = algo.intAt(
                Api.F_PASSWORD_KDF_ALGO_S_H_A256_S_H_A256_P_B_K_D_F2_H_M_A_C_S_H_A512ITER100000_S_H_A256_MOD_POW__G);
        params.p = algo.bytes(
                Api.F_PASSWORD_KDF_ALGO_S_H_A256_S_H_A256_P_B_K_D_F2_H_M_A_C_S_H_A512ITER100000_S_H_A256_MOD_POW__P);
        params.b = password.bytes(Api.F_ACCOUNT_PASSWORD__SRP_B);
        params.id = password.num(Api.F_ACCOUNT_PASSWORD__SRP_ID);
        return params;
    }

    private Peer acceptAuthorization(byte[] result, String operation)
            throws IOException
    {
        TlObj auth = TlParser.parse(new TlReader(result));
        if (auth == null || auth.id != Api.AUTH_AUTHORIZATION)
        {
            throw new IOException("unexpected reply to " + operation + ": "
                                  + describe(auth));
        }

        Peer me = Peer.fromUser(auth.obj(Api.F_AUTH_AUTHORIZATION__USER));
        if (me != null)
        {
            me.self = true;
            peers.put(me);
            updates.activate(me.id);
        }
        authorized = true;
        store.saveString("dc", String.valueOf(dcId));
        store.saveString("authorized", "1");
        Diag.info("signed in as " + (me == null ? "?" : me.title));
        return me;
    }

    private static void requireTrue(byte[] result, String operation)
            throws IOException
    {
        TlObj value = TlParser.parse(new TlReader(result));
        if (value == null || value.id != Api.BOOL_TRUE)
        {
            throw new IOException("unexpected reply to " + operation + ": "
                                  + describe(value));
        }
    }

    /**
     * Confirm a stored session still works, by asking who we are.
     *
     * Cheaper and more definitive than guessing from stored flags: if the key
     * was revoked from another device, this is where we find out.
     *
     * @return the peer, or null when the answer was "no" <em>or</em> when there
     *         was no answer. Callers that act on the difference - anything that
     *         would send the user back to the login screen - must use
     *         {@link #verifyAuthorization()} instead.
     */
    public Peer checkAuthorization()
    {
        return verifyAuthorization().peer;
    }

    /**
     * Confirm a stored session still works, distinguishing a refusal from
     * silence.
     *
     * Only the server can say a session is dead. A timeout, a dropped socket or
     * an unrecovered resync says nothing at all, and treating it as a refusal
     * logs the user out of an account they are still signed in to - see
     * {@link AuthCheck}.
     */
    public AuthCheck verifyAuthorization()
    {
        try
        {
            byte[] result = invoke(Requests.getSelf());
            TlObj[] users = TlParser.parseVector(result);
            if (users.length > 0)
            {
                Peer me = Peer.fromUser(users[0]);
                if (me != null)
                {
                    me.self = true;
                    peers.put(me);
                    authorized = true;
                    updates.activate(me.id);
                    return AuthCheck.yes(me);
                }
            }
            // A well-formed reply that names nobody. The server answered, so
            // this is an answer: there is no account behind this key.
            authorized = false;
            return AuthCheck.no("users.getSelf returned no user");
        }
        catch (RpcError e)
        {
            if (e.isNotSignedIn())
            {
                // Normal before sign-in: the key works, there is simply no
                // account attached to it yet. Keeping it is what makes the next
                // launch fast - regenerating costs two 2048-bit modPows.
                Diag.info("no account on this key yet - sign-in required");
                authorized = false;
                return AuthCheck.no(e.getMessage());
            }
            if (e.isAuthKeyInvalid())
            {
                // Someone ended this session from another device. That is a
                // logout, and it has to erase what a logout erases - not the
                // one key this used to take.
                Diag.warn("stored session is no longer valid: " + e.getMessage());
                eraseLocalAccount();
                return AuthCheck.no(e.getMessage());
            }
            return inconclusive(e);
        }
        catch (IOException e)
        {
            return inconclusive(e);
        }
    }

    /**
     * The session could not be checked. Leave every piece of stored state
     * alone, including {@link #authorized}: the previous value is the best
     * information available, and clearing it is what used to turn one lost
     * packet into a logout.
     */
    private AuthCheck inconclusive(IOException e)
    {
        String detail = String.valueOf(e.getMessage());
        // This flag has been written since sign-in and never read. Read here it
        // answers the one question the failure raises: was there an account to
        // lose? A "yes" makes the retry worth offering rather than a login box.
        boolean signedInBefore = "1".equals(store.loadString("authorized"));
        Diag.warn("authorization check inconclusive: " + detail
                  + (signedInBefore ? " (session kept - signed in previously)"
                                    : " (no previous sign-in recorded)"));
        return AuthCheck.unknown(e);
    }

    /**
     * Log out here and erase the account from this handset.
     *
     * The erasure is in a {@code finally} because the two halves are
     * independent: Telegram may never answer - a lost reply on a train - and
     * the account still has to leave the phone. The report of what was erased
     * survives the throw in {@link #lastWipeReport}, so the caller can tell
     * "the server did not confirm" from "a cache would not delete", which are
     * different problems with different answers.
     */
    public void logOut() throws IOException
    {
        try
        {
            invoke(Requests.logOut());
        }
        finally
        {
            eraseLocalAccount();
        }
    }

    /**
     * End the account on this handset: session, memory, then storage.
     *
     * Shared by Log out and by the server reporting the key as revoked, which
     * is a logout someone performed from another device. Both mean the same
     * thing locally, and only one of them used to act like it - the revoked
     * path cleared one key and left the drafts, the caches and the media import
     * markers for whoever signed in next.
     *
     * The socket is deliberately left open. {@link #invoke} refuses when there
     * is no client, and the sign-in that follows a logout goes straight to
     * {@code auth.sendCode} without reconnecting, so closing here would answer
     * the user's next keypress with "not connected". What has to stop is
     * writing, not talking, and the account epoch below is what stops it.
     */
    private void eraseLocalAccount()
    {
        authorized = false;
        // Bumped under the lock the outbox drain writes under: a drain already
        // between a send and its record update either writes before the erase
        // or not at all, never after it.
        synchronized (outboxLock) { accountEpoch++; }
        // Nothing pending may wake into an account that is being erased. The
        // drain re-checks the epoch anyway, so this is not what makes it safe -
        // it is what stops a thread sitting on a five-minute FLOOD_WAIT for a
        // message that no longer exists.
        outboxRetry.cancel();
        updates.close();
        updates.deactivate();
        peers.clear();
        cachedAvailableReactions = null;
        lastWipe = wipe.run();
        notifyOutboxChanged();
    }

    /**
     * What the last local erasure managed to erase, or null if none has run.
     *
     * Readable after {@link #logOut} has thrown, which is the case it exists
     * for.
     */
    public WipeReport lastWipeReport()
    {
        return lastWipe;
    }

    /** The list of what belongs to the account, for the shell to add to. */
    public AccountWipe accountWipe()
    {
        return wipe;
    }

    /**
     * End every server-side session, including this one.
     *
     * auth.resetAuthorizations deliberately preserves the caller, so the
     * ordinary local logout must follow it to make "everywhere" literal.
     */
    public void logOutEverywhere() throws IOException
    {
        // The two halves are reported together but must not be conditional on
        // each other. This used to throw straight out of resetAuthorizations,
        // which left the account signed in and fully stored on the handset the
        // user was holding - the one session they could definitely end.
        IOException failure = null;
        try
        {
            requireTrue(invoke(Requests.resetAuthorizations()),
                        "auth.resetAuthorizations");
        }
        catch (IOException e)
        {
            failure = e;
        }
        try
        {
            logOut();
        }
        catch (IOException e)
        {
            if (failure == null) { failure = e; }
        }
        if (failure != null) { throw failure; }
    }

    // ------------------------------------------------------------ messaging

    /**
     * The dialog list, newest first.
     *
     * The response carries dialogs, their last messages, and the users and
     * chats those refer to, all in separate vectors - so this joins them and
     * feeds {@link PeerCache} on the way through.
     */
    public DialogPage getDialogs(int limit) throws IOException
    {
        return getDialogs(limit, 0);
    }

    /**
     * The first page, offered the chance to come back as "not modified".
     *
     * @param hash of the list already held; 0 forces a full response
     */
    public DialogPage getDialogs(int limit, long hash) throws IOException
    {
        return parseDialogsReply(invoke(Requests.getDialogs(null, limit, hash)));
    }

    public DialogPage getDialogsAfter(Dialog offset, int limit)
            throws IOException
    {
        return parseDialogsReply(invoke(Requests.getDialogs(offset, limit)));
    }

    private DialogPage parseDialogsReply(byte[] result) throws IOException
    {
        TlObj res = TlParser.parse(new TlReader(result));
        if (res == null)
        {
            throw new IOException("empty reply to messages.getDialogs");
        }

        TlObj[] dialogs;
        TlObj[] messages;
        TlObj[] chats;
        TlObj[] users;
        DialogPage page = new DialogPage();

        if (res.id == Api.MESSAGES_DIALOGS)
        {
            dialogs = res.vec(Api.F_MESSAGES_DIALOGS__DIALOGS);
            messages = res.vec(Api.F_MESSAGES_DIALOGS__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_DIALOGS__CHATS);
            users = res.vec(Api.F_MESSAGES_DIALOGS__USERS);
            // Not a slice: this constructor is the whole list, which is the
            // cheapest "stop asking" signal there is - no empty page needed to
            // discover it.
            page.complete = true;
            page.total = dialogs == null ? 0 : dialogs.length;
        }
        else if (res.id == Api.MESSAGES_DIALOGS_SLICE)
        {
            dialogs = res.vec(Api.F_MESSAGES_DIALOGS_SLICE__DIALOGS);
            messages = res.vec(Api.F_MESSAGES_DIALOGS_SLICE__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_DIALOGS_SLICE__CHATS);
            users = res.vec(Api.F_MESSAGES_DIALOGS_SLICE__USERS);
            page.total = res.intAt(Api.F_MESSAGES_DIALOGS_SLICE__COUNT);
        }
        else if (res.id == Api.MESSAGES_DIALOGS_NOT_MODIFIED)
        {
            // Only reachable with a non-zero hash, which this client sends only
            // for the first page. Nothing to parse and nothing to seed: the
            // caller keeps what it already holds, including the total it was
            // last told - which is why the count this constructor carries is
            // not read, and why no field of it is on the TL whitelist.
            page.notModified = true;
            return page;
        }
        else
        {
            throw new IOException("unexpected reply to messages.getDialogs: "
                                  + describe(res));
        }

        peers.absorb(users, chats);

        Dialog[] out = new Dialog[dialogs.length];
        int w = 0;
        for (int i = 0; i < dialogs.length; i++)
        {
            TlObj d = dialogs[i];
            if (d == null || d.id != Api.DIALOG)
            {
                continue;
            }
            Peer reference = Peer.fromPeerObj(d.obj(Api.F_DIALOG__PEER));
            if (reference == null)
            {
                continue;
            }

            Dialog entry = new Dialog();
            entry.peer = peers.resolve(reference);
            entry.topMessageId = d.intAt(Api.F_DIALOG__TOP_MESSAGE);
            entry.unreadCount = d.intAt(Api.F_DIALOG__UNREAD_COUNT);
            entry.pinned = d.num(Api.F_DIALOG__PINNED) != 0;
            entry.readInboxMaxId = d.intAt(Api.F_DIALOG__READ_INBOX_MAX_ID);
            entry.readOutboxMaxId = d.intAt(Api.F_DIALOG__READ_OUTBOX_MAX_ID);
            entry.channelPts = d.flag(0) ? d.intAt(Api.F_DIALOG__PTS) : -1;

            Message last = findMessage(messages, entry.topMessageId, reference);
            if (last != null)
            {
                entry.lastMessage = Dialog.clipPreview(last.summaryText());
                entry.date = last.date;
                entry.lastMessageOutgoing = last.outgoing;
            }
            out[w++] = entry;
        }

        Dialog[] resultDialogs;
        if (w == out.length)
        {
            resultDialogs = out;
        }
        else
        {
            resultDialogs = new Dialog[w];
            System.arraycopy(out, 0, resultDialogs, 0, w);
        }
        updates.seedDialogs(resultDialogs);
        page.dialogs = resultDialogs;
        // A slice whose count is below what it just handed over is a count for
        // a different question - the folder total, or a figure that moved
        // between requests. Never let it read as "you already have them all".
        if (page.total < resultDialogs.length) { page.total = resultDialogs.length; }
        return page;
    }

    /** Message history for one peer, newest first. */
    public Message[] getHistory(Peer peer, int limit) throws IOException
    {
        return parseMessagesReply(invoke(Requests.getHistory(peer, limit)),
                "messages.getHistory");
    }

    public Message[] getHistoryBefore(Peer peer, int offsetId, int limit)
            throws IOException
    {
        return parseMessagesReply(invoke(Requests.getHistoryBefore(
                peer, offsetId, limit)), "messages.getHistory/older");
    }

    public Message[] getHistoryAfter(Peer peer, int offsetId, int limit)
            throws IOException
    {
        return parseMessagesReply(invoke(Requests.getHistoryAfter(
                peer, offsetId, limit)), "messages.getHistory/newer");
    }

    /** History window around a forwarded source message. */
    public Message[] getHistoryAround(Peer peer, int messageId, int limit)
            throws IOException
    {
        return parseMessagesReply(invoke(Requests.getHistoryAround(
                peer, messageId, limit)), "messages.getHistory/around");
    }

    /** One bounded page of text matches inside {@code peer}. */
    public MessageSearchPage searchMessages(Peer peer, String query,
            int offsetId, int addOffset, int limit) throws IOException
    {
        if (peer == null || !peers.isAddressable(peer))
        {
            throw new IOException("search peer is not addressable");
        }
        query = query == null ? "" : query.trim();
        if (query.length() < 2) { throw new IOException("search query is too short"); }
        if (query.length() > 64) { throw new IOException("search query exceeds 64 characters"); }
        if (limit < 1) { limit = 1; }
        if (limit > 30) { limit = 30; }
        TlObj reply = TlParser.parse(new TlReader(invoke(Requests.searchMessages(
                peer, query, offsetId, addOffset, limit))));
        MessageSearchPage page = MessageSearchPage.from(
                reply, peers, limit, offsetId);
        if (page == null)
        {
            throw new IOException("unexpected reply to messages.search: "
                    + describe(reply));
        }
        return page;
    }

    private Message[] parseMessagesReply(byte[] result, String method)
            throws IOException
    {
        TlObj res = TlParser.parse(new TlReader(result));
        if (res == null)
        {
            throw new IOException("empty reply to " + method);
        }

        TlObj[] messages;
        TlObj[] chats;
        TlObj[] users;

        if (res.id == Api.MESSAGES_MESSAGES)
        {
            messages = res.vec(Api.F_MESSAGES_MESSAGES__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_MESSAGES__CHATS);
            users = res.vec(Api.F_MESSAGES_MESSAGES__USERS);
        }
        else if (res.id == Api.MESSAGES_MESSAGES_SLICE)
        {
            messages = res.vec(Api.F_MESSAGES_MESSAGES_SLICE__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_MESSAGES_SLICE__CHATS);
            users = res.vec(Api.F_MESSAGES_MESSAGES_SLICE__USERS);
        }
        else if (res.id == Api.MESSAGES_CHANNEL_MESSAGES)
        {
            messages = res.vec(Api.F_MESSAGES_CHANNEL_MESSAGES__MESSAGES);
            chats = res.vec(Api.F_MESSAGES_CHANNEL_MESSAGES__CHATS);
            users = res.vec(Api.F_MESSAGES_CHANNEL_MESSAGES__USERS);
        }
        else
        {
            throw new IOException("unexpected reply to " + method + ": "
                                  + describe(res));
        }

        peers.absorb(users, chats);

        Message[] out = new Message[messages.length];
        int w = 0;
        for (int i = 0; i < messages.length; i++)
        {
            Message m = Message.from(messages[i], peers);
            if (m != null)
            {
                out[w++] = m;
            }
        }
        if (w == out.length)
        {
            return out;
        }
        Message[] trimmed = new Message[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
    }

    /** Get a bounded list of peers who reacted to one message. */
    public ReactionActorsPage getMessageReactions(Peer peer, int messageId,
                                                   int limit)
            throws IOException
    {
        if (limit < 1) { limit = 1; }
        if (limit > 100) { limit = 100; }
        byte[] result = invoke(Requests.getMessageReactions(
                peer, messageId, limit));
        TlObj res = TlParser.parse(new TlReader(result));
        if (res == null || res.id != Api.MESSAGES_MESSAGE_REACTIONS_LIST)
        {
            throw new IOException("unexpected reply to reaction list: "
                    + describe(res));
        }
        peers.absorb(
                res.vec(Api.F_MESSAGES_MESSAGE_REACTIONS_LIST__USERS),
                res.vec(Api.F_MESSAGES_MESSAGE_REACTIONS_LIST__CHATS));
        TlObj[] raw = res.vec(
                Api.F_MESSAGES_MESSAGE_REACTIONS_LIST__REACTIONS);
        ReactionActor[] actors = new ReactionActor[raw.length];
        int count = 0;
        for (int i = 0; i < raw.length; i++)
        {
            TlObj item = raw[i];
            if (item == null || item.id != Api.MESSAGE_PEER_REACTION)
            {
                continue;
            }
            ReactionActor actor = new ReactionActor();
            actor.peer = peers.resolve(Peer.fromPeerObj(
                    item.obj(Api.F_MESSAGE_PEER_REACTION__PEER_ID)));
            actor.date = item.intAt(Api.F_MESSAGE_PEER_REACTION__DATE);
            TlObj reaction = item.obj(
                    Api.F_MESSAGE_PEER_REACTION__REACTION);
            if (reaction != null && reaction.id == Api.REACTION_EMOJI)
            {
                actor.emoji = reaction.strOrEmpty(
                        Api.F_REACTION_EMOJI__EMOTICON);
            }
            else if (reaction != null
                    && reaction.id == Api.REACTION_CUSTOM_EMOJI)
            {
                actor.emoji = "[custom]";
            }
            else if (reaction != null && reaction.id == Api.REACTION_PAID)
            {
                actor.emoji = "[paid]";
            }
            actors[count++] = actor;
        }
        if (count != actors.length)
        {
            ReactionActor[] trimmed = new ReactionActor[count];
            System.arraycopy(actors, 0, trimmed, 0, count);
            actors = trimmed;
        }
        ReactionActorsPage page = new ReactionActorsPage();
        page.totalCount = res.intAt(
                Api.F_MESSAGES_MESSAGE_REACTIONS_LIST__COUNT);
        page.nextOffset = res.str(
                Api.F_MESSAGES_MESSAGE_REACTIONS_LIST__NEXT_OFFSET);
        page.actors = actors;
        return page;
    }

    /**
     * Compact palette intersected with Telegram's current global catalog and
     * this group's or channel's ChatReactions policy.
     */
    public String[] getAllowedReactions(Peer peer) throws IOException
    {
        String[] global = availableReactions();
        String[] allowed = peer == null || peer.kind == Peer.USER
                ? null : peerReactionPolicy(peer);
        return ReactionCatalog.filter(global, allowed);
    }

    private String[] availableReactions() throws IOException
    {
        if (cachedAvailableReactions != null)
        {
            return cachedAvailableReactions;
        }
        TlObj res = TlParser.parse(new TlReader(
                invoke(Requests.getAvailableReactions())));
        if (res == null || res.id != Api.MESSAGES_AVAILABLE_REACTIONS)
        {
            throw new IOException("unexpected available reactions reply: "
                    + describe(res));
        }
        TlObj[] raw = res.vec(Api.F_MESSAGES_AVAILABLE_REACTIONS__REACTIONS);
        String[] values = new String[raw.length];
        int count = 0;
        Peer self = peers.self();
        boolean premium = self != null && self.premium;
        for (int i = 0; i < raw.length; i++)
        {
            TlObj item = raw[i];
            if (item == null || item.id != Api.AVAILABLE_REACTION
                    || item.num(Api.F_AVAILABLE_REACTION__INACTIVE) != 0
                    || (item.num(Api.F_AVAILABLE_REACTION__PREMIUM) != 0
                        && !premium))
            {
                continue;
            }
            String emoji = item.str(Api.F_AVAILABLE_REACTION__REACTION);
            if (emoji != null && emoji.length() > 0)
            {
                values[count++] = emoji;
            }
        }
        cachedAvailableReactions = trimStrings(values, count);
        return cachedAvailableReactions;
    }

    /** null means all globally available normal reactions are allowed. */
    private String[] peerReactionPolicy(Peer peer) throws IOException
    {
        byte[] query = peer.kind == Peer.CHAT
                ? Requests.getFullChat(peer) : Requests.getFullChannel(peer);
        TlObj reply = TlParser.parse(new TlReader(invoke(query)));
        if (reply == null || reply.id != Api.MESSAGES_CHAT_FULL)
        {
            throw new IOException("unexpected full chat reply: "
                    + describe(reply));
        }
        TlObj full = reply.obj(Api.F_MESSAGES_CHAT_FULL__FULL_CHAT);
        TlObj policy = null;
        if (full != null && full.id == Api.CHAT_FULL)
        {
            policy = full.obj(Api.F_CHAT_FULL__AVAILABLE_REACTIONS);
        }
        else if (full != null && full.id == Api.CHANNEL_FULL)
        {
            policy = full.obj(Api.F_CHANNEL_FULL__AVAILABLE_REACTIONS);
        }
        if (policy == null || policy.id == Api.CHAT_REACTIONS_ALL)
        {
            return null;
        }
        if (policy.id == Api.CHAT_REACTIONS_NONE)
        {
            return new String[0];
        }
        if (policy.id != Api.CHAT_REACTIONS_SOME)
        {
            throw new IOException("unknown chat reaction policy");
        }
        TlObj[] raw = policy.vec(Api.F_CHAT_REACTIONS_SOME__REACTIONS);
        String[] values = new String[raw.length];
        int count = 0;
        for (int i = 0; i < raw.length; i++)
        {
            TlObj reaction = raw[i];
            if (reaction != null && reaction.id == Api.REACTION_EMOJI)
            {
                String emoji = reaction.str(Api.F_REACTION_EMOJI__EMOTICON);
                if (emoji != null && emoji.length() > 0)
                {
                    values[count++] = emoji;
                }
            }
        }
        return trimStrings(values, count);
    }

    private static String[] trimStrings(String[] values, int count)
    {
        if (count == values.length) { return values; }
        String[] trimmed = new String[count];
        System.arraycopy(values, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Resolve and cache a public peer from its @username. */
    /**
     * Peers matching {@code query}, contacts first.
     *
     * my_results is what the account already knows - contacts, chats it is in,
     * saved messages - and results is the public directory. Read in that order
     * because a chat the user is already in is the likelier target and should
     * not sit underneath strangers with similar names.
     *
     * Bounded twice over: the request asks for at most {@code limit}, and what
     * comes back is trimmed again, because the server is entitled to send more
     * than it was asked for and this array is built on a heap measured in
     * megabytes.
     */
    public Peer[] searchPeers(String query, int limit) throws IOException
    {
        if (query == null || query.trim().length() == 0)
        {
            return new Peer[0];
        }
        if (limit < 1) { limit = 1; }
        if (limit > MAX_PEER_RESULTS) { limit = MAX_PEER_RESULTS; }

        TlObj res = TlParser.parse(new TlReader(
                invoke(Requests.searchPeers(query.trim(), limit))));
        if (res == null || res.id != Api.CONTACTS_FOUND)
        {
            throw new IOException("unexpected reply to contacts.search: "
                    + describe(res));
        }
        // Before the peers are resolved: a result is only addressable once its
        // access_hash is known, and the hash arrives in these two vectors.
        peers.absorb(res.vec(Api.F_CONTACTS_FOUND__USERS),
                res.vec(Api.F_CONTACTS_FOUND__CHATS));

        Peer[] out = new Peer[limit];
        int count = 0;
        count = collect(res.vec(Api.F_CONTACTS_FOUND__MY_RESULTS), out, count);
        count = collect(res.vec(Api.F_CONTACTS_FOUND__RESULTS), out, count);

        Peer[] trimmed = new Peer[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Longest a peer search result list may be. Bounded for the heap. */
    private static final int MAX_PEER_RESULTS = 20;

    /**
     * Resolve peer references into addressable peers, skipping what cannot be.
     *
     * A result whose User or Chat did not come with the reply has no
     * access_hash, so nothing can be sent to it and opening it would fail with
     * a bare error. Dropping it here is what keeps the list to things that
     * actually work when selected.
     */
    private int collect(TlObj[] refs, Peer[] out, int count)
    {
        if (refs == null) { return count; }
        for (int i = 0; i < refs.length && count < out.length; i++)
        {
            Peer peer = peers.resolve(Peer.fromPeerObj(refs[i]));
            if (peer == null || !peers.isAddressable(peer)) { continue; }
            boolean already = false;
            for (int j = 0; j < count; j++)
            {
                if (out[j].kind == peer.kind && out[j].id == peer.id)
                {
                    already = true;
                    break;
                }
            }
            if (!already) { out[count++] = peer; }
        }
        return count;
    }

    public Peer resolveUsername(String username) throws IOException
    {
        if (username == null || username.length() == 0)
        {
            throw new IOException("public username is empty");
        }
        TlObj res = TlParser.parse(new TlReader(
                invoke(Requests.resolveUsername(username))));
        if (res == null || res.id != Api.CONTACTS_RESOLVED_PEER)
        {
            throw new IOException("unexpected reply to resolveUsername: "
                    + describe(res));
        }
        peers.absorb(res.vec(Api.F_CONTACTS_RESOLVED_PEER__USERS),
                res.vec(Api.F_CONTACTS_RESOLVED_PEER__CHATS));
        return peers.resolve(Peer.fromPeerObj(
                res.obj(Api.F_CONTACTS_RESOLVED_PEER__PEER)));
    }

    /**
     * Send a text message.
     *
     * The reply is an Updates object describing what changed. It is fed through
     * UpdateSync so the outgoing echo and its pts advance in the same order as
     * unsolicited updates.
     */
    public void sendMessage(Peer peer, String text) throws IOException
    {
        sendMessage(peer, text, rng.nextLong(), 0);
    }

    /** Edit one server-side text message and feed its Updates through sync. */
    public void editMessage(Peer peer, int messageId, String text)
            throws IOException
    {
        if (peer == null || !peers.isAddressable(peer))
        {
            throw new IOException("edit peer is not addressable");
        }
        if (messageId <= 0) { throw new IOException("message is not sent yet"); }
        if (text == null || text.trim().length() == 0)
        {
            throw new IOException("edited message is empty");
        }
        if (text.length() > 1000)
        {
            throw new IOException("edited message exceeds 1000 characters");
        }
        byte[] result = invoke(Requests.editMessage(peer, messageId, text));
        updates.accept(result);
    }

    private void sendMessage(Peer peer, String text, long randomId,
                             int replyToMessageId) throws IOException
    {
        if (!peers.isAddressable(peer))
        {
            throw new IOException("cannot address " + peer
                                  + " - no access_hash. Refresh the dialog list.");
        }
        byte[] result = invoke(Requests.sendMessage(
                peer, text, randomId, replyToMessageId));
        TlObj updateObject = TlParser.parse(new TlReader(result));
        Diag.info("sent " + text.length() + " chars to " + peer
                  + ", server replied " + describe(updateObject));
        updates.acceptSent(result, peer, text);
    }

    /**
     * Persist first, then let the background drain send with the same random_id
     * until Telegram confirms it.
     */
    public OutgoingMessage enqueueMessage(Peer peer, String text) throws IOException
    {
        return enqueueMessage(peer, text, 0);
    }

    public OutgoingMessage enqueueMessage(Peer peer, String text,
                                           int replyToMessageId)
            throws IOException
    {
        if (outgoingStore == null) { throw new IOException("outbox is not configured"); }
        if (text == null || text.trim().length() == 0)
        {
            throw new IOException("message is empty");
        }
        if (text.length() > 1000) { throw new IOException("message exceeds 1000 characters"); }
        if (!peers.isAddressable(peer))
        {
            throw new IOException("cannot address " + peer
                    + " - no access_hash. Refresh the dialog list.");
        }
        OutgoingMessage message = outgoingStore.add(peer, text,
                replyToMessageId, rng.nextLong(), System.currentTimeMillis());
        notifyOutboxChanged();
        startOutboxDrain();
        return message;
    }

    public OutgoingMessage[] outgoingMessages() throws IOException
    {
        return outgoingStore == null ? new OutgoingMessage[0] : outgoingStore.list();
    }

    public void retryOutgoing(int localId) throws IOException
    {
        OutgoingMessage message = findOutgoing(localId);
        if (message == null) { return; }
        message.state = OutgoingMessage.QUEUED;
        message.nextAttemptAt = 0;
        message.lastError = "";
        outgoingStore.save(message);
        notifyOutboxChanged();
        startOutboxDrain();
    }

    public void deleteOutgoing(int localId) throws IOException
    {
        if (outgoingStore == null) { return; }
        outgoingStore.remove(localId);
        notifyOutboxChanged();
    }

    private OutgoingMessage findOutgoing(int localId) throws IOException
    {
        OutgoingMessage[] messages = outgoingMessages();
        for (int i = 0; i < messages.length; i++)
        {
            if (messages[i].localId == localId) { return messages[i]; }
        }
        return null;
    }

    private void startOutboxDrain()
    {
        synchronized (outboxLock)
        {
            if (outboxDraining || outgoingStore == null
                    || connectionState() != ONLINE) { return; }
            outboxDraining = true;
        }
        new Thread(new Runnable()
        {
            public void run() { drainOutbox(); }
        }).start();
    }

    /**
     * Write an outbox row unless the account it belongs to has gone.
     *
     * Under {@code outboxLock}, which the wipe also takes to empty the store,
     * so the two cannot interleave: the row is written before the erase or not
     * at all.
     *
     * @return false when this drain is stale and must stop
     */
    private boolean saveOutgoing(int epoch, OutgoingMessage message)
            throws IOException
    {
        synchronized (outboxLock)
        {
            if (epoch != accountEpoch || outgoingStore == null) { return false; }
            outgoingStore.save(message);
            return true;
        }
    }

    /** @return false when this drain is stale and must stop */
    private boolean removeOutgoing(int epoch, int localId) throws IOException
    {
        synchronized (outboxLock)
        {
            if (epoch != accountEpoch || outgoingStore == null) { return false; }
            outgoingStore.remove(localId);
            return true;
        }
    }

    private void drainOutbox()
    {
        long retryAfter = 0;
        // The account this drain belongs to. A logout during a send must not be
        // followed by a row landing back in the store it just emptied.
        final int epoch = accountEpoch;
        try
        {
            while (connectionState() == ONLINE && epoch == accountEpoch)
            {
                OutgoingMessage[] messages = outgoingStore.list();
                OutgoingMessage next = null;
                long now = System.currentTimeMillis();
                for (int i = 0; i < messages.length; i++)
                {
                    if (messages[i].state != OutgoingMessage.FAILED)
                    {
                        if (messages[i].nextAttemptAt > now)
                        {
                            retryAfter = messages[i].nextAttemptAt - now;
                            break;
                        }
                        next = messages[i];
                        break;
                    }
                }
                if (next == null) { return; }

                next.state = OutgoingMessage.SENDING;
                next.attempts++;
                next.lastError = "";
                if (!saveOutgoing(epoch, next)) { return; }
                notifyOutboxChanged();
                try
                {
                    sendMessage(next.peer(), next.text, next.randomId,
                            next.replyToMessageId);
                    if (!removeOutgoing(epoch, next.localId)) { return; }
                    notifyOutboxChanged();
                }
                catch (RpcError error)
                {
                    int wait = error.floodWaitSeconds();
                    if (wait >= 0)
                    {
                        next.state = OutgoingMessage.QUEUED;
                        next.nextAttemptAt = System.currentTimeMillis()
                                + wait * 1000L;
                        next.lastError = error.getMessage();
                        if (!saveOutgoing(epoch, next)) { return; }
                        notifyOutboxChanged();
                        retryAfter = wait * 1000L;
                        return;
                    }
                    next.state = OutgoingMessage.FAILED;
                    next.lastError = error.getMessage();
                    if (!saveOutgoing(epoch, next)) { return; }
                    notifyOutboxChanged();
                }
                catch (IOException error)
                {
                    next.state = OutgoingMessage.QUEUED;
                    next.lastError = error.getMessage();
                    if (!saveOutgoing(epoch, next)) { return; }
                    notifyOutboxChanged();
                    return;
                }
            }
        }
        catch (Throwable t)
        {
            Diag.error("outbox drain failed", t);
            // The store refused a write or a delete, so what the outbox screen
            // is showing is no longer what is stored. Telling the listener is
            // the whole recovery: nothing was lost - the row is still there -
            // but the user must not be shown "sent" for a message the store
            // would not let go of.
            notifyOutboxChanged();
        }
        finally
        {
            synchronized (outboxLock) { outboxDraining = false; }
            if (retryAfter > 0) { scheduleOutboxDrain(retryAfter); }
        }
    }

    /**
     * Come back to the outbox in {@code delay} milliseconds.
     *
     * The earliest deadline wins and there is only ever one waiter; see
     * {@link DelayedWake}. A later duplicate is dropped rather than pushing the
     * sooner retry back, which is what a FLOOD_WAIT on a second message would
     * otherwise do to the first one.
     */
    private void scheduleOutboxDrain(long delay)
    {
        outboxRetry.schedule(delay);
    }

    /** Milliseconds until the pending outbox retry, or -1. For diagnostics. */
    public long outboxRetryInMs()
    {
        return outboxRetry.pendingMs();
    }

    private void notifyOutboxChanged()
    {
        OutgoingListener listener = outgoingListener;
        if (listener != null)
        {
            try { listener.onOutboxChanged(); }
            catch (Throwable t) { Diag.error("outbox listener failed", t); }
        }
    }

    /** Mark everything up to {@code maxId} as read. */
    public void markRead(Peer peer, int maxId) throws IOException
    {
        if (peer.kind == Peer.CHANNEL)
        {
            invoke(Requests.readChannelHistory(peer, maxId));
        }
        else
        {
            byte[] result = invoke(Requests.readHistory(peer, maxId));
            updates.acceptAffected(result);
        }
        Diag.info("marked read up to " + maxId + " in " + peer);
    }

    public void forwardMessage(Peer from, int messageId, Peer to)
            throws IOException
    {
        if (!peers.isAddressable(from) || !peers.isAddressable(to))
        {
            throw new IOException("cannot address forward source or destination");
        }
        if (messageId <= 0) { throw new IOException("message is not sent yet"); }
        byte[] result = invoke(Requests.forwardMessage(
                from, messageId, to, rng.nextLong()));
        updates.accept(result);
    }

    public void deleteMessage(Peer peer, int messageId, boolean revoke)
            throws IOException
    {
        if (peer == null || messageId <= 0)
        {
            throw new IOException("message is not sent yet");
        }
        byte[] result;
        if (peer.kind == Peer.CHANNEL)
        {
            result = invoke(Requests.deleteChannelMessage(peer, messageId));
        }
        else
        {
            result = invoke(Requests.deleteMessages(messageId, revoke));
        }
        updates.acceptAffected(result);
    }

    public Profile getProfile(Peer user) throws IOException
    {
        if (user == null || user.kind != Peer.USER)
        {
            throw new IOException("profiles are available for users only");
        }
        if (!user.self && !peers.isAddressable(user))
        {
            throw new IOException("cannot address user profile");
        }
        TlObj res = TlParser.parse(new TlReader(
                invoke(Requests.getFullUser(user))));
        if (res == null || res.id != Api.USERS_USER_FULL)
        {
            throw new IOException("unexpected reply to users.getFullUser: "
                    + describe(res));
        }
        return Profile.from(res, user, peers);
    }

    public Profile updateProfile(String firstName, String lastName,
                                 String about) throws IOException
    {
        if (firstName == null || firstName.trim().length() == 0)
        {
            throw new IOException("first name is required");
        }
        if (lastName == null) { lastName = ""; }
        if (about == null) { about = ""; }
        TlObj user = TlParser.parse(new TlReader(invoke(
                Requests.updateProfile(firstName, lastName, about))));
        if (user == null || user.id != Api.USER)
        {
            throw new IOException("unexpected reply to account.updateProfile: "
                    + describe(user));
        }
        peers.put(Peer.fromUser(user));
        return getProfile(peers.self());
    }

    // ------------------------------------------------------------ internal

    /**
     * Invoke exactly once.
     *
     * Once {@link MtClient#invokeWithSaltRetry} has written the encrypted
     * packet, an IOException cannot tell us whether Telegram accepted it and
     * only the reply was lost. Replaying a login or message RPC on another
     * route would therefore be unsafe. Automatic route fallback is confined to
     * {@link #connect(int)}; after a runtime failure the caller may reconnect,
     * but repeating the user operation requires an explicit user action.
     *
     * Salt/time corrections are the sole exception: the server explicitly says
     * it discarded that MTProto envelope, so MtClient can safely rebuild it.
     */
    private synchronized byte[] invoke(byte[] query) throws IOException
    {
        if (client == null)
        {
            // "not connected" is true but useless while the session is
            // deliberately parked: the user sees an error on a chat they just
            // opened and has no way to know a download is holding the only
            // socket, or that waiting would fix it.
            if (mediaParked)
            {
                throw new IOException("busy: downloading from another data "
                        + "centre, the connection returns when it finishes");
            }
            throw new IOException("not connected");
        }
        try
        {
            return client.invokeWithSaltRetry(query);
        }
        catch (IOException e)
        {
            Diag.warn("request failed without replay: " + e.getMessage());
            connectionDiagnostics.failed(connectionConfig.lastSuccessful, e);
            throw e;
        }
    }

    /** Replace our complete ordinary-emoji reaction set on one message. */
    public void sendReactions(Peer peer, int messageId, String[] emoji)
            throws IOException
    {
        if (!peers.isAddressable(peer))
        {
            throw new IOException("cannot address " + peer
                    + " - refresh the dialog list");
        }
        if (messageId <= 0) { throw new IOException("message is not sent yet"); }
        if (emoji != null && emoji.length > 12)
        {
            throw new IOException("too many selected reactions");
        }
        byte[] result = invoke(Requests.sendReactions(peer, messageId, emoji));
        updates.accept(result);
    }

    /**
     * Open a one-shot, separately-sessioned photo stream. No file RPC is made
     * until the returned stream is read.
     */
    public PhotoInputStream openPhoto(PhotoRef photo, int viewportWidth,
                                      int viewportHeight, DownloadToken token)
            throws IOException
    {
        if (photo == null) { throw new IOException("message has no photo"); }
        if (viewportWidth < 1 || viewportHeight < 1)
        {
            throw new IOException("photo viewport is not ready");
        }
        PhotoSizeRef size = photo.choose(viewportWidth, viewportHeight);
        if (size.kind == PhotoSizeRef.CACHED)
        {
            return new PhotoInputStream((PhotoInputStream.Source) null,
                    photo, size, token);
        }
        // The user tapped this one, so parking the session for it is a cost
        // they can see and are waiting through.
        return new PhotoInputStream(openMediaSource(photo.dcId, true),
                photo, size, token);
    }

    /** Open the compact current avatar attached to a dialog peer. */
    public PhotoInputStream openAvatar(Peer peer, DownloadToken token)
            throws IOException
    {
        if (peer == null || peer.avatar == null)
        {
            throw new IOException("peer has no avatar");
        }
        if (peer.kind != Peer.CHAT && peer.accessHash == 0)
        {
            throw new IOException("peer avatar is not addressable");
        }
        // Background work: never worth taking the session down for.
        return new PhotoInputStream(openMediaSource(peer.avatar.dcId, false),
                peer, peer.avatar, token);
    }

    /**
     * True when {@link #openMediaClient} would hand back the live session
     * rather than a connection of its own - in which case the caller must not
     * close it.
     */
    private boolean mediaReusesSession(int targetDc)
    {
        MtClient primary = client;
        return primary != null && targetDc == dcId && primary.isConnected();
    }

    /**
     * A connection to stream a file from, plus how to give it back.
     *
     * Three shapes, and the caller must not have to know which it got:
     * the live session borrowed for a same-DC file, a connection of our own
     * alongside the session, or a connection of our own with the session parked
     * because this device allows only one socket.
     */
    /**
     * @param mayPark true only for a transfer the user explicitly asked for.
     *                Parking drops the session, stops updates, and on a first
     *                visit to a data centre costs a full DH handshake - about
     *                thirty seconds on a 2011 handset. That is a defensible
     *                price for a photo somebody tapped, and not a defensible
     *                price for a decorative thumbnail nobody asked for.
     */
    private PhotoInputStream.Source openMediaSource(int targetDc, boolean mayPark)
            throws IOException
    {
        if (mediaReusesSession(targetDc))
        {
            return new MediaSource(openMediaClient(targetDc), false, false);
        }

        boolean park = connectionConfig.singleSocket && mayPark;
        if (connectionConfig.singleSocket && !mayPark)
        {
            // Deliberately never attempted: on a one-socket device this would
            // either fail on the open or, if allowed to park, take the whole
            // client offline for a background download.
            throw new IOException("file is on dc" + targetDc
                    + "; single socket mode fetches those only on request");
        }
        if (park) { parkSessionForMedia(); }
        try
        {
            return new MediaSource(openMediaClient(targetDc), true, park);
        }
        catch (IOException e)
        {
            // The session must come back even when the transfer never started,
            // or a single failed photo would leave the client offline.
            if (park) { resumeSessionAfterMedia(); }
            throw e;
        }
        catch (RuntimeException e)
        {
            if (park) { resumeSessionAfterMedia(); }
            throw e;
        }
    }

    private final class MediaSource implements PhotoInputStream.Source
    {
        private MtClient media;
        private final boolean owned;
        private final boolean parked;

        MediaSource(MtClient media, boolean owned, boolean parked)
        {
            this.media = media;
            this.owned = owned;
            this.parked = parked;
        }

        public byte[] invoke(byte[] query) throws IOException
        {
            if (media == null) { throw new IOException("media session closed"); }
            return media.invokeWithSaltRetry(query);
        }

        public void close()
        {
            MtClient open = media;
            media = null;
            // Only close what we opened: a borrowed session must outlive the
            // transfer, and closing it here would take the client offline the
            // moment a photo finished downloading.
            if (open != null && owned)
            {
                try { open.close(); } catch (Throwable ignored) { }
            }
            if (parked) { resumeSessionAfterMedia(); }
        }
    }

    /**
     * A connection able to serve upload.getFile for {@code targetDc}.
     *
     * Reuses the live session whenever the file lives on the data centre we are
     * already talking to. That is the overwhelmingly common case, and opening a
     * second connection for it was pure waste: MTProto multiplexes requests
     * over one connection by design, and MtClient already tracks concurrent
     * requests through its waiters table.
     *
     * It was also actively harmful. On a Samsung GT-C3592 the platform refuses
     * a second concurrent socket outright - ConnectionNotFoundException on open
     * - and the attempt desynchronised the connection already in use, so the
     * messages.getHistory running alongside it died and the chat rendered
     * empty. Photos and avatars could never work there at all.
     *
     * A different data centre still needs its own connection, and on such a
     * handset that will still fail; there is no way around holding two at once.
     */
    /**
     * Park the session so a media transfer can have the only socket.
     *
     * Built on the same lifecycle as {@link #pause}: clearing {@code foreground}
     * is what stops {@code startReconnect} from racing us back onto the network
     * while the media connection holds the one socket this device allows.
     */
    private void parkSessionForMedia()
    {
        synchronized (lifecycleLock)
        {
            foreground = false;
            reconnectToken++;
            lifecycleLock.notifyAll();
        }
        MtClient old = client;
        client = null;
        if (old != null) { old.close(); }
        updates.offline();
        connectionDiagnostics.closed();
        mediaParked = true;
        setConnectionState(PAUSED, 0, "parked for a media transfer");
        Diag.info("session parked: file is on another dc and single-socket "
                + "mode is on");
    }

    /** Bring the session back after a parked transfer, however it ended. */
    private void resumeSessionAfterMedia()
    {
        synchronized (lifecycleLock)
        {
            foreground = true;
            reconnectToken++;
            lifecycleLock.notifyAll();
        }
        mediaParked = false;
        Diag.info("media transfer finished, restoring the session");
        if (everConnected) { startReconnect(true); }
        else { setConnectionState(IDLE, 0, "press Connect"); }
    }

    private MtClient openMediaClient(int targetDc) throws IOException
    {
        if (targetDc < 1) { throw new IOException("photo has invalid dc"); }

        if (mediaReusesSession(targetDc))
        {
            Diag.info("media over the existing dc" + targetDc + " session");
            return client;
        }

        IOException last = null;
        int[] attempts = connectionConfig.attempts();
        for (int i = 0; i < attempts.length; i++)
        {
            MtClient candidate = null;
            try
            {
                int mode = attempts[i];
                DcEndpoint endpoint = dcDirectory.endpoint(targetDc, true);
                LinkSpec spec = links.create(mode, targetDc, endpoint,
                        connectionConfig, rng);
                candidate = new MtClient(spec.link, rng);
                candidate.connect(targetDc, spec.host, spec.port, 30000, spec.media);
                MtClient primary = client;
                AuthKeyLoad persisted = store.load(targetDc, Dc.isTest());
                if (persisted.isCorrupt() || persisted.isIoError())
                {
                    // Media is decorative; a fresh key for the file DC costs a
                    // handshake and nothing else. Still say which it was.
                    Diag.warn("stored media key for dc" + targetDc
                              + " unusable (" + persisted.describe() + ")");
                }
                AuthKey key = MediaAuthorization.select(dcId, targetDc,
                        primary == null ? null : primary.authKey(),
                        persisted.key);
                if (key == null)
                {
                    key = candidate.authenticate();
                    store.save(key);
                }
                else
                {
                    candidate.resume(key, 0);
                }

                if (targetDc != dcId)
                {
                    String markerName = MediaAuthorization.markerName(
                            targetDc, Dc.isTest());
                    String marker = store.loadString(markerName);
                    if (MediaAuthorization.needsImport(dcId, targetDc,
                            key, marker))
                    {
                        byte[] exportedRaw = invoke(
                                Requests.exportAuthorization(targetDc));
                        TlObj exported = TlParser.parse(new TlReader(exportedRaw));
                        if (exported == null
                                || exported.id != Api.AUTH_EXPORTED_AUTHORIZATION)
                        {
                            throw new IOException("unexpected auth export result");
                        }
                        byte[] importedRaw = candidate.invokeWithSaltRetry(
                                Requests.importAuthorization(
                                        exported.num(
                                                Api.F_AUTH_EXPORTED_AUTHORIZATION__ID),
                                        exported.bytes(
                                                Api.F_AUTH_EXPORTED_AUTHORIZATION__BYTES)));
                        TlObj imported = TlParser.parse(new TlReader(importedRaw));
                        if (imported == null || imported.id != Api.AUTH_AUTHORIZATION)
                        {
                            throw new IOException("unexpected auth import result");
                        }
                        store.saveString(markerName, String.valueOf(key.keyId()));
                    }
                }
                return candidate;
            }
            catch (SecurityException e)
            {
                last = new IOException("media route denied: " + e.getMessage());
                if (candidate != null) { candidate.close(); }
            }
            catch (IOException e)
            {
                last = e;
                if (candidate != null) { candidate.close(); }
            }
        }
        throw last == null ? new IOException("no media connection routes") : last;
    }

    private static Message findMessage(TlObj[] messages, int id, Peer peer)
    {
        for (int i = 0; i < messages.length; i++)
        {
            TlObj m = messages[i];
            if (m == null)
            {
                continue;
            }
            int msgId = messageId(m);
            if (msgId == id)
            {
                return Message.from(m, null);
            }
        }
        return null;
    }

    private static int messageId(TlObj m)
    {
        if (m.id == Api.MESSAGE)
        {
            return m.intAt(Api.F_MESSAGE__ID);
        }
        if (m.id == Api.MESSAGE_SERVICE)
        {
            return m.intAt(Api.F_MESSAGE_SERVICE__ID);
        }
        return -1;
    }

    private static String describe(TlObj obj)
    {
        return obj == null ? "null" : ("0x" + Integer.toHexString(obj.id));
    }

    private int lastSentCodeType;
    private int lastSentCodeLength;

    /** Number of digits the server expects, or 0 when it did not say. */
    public int lastSentCodeLength()
    {
        return lastSentCodeLength;
    }

    /** Constructor id of the auth.SentCodeType from the last {@link #sendCode}. */
    public int lastSentCodeType()
    {
        return lastSentCodeType;
    }

    public String lastSentCodeTypeName()
    {
        return describeSentCodeType(lastSentCodeType);
    }

    private static String describeSentCodeType(int id)
    {
        if (id == Api.AUTH_SENT_CODE_TYPE_APP)          { return "the Telegram app"; }
        if (id == Api.AUTH_SENT_CODE_TYPE_SMS)          { return "SMS"; }
        if (id == Api.AUTH_SENT_CODE_TYPE_CALL)         { return "a phone call"; }
        if (id == Api.AUTH_SENT_CODE_TYPE_FLASH_CALL)   { return "a flash call"; }
        if (id == Api.AUTH_SENT_CODE_TYPE_MISSED_CALL)  { return "a missed call"; }
        if (id == Api.AUTH_SENT_CODE_TYPE_EMAIL_CODE)   { return "email"; }
        if (id == Api.AUTH_SENT_CODE_TYPE_FRAGMENT_SMS) { return "Fragment SMS"; }
        return "0x" + Integer.toHexString(id);
    }
}
