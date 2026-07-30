package tgtest;

import java.security.MessageDigest;

import tg.crypto.HmacSha512;
import tg.crypto.Pbkdf2;
import tg.crypto.Sha512;

/** SHA-512, HMAC and PBKDF2 vectors. */
public final class Sha512Test implements Test
{
    public String name() { return "crypto/sha512-pbkdf2"; }

    public void run() throws Exception
    {
        Assert.bytesEqual("SHA-512 empty",
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce"
          + "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            Sha512.hash(new byte[0]));
        Assert.bytesEqual("SHA-512 abc",
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
          + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Sha512.hash(Assert.ascii("abc")));
        Assert.bytesEqual("HMAC-SHA512 RFC4231",
            "87aa7cdea5ef619d4ff0b4241a1d6cb02379f4e2ce4ec2787ad0b30545e17cde"
          + "daa833b7d6b8a702038b274eaea3f4e4be9d914eeb61f1702e696c203a126854",
            HmacSha512.compute(Assert.repeat((byte)0x0b,20), Assert.ascii("Hi There")));
        Assert.bytesEqual("PBKDF2-HMAC-SHA512 iter1",
            "867f70cf1ade02cff3752599a3a53dc4af34c7a669815ae5d513554e1c8cf252"
          + "c02d470a285a0501bad999bfe943c08f050235d7d68b1da55e63f73b60a57fce",
            Pbkdf2.derive(Assert.ascii("password"), Assert.ascii("salt"), 1, 64));

        byte[] longData = new byte[777];
        for (int i=0;i<longData.length;i++) { longData[i]=(byte)i; }
        Assert.bytesEqual("SHA-512 differential",
                MessageDigest.getInstance("SHA-512").digest(longData),
                Sha512.hash(longData));
    }
}
