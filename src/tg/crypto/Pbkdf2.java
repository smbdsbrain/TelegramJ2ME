package tg.crypto;

/** PBKDF2-HMAC-SHA512 used by Telegram's cloud-password KDF benchmark. */
public final class Pbkdf2
{
    public interface Progress
    {
        /** Return false to cancel. */
        boolean update(int completed, int total);
    }

    private Pbkdf2() { }

    public static byte[] derive(byte[] password, byte[] salt, int iterations, int length)
    {
        return derive(password, salt, iterations, length, null);
    }

    public static byte[] derive(byte[] password, byte[] salt, int iterations, int length,
                                Progress progress)
    {
        if (iterations < 1 || length < 1)
        {
            throw new IllegalArgumentException("invalid PBKDF2 parameters");
        }
        HmacSha512 hmac = new HmacSha512(password);
        byte[] out = new byte[length];
        byte[] input = new byte[salt.length + 4];
        System.arraycopy(salt, 0, input, 0, salt.length);
        byte[] u = new byte[64];
        byte[] t = new byte[64];
        int blocks = (length + 63) / 64;
        int done = 0;
        int total = blocks * iterations;
        for (int block = 1; block <= blocks; block++)
        {
            int p = salt.length;
            input[p]=(byte)(block>>>24);input[p+1]=(byte)(block>>>16);
            input[p+2]=(byte)(block>>>8);input[p+3]=(byte)block;
            hmac.compute(input, 0, input.length, u, 0);
            System.arraycopy(u, 0, t, 0, 64);
            done++;
            for (int i = 1; i < iterations; i++)
            {
                hmac.compute(u, 0, u.length, u, 0);
                for (int j = 0; j < 64; j++) { t[j] ^= u[j]; }
                done++;
                if (progress != null && (done % 1000) == 0
                        && !progress.update(done, total))
                {
                    throw new IllegalStateException("PBKDF2 cancelled");
                }
            }
            int n = Math.min(64, length - (block - 1) * 64);
            System.arraycopy(t, 0, out, (block - 1) * 64, n);
        }
        if (progress != null) { progress.update(total, total); }
        return out;
    }
}
