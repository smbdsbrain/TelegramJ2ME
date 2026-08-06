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

# tg.crypto.AuthKeySeeding is the barrier a permanent auth_key crosses, and its
# gather count stopped being a constant: it measures what the handset's clock
# yields and gathers until it has 256 credited bits. That is a loop with an exit
# condition computed from live measurements - exactly the shape an optimiser can
# change the meaning of - and, like the budgets above, the desktop suite cannot
# see it because it never runs ProGuard's output.
#
# Kept so tools/smoke-emulator.ps1 can run one barrier against the obfuscated,
# optimised artifact and read back what it chose. The Outcome class is kept with
# it because the figures are the point; nothing else in the seeding path needs a
# rule, since JitterYield and MinEntropy are reached by ordinary references.
-keep class tg.crypto.AuthKeySeeding {
    public static *;
}
-keep class tg.crypto.AuthKeySeeding$Outcome {
    public *;
}
# ...and the two members of Rng the harness needs to hand it a pool and wipe it
# afterwards. wipe() has no caller inside the client - the pool behind a live
# session is meant to persist - so the shrinker is right to drop it and this rule
# is what keeps the throwaway pool in a test from outliving the check.
-keep class tg.crypto.Rng {
    public <init>();
    public void wipe();
}

# No keep rule is needed for the TL layer. tg.tl.TlParser dispatches on an int
# kind with a switch and tg.api.TlSchema is a data table, so every TL type is
# reached through an ordinary reference that ProGuard can trace. Nothing is
# resolved by name, which is also why -Release may obfuscate this target.
#
# (There used to be a -keep for tg.tl.TlRegistry here. That class was never
# written - the rule matched nothing and protected nothing.)
