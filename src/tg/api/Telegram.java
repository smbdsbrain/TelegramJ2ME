package tg.api;

import java.io.IOException;

import tg.crypto.Pbkdf2;
import tg.crypto.Rng;
import tg.diag.Diag;
import tg.io.Transport;
import tg.mt.AuthKey;
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
    private final Object outboxLock = new Object();

    private MtClient client;
    private OutgoingStore outgoingStore;
    private OutgoingListener outgoingListener;
    private boolean outboxDraining;
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
        outgoingStore = store;
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

                AuthKey stored = store.load(dc, Dc.isTest());
                if (stored != null)
                {
                    candidate.resume(stored, 0);
                    Diag.info("resumed with stored key for dc" + dc);
                }
                else
                {
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
                Diag.warn("stored session is no longer valid: " + e.getMessage());
                store.clear(dcId, Dc.isTest());
                store.saveString("authorized", null);
                updates.close();
                updates.deactivate();
                authorized = false;
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

    public void logOut() throws IOException
    {
        try
        {
            invoke(Requests.logOut());
        }
        finally
        {
            store.clear(dcId, Dc.isTest());
            store.saveString("authorized", null);
            peers.clear();
            cachedAvailableReactions = null;
            authorized = false;
            updates.close();
            updates.deactivate();
            if (outgoingStore != null) { outgoingStore.clear(); }
            notifyOutboxChanged();
        }
    }

    /**
     * End every server-side session, including this one.
     *
     * auth.resetAuthorizations deliberately preserves the caller, so the
     * ordinary local logout must follow it to make "everywhere" literal.
     */
    public void logOutEverywhere() throws IOException
    {
        requireTrue(invoke(Requests.resetAuthorizations()),
                    "auth.resetAuthorizations");
        logOut();
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

    private void drainOutbox()
    {
        long retryAfter = 0;
        try
        {
            while (connectionState() == ONLINE)
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
                outgoingStore.save(next);
                notifyOutboxChanged();
                try
                {
                    sendMessage(next.peer(), next.text, next.randomId,
                            next.replyToMessageId);
                    outgoingStore.remove(next.localId);
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
                        outgoingStore.save(next);
                        notifyOutboxChanged();
                        retryAfter = wait * 1000L;
                        return;
                    }
                    next.state = OutgoingMessage.FAILED;
                    next.lastError = error.getMessage();
                    outgoingStore.save(next);
                    notifyOutboxChanged();
                }
                catch (IOException error)
                {
                    next.state = OutgoingMessage.QUEUED;
                    next.lastError = error.getMessage();
                    outgoingStore.save(next);
                    notifyOutboxChanged();
                    return;
                }
            }
        }
        catch (Throwable t)
        {
            Diag.error("outbox drain failed", t);
        }
        finally
        {
            synchronized (outboxLock) { outboxDraining = false; }
            if (retryAfter > 0) { scheduleOutboxDrain(retryAfter); }
        }
    }

    private void scheduleOutboxDrain(final long delay)
    {
        new Thread(new Runnable()
        {
            public void run()
            {
                try { Thread.sleep(delay); }
                catch (InterruptedException ignored) { }
                startOutboxDrain();
            }
        }).start();
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
                AuthKey persisted = store.load(targetDc, Dc.isTest());
                AuthKey key = MediaAuthorization.select(dcId, targetDc,
                        primary == null ? null : primary.authKey(), persisted);
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
