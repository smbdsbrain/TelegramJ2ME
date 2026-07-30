# Entry points for dist/probe.jar - the small hardware-reconnaissance suite.
#
# Deliberately does NOT keep the Telegram stack, so ProGuard shrinks crypto/TL/
# MTProto out of this JAR entirely. The first thing we install on an unknown
# 2011 handset should be as small and as boring as possible.

-keep public class tg.app.ProbeMidlet {
    public <init>();
    protected void startApp();
    protected void pauseApp();
    protected void destroyApp(boolean);
}
