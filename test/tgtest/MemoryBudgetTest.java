package tgtest;

import tg.app.HeapMeasurement;
import tg.mem.MemoryBudget;
import tg.mem.MemoryPressure;
import tg.mem.MemoryRelief;

/**
 * The budget holder is the only place in the client that still carries a size
 * literal, so it is the only place a mistake can quietly resize every buffer at
 * once.
 *
 * Three properties matter and none of them is checkable by reading the code:
 *
 *   - at or above the reference heap every budget equals the value that shipped
 *     before this class existed, so the two handsets that were actually
 *     validated cannot regress;
 *   - shrinking the ceiling never increases a budget and never takes one below
 *     its floor, so a small heap degrades instead of wedging;
 *   - a handset that reports nonsense - zero, a negative, or more heap than the
 *     VM can address - still produces a usable profile.
 *
 * The reference and floor tables below are written out as literals on purpose.
 * They duplicate MemoryBudget's private constants so that changing one of them
 * fails here rather than silently reshaping the client.
 */
public final class MemoryBudgetTest implements Test
{
    /** Order is fixed and shared by every table in this suite. */
    private static final String[] NAMES = {
        "packetBytes", "inflateOutputBytes", "photoCompressedBytes",
        "updateQueueBytes", "httpQueueBytes",
        "maxDialogs", "dialogPageSize", "maxHistory", "historyPageSize",
        "layoutWindowScreens", "historyPrefetchMargin", "dialogPrefetchMargin",
        "peerCacheEntries", "avatarCacheEntries", "thumbnailCacheEntries",
        "screenStackDepth", "photoPixels"
    };

    /**
     * What every one of these was, as a literal, before this class existed.
     *
     * With one exception: maxDialogs was 200 and is 120. It went *down*,
     * because it stopped meaning the same thing - it is the window held around
     * the reader now, not a limit on how far the chat list goes, and a window
     * is sized by the slack scrolling needs rather than by how much of an
     * account it covers. Three pages, 52 KB at the measured 431 bytes a row;
     * the derivation is in MemoryBudget beside the constant.
     */
    private static final int[] REFERENCE = {
        1024 * 1024, 2 * 1024 * 1024, 512 * 1024, 256 * 1024, 256 * 1024,
        120, 40, 120, 30,
        3, 15, 20,
        500, 16, 12,
        16, 307200
    };

    /** Below these, shrinking further breaks something instead of saving something. */
    private static final int[] FLOOR = {
        256 * 1024, 192 * 1024, 48 * 1024, 32 * 1024, 64 * 1024,
        40, 10, 20, 10,
        1, 5, 5,
        64, 2, 2,
        4, 16384
    };

    /** The five that bound a single allocation and are also capped by the block. */
    private static final int BLOCK_CAPPED_COUNT = 3;

    private static final int REFERENCE_HEAP = 4 * 1024 * 1024;

    public String name() { return "mem/budget-derivation"; }

    public void run()
    {
        try
        {
            tablesAgree();
            unmeasuredIsTheShippedProfile();
            atOrAboveReferenceNothingChanges();
            shrinkingNeverGrowsABudget();
            floorsHold();
            nonsenseIsSurvivable();
            largestBlockBoundsSingleAllocations();
            viabilityIsAdvisory();
            decodeCostIsAnOverEstimate();
            reportingSurvivesEveryState();
            measurementSurvivesARestart();
            aProbeThatNeverReturnsStopsBeingTried();
            headroomUsesTheMeasuredCeiling();
            pressureShedsInOrderAndStops();
            aBoundedShedStopsWhereItIsTold();
            theAvatarEstimateDoesNotScale();
        }
        finally
        {
            // Every later suite in this JVM would otherwise inherit whatever
            // ceiling the last case installed.
            MemoryBudget.reset();
            MemoryPressure.setRelief(null);
        }
    }

    // ------------------------------------------------------------ the tables

    private static void tablesAgree()
    {
        Assert.equal("one floor per budget", NAMES.length, FLOOR.length);
        Assert.equal("one reference per budget", NAMES.length, REFERENCE.length);
        for (int i = 0; i < NAMES.length; i++)
        {
            Assert.isTrue(NAMES[i] + " floor is below its reference",
                    FLOOR[i] < REFERENCE[i]);
            Assert.isTrue(NAMES[i] + " floor is positive", FLOOR[i] > 0);
        }
        // "Older" can never do anything if a full page cannot fit twice over.
        Assert.isTrue("history floor holds at least two pages",
                FLOOR[7] >= 2 * FLOOR[8]);
    }

