# Shared ProGuard configuration for the device profile.
#
# The job that matters here is PREVERIFICATION: CLDC's verifier will not accept
# a class without a StackMap attribute, and `-microedition` is what makes
# ProGuard emit CLDC-flavour StackMap rather than J2SE StackMapTable.
# Shrinking is a welcome side effect - it keeps the JAR inside whatever size
# limit the handset turns out to impose.
#
# -injars / -outjars / -libraryjars and the entry-point keeps are supplied by
# tools/build.ps1 and config/proguard-<target>.pro.

-microedition

# Keep the build readable until we have hardware results. tools/build.ps1
# -Release swaps these for optimisation + obfuscation.
-dontoptimize
-dontobfuscate

-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# NOTE: there is deliberately no blanket
#     -keep public class * extends javax.microedition.midlet.MIDlet
# here. src/ holds several MIDlets, and keeping all of them in every target
# would drag the whole crypto stack into probe.jar - which exists precisely
# because the first install on an unknown handset should be tiny. Each
# config/proguard-<target>.pro keeps exactly one entry point; the AMS only ever
# instantiates the one named in that target's JAD.

# lcdui calls back into listeners; keep the callback signatures intact.
-keepclassmembers class * implements javax.microedition.lcdui.CommandListener {
    public void commandAction(javax.microedition.lcdui.Command,
                              javax.microedition.lcdui.Displayable);
}
-keepclassmembers class * implements javax.microedition.lcdui.ItemStateListener {
    public void itemStateChanged(javax.microedition.lcdui.Item);
}
-keepclassmembers class * implements java.lang.Runnable {
    public void run();
}

# rt.jar is only a stand-in for cldcapi11.jar when WTK is absent; it drags in
# references we neither use nor ship. Warnings about them are noise.
-dontnote
-dontwarn java.**
-dontwarn javax.**
