package tg.mt;

import java.io.IOException;

/**
 * An {@code rpc_error} returned by Telegram.
 *
 * These are not transport failures - the request arrived, was understood, and
 * was refused. Several of them are ordinary control flow rather than problems:
 *
 * <ul>
 *   <li>{@code 303 PHONE_MIGRATE_X} / {@code NETWORK_MIGRATE_X} - this account
 *       lives on a different data centre; reconnect there and redo the
 *       handshake. Unavoidable during login, since the client cannot know which
 *       DC owns a phone number until it asks.</li>
 *   <li>{@code 420 FLOOD_WAIT_X} - wait X seconds. On a legacy client that
 *       reconnects often this is a normal thing to encounter, and the number
 *       matters, so it is parsed out.</li>
 *   <li>{@code 401 AUTH_KEY_UNREGISTERED} / {@code SESSION_PASSWORD_NEEDED} -
 *       authorization state, handled by the login flow.</li>
 * </ul>
 *
 * The numeric suffix carried by MIGRATE and FLOOD_WAIT is extracted here so
 * callers do not each reimplement the parsing.
 */
public class RpcError extends IOException
{
    private final int code;
    private final String type;

    public RpcError(int code, String type)
    {
        super(code + " " + type);
        this.code = code;
        this.type = type;
    }

    public int code()
    {
        return code;
    }

    public String type()
    {
        return type;
    }

    /** True for the errors that name another data centre. */
    public boolean isMigrate()
    {
        return code == 303 && migrateDc() > 0;
    }

    /**
     * The data centre named by a MIGRATE error, or -1.
     *
     * Covers PHONE_MIGRATE_X, NETWORK_MIGRATE_X, USER_MIGRATE_X and
     * FILE_MIGRATE_X, which differ only in which operation triggered them.
     */
    public int migrateDc()
    {
        return suffixAfter("_MIGRATE_");
    }

    /** Seconds to wait for FLOOD_WAIT_X, or -1. */
    public int floodWaitSeconds()
    {
        if (code != 420)
        {
            return -1;
        }
        return suffixAfter("FLOOD_WAIT_");
    }

    public boolean isFloodWait()
    {
        return floodWaitSeconds() >= 0;
    }

    /** The account has 2FA enabled and auth.checkPassword is required. */
    public boolean isPasswordNeeded()
    {
        return "SESSION_PASSWORD_NEEDED".equals(type);
    }

    /**
     * The stored auth_key must be thrown away and a new handshake run.
     *
     * Deliberately excludes AUTH_KEY_UNREGISTERED. That error means the key is
     * valid but no user account is attached to it - which is the normal state
     * of a freshly generated key, before sign-in. Treating it as "invalid"
     * discards a perfectly good key and forces another two 2048-bit modular
     * exponentiations, one of the most expensive operations on constrained
     * hardware. The same key is what auth.sendCode and auth.signIn are sent over.
     */
    public boolean isAuthKeyInvalid()
    {
        return "AUTH_KEY_INVALID".equals(type)
            || "SESSION_REVOKED".equals(type)
            || "SESSION_EXPIRED".equals(type)
            || "USER_DEACTIVATED".equals(type);
    }

    /** The key is fine, there is just nobody signed in on it yet. */
    public boolean isNotSignedIn()
    {
        return "AUTH_KEY_UNREGISTERED".equals(type);
    }

    private int suffixAfter(String marker)
    {
        if (type == null)
        {
            return -1;
        }
        int at = type.indexOf(marker);
        if (at < 0)
        {
            return -1;
        }
        int from = at + marker.length();
        int to = from;
        while (to < type.length() && type.charAt(to) >= '0' && type.charAt(to) <= '9')
        {
            to++;
        }
        if (to == from)
        {
            return -1;
        }
        try
        {
            return Integer.parseInt(type.substring(from, to));
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }
}