    // ------------------------------------------------------- the two states

    private static void unmeasuredIsTheShippedProfile()
    {
        MemoryBudget.reset();
        Assert.equal("no measurement means no source", MemoryBudget.SOURCE_DEFAULT,
                MemoryBudget.source());
        Assert.equal("no measurement means no ceiling", 0, MemoryBudget.ceiling());
        assertEqualsReference("unmeasured");
    }

    private static void atOrAboveReferenceNothingChanges()
    {
        install(REFERENCE_HEAP);
        assertEqualsReference("exactly at the reference heap");

        // The literal the Samsung GT-C3592 reports. Twenty bytes below 5 MiB,
        // which is why the reference is 4 MiB and not 5: with a 5 MiB reference
        // and integer arithmetic this handset would land one unit below every
        // value it was validated with.
        install(5242860);
        assertEqualsReference("the measured GT-C3592 heap");

        install(8 * 1024 * 1024);
        assertEqualsReference("a heap twice the reference");

        install(64 * 1024 * 1024);
        assertEqualsReference("a heap sixteen times the reference");
    }

    private static void assertEqualsReference(String why)
    {
        int[] actual = snapshot();
        for (int i = 0; i < NAMES.length; i++)
        {
            Assert.equal(NAMES[i] + " is the shipped value: " + why,
                    REFERENCE[i], actual[i]);
        }
    }

    // -------------------------------------------------------- the whole range

    private static void shrinkingNeverGrowsABudget()
    {
        int[] ceilings = {
            0, 64 * 1024, 128 * 1024, 256 * 1024, 512 * 1024,
            1024 * 1024, 1536 * 1024, 2 * 1024 * 1024, 3 * 1024 * 1024,
            REFERENCE_HEAP, 5 * 1024 * 1024, 8 * 1024 * 1024, 64 * 1024 * 1024
        };

        int[] previous = null;
        for (int c = 0; c < ceilings.length; c++)
        {
            if (ceilings[c] == 0) { MemoryBudget.reset(); }
            else { install(ceilings[c]); }

            int[] current = snapshot();
            for (int i = 0; i < NAMES.length; i++)
            {
                Assert.isTrue(NAMES[i] + " stays at or above its floor at "
                                + ceilings[c],
                        current[i] >= FLOOR[i]);
                Assert.isTrue(NAMES[i] + " never exceeds its reference at "
                                + ceilings[c],
                        current[i] <= REFERENCE[i]);
                if (previous != null && ceilings[c] != 0)
                {
                    Assert.isTrue(NAMES[i] + " does not shrink as the heap grows,"
                                    + " at " + ceilings[c],
                            current[i] >= previous[i]);
                }
            }
            if (ceilings[c] != 0) { previous = current; }
        }
    }

    private static void floorsHold()
    {
        // Far below anything a client could run on. Everything must still be
        // its floor exactly - never zero, never negative, never divided further.
        install(64 * 1024);
        int[] actual = snapshot();
        for (int i = 0; i < NAMES.length; i++)
        {
            Assert.equal(NAMES[i] + " sits on its floor on a 64 KB heap",
                    FLOOR[i], actual[i]);
        }
    }

    // ------------------------------------------------- surviving a bad number

    private static void nonsenseIsSurvivable()
    {
        MemoryBudget.init(-1, -1, MemoryBudget.SOURCE_MEASURED);
        Assert.equal("a negative ceiling is discarded", 0, MemoryBudget.ceiling());
        assertEqualsReference("a negative measurement");

        MemoryBudget.init(0, 0, MemoryBudget.SOURCE_MEASURED);
        assertEqualsReference("a zero measurement");

        // reference * ceiling overflows an int long before this, which is the
        // whole reason the arithmetic is done in long.
        MemoryBudget.init(Long.MAX_VALUE, Long.MAX_VALUE, MemoryBudget.SOURCE_MEASURED);
        assertEqualsReference("a ceiling larger than the address space");
        int[] actual = snapshot();
        for (int i = 0; i < NAMES.length; i++)
        {
            Assert.isTrue(NAMES[i] + " is positive after an absurd ceiling",
                    actual[i] > 0);
        }
    }

