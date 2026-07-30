# Entry points for dist/tg.jar - the full Telegram client suite.

-keep public class tg.app.TgMidlet {
    public <init>();
    protected void startApp();
    protected void pauseApp();
    protected void destroyApp(boolean);
}

# The MTProto layer dispatches TL constructors from a generated table. Once
# generated/ exists, keep its dispatch entry points so shrinking cannot remove
# a type that is only ever reached by constructor id.
-keep class tg.tl.TlRegistry { *; }
