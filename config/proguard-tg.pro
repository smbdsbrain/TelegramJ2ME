# Entry points for dist/tg.jar - the full Telegram client suite.

-keep public class tg.app.TgMidlet {
    public <init>();
    protected void startApp();
    protected void pauseApp();
    protected void destroyApp(boolean);
}

# tg.mem.MemoryBudget holds every size limit in the client, and the one thing
# that must stay true of it is that its values are read at runtime.
#
# This is not about reflection. Every budget used to be a `static final int`
# with a constant initialiser - a constant variable in the JLS sense, which
# javac inlines at every use site. Turning them into accessors is what makes a
# measured heap able to change anything at all, and the layer that could quietly
# undo it is ProGuard's value propagation, not javac. The desktop suite cannot
# see that: it runs against build/desktop/classes, which ProGuard never touches,
# and config/proguard-debug.pro disables optimisation for every non-release
# build. So the rule exists to let tools/smoke-emulator.ps1 call init() on the
# obfuscated, optimised artifact and observe the budgets actually move.
-keep class tg.mem.MemoryBudget {
    public static *;
}

# No keep rule is needed for the TL layer. tg.tl.TlParser dispatches on an int
# kind with a switch and tg.api.TlSchema is a data table, so every TL type is
# reached through an ordinary reference that ProGuard can trace. Nothing is
# resolved by name, which is also why -Release may obfuscate this target.
#
# (There used to be a -keep for tg.tl.TlRegistry here. That class was never
# written - the rule matched nothing and protected nothing.)