    private static void largestBlockBoundsSingleAllocations()
    {
        // A handset that reports plenty of heap but fragments into small blocks.
        // Total free memory and the largest contiguous allocation are different
        // numbers, and this is the case where believing the first one crashes.
        MemoryBudget.init(8 * 1024 * 1024, 128 * 1024, MemoryBudget.SOURCE_MEASURED);

        int[] actual = snapshot();
        for (int i = 0; i < BLOCK_CAPPED_COUNT; i++)
        {
            int cap = Math.max(FLOOR[i], (128 * 1024) / 2);
            Assert.isTrue(NAMES[i] + " is bounded by the largest block",
                    actual[i] <= cap);
        }
        Assert.isTrue("the pixel budget is bounded by the largest block",
                MemoryBudget.photoPixels() <= Math.max(FLOOR[15], (128 * 1024) / 8));

        // The floor still wins over the block cap. A device whose largest block
        // cannot hold the smallest legal packet is not one we can rescue by
        // rejecting every packet the server sends - viable() is what says so.
        Assert.equal("the packet floor outranks a tiny block",
                FLOOR[0], MemoryBudget.packetBytes());

        // A ceiling with no block measurement must not be treated as a zero block.
        install(8 * 1024 * 1024);
        assertEqualsReference("a ceiling with an unknown largest block");
    }

    // -------------------------------------------------------------- advisory

    private static void viabilityIsAdvisory()
    {
        MemoryBudget.reset();
        Assert.isTrue("an unmeasured device is assumed viable", MemoryBudget.viable());

        // Both bounds are measured, not chosen. Driving the client under a
        // constrained heap: at a 1536 KB ceiling the connect task dies with an
        // OutOfMemoryError before the dialog list; at 3584 KB sign-in, avatars,
        // a chat and a full photo decode all work.
        install(1536 * 1024);
        Assert.isFalse("a ceiling that could not complete connect is not viable",
                MemoryBudget.viable());

        install(2 * 1024 * 1024);
        Assert.isTrue("2 MB is the viability threshold", MemoryBudget.viable());

        install(3584 * 1024);
        Assert.isTrue("a ceiling that ran the whole client is viable",
                MemoryBudget.viable());

        Assert.equal("the threshold is reportable", 2 * 1024 * 1024,
                MemoryBudget.viableHeap());

        // Advisory, not a refusal: every budget is still a working number.
        int[] actual = snapshot();
        for (int i = 0; i < NAMES.length; i++)
        {
            Assert.isTrue(NAMES[i] + " is still usable below the viability floor",
                    actual[i] >= FLOOR[i]);
        }
    }

    private static void decodeCostIsAnOverEstimate()
    {
        // Four bytes per pixel is the framebuffer alone. The coefficient arrays
        // and the compressed bytes are still live when it is allocated, so an
        // estimate that only counted the framebuffer would wave through decodes
        // that cannot fit.
        long cost = MemoryBudget.photoDecodeCost(640, 480, 512 * 1024);
        Assert.isTrue("a 640x480 decode costs more than its framebuffer",
                cost > 640L * 480L * 4L);
        Assert.isTrue("the compressed bytes are counted",
                cost >= 640L * 480L * 4L + 512L * 1024L);

        Assert.equal("an empty image costs nothing but its bytes",
                1024L, MemoryBudget.photoDecodeCost(0, 0, 1024));
        Assert.equal("negative dimensions cannot produce a negative cost",
                0L, MemoryBudget.photoDecodeCost(-1, -1, -1));
    }

