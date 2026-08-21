# Alcatel One Touch 810D — RNG seeding, measured

Nothing here is taken from a specification site. Every figure below was read off
the physical handset by `J2MEgram Probe`, menu item **Entropy measure**.

**Verdict: about 58 bits of min-entropy per `Entropy.gather()` call, and no
repeated seed across seven launches — six of them cold boots started from an
identical wall clock.** That is enough to stop treating the seeding as an
unknown, and not enough to generate a 2048-bit DH secret from a single
`gather()`. See [what this changes](#what-this-changes).

| | |
|---|---|
| Handset | Alcatel One Touch 810D |
| Runtime | MIDP 2.0 / CLDC 1.1, ~5.06 MB VM heap |
| Measured | 2026-07-31 |
| Build | `probe` target, `-Env test`, unobfuscated |
| Conditions | SIM in the phone; battery removed for at least a minute between cold boots; clock set by hand to `00:00` each time on the firmware's default date |

---

## a. Clock granularity

```
changes = 260 in 1500 ms
min delta = 4 ms  <- tick
max delta = 33 ms
distinct deltas = 9
top: 5(141) 4(88) 14(11)
reads per tick = 1113
USABLE (3-9 ms)
```

The tick is 4 ms and, importantly, it is **not uniform**: 5 ms dominates, 4 ms
is next, and there is a tail out to 33 ms across 9 distinct deltas. A clock that
advanced in exact fixed steps would leave the busy loop nothing to measure. This
one carries scheduler noise in its own right.

## b. Jitter spin counts

```
n = 1723 in 8002 ms
range 25..1297 step 3
clamped 102 outside range
distinct 119 lvls 8
first: 302 1133 1256 1250 1258
p_max = 118 per 1000
H_raw = 3.000
H_99% = 2.750
Hc = 1.750 pair/2 = 1.500
serial correlation detected;
discounted by pair/2 over Hc.
lag1 repeats = 4 per 1000
headline H = 2.250
gather() takes 26 samples
=> 58 bits per gather()
```

The distribution is **bimodal**, which is the single most important structural
fact about this source: a loop that starts just after a tick counts a full
tick's worth of reads (~1250), one that starts near a boundary counts almost
nothing (the `302` and the `25` floor). Both populations are real; neither is an
outlier.

The serial-correlation check found a genuine but mild dependence — pair entropy
per sample is 1.500 against 1.750 for single samples, a 14% discount, which
takes the 99%-bounded figure from 2.750 to 2.250 bits per sample.

`gather()` collects 26 samples in its 120 ms window, hence 58 bits.

Two honest limits on that number. `clamped 102 outside range` means 5.9% of
samples fell outside the calibrated range and were folded into an end bucket,
which lowers the estimate. And MCV assumes IID; the pair check is a correlation
discount, not the full SP 800-90B non-IID track. Both push the figure down, so
58 is a lower bound.

## c. Identity hash codes

```
held 256: distinct 256
 83f4a60 f044eaeb 130b281a
 stride 190275415 in 1/255
dropped 256: distinct 256
 => H 8.000 per call
```

**This runtime does not hand out a sequential counter.** The most common stride
between consecutive allocations occurred once in 255 gaps, and the values look
like a randomised identity hash rather than an address or an index.

The `dropped` variant is the one that matters, because it is what `gather()`
actually does — allocate an object, read its hash, discard it. All 256 were
distinct, so the allocator does not reuse a slot and hand back the same value.
`H 8.000 per call` is the ceiling the sample size allows (256 distinct out of
256), so the true figure is **at least** 8 bits and was not pinned further.

`gather()` folds in two freshly allocated objects' hashes — the `new Object()`
and the `Sha256` it builds — so this contributes on every call. The 58-bit
headline **counts it as zero**.

## d. Heap readings

```
allocated 256 objects
idle reads: 1 distinct/256
after alloc: 256 distinct/256
spread = 1020 b
totalMemory: 1 distinct
free now = 3565948
=> <= 8.000 bits per read
(drift, not unpredictability)
```

`freeMemory()` is constant when nothing is allocated between reads and moves on
every allocation. That is deterministic drift, not unpredictability — an
observer who knows the allocation sequence knows the value — so it is counted at
zero, as the report says.

## e. Cross-restart determinism

The critical test. Seven launches, six of them cold boots with the battery out
for at least a minute and the clock set by hand to `00:00` each time.

```
launches recorded = 7
suite = probe
#1 t=1785511694517 82372406
#2 t=1293829211577 9d40f941
#3 t=1293829210581 af3d0662
#4 t=1293829213517 f56a1072
#5 t=1293829218535 eaa5663f
#6 t=1293829211521 acb06c4e
#7 t=1293829213517 44832567
digest collisions = 0
clock went BACKWARDS 3x
=> CLOCK RESETS AT BOOT
```

**`#4` and `#7` started at the same millisecond — `t=1293829213517` — and
produced completely different digests.** That is a direct demonstration that the
seed is not derived from the wall clock: pin the clock exactly and `gather()`
still diverges. It is the strongest single result in this report, and it arrived
by accident of timing rather than by design.

Launches `#2`–`#7` all begin `1293829...`, which is **2011-01-01 00:00 local
time at UTC+3** — the firmware's default date, plus the hand-entered `00:00`.
They span only about 8 seconds, so the adversarial condition was tight.

`#1` is the launch before the battery was first removed, carrying the real
2026 clock. It does not disturb the comparison, which is pairwise over digests.

### Two device facts worth recording

The OT-810D **loses the RTC when the battery is removed** and then **requires
the user to enter the time by hand** before proceeding. In the field that is a
weak mitigation: a phone that has lost power always gets a human-supplied
timestamp rather than a fixed epoch. It cannot be relied on — a user may well
enter `00:00`, which is exactly what was done here to create the worst case.

A plain power-off with the battery in place preserves the clock.

## f. Key-press timing

50 presses, typed briskly rather than at a natural composing pace.

```
n = 50 presses over 7 s
delta 88..272 ms
mean 157 ms
distinct deltas = 33
gcd(deltas) = 1 ms
H_raw = 3.000
d & 15 -> 3.000
d & 31 -> 3.000
distinct keys = 21
usable = 3.000 bits/press
256 bits needs 86 presses
```

**3 bits per press, and the keyboard is a worse deal than jitter on this
handset.** That is the opposite of what `Entropy`'s own notes assume.

Per unit of time it is not close. Jitter yields 58 bits per 120 ms `gather()`,
so 256 bits costs five gathers — about 600 ms of busy-looping, invisible to the
user. The same 256 bits from the keyboard costs 86 presses, roughly 13 seconds
of brisk typing, and needs the user's attention throughout.

There is still a quality argument for the keyboard that this number does not
capture: human motor noise is unpredictable in a way that is easy to defend,
whereas jitter's unpredictability rests on scheduler behaviour that a determined
attacker might model. Key timing remains worth folding in when it is free. It
cannot be the plan.

Three limits on the 3.000 figure, all pushing different ways:

- **No confidence bound.** 49 intervals is below the 256-sample floor at which
  `MinEntropy` will print a 99% bound, so this is a raw MCV estimate and less
  trustworthy than the jitter figure, which had 1723 samples and the full bound.
- **The typing was fast and even** — 88..272 ms around a 157 ms mean. Natural
  composing, with pauses to think and reaches across the keypad, would spread
  the intervals further and measure higher. This is a floor for this user, not
  for the handset.
- **`gcd(deltas) = 1 ms` does not prove millisecond resolution.** The gcd test
  catches a uniform quantum — a 15 or 16 ms event clock would show up as a gcd
  of 15 or 16. It cannot see this handset's case, where the tick alternates
  between 4 and 5 ms (section a): sums of 4s and 5s hit essentially every
  integer, so the gcd collapses to 1 while the real timing resolution stays
  around 4 ms. Read the gcd as "no single quantum found", not as "resolution is
  1 ms".

`distinct keys = 21` confirms the run was not one key pressed rhythmically,
which would have manufactured a regularity the estimator would have punished.

---

## What this changes

Applying the decision rule written before the handset was run: no digest
collision, the clock is not frozen, and `8 <= B < 128`.

**The caveat is sharpened, not lifted.** `Entropy.estimatedBitsPerGather` now
returns 58 instead of 0 — a measured figure for this handset, and not a claim
about any other. A single `gather()` is short of a 2048-bit DH secret by roughly
a factor of five.

*Later:* two further handsets showed that this number is a property of the
clock rather than of the code — 21 bits on a Samsung GT-C3592, 135–165 on a
Nokia C3-00 — so nothing sizes itself from it any more. The barrier measures its
own yield while collecting (issue #2), which on this handset comes to about ten
gathers. The 58 remains what it always was: one device's measured floor.

Recorded separately, because it is orthogonal to the bit count: **the wall clock
contributes 0 bits across cold boots on this handset.** Jitter and the
allocator's identity hashes carry the pool alone. The `#4`/`#7` pair is what
proves that is survivable here.

And the design direction changes. `Entropy`'s notes describe collecting a few
seconds of keyboard interaction before generating an auth_key, on the premise
that the user is the best source available. Measured, the user is the *expensive*
source: five `gather()` calls buy 256 bits in 600 ms with no user involvement,
where the keyboard needs 86 presses. **The cheap fix is to seed from several
gathers; keyboard entropy is a supplement, not the mechanism.**

## Acted on

- **Multi-gather seeding shipped in v0.7.1.** `tg.crypto.AuthKeySeeding` folds
  five separated `gather()` results into the pool, under a domain-separating
  context, before `tg.mt.Handshake` draws anything for a permanent key; `Rng()`
  still calls `gather()` once, which is the right cost for nonces and padding.
  The count is sized from the 58 above against a 256-bit target — a division, not
  an addition, since these measurements do not establish independence between
  consecutive gathers.

## Still open

- **Independence between gathers is assumed, not measured.** The sizing rule
  above treats five gathers as covering 256 bits. The samples come from the same
  scheduler on the same idle handset seconds apart, and this run says nothing
  about how much they share. Measuring that needs a probe run built for it.
- **Identity-hash entropy above 8 bits/call** is unpinned — the measurement is
  capped by its own sample size, and it is charged at zero in the 58.
- **The keyboard figure has no confidence bound** at 49 intervals, and was taken
  at a brisk even pace. A few hundred presses at a natural pace would give a
  number worth relying on; 3 bits/press is only firm enough to conclude that
  jitter is the better mechanism.
- **One handset is one handset.** Nothing here transfers to another runtime.
