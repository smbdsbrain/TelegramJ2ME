package tgtest;

import java.io.File;
import java.io.InputStream;

import org.microemu.MIDletBridge;
import org.microemu.MIDletContext;
import org.microemu.MicroEmulator;
import org.microemu.RecordStoreManager;
import org.microemu.app.Config;
import org.microemu.app.launcher.Launcher;
import org.microemu.app.util.FileRecordStoreManager;

/**
 * A working {@code javax.microedition.rms.RecordStore} for a headless run.
 *
 * {@code RecordStore}'s static methods go through
 * {@code MIDletBridge.getRecordStoreManager()}, and with no {@code MicroEmulator}
 * registered every call throws a NullPointerException that the caller dutifully
 * turns into "RMS read failed". Anything that has to survive a restart - an auth
 * key, a signed-in session, the stored heap measurement, the probe's own
 * cross-restart entropy log - therefore cannot be exercised without this.
 *
 * <h3>Why it is not part of {@link EmulatorHarness}</h3>
 * It was, and that made it unreachable from the one harness that needed it most.
 * {@code EmulatorHarness} holds a {@code TgMidlet} subclass as a field, so
 * loading the class resolves {@code tg.app.TgMidlet} - which is not in
 * {@code dist/probe.jar}. {@link ProbeSmokeTest} runs against that jar and got a
 * {@code NoClassDefFoundError} before it had installed anything. Separating the
 * record store from the client's driver is what lets both suites use it.
 */
public final class EmulatorRecords
{
    private EmulatorRecords() { }

    /** Register the record store, once per JVM. */
    public static void install()
    {
        if (MIDletBridge.getMicroEmulator() == null)
        {
            MIDletBridge.setMicroEmulator(new Emulator());
        }
    }

    /**
     * A record store that writes where the GUI emulator writes.
     *
     * {@code FileRecordStoreManager.getSuiteFolder()} asks
     * {@code emulator.getLauncher().getSuiteName()}, and a Launcher is a MIDlet
     * that wants the whole app frame behind it. Overriding the one protected
     * method it needs is the entire difference between a harness with
     * persistence and one without.
     *
     * The folder is deliberately the same "suite-null" that MicroEmulator
     * itself produces when a MIDlet class is named directly rather than a suite
     * being installed, so a profile written here and one written by
     * tools/run-emulator.ps1 are the same profile.
     */
    private static final class SuiteRecords extends FileRecordStoreManager
    {
        private final File folder;

        SuiteRecords()
        {
            folder = new File(Config.getConfigPath(), "suite-null");
            folder.mkdirs();
        }

        protected File getSuiteFolder() { return folder; }
    }

    /**
     * The eight methods MIDletBridge needs before RecordStore will work.
     *
     * getLauncher() stays null: nothing reaches it once getSuiteFolder is
     * overridden, and building a real one would drag in the emulator's UI.
     */
    private static final class Emulator implements MicroEmulator
    {
        private final RecordStoreManager records = new SuiteRecords();

        Emulator()
        {
            records.init(this);
        }

        public RecordStoreManager getRecordStoreManager() { return records; }
        public Launcher getLauncher() { return null; }
        public String getAppProperty(String key) { return null; }

        public InputStream getResourceAsStream(String name)
        {
            return EmulatorRecords.class.getResourceAsStream(name);
        }

        public void notifyDestroyed(MIDletContext context) { }
        public void destroyMIDletContext(MIDletContext context) { }
        public int checkPermission(String permission) { return 1; }
        public boolean platformRequest(String url) { return false; }
    }
}
