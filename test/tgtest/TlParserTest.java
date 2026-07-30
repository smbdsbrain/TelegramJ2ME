package tgtest;

import tg.api.Api;
import tg.tl.TlObj;
import tg.tl.TlParser;
import tg.tl.TlReader;
import tg.tl.TlWriter;

/**
 * Table-driven parsing, with the emphasis on constructors that carry more than
 * one flags field.
 *
 * <h3>The bug this exists to prevent</h3>
 * Five constructors - user, message, channel, userFull, channelFull - declare
 * both {@code flags} and {@code flags2}, and their later fields are conditional
 * on one or the other. The first version of the generator recorded only the bit
 * number, so {@code flags2.12?int} was tested against bit 12 of {@code flags},
 * and the parser kept only one flags value, so every {@code flags.N?} field
 * declared after {@code flags2} was tested against {@code flags2}.
 *
 * The result was not a clean failure. Parsing silently took the wrong branch,
 * consumed the wrong number of bytes, and surfaced far away as
 * "bytes length N exceeds the M bytes remaining" in an unrelated field - which
 * is exactly what a real sign-in produced, because auth.authorization contains
 * a User.
 *
 * So the test builds a user whose two flag sets deliberately disagree: a bit
 * set in one is clear in the other, at every position that matters. Any
 * confusion between them changes the answer.
 */
public final class TlParserTest implements Test
{
    public String name()
    {
        return "tl/parser-multiple-flags";
    }

    public void run() throws Exception
    {
        userWithBothFlagSets();
        absentOptionalFieldsAreNotRead();
    }

    private void userWithBothFlagSets() throws Exception
    {
        // flags:  access_hash(0), first_name(1), username(3), self(10)
        //         deliberately NOT last_name(2), and nothing at 12
        int flags = (1 << 0) | (1 << 1) | (1 << 3) | (1 << 10);
        // flags2: close_friend(2), bot_active_users(12)
        //         bit 2 is clear in `flags`, bit 12 is clear in `flags` too,
        //         so mixing the two changes which fields are read.
        int flags2 = (1 << 2) | (1 << 12);

        TlWriter w = new TlWriter(128);
        w.writeInt(Api.USER);
        w.writeInt(flags);
        w.writeInt(flags2);
        w.writeLong(1234567890123L);         // id
        w.writeLong(-8877665544332211L);     // access_hash    flags.0
        w.writeString("Test");               // first_name     flags.1
        w.writeString("tester");             // username       flags.3
        w.writeInt(42);                      // bot_active_users flags2.12

        byte[] wire = w.toByteArray();
        TlObj user = TlParser.parse(new TlReader(wire));

        Assert.isTrue("parsed a user", user != null);
        Assert.equal("constructor", Api.USER, user.id);

        Assert.equal("id", 1234567890123L, user.num(Api.F_USER__ID));
        Assert.equal("access_hash (flags.0)",
                -8877665544332211L, user.num(Api.F_USER__ACCESS_HASH));
        Assert.equal("first_name (flags.1)", "Test", user.str(Api.F_USER__FIRST_NAME));
        Assert.equal("username (flags.3)", "tester", user.str(Api.F_USER__USERNAME));

        // Declared before flags2, conditional on flags.10 - the value that a
        // single shared flags variable would have clobbered.
        Assert.equal("self (flags.10)", 1L, user.num(Api.F_USER__SELF));

        // Conditional on flags2, at bit positions that are clear in flags.
        Assert.equal("close_friend (flags2.2)", 1L, user.num(Api.F_USER__CLOSE_FRIEND));
        Assert.equal("bot_active_users (flags2.12)",
                42L, user.num(Api.F_USER__BOT_ACTIVE_USERS));

        // last_name's bit is clear, so it must be absent rather than having
        // eaten the username's bytes.
        Assert.isTrue("last_name absent (flags.2 clear)",
                user.str(Api.F_USER__LAST_NAME) == null);

        // And the whole thing must have consumed exactly the bytes written -
        // the real symptom of getting this wrong is a length mismatch later.
        TlReader r = new TlReader(wire);
        TlParser.parse(r);
        Assert.equal("consumed the whole object", 0, r.remaining());
    }

    /**
     * A user with no optional fields at all: proves that a cleared bit really
     * means "read nothing" rather than "read a zero".
     */
    private void absentOptionalFieldsAreNotRead() throws Exception
    {
        TlWriter w = new TlWriter(64);
        w.writeInt(Api.USER);
        w.writeInt(0);                       // flags: nothing set
        w.writeInt(0);                       // flags2: nothing set
        w.writeLong(555L);                   // id is unconditional
        w.writeInt(0x7fffffff);              // a sentinel that must NOT be read

        TlReader r = new TlReader(w.toByteArray());
        TlObj user = TlParser.parse(r);

        Assert.equal("id", 555L, user.num(Api.F_USER__ID));
        Assert.equal("access_hash absent", 0L, user.num(Api.F_USER__ACCESS_HASH));
        Assert.isTrue("first_name absent", user.str(Api.F_USER__FIRST_NAME) == null);
        Assert.equal("sentinel left unconsumed", 4, r.remaining());
    }
}