    private static void reportingSurvivesEveryState()
    {
        MemoryBudget.reset();
        Assert.equal("the unmeasured report has every line", 9,
                MemoryBudget.lines().length);
        Assert.equal("default provenance is named", "default",
                MemoryBudget.sourceName(MemoryBudget.SOURCE_DEFAULT));
        Assert.equal("stored provenance is named", "stored",
                MemoryBudget.sourceName(MemoryBudget.SOURCE_STORED));
        Assert.equal("measured provenance is named", "measured",
                MemoryBudget.sourceName(MemoryBudget.SOURCE_MEASURED));

        MemoryBudget.init(5242860, 5040984, MemoryBudget.SOURCE_STORED);
        Assert.equal("provenance survives init", MemoryBudget.SOURCE_STORED,
                MemoryBudget.source());
        String[] lines = MemoryBudget.lines();
        for (int i = 0; i < lines.length; i++)
        {
            Assert.isTrue("report line " + i + " is present",
                    lines[i] != null && lines[i].length() > 0);
        }

        // A measured ceiling is rounded down to a 64 KB grain, so a handset that
        // reports a slightly different figure each launch does not resize a
        // cache behind the user's back.
        Assert.equal("the stored ceiling is on a 64 KB grain", 0,
                MemoryBudget.ceiling() % (64 * 1024));
    }

    // ------------------------------------------------------------ persistence

    /**
     * The whole point of storing the measurement is that the second launch does
     * not repeat a probe which briefly allocates the entire heap. So the stored
     * value has to come back identical, and an absent or stale one has to leave
     * the reference profile in place rather than half-applying something.
     */
    private static void measurementSurvivesARestart()
    {
        MemoryAuthKeyStore store = new MemoryAuthKeyStore();

        MemoryBudget.reset();
        Assert.isFalse("an empty store has nothing to apply",
                HeapMeasurement.applyStored(store));
        assertEqualsReference("an empty store");

        Assert.isTrue("a probe produces a measurement",
                HeapMeasurement.measure(store));
        int measuredCeiling = MemoryBudget.ceiling();
        int measuredBlock = MemoryBudget.largestBlock();
        Assert.isTrue("the probe measured something", measuredCeiling > 0);
        Assert.equal("a fresh probe is marked measured", MemoryBudget.SOURCE_MEASURED,
                MemoryBudget.source());

        // Restart: budget forgotten, store retained.
        MemoryBudget.reset();
        Assert.isTrue("the stored measurement is applied",
                HeapMeasurement.applyStored(store));
        Assert.equal("the ceiling survives a restart", measuredCeiling,
                MemoryBudget.ceiling());
        Assert.equal("the largest block survives a restart", measuredBlock,
                MemoryBudget.largestBlock());
        Assert.equal("a restored measurement is marked stored",
                MemoryBudget.SOURCE_STORED, MemoryBudget.source());

        // A stored value written by an older probe must be ignored, not
        // reinterpreted: the number means whatever the probe that wrote it meant.
        store.saveString("heap.probe.version", "0");
        MemoryBudget.reset();
        Assert.isFalse("a stale probe version is discarded",
                HeapMeasurement.applyStored(store));
        assertEqualsReference("a stale probe version");

        // Corruption is a bad line in a record store, not a hypothetical.
        store.saveString("heap.probe.version", "1");
        store.saveString("heap.ceiling", "not a number");
        MemoryBudget.reset();
        Assert.isFalse("an unparseable ceiling is discarded",
                HeapMeasurement.applyStored(store));
        assertEqualsReference("an unparseable ceiling");
    }

    private static void aProbeThatNeverReturnsStopsBeingTried()
    {
        MemoryAuthKeyStore store = new MemoryAuthKeyStore();
        Assert.isFalse("a fresh store is not exhausted",
                HeapMeasurement.exhausted(store));

        // What the client sees after two launches whose probe took the VM with
        // it: the counter was written before each probe and never cleared.
        store.saveString("heap.probe.attempts", "2");
        Assert.isTrue("two dead probes exhaust the budget",
                HeapMeasurement.exhausted(store));

        MemoryBudget.reset();
        Assert.isFalse("an exhausted store refuses to probe again",
                HeapMeasurement.measure(store));
        assertEqualsReference("after refusing to probe");

        // A probe that does return clears the counter, so a one-off failure
        // does not accumulate towards the limit across unrelated launches.
        store.saveString("heap.probe.attempts", "1");
        Assert.isTrue("one earlier failure still allows a probe",
                HeapMeasurement.measure(store));
        Assert.equal("a successful probe clears the counter", "0",
                store.loadString("heap.probe.attempts"));
    }

    // --------------------------------------------------------------- pressure

