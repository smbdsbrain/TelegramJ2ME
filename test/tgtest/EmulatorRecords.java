package tgtest;

import java.io.File;
import java.io.InputStream;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

import org.microemu.MIDletBridge;
import org.microemu.MIDletContext;
import org.microemu.MicroEmulator;
import org.microemu.RecordStoreManager;
import org.microemu.app.Config;
import org.microemu.app.launcher.Launcher;
import org.microemu.app.util.FileRecordStoreManager;
import org.microemu.util.ExtendedRecordListener;
import org.microemu.util.RecordStoreImpl;

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
    /** The working emulator displaced by {@link #installUnreadable}. */
    private static MicroEmulator displaced;

    private EmulatorRecords() { }

    /** Register the record store, once per JVM. */
    public static void install()
    {
        if (MIDletBridge.getMicroEmulator() == null)
        {
            MIDletBridge.setMicroEmulator(new Emulator(new SuiteRecords()));
        }
    }

    /**
     * Swap in a record store that refuses to open, until {@link #restore}.
     *
     * A handset whose RMS cannot be read is not a handset with no data, and the
     * store has to say which one it is looking at. Nothing in the client can
     * produce that state on demand - {@code RecordStore}'s static methods reach
     * the manager through {@code MIDletBridge}, and the manager is what a test
     * can replace. Replacing it here rather than adding an indirection to
     * {@code tg.plat} keeps the injection out of the JAR entirely.
     *
     * Always pair with {@link #restore} in a finally block: the suites share one
     * JVM, and a store left broken fails every later test for the wrong reason.
     */
    public static void installUnreadable()
    {
        install();
        if (displaced == null) { displaced = MIDletBridge.getMicroEmulator(); }
        MIDletBridge.setMicroEmulator(new Emulator(new Unreadable()));
    }

    /** Put the working record store back. Safe to call when nothing is broken. */
    public static void restore()
    {
        if (displaced != null)
        {
            MIDletBridge.setMicroEmulator(displaced);
            displaced = null;
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
     * A record store that fails at the door.
     *
     * Only {@code openRecordStore} needs to throw to reproduce an unreadable
     * store: every caller in the client opens before it reads or writes.
     */
    private static final class Unreadable implements RecordStoreManager
    {
        public String getName() { return "unreadable"; }

        public RecordStore openRecordStore(String name, boolean create)
                throws RecordStoreException
        {
            throw new RecordStoreException("injected RMS failure");
        }

        public void deleteRecordStore(String name) throws RecordStoreException
        {
            throw new RecordStoreException("injected RMS failure");
        }

        public void saveChanges(RecordStoreImpl store)
                throws RecordStoreException
        {
            throw new RecordStoreException("injected RMS failure");
        }

        public String[] listRecordStores() { return new String[0]; }
        public int getSizeAvailable(RecordStoreImpl store) { return 0; }
        public void init(MicroEmulator emulator) { }
        public void deleteStores() { }
        public void setRecordListener(ExtendedRecordListener listener) { }
        public void fireRecordStoreListener(int type, String name) { }
    }

    /**
     * The eight methods MIDletBridge needs before RecordStore will work.
     *
     * getLauncher() stays null: nothing reaches it once getSuiteFolder is
     * overridden, and building a real one would drag in the emulator's UI.
     */
    private static final class Emulator implements MicroEmulator
    {
        private final RecordStoreManager records;

        Emulator(RecordStoreManager records)
        {
            this.records = records;
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
