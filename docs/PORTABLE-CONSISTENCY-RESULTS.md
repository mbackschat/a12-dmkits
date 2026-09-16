# Portable consistency checker — measured results

Dated measurements of the Kernel-free checker, newest first. **Every LATENCY here is informative and uncertified, and always will be**: [H5](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner) requires a budget to pass IF137's six-point gate before the implementation it gates is measured, and a millisecond figure taken on one machine has no basis that survives another. No duration on this page is a bar, a regression floor or an acceptance criterion, and the lanes that produce them write no anchor, because an anchor is a bar.

**Two quantities ARE certified, as of the owner's 2026-09-16 ratification** — the [split by what each quantity can honestly bear](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification), which carries each number's six-point disposition: the **delivery byte ceiling** (1 MiB on the packed npm tarball, held by `:consistency:verifyConsistencyDeliveryCeiling` in push CI) and the **prepared growth ratio** (`PerfBounds.SUB_QUADRATIC_MAX`, held by `:adapter:checkerGrowthPerfTest`). The **cold growth ratio** is certified on the same footing and asserted since H5-d, by `:adapter:checkerColdStartPerfTest`. Its durations are not, and never will be. So read a table below for its curve and its shape, never for permission to be slower.

Results never land in a spec, and a measurement's home is not its explanation: the mechanism paragraphs below are marked as hypotheses where they are hypotheses.

## 2026-09-16 — H5-d: the cold curve gets a bar, and it is the looser of the two <a id="cold-paired-2026-09-16"></a>

**Lane:** `./build.sh :adapter:checkerColdStartPerfTest` (`adapter.perf.CheckerColdStartPerfTest`), one **fresh JVM per observation**, now launched as **51 interleaved N/4N pairs** instead of two sequential campaigns — the ratified paired estimator, median of the within-pair ratios. Same machine, JDK and flat workload as the 2026-09-15 rows below; `JdkIdentity.requireSupported` gates the lane and every process's rejecting-finding set is compared whole, empty at both scales. **Revision:** `fc1605c28`.

| quantity | 200 programs | 800 programs |
|---|---|---|
| `check` median | 307.79 ms | 859.24 ms |
| `check` p95 | 376.98 ms | 1 015.82 ms |
| `check` min–max | 297.50–500.31 ms | 793.46–1 258.63 ms |
| `process` median | 352.91 ms | 901.34 ms |
| **median within-pair ratio** | — | **2.75×** |

**The bar is the ratio and nothing else.** 2.75× over a 4× scale-up sits under the ratified `PerfBounds.SUB_QUADRATIC_MAX` floor of 9.0 with 3.3× of headroom. No duration in that table is asserted or anchored, and none ever will be here: a cold millisecond figure has no basis that survives another machine, which is why [the ratification](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification) gave this boundary a ratio and gave delivery the only absolute bar.

