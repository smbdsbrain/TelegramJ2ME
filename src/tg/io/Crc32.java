package tg.io;

/**
 * CRC-32, the one from PKZIP and gzip.
 *
 * CLDC 1.1 has no {@code java.util.zip}, so this is here rather than imported.
 *
 * <h3>What it is for and what it is not</h3>
 * It detects accidental damage: a torn write, a flipped bit, a record read back
 * short. That is the whole failure model a feature phone's flash presents, and
 * a checksum is what turns "this record decoded into something implausible"
 * into "this record is damaged" before anything acts on it.
 *
 * It is <em>not</em> tamper detection. Anyone who can edit an RMS record can
 * recompute the checksum in the same edit, and RMS on these handsets offers no
 * encryption and no access control beyond "other MIDlet suites cannot read it".
 * Nothing here changes that; see docs/architecture.md.
 *
 * <h3>Table-free</h3>
 * The usual 256-entry table costs 1 KB of heap and the bytecode to build it, on
 * a device where the whole budget is single-digit megabytes and every class
 * counts against the JAR. This is the branch-free bit-at-a-time form: eight
 * shifts per byte, no table, and the records it covers are a few hundred bytes.
 */
public final class Crc32
{
    /** The reversed polynomial, as everything else in this family uses it. */
    private static final int POLYNOMIAL = 0xEDB88320;

    private Crc32() { }

    public static int of(byte[] data)
    {
        return of(data, 0, data == null ? 0 : data.length);
    }

    public static int of(byte[] data, int offset, int length)
    {
        int crc = 0xFFFFFFFF;
        for (int i = 0; i < length; i++)
        {
            crc ^= data[offset + i] & 0xFF;
            for (int bit = 0; bit < 8; bit++)
            {
                // >>> on purpose: the register is unsigned, and >> would drag
                // the sign bit in and quietly produce a different checksum for
                // every input whose intermediate crc goes negative.
                crc = (crc >>> 1) ^ ((crc & 1) != 0 ? POLYNOMIAL : 0);
            }
        }
        return crc ^ 0xFFFFFFFF;
    }
}