    /**
     * headroom() exists because freeMemory() is the wrong number on CLDC: the
     * heap grows on demand, so what is free right now understates what is
     * available, sometimes by megabytes. The measured ceiling is what converts
     * two misleading readings into a usable one.
     */
    private static void headroomUsesTheMeasuredCeiling()
    {
        MemoryBudget.reset();
        MemoryPressure.setRelief(null);

        long unmeasured = MemoryPressure.headroom();
        Assert.isTrue("an unmeasured client falls back to free memory",
                unmeasured > 0);

        // A ceiling above everything this VM has committed so far: headroom
        // must now exceed what freeMemory alone reports, because the heap can
        // still grow into the difference. That gap is the whole reason the
        // measurement is worth storing.
        Runtime rt = Runtime.getRuntime();
        long room = 64L * 1024L * 1024L;
        MemoryBudget.init(rt.totalMemory() + room, 0, MemoryBudget.SOURCE_MEASURED);
        Assert.isTrue("a measured ceiling counts room the heap can still grow into",
                MemoryPressure.headroom() > rt.freeMemory());

        // A ceiling that under-reports - a value stored when the VM was smaller
        // - must never make the client claim less room than it can see.
        MemoryBudget.init(64 * 1024, 0, MemoryBudget.SOURCE_STORED);
        Assert.isTrue("an under-reported ceiling never hides real free memory",
                MemoryPressure.headroom() >= rt.freeMemory());

        MemoryBudget.reset();
        Assert.isTrue("zero always fits", MemoryPressure.fits(0));
        Assert.isFalse("an impossible request never fits",
                MemoryPressure.fits(Long.MAX_VALUE / 2));
    }

    /**
     * The ladder has to escalate in order, stop as soon as the request fits,
     * and survive a level that throws - it runs on a worker thread while the UI
     * is live, and a shed that fails must not become the failure.
     */
    private static void pressureShedsInOrderAndStops()
    {
        MemoryBudget.reset();

        final java.util.Vector called = new java.util.Vector();
        MemoryPressure.setRelief(new MemoryRelief()
        {
            public int levels() { return 4; }
            public void release(int level)
            {
                called.addElement(new Integer(level));
                if (level == 2) { throw new RuntimeException("level 2 is broken"); }
            }
        });
        try
        {
            int before = MemoryPressure.shedEvents();

            // Nothing on this desktop JVM can satisfy this, so the ladder runs
            // to the end and then admits defeat rather than pretending.
            Assert.isFalse("an unsatisfiable reserve refuses",
                    MemoryPressure.reserve(Long.MAX_VALUE / 4));
            Assert.equal("every level was tried", 4, called.size());
            for (int i = 0; i < 4; i++)
            {
                Assert.equal("levels run in order", i + 1,
                        ((Integer) called.elementAt(i)).intValue());
            }
            Assert.equal("every shed is counted", before + 4,
                    MemoryPressure.shedEvents());

            // A request that already fits must not shed at all: the ladder is
            // for pressure, and firing it on an idle heap would be a stutter
            // with nothing to show for it.
            called.removeAllElements();
            int quiet = MemoryPressure.shedEvents();
            Assert.isTrue("a small reserve is granted without shedding",
                    MemoryPressure.reserve(1024));
            Assert.equal("an idle heap sheds nothing", 0, called.size());
            Assert.equal("an idle heap counts nothing", quiet,
                    MemoryPressure.shedEvents());

            Assert.equal("the report is two lines", 2, MemoryPressure.lines().length);
        }
        finally { MemoryPressure.setRelief(null); }
    }

