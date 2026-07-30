package tg.tl;

import java.io.IOException;

import tg.api.TlSchema;
import tg.io.Inflate;

/**
 * Table-driven TL reader.
 *
 * TL is not self-describing: without knowing a constructor's field layout there
 * is no way to tell where it ends, so a client cannot skip a type it does not
 * understand. That is why the generated {@link TlSchema} covers the whole
 * transitive closure of what we might receive rather than only the types we
 * read - a Photo inside a Message has to be walked even though the MVP ignores
 * it.
 *
 * <h3>Field descriptor encoding</h3>
 * Written by tools/generate-tl.py, consumed here:
 * <pre>
 *   idHigh idLow fieldCount   then per field: kind, condition, [elementKind]
 * </pre>
 * A condition of 0 means the field is unconditional. Otherwise
 * {@code condition - 1} packs {@code slot * 32 + bit}: the field is present
 * only when {@code bit} is set in the constructor's {@code slot}-th flags field.
 *
 * The slot is not decoration. Five constructors - user, message, channel,
 * userFull and channelFull - declare both {@code flags} and {@code flags2}, and
 * their later fields are conditional on the second one. Testing those bits
 * against the first flags field desynchronises the parse, which surfaces much
 * later as a nonsensical length in some unrelated field.
 *
 * <h3>Bounds</h3>
 * Everything parsed here came off a socket. Depth is capped so a crafted
 * message cannot drive the stack into a StackOverflowError, and every length
 * is validated by {@link TlReader} before it allocates.
 */
public final class TlParser
{
    // Kinds, matching tools/generate-tl.py.
    private static final int K_INT = 1;
    private static final int K_LONG = 2;
    private static final int K_INT128 = 3;
    private static final int K_INT256 = 4;
    private static final int K_DOUBLE = 5;
    private static final int K_BYTES = 6;
    private static final int K_BOOL = 7;
    private static final int K_OBJECT = 8;
    private static final int K_FLAGS = 9;
    private static final int K_TRUE = 10;
    private static final int K_VECTOR = 11;
    private static final int K_BARE_VECTOR = 12;
    private static final int K_STRING = 13;

    /**
     * TL nests shallowly in practice - a dialog list is maybe six deep. A limit
     * well above that still stops a hostile stream from exhausting the stack,
     * which on a handset would take the MIDlet down.
     */
    private static final int MAX_DEPTH = 32;

    /** Refuse to build a vector larger than this regardless of what is claimed. */
    private static final int MAX_VECTOR = 100000;

    /** Flags fields per constructor. The schema uses at most two today. */
    private static final int MAX_FLAGS_SLOTS = 4;

    private TlParser() { }

    /**
     * Read one boxed object.
     *
     * @return the parsed object, or null for TL {@code null} / an empty vector
     *         element
     */
    public static TlObj parse(TlReader r) throws IOException
    {
        return parse(r, 0);
    }

