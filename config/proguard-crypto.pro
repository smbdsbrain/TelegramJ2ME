# Entry points for dist/crypto.jar - crypto verification and benchmarks on the
# device.
#
# Installed after probe.jar has shown the handset runs our JARs at all. This one
# necessarily carries the whole crypto stack, including the ported BigInteger,
# so it is the first build whose size is worth watching.

-keep public class tg.app.CryptoMidlet {
    public <init>();
    protected void startApp();
    protected void pauseApp();
    protected void destroyApp(boolean);
}

# SelfTest is reached only from the MIDlet, but keeping it explicitly means a
# vector cannot be shrunk away by an -optimizationpasses run and silently stop
# being verified on hardware.
-keep class tg.crypto.SelfTest {
    public static *;
}