    /**
     * A bounded shed, for work the user did not ask for.
     *
     * The ladder's later levels are the avatar cache and the open conversation's
     * thumbnails, which are exactly what the avatar and thumbnail decodes are
     * filling. Unbounded, those two callers clear the cache they are populating
     * and the client does the work twice; bounded at level one they still get
     * the collect and may drop the cached full-screen photo, which is the only
     * level that is pure gain for them.
     */
    private static void aBoundedShedStopsWhereItIsTold()
    {
        MemoryBudget.reset();

        final java.util.Vector called = new java.util.Vector();
        MemoryPressure.setRelief(new MemoryRelief()
        {
            public int levels() { return 4; }
            public void release(int level)
            {
                called.addElement(new Integer(level));
            }
        });
        try
        {
            Assert.isFalse("an unsatisfiable bounded reserve still refuses",
                    MemoryPressure.reserve(Long.MAX_VALUE / 4, 1));
            Assert.equal("only level 1 ran", 1, called.size());
            Assert.equal("and it was level 1", 1,
                    ((Integer) called.elementAt(0)).intValue());

            called.removeAllElements();
            Assert.isFalse("a bound of two stops at two",
                    MemoryPressure.reserve(Long.MAX_VALUE / 4, 2));
            Assert.equal("two levels ran", 2, called.size());

            // Zero means "collect, but give nothing back". A caller that cannot
            // afford to disturb any cache still wants the garbage swept.
            called.removeAllElements();
            Assert.isFalse("a bound of zero sheds nothing",
                    MemoryPressure.reserve(Long.MAX_VALUE / 4, 0));
            Assert.equal("no level ran", 0, called.size());

            // The unbounded form is the one every existing caller uses and it
            // must not have changed meaning.
            called.removeAllElements();
            Assert.isFalse("the unbounded form still runs the whole ladder",
                    MemoryPressure.reserve(Long.MAX_VALUE / 4));
            Assert.equal("every level ran", 4, called.size());

            // A bound above the ladder is not an invitation to invent levels.
            called.removeAllElements();
            Assert.isFalse("a bound above the ladder is harmless",
                    MemoryPressure.reserve(Long.MAX_VALUE / 4, 99));
            Assert.equal("still only four levels exist", 4, called.size());

            called.removeAllElements();
            Assert.isTrue("a bounded reserve that fits sheds nothing",
                    MemoryPressure.reserve(1024, 1));
            Assert.equal("an idle heap sheds nothing", 0, called.size());
        }
        finally { MemoryPressure.setRelief(null); }
    }

    /**
     * The avatar estimate, which is a measurement rather than a budget.
     *
     * Every other number in {@code MemoryBudget} is a share of the heap. This
     * one describes an object the server produces - the 160x160 small peer photo
     * - so it must not scale with our heap in either direction, or a small
     * handset would talk itself into believing avatars are cheap there.
     */
    private static void theAvatarEstimateDoesNotScale()
    {
        MemoryBudget.reset();
        long unmeasured = MemoryBudget.avatarDecodeCost();
        Assert.isTrue("an avatar costs more than its framebuffer",
                unmeasured > 160L * 160L * 4L);

        install(REFERENCE_HEAP);
        Assert.equal("the reference heap does not change it", unmeasured,
                MemoryBudget.avatarDecodeCost());
        install(512 * 1024);
        Assert.equal("a tiny heap does not change it", unmeasured,
                MemoryBudget.avatarDecodeCost());
        install(64 * 1024 * 1024);
        Assert.equal("a large heap does not change it", unmeasured,
                MemoryBudget.avatarDecodeCost());
        MemoryBudget.reset();

        // The measured blobs: 160x160 at up to 16 129 compressed bytes, read
        // back out of a real account's tgavatars store. If this ever has to
        // change, it changes because a new measurement said so.
        Assert.equal("it prices the measured avatar",
                MemoryBudget.photoDecodeCost(160, 160, 16 * 1024),
                MemoryBudget.avatarDecodeCost());
    }

    // -------------------------------------------------------------- helpers

    private static void install(int ceiling)
    {
        MemoryBudget.init(ceiling, 0, MemoryBudget.SOURCE_MEASURED);
    }

    private static int[] snapshot()
    {
        return new int[] {
            MemoryBudget.packetBytes(),
            MemoryBudget.inflateOutputBytes(),
            MemoryBudget.photoCompressedBytes(),
            MemoryBudget.updateQueueBytes(),
            MemoryBudget.httpQueueBytes(),
            MemoryBudget.maxDialogs(),
            MemoryBudget.dialogPageSize(),
            MemoryBudget.maxHistory(),
            MemoryBudget.historyPageSize(),
            MemoryBudget.layoutWindowScreens(),
            MemoryBudget.historyPrefetchMargin(),
            MemoryBudget.dialogPrefetchMargin(),
            MemoryBudget.peerCacheEntries(),
            MemoryBudget.avatarCacheEntries(),
            MemoryBudget.thumbnailCacheEntries(),
            MemoryBudget.screenStackDepth(),
            MemoryBudget.photoPixels()
        };
    }
}