    private static TlObj parse(TlReader r, int depth) throws IOException
    {
        if (depth > MAX_DEPTH)
        {
            throw new TlException("TL nesting deeper than " + MAX_DEPTH);
        }

        int id = r.readInt();

        // gzip_packed can appear anywhere an object can, not only at the top of
        // an rpc_result.
        if (id == Tl.GZIP_PACKED)
        {
            byte[] inflated = Inflate.gunzip(r.readBytes());
            return parse(new TlReader(inflated), depth + 1);
        }

        if (id == Tl.VECTOR)
        {
            // A bare vector where an object was expected: wrap it so callers
            // get something uniform.
            TlObj wrapper = new TlObj(Tl.VECTOR, 1);
            wrapper.setRef(0, readObjectVector(r, depth));
            return wrapper;
        }

        if (id == Tl.BOOL_TRUE || id == Tl.BOOL_FALSE)
        {
            TlObj b = new TlObj(id, 1);
            b.nums[0] = (id == Tl.BOOL_TRUE) ? 1 : 0;
            return b;
        }

        if (id == Tl.NULL)
        {
            return null;
        }

        int off = TlSchema.offsetOf(id);
        if (off < 0)
        {
            throw new TlException("unknown constructor 0x" + Integer.toHexString(id)
                                  + " - not in the generated schema closure. "
                                  + "Add its type to config/tl-whitelist.txt "
                                  + "and regenerate.");
        }

        char[] table = TlSchema.table();
        int pos = off + 2;
        int fieldCount = table[pos++];

        TlObj obj = new TlObj(id, fieldCount);

        // A constructor's flags fields, in declaration order. TL guarantees a
        // flags field appears before anything conditional on it, so these are
        // always populated by the time they are consulted.
        int[] flagsBySlot = new int[MAX_FLAGS_SLOTS];
        int flagsSeen = 0;

        for (int f = 0; f < fieldCount; f++)
        {
            int kind = table[pos++];
            int cond = table[pos++];
            int elemKind = 0;
            if (kind == K_VECTOR || kind == K_BARE_VECTOR)
            {
                elemKind = table[pos++];
            }

            if (cond != 0)
            {
                int packed = cond - 1;
                int slot = packed >> 5;
                int bit = packed & 31;
                if (slot >= flagsSeen)
                {
                    throw new TlException("constructor 0x" + Integer.toHexString(id)
                                          + " field " + f + " is conditional on flags slot "
                                          + slot + " but only " + flagsSeen
                                          + " flags field(s) have been read");
                }
                if ((flagsBySlot[slot] & (1 << bit)) == 0)
                {
                    continue;                   // conditional field is absent
                }
            }

            switch (kind)
            {
                case K_INT:
                    obj.nums[f] = r.readInt();
                    break;

                case K_FLAGS:
                {
                    int value = r.readInt();
                    if (flagsSeen < MAX_FLAGS_SLOTS)
                    {
                        flagsBySlot[flagsSeen++] = value;
                    }
                    else
                    {
                        throw new TlException("constructor 0x" + Integer.toHexString(id)
                                              + " has more than " + MAX_FLAGS_SLOTS
                                              + " flags fields");
                    }
                    if (!obj.hasFlags)
                    {
                        obj.flags = value;      // the first one, for TlObj.flag()
                        obj.hasFlags = true;
                    }
                    obj.nums[f] = value;
                    break;
                }

                case K_LONG:
                    obj.nums[f] = r.readLong();
                    break;

                case K_DOUBLE:
                    obj.nums[f] = r.readLong();  // kept as raw bits
                    break;

                case K_INT128:
                    obj.setRef(f, r.readRaw(16));
                    break;

                case K_INT256:
                    obj.setRef(f, r.readRaw(32));
                    break;

                case K_BYTES:
                    obj.setRef(f, r.readBytes());
                    break;

                case K_STRING:
                    obj.setRef(f, r.readString());
                    break;

                case K_BOOL:
                    obj.nums[f] = r.readBool() ? 1 : 0;
                    break;

                case K_TRUE:
                    // Present-by-flag only; occupies no bytes on the wire.
                    obj.nums[f] = 1;
                    break;

                case K_OBJECT:
                    obj.setRef(f, parse(r, depth + 1));
                    break;

                case K_VECTOR:
                    obj.setRef(f, readVector(r, elemKind, depth, true));
                    break;

                case K_BARE_VECTOR:
                    obj.setRef(f, readVector(r, elemKind, depth, false));
                    break;

                default:
                    throw new TlException("unknown field kind " + kind
                                          + " in constructor 0x" + Integer.toHexString(id));
            }
        }
        return obj;
    }

    private static Object readVector(TlReader r, int elemKind, int depth, boolean boxed)
            throws IOException
    {
        int count = boxed ? r.readVectorCount() : r.readVectorCountBare();
        if (count > MAX_VECTOR)
        {
            throw new TlException("vector of " + count + " elements refused");
        }

        switch (elemKind)
        {
            case K_INT:
            {
                long[] out = new long[count];
                for (int i = 0; i < count; i++) { out[i] = r.readInt(); }
                return out;
            }
            case K_LONG:
            {
                long[] out = new long[count];
                for (int i = 0; i < count; i++) { out[i] = r.readLong(); }
                return out;
            }
            case K_BYTES:
            {
                byte[][] out = new byte[count][];
                for (int i = 0; i < count; i++) { out[i] = r.readBytes(); }
                return out;
            }
            case K_STRING:
            {
                String[] out = new String[count];
                for (int i = 0; i < count; i++) { out[i] = r.readString(); }
                return out;
            }
            default:
            {
                TlObj[] out = new TlObj[count];
                for (int i = 0; i < count; i++)
                {
                    out[i] = parse(r, depth + 1);
                }
                return out;
            }
        }
    }

    private static TlObj[] readObjectVector(TlReader r, int depth) throws IOException
    {
        int count = r.readVectorCountBare();
        if (count > MAX_VECTOR)
        {
            throw new TlException("vector of " + count + " elements refused");
        }
        TlObj[] out = new TlObj[count];
        for (int i = 0; i < count; i++)
        {
            out[i] = parse(r, depth + 1);
        }
        return out;
    }

    /**
     * Parse a complete response buffer, unwrapping a top-level vector.
     *
     * Some methods return {@code Vector<T>} directly - users.getUsers is the
     * one the MVP needs - and that arrives as a bare vector rather than a
     * constructor.
     */
    public static TlObj[] parseVector(byte[] data) throws IOException
    {
        TlReader r = new TlReader(data);
        int id = r.peekInt();
        if (id == Tl.VECTOR)
        {
            r.readInt();
            return readObjectVector(r, 0);
        }
        return new TlObj[] { parse(r) };
    }
}
