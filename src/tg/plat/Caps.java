package tg.plat;

import java.util.Vector;

/**
 * Runtime capability and platform probe.
 *
 * Java ME implementations differ widely, so nothing here is assumed: every
 * optional API is resolved with Class.forName() at runtime and every property
 * is read rather than predicted. The output can be captured when evaluating a
 * new runtime.
 */
public final class Caps
{
    /** Properties every CLDC/MIDP implementation is required to publish. */
    private static final String[] CORE_PROPERTIES = {
        "microedition.platform",
        "microedition.configuration",
        "microedition.profiles",
        "microedition.locale",
        "microedition.encoding",
        "microedition.commports"
    };

    /** Version properties that only appear when the matching JSR is present. */
    private static final String[] OPTIONAL_PROPERTIES = {
        "microedition.io.file.FileConnection.version",
        "bluetooth.api.version",
        "microedition.media.version",
        "microedition.pim.version",
        "microedition.location.version",
        "microedition.m3g.version",
        "microedition.sensor.version",
        "wireless.messaging.sms.smsc"
    };

    /**
     * Optional APIs, as {label, class name}. Class.forName is the only reliable
     * test: a device can publish a version property and still fail to load the
     * class, and vice versa.
     */
    private static final String[][] OPTIONAL_CLASSES = {
        { "Raw TCP (MIDP 2.0)",  "javax.microedition.io.SocketConnection" },
        { "Server socket",       "javax.microedition.io.ServerSocketConnection" },
        { "UDP datagram",        "javax.microedition.io.UDPDatagramConnection" },
        { "HTTPS",               "javax.microedition.io.HttpsConnection" },
        { "RMS",                 "javax.microedition.rms.RecordStore" },
        { "JSR-75 FileConn",     "javax.microedition.io.file.FileConnection" },
        { "JSR-82 Bluetooth",    "javax.bluetooth.LocalDevice" },
        { "JSR-135 MMAPI",       "javax.microedition.media.Manager" },
        { "JSR-179 Location",    "javax.microedition.location.LocationProvider" },
        { "JSR-120 SMS",         "javax.wireless.messaging.MessageConnection" },
        { "JSR-184 M3G",         "javax.microedition.m3g.Graphics3D" },
        { "GameCanvas",          "javax.microedition.lcdui.game.GameCanvas" }
    };

    private Caps() { }

    /** Human-readable report, one "key = value" per element. */
    public static String[] report()
    {
        Vector v = new Vector(48);

        v.addElement("-- build --");
        v.addElement("version = " + tg.app.BuildInfo.VERSION);
        v.addElement("build = " + tg.app.BuildInfo.BUILD);
        v.addElement("target = " + tg.app.BuildInfo.TARGET);
        v.addElement("bootclasspath = " + tg.app.BuildInfo.BOOTMODE);

        v.addElement("-- platform --");
        for (int i = 0; i < CORE_PROPERTIES.length; i++)
        {
            v.addElement(CORE_PROPERTIES[i] + " = " + prop(CORE_PROPERTIES[i]));
        }

        v.addElement("-- memory --");
        Runtime rt = Runtime.getRuntime();
        v.addElement("totalMemory = " + rt.totalMemory());
        v.addElement("freeMemory = " + rt.freeMemory());
        v.addElement("usedMemory = " + (rt.totalMemory() - rt.freeMemory()));

        v.addElement("-- optional APIs --");
        for (int i = 0; i < OPTIONAL_CLASSES.length; i++)
        {
            String label = OPTIONAL_CLASSES[i][0];
            v.addElement(label + " = " + (hasClass(OPTIONAL_CLASSES[i][1]) ? "yes" : "NO"));
        }

        v.addElement("-- optional API versions --");
        for (int i = 0; i < OPTIONAL_PROPERTIES.length; i++)
        {
            String value = prop(OPTIONAL_PROPERTIES[i]);
            if (value != null)
            {
                v.addElement(OPTIONAL_PROPERTIES[i] + " = " + value);
            }
        }

        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    /** True when the class can actually be resolved by this VM. */
    public static boolean hasClass(String name)
    {
        try
        {
            Class.forName(name);
            return true;
        }
        catch (Throwable t)
        {
            // ClassNotFoundException on a missing JSR, but a partially
            // installed one can raise NoClassDefFoundError or worse.
            return false;
        }
    }

    /** System property, or null. Some handsets throw instead of returning null. */
    public static String prop(String key)
    {
        try
        {
            return System.getProperty(key);
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
