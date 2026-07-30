# Entry points for dist/tg.jar - the full Telegram client suite.

-keep public class tg.app.TgMidlet {
    public <init>();
    protected void startApp();
    protected void pauseApp();
    protected void destroyApp(boolean);
}

# No keep rule is needed for the TL layer. tg.tl.TlParser dispatches on an int
# kind with a switch and tg.api.TlSchema is a data table, so every TL type is
# reached through an ordinary reference that ProGuard can trace. Nothing is
# resolved by name, which is also why -Release may obfuscate this target.
#
# (There used to be a -keep for tg.tl.TlRegistry here. That class was never
# written - the rule matched nothing and protected nothing.)