**It is the looser of the two growth instruments, and the numbers say so rather than a comment.** The prepared lane measures **5.33×** on the same workload at the same two scales; this one measures 2.75×. Both clock the same operation, model text to complete report, so the gap has to come from what only a fresh process carries — the per-process fixed cost and the absence of JIT. That much is a **hypothesis**: it is consistent with the ~130 ms of cold class loading the 2026-09-15 profile estimated (subtracting it puts the checker's own cold work at 4.10× per 4× scale-up, near-linear), but the subtraction reuses these same two medians and therefore cannot test itself. What would settle it is a child that reports its own class-loading time directly, and that has not been measured. The practical consequence is the part to act on: **a blowup trips the prepared lane first, and this lane is not claimed to see any defect class that one cannot.**

**Interleaving widened each scale's tail and that is the trade it exists to make.** The post-fix run of 2026-09-15 saw 725.70–870.25 ms at 800 programs against 793.46–1 258.63 ms here. Fifty-one pairs alternate the scales, so each scale's samples now span the whole 73-second run instead of clustering in one half of it: drift lands *inside* every distribution rather than between the two, which is what makes the ratio robust and the per-scale spread wider. The ratio is the quantity under assertion, so that is the right direction to pay in.

**The assertion was controlled rather than assumed, and the control measured reproducibility for free.** With the floor lowered to 2.0 — under the observed ratio, the rest of the lane untouched — the lane refused across all three independent re-measurements, at 2.791×, 2.766× and 2.765×, and failed on their median, which is the helper's acceptance rule. Without a control of that shape a growth assertion can pass while computing nothing. The by-product settles IF137's point 5 by measurement instead of by assertion: **four independent estimates of this ratio — 2.75× in the accepted run, then 2.791/2.766/2.765 — span 1.5 %**, over 408 fresh JVMs in total. Each attempt also reported its own per-scale medians rather than an accumulated set, which is why a re-measured breach still describes runs that happened.

## 2026-09-16 — H5's resources-and-delivery boundary, in the collector that already owned it <a id="delivery-2026-09-16"></a>

**Collector:** `scripts/interpreter-delivery-footprint.init.gradle` — the existing IF173 collector, extended rather than duplicated, because the reason it is an *init script* injected into the previous release's checkout is that both revisions measure by one definition of a byte basis. **Revision:** `153ae86a2`. Measured on the current tree with the artifact smokes skipped and an existing native binary supplied, which is the collector's own shape-test path; the cross-revision comparison is a release-grade run and is not what these rows needed.

| base | definition | bytes |
|---|---|---|
| `checker-jvm-thin` | the checker's JVM jar only | 1 059 331 |
| `checker-js-klib` | the checker's Kotlin/JS KLIB only; not a production package | 1 148 688 |
| `checker-npm-packed` | the real packed tarball, the artifact H2's Node and browser consumers receive | **634 443** |

**The packed tarball is the number that answers the boundary.** 620 KiB is what a Node or browser consumer downloads to check a DocumentModel without the Kernel; the JVM jar and the KLIB are development artifacts and are recorded so a later jump can be attributed to one of the three rather than to "the checker got bigger". For scale, the interpreter's own packed npm tarball at the same revision is 1 311 139 bytes.

**Re-measured and ratified 2026-09-16.** Two further packs at `0d1d6f2fe` returned 634 443 bytes again — the same length and the same SHA-256, so `npm pack` is bit-reproducible here and this quantity needs no sampling regime. The owner then ratified **1 MiB** as the ceiling, held by `:consistency:verifyConsistencyDeliveryCeiling` on every push; it reuses the pack the consumer test already performs, so the bar costs nothing that was not already being built. A control run with the ceiling lowered to 100 000 bytes refuses with the byte count and the corrective, which is the only evidence that the comparison is live.

**Both baseline arms were controlled before landing.** `:consistency` is younger than the releases this collector runs against, so a baseline without it is the ordinary case, and the three ids must still appear — the collector's base-set equality check refuses a report whose ids differ. Pointed at a deliberately unresolvable project name, the run emits all three as `NOT_AVAILABLE_IN_BASELINE` with the baseline flag set, and refuses outright without it, naming the missing tasks.

## 2026-09-15 — H5-c, prepared growth, and the control that keeps the cubic from returning <a id="growth-2026-09-15"></a>

**Lane:** `./build.sh :adapter:checkerGrowthPerfTest` (`adapter.perf.CheckerGrowthPerfTest`), in-process and warm, the ratified paired estimator over 5 interleaved N/4N pairs, median of within-pair ratios. Same machine, JDK and workload as below; typed authoring and the silence preflight — which doubles as the warm-up — sit outside every clock.

| quantity | 200 programs | 800 programs | within-pair ratio |
|---|---|---|---|
| prepared `check`, median | 51.23 ms | 261.19 ms | **5.33×** |

**What the ratio says.** Linear over a 4× scale-up would be 4× and quadratic 16×, so the prepared checker is mildly super-linear — an exponent of about 1.2 — and sits under the estate's ratified `PerfBounds.SUB_QUADRATIC_MAX` catastrophe floor with roughly 1.7× of headroom. Prepared checking is about three times faster in absolute terms than the cold lane's derived own-work figures (166 ms and 694 ms at the same two scales), which is the JIT, and shows a slightly higher exponent, which is within what a five-pair median separates.

**This lane asserts where the cold one only records, and the asymmetry is deliberate.** No checker latency is ratified, so no duration is asserted anywhere. A growth RATIO is a different thing: it is already ratified as a mechanism, survives a faster or slower machine, and is the only control that can see the defect class the cold lane found. A cubic checker returns exactly the right answers, so no correctness test in the estate can catch one — and the fix for the 2026-09-15 cubic is a single hoisted value with nothing structural stopping the next caller from un-hoisting it.

## 2026-09-15 — the curve was a cubic term, and it is gone <a id="hoisted-2026-09-15"></a>

Same lane, same machine, same workload, same preflight as the section below; the only difference is the fix described under it. **Median `check`, before → after:**

| programs | before | after the walk fix | + the kind fix | total factor |
|---|---|---|---|---|
| 100 | 330.58 ms | 217.30 ms | 211.89 ms | 1.56× |
| 200 | 552.13 ms | 300.91 ms | 296.10 ms | 1.86× |
| 400 | 1 562.90 ms | 503.48 ms | 469.97 ms | 3.33× |
| 800 | 8 567.77 ms | 908.17 ms | **823.88 ms** | **10.4×** |

**The shape changed, which is the part that matters.** Per-doubling ratios were 1.67× / 2.83× / 5.48× and are now 1.40× / 1.59× / 1.75×. Take out the fixed ~130 ms of cold class loading and the checker's own work goes 82 → 166 → 340 → 694 ms, which is 2.02× / 2.05× / 2.04× per doubling: **linear**, and more convincingly so than the totals suggest. The p95 spread collapsed with it — 725.70–870.25 ms at 800 programs against 4 404.28–9 970.16 ms before — so the wide tail that earlier read like memory pressure was the cubic term meeting a scheduler, not the heap.

**The locus, named from the flight recorder rather than from a reading.** 95 % of the samples on a 400-program model sat in two places: `declaredReferences` with its `resolveRelative`, and `computationSelfReadFindings` with its `computedTargetOf`. `declaredReferences` is a whole-model walk resolving one reference per element, and it resolved each by scanning the resolvable elements — quadratic. Fifteen families call it, and `computationSelfReadFindings` called it **per element**, which made the whole check cubic in declarations. The fix is two derived values on `PreparedModel`: the walk computed once, and the resolvable elements keyed by path. No family changed, and none had to remember to hoist anything — a rule no guard could have enforced, which is how the cubic arrived unnoticed.

**The second fix came from re-profiling rather than from guessing what was next.** With the walk hoisted, the top of the new profile was `DeclaredKind.of` and `DeclaredElement.declaredKind` — 14 of about 60 samples — because the kind was a `get()` that re-ran a four-entry scan on every access, and dozens of families filter on it. Storing it per element is one line and took 800 programs from 908 ms to 824 ms.

**Then it stopped, and that was a measurement too.** The profile after both fixes has no concentration left: the largest non-dispatch entries are 4–5 samples each, spread across `levelClassOf`, `iteratedLevels`, `crossedRepetitions` and `addressedTargetOf` — the per-reference walkers. About sixty sites still resolve a path with `elements.firstOrNull { it.path == … }`, so a family hot on a different model shape could contribute an n² term, and the same index that fixed the walk would fix them. None is hot here and the curve is linear on this workload, so they are left measured-and-untouched rather than swept on suspicion; re-profile before touching them.

## 2026-09-15 — H5-b, the cold first result, and the growth it exposed <a id="cold-2026-09-15"></a>

**Lane:** `./build.sh :adapter:checkerColdStartPerfTest` (`adapter.perf.CheckerColdStartPerfTest`), one **fresh JVM per observation**, 50 independent processes per scale, single-fork, no anchor written. **Machine:** Apple M1, 8 processors, 16 GB. **JDK:** OpenJDK 21.0.12.1 (the pin; `JdkIdentity.requireSupported` gates the lane). **Revision:** `505b29d60`. **Workload:** the typed synthesizer's flat shape at `programs` rules **and** `programs` computations over `2 × programs` number fields in one root group (`adapter.perf.ProfileSynthesisTargetProfiles.modelJson`). **Preflight:** every process printed an empty rejecting-finding set, compared whole, so each row times a complete report rather than a rejection path.

`check` is model TEXT to complete `ConsistencyReport`, inside the fresh process. `process` adds OS-process start ahead of it — what a standalone user waits for.

| programs (rules + computations) | fields | `check` median | `check` p95 | `check` min–max | `process` median |
|---|---|---|---|---|---|
| 20 | 40 | 140.08 ms | 150.50 ms | 137.36–159.38 | 180.58 ms |
| 80 | 160 | 259.98 ms | 265.70 ms | 241.39–268.07 | 299.91 ms |
| 100 | 200 | 330.58 ms | 493.82 ms | 288.60–841.01 | 377.49 ms |
| 200 | 400 | 552.13 ms | 724.58 ms | 520.45–937.15 | 595.27 ms |
| 400 | 800 | 1 562.90 ms | 1 977.14 ms | 1 464.13–2 019.90 | 1 605.07 ms |
| 800 | 1 600 | 8 567.77 ms | 9 712.88 ms | 4 404.28–9 970.16 | 8 614.97 ms |

**Process start costs a flat ~45 ms and is not the story.** It is 40 ms of the 180 ms at 20 programs and 47 ms of the 8 615 ms at 800, so every question about scale is a question about `check`.

**The per-doubling ratio is not constant — it grows.** 100→200 is 1.67×, 200→400 is 2.83×, 400→800 is **5.48×**. A quadratic cost would hold 4× per doubling; a linear one 2×. So the measured curve is super-linear throughout the range and steeper than quadratic at the top of it, and the flattening at the bottom is the fixed cold cost (~130 ms of class loading and serializer setup) dominating a small model rather than better scaling.

**It is NOT memory pressure, and that was measured rather than assumed.** The 800-program row's 4.4–10.0 s spread around an 8.6 s median is equally the signature of a heap near its limit, so the same scale was re-measured with a fixed 6 GB heap (`-PcheckerColdHeap=6g`, against a ~4 GB platform default on this machine): median 8 685.91 ms, p95 10 595.98 ms — within 1.4 % of the default-heap median. The growth is therefore in the work, not in the collector. The wide spread at that scale remains unexplained; over a ~9-second single-threaded run, scheduling and JIT variance are candidates and neither has been measured.

**One topology, stated as the boundary it is.** Every row above is the flat shape: one root group holding all fields, every rule and computation declared in it. That is plausibly a worst case for any family that scans declarations per reference, and real models nest. The three shaped profiles (compact, breadth, depth) that would vary topology carry no scale knob, so this measurement transfers to them only as a hypothesis. Do not read these numbers as "the checker on a model of this size" — read them as "the checker on a *flat* model of this size".

**What is not claimed.** No locus. The curve says the cost is super-linear in declarations; it does not say which of the 103 families, or the shared preparation beneath them, carries it. Naming that needs a profile of one scale, which is its own piece of work and is not this lane's — and attributing it now from a reading of the sources would be a mechanism wearing an observation's confidence.
