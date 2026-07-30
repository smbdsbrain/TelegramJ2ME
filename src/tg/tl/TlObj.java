package tg.tl;

/**
 * A parsed TL object, held generically.
 *
 * The alternative - a Java class per constructor - would mean well over a
 * thousand classes for the schema closure this client needs. On a handset that
 * is a large JAR, slow class loading and a lot of duplicated constant pool, in
 * exchange for type safety we cannot use anyway because most of those types are
 * only ever skipped past.
 *
 * So fields are addressed by index. The indices are not magic numbers: the code
 * generator emits named constants for every field the client actually reads, so
 * a schema change that reorders a constructor breaks the build rather than
 * silently reading the wrong field.
 *
 * Scalars live in {@link #nums} and everything else in {@link #refs}, both
 * indexed by field position. Two arrays per object is more allocation than a
 * purpose-built class, but it is bounded and predictable, which matters more.
 */
public final class TlObj
{
    /** Constructor id, as in tg.api.Api. */
    public int id;

    /** int, long, Bool and flags values, by field index. */
    public long[] nums;

    /** String, byte[], TlObj and TlObj[] values, by field index. Lazily created. */
    public Object[] refs;

    /** Value of the {@code flags} field, if the constructor has one. */
    public int flags;

    /** True when the constructor declares a flags field. */
    public boolean hasFlags;

    public TlObj(int id, int fieldCount)
    {
        this.id = id;
        this.nums = new long[fieldCount];
    }

    public long num(int field)
    {
        return nums[field];
    }

    public int intAt(int field)
    {
        return (int) nums[field];
    }

    public boolean boolAt(int field)
    {
        return nums[field] != 0;
    }

    public Object ref(int field)
    {
        return refs == null ? null : refs[field];
    }

    public String str(int field)
    {
        Object o = ref(field);
        return o instanceof String ? (String) o : null;
    }

    /** Never null - an absent string reads as empty, which is what callers want. */
    public String strOrEmpty(int field)
    {
        String s = str(field);
        return s == null ? "" : s;
    }

    public byte[] bytes(int field)
    {
        Object o = ref(field);
        return o instanceof byte[] ? (byte[]) o : null;
    }

    public TlObj obj(int field)
    {
        Object o = ref(field);
        return o instanceof TlObj ? (TlObj) o : null;
    }

    public TlObj[] vec(int field)
    {
        Object o = ref(field);
        return o instanceof TlObj[] ? (TlObj[]) o : EMPTY;
    }

    public long[] longVec(int field)
    {
        Object o = ref(field);
        return o instanceof long[] ? (long[]) o : new long[0];
    }

    /** True when the optional field at {@code bit} is present. */
    public boolean flag(int bit)
    {
        return (flags & (1 << bit)) != 0;
    }

    void setRef(int field, Object value)
    {
        if (refs == null)
        {
            refs = new Object[nums.length];
        }
        refs[field] = value;
    }

    private static final TlObj[] EMPTY = new TlObj[0];

    public String toString()
    {
        return "TlObj(0x" + Integer.toHexString(id) + ", " + nums.length + " fields)";
    }
}
