# Entry points for dist/probe.jar - the hardware-reconnaissance suite.
#
# This used to keep the Telegram stack out entirely, on the argument that the
# first thing installed on an unknown 2011 handset should be as small and as
# boring as possible. The crypto vectors, the modPow benchmark and the seeding
# barrier were a second MIDlet for the same reason, and a device session
# therefore meant installing two suites and uploading two sets of reports. They
# are one suite now: this JAR reaches the crypto stack including the ported
# BigInteger, and is correspondingly larger. See tg.app.ProbeMidlet.
#
# The client stack is still shrunk away - nothing here reaches tg.api or the
# MTProto session layer.

-keep public class tg.app.ProbeMidlet {
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
