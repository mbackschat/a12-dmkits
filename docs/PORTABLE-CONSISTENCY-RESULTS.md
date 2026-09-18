# Portable consistency checker — measured results

Dated measurements of the Kernel-free checker, newest first. **Every LATENCY here is informative and uncertified, and always will be**: [H5](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner) requires a budget to pass IF137's six-point gate before the implementation it gates is measured, and a millisecond figure taken on one machine has no basis that survives another. No duration on this page is a bar, a regression floor or an acceptance criterion, and the lanes that produce them write no anchor, because an anchor is a bar.

**Five quantities ARE certified — three by the owner's 2026-09-16 ratification, the fourth on 2026-09-17 and the fifth on 2026-09-18** — the [split by what each quantity can honestly bear](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification), which carries each number's six-point disposition: the **delivery byte ceiling** (1 MiB on the packed npm tarball, held by `:consistency:verifyConsistencyDeliveryCeiling` in push CI) and the **prepared growth ratio** (`PerfBounds.SUB_QUADRATIC_MAX`, held by `:adapter:checkerGrowthPerfTest`). The **cold growth ratio** is certified on the same footing and asserted since H5-d, by `:adapter:checkerColdStartPerfTest`. Its durations are not, and never will be. The fourth is the **keystroke reuse ratio** (`1.0×`, held by `:adapter:checkerAfterEditPerfTest` since 2026-09-17), and it is the only one whose ceiling comes from a feature's work count rather than from a measurement. The fifth is the delivery ceiling's **JVM half** (2 MiB on the `jvmJar`, held by `:consistency:verifyConsistencyJvmJarCeiling` on `:consistency:check`), which binds the jar and deliberately not the resolved closure — [the ratification](#jvm-closure-2026-09-18). So read a table below for its curve and its shape, never for permission to be slower.

**Every OTHER boundary is now UNSET by decision rather than by sequence**, which is the difference between this page today and on 2026-09-16: the after-edit lane's two remaining arms and the combined lane's additivity ratio each sat at ~1.0 by construction, so a bar on any of them could only be read off the lane's own spread. *(The DECISION stands; its stated basis moved on 2026-09-18 — the two after-edit arms now measure 1.27x and 1.30x, because only the snapshot path still records what a cell is keyed on. Amended [below](#after-edit-2026-09-16).)* The three dispositions are in the [ratification record](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification); none of them is waiting on a further run.

Results never land in a spec, and a measurement's home is not its explanation: the mechanism paragraphs below are marked as hypotheses where they are hypotheses.

## 2026-09-18 — the growth lane went red, and the quadratic scan the profile found under it <a id="quadratic-2026-09-18"></a>

> **LARGELY LIFTED 2026-09-18 — four lanes re-measured on a verified-quiet machine.** The **prepared growth ratio** stands at **4.19x** over three estimates spanning 6.2 %, and the same sitting re-took the prepared **Node** (4.33x), prepared **browser** (4.30x) and **after-edit** (keystroke 0.802x) lanes — [the whole set](#quiet-set-2026-09-18). Those figures are no longer provisional and the six contended growth figures they replace are scratched.
>
> **Two things this banner still covers.** The **cell-store residual** is re-measured but NOT settled, because its 2026-09-17 baseline is itself unverified — see the set's last paragraph. And the **hash-map re-profile** has not been run. Everything else dated 2026-09-18 that is a wall-clock figure and is not in the set above remains provisional.
>
> **EVERY OTHER WALL-CLOCK FIGURE DATED 2026-09-18 ON THIS PAGE IS PROVISIONAL, by the owner's instruction and for a measured reason.** The machine was found contended *after* the runs — `corespotlightd` at 85 % CPU with 66 hours accumulated, a Webex helper at 47 %, 5.3 GB in the memory compressor — so no duration, no median and no paired ratio measured that day may be used as a basis, including the ones that read as improvements. **What survives is of two other kinds**, and the findings below are written to rest only on those: SOURCE facts, which a reader can verify by reading the code, and profile SHARES, because a frame's percentage of its own process's samples does not depend on how much CPU another process stole. Re-measurement on a quiet machine is owed for all of it.

**What was OBSERVED is that the lane exited red; whether the bar was really breached is now ANSWERED — it was not** ([the quiet-machine set](#growth-quiet-2026-09-18), 4.19x against 9.0). Read the paragraph below for what a red exit is worth as evidence, which is the durable lesson; the suspicion it records was correct. `:adapter:checkerGrowthPerfTest` asserts the ratified `PerfBounds.SUB_QUADRATIC_MAX` of 9.0 on the JVM prepared curve, and after [the linear cell tax was removed](#cell-cost-2026-09-18) it failed, printing 9.07x and then two re-measurements above the floor. A red exit is an event rather than a number, and on a contended machine it is not evidence that the curve crossed 9.0 — the same lane printed 6.51x and 7.82x an hour later on a build that only removed work. **So the red is what prompted the investigation and is not a finding.** The finding is what the investigation found, and it is a source fact plus a share table.

**The mechanism: one `firstOrNull` over a whole-model list, asked once per declaration.** `TrackedDeclaration.declaredReference()` scanned `PreparedModel.declaredReferenceList` for its own path. With families now dispatched per declaration that is N scans of an N-element list. `node --cpu-prof` on the 800-program model put it at **1 154 ms — the largest single frame in the run, growing 19.5x against the check's own 5.5x** over the 4x scale-up. Four more frames grew the same way for the same reason: `declarationAt` (29x), `repeatableGroupPaths` (39x), `crossedRepetitions` (16x), `addressedTargetOf` (25x).

**The fix is three memoized indexes and no behaviour change** — `declaredReferenceByPath` in preparation, `cursorsByPath` and a repeatable-group list on the fact base, each first-occurrence-wins because `firstOrNull` is the answer it replaces. The read barrier's charge is untouched: `repeatableGroups()` records `DeclarationSetShape` exactly as `allDeclarations()` does, so no cell key moves.

| share of an 800-program run | before | after |
|---|---|---|
| `declaredReference`'s scan | **14.4 %** | **0.0 %** |
| `repeatableGroupPaths` | 2.4 % | 0.0 % |
| `crossedRepetitions` | 2.0 % | 0.0 % |
| `declarationAt` | 1.8 % | 0.0 % |
| `addressedTargetOf` | 1.2 % | 0.1 % |
| **the five together** | **21.8 %** | **0.1 %** |

**The share table is the evidence, and the wall clock deliberately is not.** A frame's share of its own run does not depend on how fast the machine was, which is the whole reason this finding is stated in shares — and the contention was visible in the lanes' own output before it was diagnosed: the Node arm came back with a p95 of 1 011 ms against 377 ms an hour earlier, its minimum worse too, which is contention and not code. The source half needs no instrument at all: a `firstOrNull` over a whole-model list, on a member called once per declaration, is quadratic by inspection.

**But the profile could not tell us how BIG the class was, and a source sweep the same day found 29 more sites.** A CPU profile names its hottest frames, so it locates a cost and never bounds one — the five frames above are the members large enough to surface, and enumerating `allDeclarations()`'s call sites afterwards gave **63 in total (2026-09-18)**, of which **29 were the identical bare shape**: `firstOrNull { it.path == X }`, a linear scan where `declarationAt` is the memoized lookup with the same answer and the same `DeclarationSetShape` charge. All 29 are converted, and `CheckerPathLookupGuardTest` scans every checker source so the idiom cannot return unnoticed in any spelling that has yet defeated it (the limits are stated below — it is a scan, not a proof). The oracle for "no verdict moved" is `:consistency:check` reporting **0 failures** over its 154 classes on both the JVM and Node carriers; the test COUNT is unchanged at 696 because this sweep added no test, so the count is invariant by construction and it is the failure column that could have falsified the claim. **No timing claim is made and none is needed** — a per-declaration linear scan is quadratic by inspection, which is why this half survives the banner above while the durations do not.

**8 call sites remain and none is a path lookup of any shape** — down from 34 after two further sweeps on 2026-09-18. By OPERATOR they are `filter` 4, `any` 1, `count` 1, `none` 1, `groupBy` 1; by QUESTION, 3 subtree filters, 3 aggregates that read a body (`indexFieldNameOf`, `declaredPattern`), one depth filter and the element-name census. The two sweeps were the eleven repeatability questions, which moved to `repeatableGroups()`, and the fifteen path-plus-kind gates, which moved to a `declarationAt(path, kind)` overload. *Superseded figures, 2026-09-18: the 34-site census read `firstOrNull` 13, `any` 11, `filter` 6, `count` 2, `groupBy` 1, `none` 1. The "25 compound / 9 aggregate" split published before that was withdrawn outright — those numbers were apportioned by operator while the prose beside them described a predicate SHAPE, and re-deriving by shape gave 23/11 on my definition against 19/15 on a cold reviewer's, which counted a path equality only as a conjunct and not inside a disjunction. Neither was wrong: the quantity was never defined, which is why an operator census is what stands here.*

**The compound shape WAS convertible, and the argument that it was not had one unexamined premise (corrected 2026-09-18).** This paragraph said a path equality paired with a kind test is "*not* equivalent to a lookup", because paths can collide — a model declaring two members of one parent under one name draws `CK_MODEL_NOT_BUILDABLE` and the checker keeps judging it — so where the first member at a path is a group and the second a field, the scan finds the field while the lookup finds the group and rejects it. Every step of that is measured and still true. The premise it never stated is that the index stays keyed by PATH: keyed by **(path, kind)**, first-wins on both components, the scan and the lookup agree on every input including a collided path, and the fifteen gates moved with no verdict change and no change of charge — `declaredKind` records nothing of its own, being a member the set's shape already covers. What survived the correction is the narrower claim: a lookup is equivalent for "the one where" and for existence, and not for `filter` or `count`, since two declarations can share a path AND a kind.

**The guard's own history is the argument for scanning rather than reviewing.** Its first version flagged a compound site, because a Kotlin string-template operand's inner `}` closed a character-class regex early — precisely the false positive that would have driven the verdict-changing rewrite; it balances braces instead. A cold review then defeated the second version three more ways, each an exactly-equivalent spelling it let through: a named lambda parameter (`candidate -> candidate.path == p`, an idiom four checker predicates already use), reversed operands (`p == it.path`), and an interposed `let`. All three now die as planted mutants, the operator set covers `first`/`single`/`last`/`indexOfFirst` too, and comments are blanked first — because this repository documents the rejected idiom beside the accessor it belongs to, so prose about the shape was itself being flagged. What the guard holds is every spelling anyone has yet defeated it with, not every spelling that could exist.

**Where the ratio actually stands: 4.19x, MEASURED ON A QUIET MACHINE (2026-09-18, later the same day).** <a id="growth-quiet-2026-09-18"></a> The six contended figures — 6.51x, 7.82x, 8.06x, 9.07x, 9.93x and 9.52x, across three builds and one contended machine — are **scratched by the owner's instruction**, and they were a spread about the machine rather than a measurement of the curve. Three independent estimates on a machine whose busiest process was the agent itself (1-minute load 1.35, nothing above 40 %, verified immediately before the set):

| estimate | medians, 200 -> 800 programs | paired ratio |
|---|---|---|
| 1 | 86.07 -> 337.65 ms | **4.13x** |
| 2 | 73.39 -> 317.27 ms | **4.39x** |
| 3 | 68.65 -> 299.40 ms | **4.19x** |
| **median of three** | — | **4.19x** |

**So the curve was never near the bar.** 4.19x against the ratified `PerfBounds.SUB_QUADRATIC_MAX` of 9.0 is 2.15x of headroom, and the red exit that opened this investigation was contention, exactly as the banner above suspected but could not assert. **Spread across the three is 6.2 %**, against a contended spread of 53 %, which is itself the measurement of what the contention was worth. The durations fall monotonically across the three invocations (86 -> 73 -> 69 ms at the small scale) — page cache and JIT warming across separate JVMs — and that is the standing reason the ratio is the asserted quantity and no duration here is.

**What this settles beyond the number.** The two optimisation slices deferred for want of this measurement — the condition-reference barrier arm and the subtree filters — are **not** justified by a growth problem, because there is no growth problem to justify them: both would buy a constant factor on a curve sitting at less than half its bar, and the barrier arm would buy it against a charge-soundness risk. They stay unbuilt, and the re-open trigger is a ratio that moves, not a slice that looks appealing.

### The same sitting re-measured the other three owed lanes, and the prepared curve agrees across all three HOSTS <a id="quiet-set-2026-09-18"></a>

The quiet window was used for the rest of what the banner voided. Same machine state, same session, each lane its own Gradle invocation with nothing beside it:

| lane | host | prepared 200 -> 800 programs | paired ratio |
|---|---|---|---|
| `:adapter:checkerGrowthPerfTest` | JVM | 68.65 -> 299.40 ms (median of three estimates) | **4.19x** |
| `:adapter:checkerPreparedNodePerfTest` | Node v24.18.0 | 234.35 -> 1025.89 ms (n=51 each) | **4.33x** |
| `:adapter:checkerPreparedBrowserPerfTest` | Chrome/153.0.8010.48 | 178.80 -> 782.50 ms (n=51 each) | **4.30x** |

**Three runtimes, spread 3.3 %.** That is the strongest statement this record can make about the prepared curve: the growth is a property of the algorithm and not of the host, measured on three independent engines whose absolute speeds differ by more than 3x. It is also the cross-host agreement H5's host axis asks for, obtained without varying anything but the host.

**The after-edit lane holds its ratified ceiling with room.** `:adapter:checkerAfterEditPerfTest` at 200 programs, 11 samples: keystroke stateless 135.831 -> snapshot 110.465 ms, **paired ratio 0.802x** against the ratified `1.0x` reuse ceiling. Its two companion arms are not bounded and are printed for shape only — build-snapshot 1.614x and saved-edit 1.493x, both of which *should* exceed 1.0 because building or re-admitting is work a keystroke does not do.

**What this does NOT settle: the cell-store residual.** The prepared-Node 200-program check is **234.35 ms** quiet-measured, against `36c6766e6`'s ~1 195 ms regression (closed) and the contended 304.39 ms (so contention was worth ~23 % here). Dividing by the 129.05 ms this arm published on 2026-09-17 gives 1.82x rather than the 2.36x the gap row carries — **but that division spans two machine states and the 2026-09-17 baseline is not itself quiet-verified**, so it is one measured number over one unverified one. The residual therefore stays [a gap row](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#cell-tax) and does not become a finding; closing it needs the *baseline* re-measured on a quiet machine, which is a different run from any taken here.

**The 200-versus-800 share comparison then found the NEXT quadratic term, and it is a source fact too.** `scopedTo` makes a fresh `TrackedFactBase` per declaration, and its derived views — the cursor list and the two indexes over it — were per-INSTANCE `by lazy`. So N scopes each rebuilt an N-element list: `getStringHashCode` under the index build went from **0.2 % to 15.6 %** of the run, its share growing **88x** against the 4x scale-up, and its two hottest stacks both bottom out in that builder. The member's own contract had said it "shares this base's model, transcript and cursor list" while sharing the first two, which is why [the views are now one object per check](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#cell-tax) and why identity — not equality — is what `ScopedFactBaseSharesItsViewsTest` asserts.

## 2026-09-18 — H5-j: a Node consumer's heap across 400 checks, which does not move <a id="memory-node-2026-09-18"></a>

**Lane:** `./build.sh :adapter:checkerMemoryNodePerfTest` (`adapter.perf.CheckerRetainedMemoryNodePerfTest`), one warm Node process, the same 200-program synthesis profile the growth lanes use, runtime **v24.18.0** pinned by `NodeIdentity`. **It asserts no bound and never will assert one taken from its own output** — per H5's ordering rule the collector comes first and the bar is the owner's through [IF137](INTERPRETER-FINDINGS.md#if137)'s six points.

**Its subject is RELEASE behaviour** — a heap flat across repeated checks, or one growing per call — which is §8's other clause beside the retained-bytes one [`CheckerRetainedMemoryPerfTest`](#memory-2026-09-17) answers on the JVM by holding a `ConsistencySnapshot` live.

*Corrected 2026-09-18: this paragraph said the subject "follows from the published surface rather than from a choice", because `a12-consistency.d.ts` then exported only string-in string-out calls and a Node consumer could hold no analysis. `snapshotOfJson` shipped a holdable handle later the same day, so retained bytes ARE now measurable on this carrier and H5-j re-opens for that arm ([the record](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5i-trigger-fired)). The measurements below are unaffected — they measure release, which is what they always measured.*

| settled post-GC heap | run 1 | run 2 |
|---|---|---|
| baseline, before any timed check | 11 560 928 | 11 574 496 |
| after 200 checks | 11 440 384 | 11 399 264 |
| after 400 checks | 11 599 072 | 11 516 440 |
| **first phase's delta** | **−120 544** | **−175 232** |
| **second phase's delta** | **+158 688** | **+117 176** |

**400 checks retain nothing measurable, and the SHAPE is what says so rather than the size.** Two equal phases are run so a per-call leak and a one-off warm-up cost can be told apart without a bar: a leak makes the second delta match the first. Here the first delta is NEGATIVE in both runs and the second is a fifth of a percent of an ~11.5 MB heap, so the deltas are the engine's own noise. The resolution that gives is ~175 KB over 200 checks, i.e. **under ~0.9 KB per check** — a measurement floor, not a bound.

**The two runs bracket the memoization defect, which makes them an unplanned control.** Run 1 ran the build committed at `36c6766e6`, where a check cost ~1.2 s; run 2 ran it at 509 ms ([the regression and its fix](#cell-cost-2026-09-18)), and the lane's own wall time fell from **466 s to 204 s**. The baseline heap moved 13 568 bytes between them, 0.1 %. So a 2.4× per-check cost difference left retention untouched: that defect was pure garbage, and this arm was blind to it — which is precisely why the retained-memory question and the per-call cost question need separate lanes.

**Every precondition is read out of the child's own output, because a number here is worthless without them.** The child refuses without `--expose-gc`; it then proves explicit collection actually reclaims, by allocating a million objects, dropping them and requiring at least half back — a post-GC reading on an engine that ignored the request is a reading of whatever happened to be uncollected. The parent requires exactly one gate line, exactly one warm-up with no rejecting findings and the wire shape the other arms measure (a reading over a model the checker refuses would measure the cost of rejecting), exactly one observation, and all three heap figures positive — that last one is not a bound but a check that the quantity was read at all, since a missing field prints as a plausible collector line. **Each check gets a DISTINCT diagnostic label**, deliberately: identical calls could read flat because something recognised them rather than because nothing was retained.

## 2026-09-18 — the cell store cost ~9x per check, and the growth gates could not see it <a id="cell-cost-2026-09-18"></a>

**How it surfaced, which is the part worth carrying.** Nothing looked for this. The Node retained-memory collector (H5-j) took 7m51s for 401 checks, so one check cost ~1.16 s where [the prepared-Node arm](#prepared-node-2026-09-17) had published **129.05 ms** the day before on the same model — and the 800-program figure it published, 828.67 ms, was *lower than a 200-program check now*. Running that arm's own child by hand on the installed package reproduced it immediately: **1 231 / 1 158 / 1 405 / 1 073 ms**. A perf lane's own printed cost is a measurement whether or not the lane asserts on it; this one had been asserting a ratio and printing a ~9x regression for nine commits.

| state of the 200-program check | median | how |
|---|---|---|
| published 2026-09-17, before the cell store existed | **129.05 ms** | `:adapter:checkerPreparedNodePerfTest` |
| as committed at `36c6766e6` | **~1 195 ms** (of four: 1 073-1 405) | that lane's child, run by hand |
| after the memoization fix below | **509.36 ms** (p95 610.17, min 471.44) | the lane, 51 warm observations |
| with cell bookkeeping disabled as a probe | **~330 ms** (356.7 / 302.7 / 293.9 / 352.7) | the lane's child on a planted build |
| after the stateless path stopped paying for a store nobody keeps | **304.39 ms** (p95 377.28, min 296.07) | the lane, 51 warm observations |

**The defect: a whole-model projection was rebuilt per cell.** `FactRead.DeclarationSetShape`'s value is every declaration's identity row plus the resolvable paths, and it lived in `CheckCells` as a FUNCTION. Every cell keyed on the set shape calls it twice — once when produced, once when validated against another model — so a 200-program check materialized that projection some hundreds of times. `node --cpu-prof` put `setShapeValue` at **0.532 s of self time plus 1.014 s of garbage collection in a 3.93 s** two-check run: the two largest frames in the profile, and the only two that named this checker rather than the standard library. The fix is `PreparedModel.declarationSetShape`, a `by lazy` beside `rowsByPath`, which projects the same members and was memoized from the start. Locked by `PreparedModelDerivedValueTest` on IDENTITY — the one oracle a rebuild cannot satisfy, where structural equality would pass either way.

**What the remaining 3.9x was, and what it is not.** Memoization recovered ~1 195 -> 509 ms; the published 129 ms did not come back, and the rest was the cell store's own bookkeeping plus something not yet attributed. Disabling both halves of that bookkeeping — the read transcript's `record` and the per-read value capture — put the same check at ~330 ms, so bookkeeping accounted for ~180 ms and the rest for ~200 ms.

*Amended later the same day, after the probe became a change — and every duration in this paragraph is provisional under the banner above:* `check` now tells `checkRun` it will not keep the store (`CellRetention.DISCARDED`), and the lane measures **304.39 ms** — 205 ms removed at 200 programs and 795 ms at 800. **That half is LINEAR, measured directly rather than by subtracting across days: 3.88x over the 4x scale-up**, which is what a per-cell cost should be, cells being 38 plus 60 per declaration. The residual over the published figure is 2.36x and stays [a gap row](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#cell-tax) rather than a mechanism — and it is the half that may genuinely be super-linear: 175 ms -> 1 301 ms is **7.4x**, though that one is derived by subtracting two runs a day apart and is weaker evidence than the 3.88x beside it.

**The growth gate stayed green throughout, and was never going to catch this.** The lane's asserted quantity, the paired ratio, *fell* from **6.42x to 5.75x** while the absolute cost rose ~9x: a large linear addition depresses a ratio, so the one certified bar on this lane moved in the reassuring direction. Removing that addition moved it back up to **6.73x**, which is the same effect read in reverse and the closest thing here to a control on the mechanism — a ratio is a poor instrument for an absolute regression in EITHER direction. A ratio bound is blind to this whole class by construction, and no other lane on this page asserts a duration — by design, and correctly, since a millisecond figure has no cross-machine basis. The lever that remains is the printed output, which is why the [run-discipline trigger](AGENT-RUN-DISCIPLINE.md) now names this lane for any change to the check path: it costs 3m07s and prints the number.

## 2026-09-18 — what a JVM consumer of the checker actually resolves <a id="jvm-closure-2026-09-18"></a>

H5-k names the reverse of the delivery gap: the packed npm tarball is bounded at 1 MiB and **nothing bounds what a Maven consumer pulls in**. So the first thing that row needs is the number, and it had never been printed. Measured by resolving `:consistency`'s own `jvmRuntimeClasspath` and adding the module's jar, which is what a consumer declaring the dependency gets:

| artifact | bytes |
|---|---|
| `consistency-jvm-0.13.0.jar` | 1 188 850 |
| `kotlin-stdlib-2.2.0.jar` | 1 750 373 |
| `kotlinx-serialization-core-jvm-1.9.0.jar` | 399 128 |
| `kotlinx-serialization-json-jvm-1.9.0.jar` | 278 414 |
| `annotations-13.0.jar` | 17 536 |
| `predicates-jvm-0.13.0.jar` | 4 109 |
| **total** | **3 638 410 over six artifacts** |

**Two readings, and the second is the one that matters for a ceiling.** The checker's own jar is 1 188 850 bytes — larger than the whole packed npm tarball's 1 MiB ceiling, which is not a contradiction: the JS artifact is a tree-shaken bundle of one target and the jar carries class files for the JVM target plus its resources. And **two thirds of what a consumer resolves is not the checker at all**: the Kotlin standard library with its `annotations` transitive, plus kotlinx-serialization, are 2 445 451 bytes of the 3 638 410, so a ceiling drawn over the closure would mostly bound dependencies whose size this project does not control, while a ceiling drawn over the module's own jar bounds the thing a change to this repository can move.

**RATIFIED the same day: 2 MiB on the OWN JAR, closure total informative.** The owner took the split above and bound the jar — 2 097 152 bytes against a jar that measures **1 190 483** at ratification time, so 1.76× headroom, the posture the tarball ceiling already carries at 1.65× (1 MiB against 634 443). It certifies bounded growth and never smallness. *(The table above reads 1 188 850, taken at `7d5d95050`; the jar grew 1 633 bytes over the commits between, which is why the bar's headroom is quoted from its own run rather than from the table.)* The closure total stays unbounded deliberately, on the reasoning of the paragraph above: a closure bar would fire on a Kotlin stdlib bump, and that alarm's remedy is never in this repository.

**The comparison is live, which is the only evidence a bar of this kind can give.** With the ceiling lowered to 1 000 000 the task refuses, printing `consistency-jvm-0.13.0.jar is 1190483 bytes against a 1000000 ceiling (0.84x headroom)` and then the corrective — find what entered the jar, or take the new number through IF137's six points. Same control the tarball ceiling documents at 100 000 bytes; restored from a byte copy immediately after.

*Re-derive with `./build.sh :consistency:consistencyJvmClosureReport`, which writes `consistency/build/reports/consistency-closure/jvm-consumer-closure.txt`, and `./build.sh :consistency:verifyConsistencyJvmJarCeiling` for the bar. Both ride `:consistency:check`.*

## 2026-09-17 — the checker's published ABI, enumerated for the first time <a id="abi-2026-09-17"></a>

Row 8 lists **Java API compatibility**, and its oracle asks for a "compiled public API comparison where relevant". The checker became a published artifact earlier the same day, so the question is what its ABI actually is — and nothing had ever printed it.

| what was measured | result |
|---|---|
| `api/consistency.api` (JVM), generated by `updateLegacyAbi` | 1 437 lines, **183 public classes** |
| — of those, in `io.github.mbackschat.a12.dm.consistency` | 159 |
| — of those, in `…consistency.syntax` | 24 |
| `api/consistency.klib.api` (Kotlin/JS + common) | 1 306 lines, member-level |
| the **shipped** TypeScript surface, `a12-consistency.d.ts` | **4** declaration lines, of which **2** are exported: `checkModelJson` and `checkEditJson`. The other two are a `Nullable<T>` alias and an unexported `KtSingleton<T>` helper that Kotlin/JS emits. *(Corrected 2026-09-17: this said "3 lines", which is what `wc -l` reports because the file ends without a newline — the count to state for a declaration file is its declarations.)* |
| `…syntax` types named in the shipped `.d.ts` | **0** |
| `checkLegacyAbi` on `:consistency:check` before this change | absent |

**The exposure was narrower and sharper than "the checker has no dump".** Both façade goldens already name checker types — `api/dmkits-consistency-gate.api` carries `check(…consistency.ModelSource)` and `report(): …consistency.ConsistencyReport`, `api/dmkits-interpreter-preflight.api` carries `CheckOutcome` and `ConsistencyReport` — but a type's **name** in a signature is all that pins. `ConsistencyReport` could lose `getFindings()`, change its return type or gain a member and **both published goldens would stay byte-identical**, while every consumer calling `run.report().findings()` breaks. That is the invariant that had no enforcement locus.

**Enabling validation was not sufficient, and the gap is the one this repository has shipped before.** Kotlin does not add `checkLegacyAbi` to `check`, so a committed `api/` directory reads as pinned and is verified by nobody. `:consistency:check` now depends on it; CI already names that task, so the gate reaches CI with no workflow edit.

**Each carrier was controlled separately, because [IRG3](INTERPRETER-GAPS.md) records a KLIB dump silently losing the member depth its JVM twin kept.** Deleting `public final fun getFindings ()Ljava/util/List;` from the JVM golden fails with `<<<ABI has changed>>>` naming that exact member; deleting the KLIB `<get-findings>` line fails naming `consistency.klib.api`. Neither verdict was allowed to stand in for the other — and no filter is applied here, so neither dump can be reduced to bare type headers the way a `*` pattern once reduced the interpreter's.

**The first reading of the dump is itself the finding: 183 public classes against two shipped functions.** Almost all of it is public by omission — Kotlin's default visibility is public, and the module was written as internal implementation before it became a published artifact. The gate is deliberately **unfiltered** so this stays visible; a filter drawn today would encode the accident as the contract. What the checker's contract *should* be is a design call, not a side effect of adding a gate, so it is recorded with a re-open trigger rather than decided here ([the disposition](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#stage-8-abi)).

## 2026-09-17 — H5-h's browser arm closes the prepared row's host grid <a id="prepared-browser-2026-09-17"></a>

With the Node arm below, §8's prepared row is now measured on all three hosts the owner's hard-acceptance line names. **One warm browsing context runs the entire campaign**, which is the exact opposite of what the cold browser arm does with a fresh context per observation — and the reason this is a separate page rather than a parameter. §8 asks for the cold and prepared costs "reported separately"; one page cannot be both a context that has never loaded the checker and one that has loaded and warmed it.

**Lane:** `./build.sh :adapter:checkerPreparedBrowserPerfTest` (`adapter.perf.CheckerPreparedBrowserPerfTest`), 51 interleaved N/4N pairs inside one warm context in a system Chrome driven by Playwright, the ratified paired estimator. Runtime **Chrome/153.0.8010.48**, recorded rather than pinned, as the cold browser arm explains. Same machine, workload and generator as the two prepared arms below. **Revision:** `8eaa8a059` plus this lane.

| quantity | 200 programs | 800 programs |
|---|---|---|
| `check` median — warm whole-model check through report text | 104.30 ms | 691.60 ms |
| `check` p95 | 121.70 ms | 729.00 ms |
| `check` min–max | 101.10–130.70 ms | 667.00–797.00 ms |
| `reportChars` — the serialized report's length | **4 892** (1 distinct value) | **4 892** (1 distinct value) |
| **median within-pair ratio** | — | **6.59×** |

**The bar is the ratio and nothing else.** 6.59× sits under the ratified `PerfBounds.SUB_QUADRATIC_MAX` floor of 9.0 with **1.37× of headroom — the thinnest of any checker lane**, narrowly under the Node arm's 1.40×. No duration is asserted and no anchor written; a browser millisecond has no basis that survives another machine, another Chrome or another bundler.

**The three prepared ratios rise with distance from the JVM, and no mechanism is offered.** JVM **5.33×**, plain Node **6.42×**, browser **6.59×**, on one workload at one pair of scales. What CAN be said is what the rival mechanism is not: this arm's `check` renders the report to JSON inside the call where the JVM quantity stops at the object, and `reportChars` is **4 892 at both scales on both JS hosts** — so the serialization is a fixed per-call cost, cannot scale with the model, and in any case a fixed cost *depresses* a ratio rather than raising it. The remaining candidates (engine, bundler output, a different growth constant) are unmeasured, and the discriminator would be a JS entry point returning a report without serializing it, which the published surface deliberately does not offer.

**Its own toolchain defect, found by running it rather than by reading it.** The first run failed with `locator.waitFor: Timeout 30000ms exceeded`. Playwright's own locator timeout is 30 s and fires *before* the harness's `withDeadline` wrapper — so the wrapper looked like the bound and was not. The cold arm never exposed it because each of its waits is sub-second, while this page reports once after roughly 100 s. The wait now carries an explicit bound and the wrapper stays strictly looser as a backstop. **A wrapper that cannot fire before the thing it wraps is not a deadline** — the same shape as the unreachable child-process fuse found hours earlier, in a different library.

**The assertion is controlled rather than assumed.** With the floor lowered to 2.0, the lane refused across all three independent re-measurements — **6.576×, 6.585×, 6.591×** (107.5→709.4, 110.8→726.4, 109.7→725.5 ms) — failing on their median. **Four independent estimates spanning 0.2 %**, which is the tightest spread on this page and exactly what one warm context should give: no process launches and no fresh-context variance enter the numbers at all.

## 2026-09-17 — H5-h: the PREPARED boundary on plain Node, and the rival mechanism ruled out <a id="prepared-node-2026-09-17"></a>

§8's prepared row reads host-neutral, but the owner's hard-acceptance section attaches "JVM, plain Node and browser consumers" to the whole budget list, so this cell was one of six the [host-axis record](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#stage-8-h5-host-axis) names. **What made it schedulable was reading the JVM lane rather than assuming what it measures:** `CheckerGrowthPerfTest` times `check(ModelSource(text))` on a warmed runtime — a repeated whole-model check, *not* a snapshot-reuse call. So the analogue is `checkModelJson` after a per-scale warm-up, which the checker's published JS surface already supports. Had "prepared" meant snapshot reuse, this arm would have been blocked on publishing a prepared API to JavaScript. *(Corrected 2026-09-18: the sentence that followed said `JsApi.kt` "declines for stated reasons" — it published `snapshotOfJson` later the same day, forced by N's own acceptance matrix. The measurement above is unaffected; the analogue was chosen by reading what the JVM lane clocks, not by what JS happened to export.)*

**Lane:** `./build.sh :adapter:checkerPreparedNodePerfTest` (`adapter.perf.CheckerPreparedNodePerfTest`), **one warm Node process** running all 102 observations as 51 interleaved N/4N pairs — the ratified paired estimator. One process, not one per observation: warmth is what this boundary is about, so relaunching would measure exactly what it excludes. Runtime **Node v24.18.0**, pinned by `checkerNodeMajor` and gated by `NodeIdentity`. The warm-up doubles as the silence and report-shape preflight, the arrangement the JVM lane uses. **Revision:** `7da510970` plus this lane.

| quantity | 200 programs | 800 programs |
|---|---|---|
| `check` median — warm whole-model check through report text | 129.05 ms | 828.67 ms |
| `check` p95 | 195.88 ms | 1 015.02 ms |
| `check` min–max | 126.04–202.35 ms | 802.62–1 060.32 ms |
| `reportChars` — the serialized report's length | **4 892** (1 distinct value) | **4 892** (1 distinct value) |
| **median within-pair ratio** | — | **6.42×** |

**The bar is the ratio and nothing else.** 6.42× over a 4× scale-up sits under the ratified `PerfBounds.SUB_QUADRATIC_MAX` floor of 9.0, with **1.40× of headroom — the thinnest of any checker growth lane** (the cold arms carry 2.3–3.3×). `SUB_QUADRATIC_MAX` was ratified as a mechanism on 2026-09-16, before this host had a lane, so asserting it here is not a bar fitted to this lane's own first run. No duration is asserted and no anchor written.

**The rival mechanism is ruled out by measurement rather than by argument, and it cost one integer.** This arm's `check` includes rendering the report to JSON, which the JVM lane's quantity excludes — an obvious candidate for why the ratios differ. So the child also reports the serialized report's length: **4 892 characters at both scales, one distinct value across all 306 observations of the accepted run and the control.** The report is model-INDEPENDENT, so the serialization is a fixed per-call cost and cannot account for any part of the difference. It also cannot account for it in the other direction: a fixed cost added to both scales *depresses* a ratio, so the underlying growth on Node sits, if anything, further above the JVM's than the raw figures show. What the difference IS remains unmeasured; the discriminator would be a JS entry point returning a report without serializing it, which the published surface deliberately does not offer.

**Against the JVM prepared arm — comparable quantity, and the Node arm is worse on both axes.** The JVM lane measures 51.23 → 261.19 ms at **5.33×**; this one 129.05 → 828.67 ms at 6.42×. Absolute figures are informative-only and neither is anchored, so what is worth reading is that the ratio is higher on a second carrier — which is why asserting the ratified floor here rested on this arm's own measurement rather than on the JVM's. Asserting a bar on a new carrier is a claim, never an inheritance.

**The assertion is controlled rather than assumed.** With the floor lowered to 2.0 and the rest of the lane untouched, it refused across all three independent re-measurements — **6.285×, 6.210×, 6.251×** (150.37→946.69, 151.12→928.58, 143.38→895.83 ms) — failing on their median, which is the helper's acceptance rule. **Five independent estimates of this ratio now exist — 6.42× accepted, 6.30× from the pre-discriminator run, then 6.285/6.210/6.251 — spanning 3.4 %**, over 510 warm observations.

## 2026-09-17 — the cold lanes' fuses could not fire, and all three re-measure unchanged <a id="cold-fuse-2026-09-17"></a>

Every perf lane in `:adapter` that launches a child advertised a harness timeout that was unreachable code. The shape was identical at four sites — `CheckerColdStartPerfTest`, `CheckerColdNodePerfTest` (the observation child **and** the version probe) and `CheckerColdBrowserPerfTest`'s tool runner — and the oldest carried it from the day it was written: read the child's whole output into a string, *then* call the timed `waitFor`. `readText()` blocks until EOF and EOF arrives only when the child exits, so a hung child never reached the timeout and the `error(…)` line naming it could never run.

| what was measured | result |
|---|---|
| the control against a deliberate re-implementation of the old shape | `the fuse fires on a child that never exits` **FAILED** (preemptive 20 s bound); the 4 MiB flood case **PASSED** |
| the same control after the fix | both pass, 4 cases green |
| sites carrying the defect | **4**, in 3 classes; `ProfileCliPerfTest` in the same package had the correct shape all along |

**The passing half of that first run is the interesting half: it is why the defect was invisible.** The old shape was *dead-fused*, not deadlock-prone — reading first is exactly what keeps a loud child from blocking — so every lane ran correctly on every good day and the fuse existed only in prose. And the repair that suggests itself makes it worse: wait first, read second, and a child whose output passes the pipe buffer blocks in its own `write` while the parent waits for an exit that can no longer come. `ChildProcess.runWithFuse` redirects to a file instead, so there is nothing for the child to block on and nothing to read before waiting; `ChildProcessFuseTest` locks both halves, because a change satisfying one and not the other is not a fix. [The shell half of this rule](AGENT-RUN-DISCIPLINE.md) had been written down in this repository for a year.

**All three cold lanes re-measured after the migration, and the quantity did not move on any host:**

| lane | ratio before | ratio after | medians after |
|---|---|---|---|
| `checkerColdStartPerfTest` (JVM) | 2.75× | **2.65×** | 314.52 → 834.53 ms |
| `checkerColdNodePerfTest` | 3.90× | **3.93×** | 260.46 → 1 022.03 ms |
| `checkerColdBrowserPerfTest` | 3.88× | **3.92×** | 203.50 → 796.30 ms |

Durations moved by a few percent and every ratio by about one, which is the spread these lanes already publish across re-measurements — the standing reason the ratio is the asserted quantity and no duration is.

**The extraction that followed found the shape hole's THIRD instance, which the review had not reached.** Three arms carried identical `report`/`format`/`requiredProperty`/field-parsing helpers, so they moved to `ColdLane`; each arm keeps its own observation record, those being genuinely different shapes. Following the shared code is what surfaced it: the JVM child printed no verdict at all, so `CheckerColdStartPerfTest`'s silence preflight had exactly the gap closed on the two JS arms an hour earlier — an empty rejecting set is also what a report with no findings looks like. The sibling sweep the root-cause rule asks for was what found it, not the review.

**And the fix for it nearly shipped an interop defect, which is the part worth recording.** Reading the verdict needs a status NAME. `ReportJson.wireName` is private, and its own comment says the mapping is written out precisely so nothing reflects over the class — so a `when` over the sealed status inside a perf lane would have been a second statement of a shape the checker owns, the #1 interop rule's exact prohibition one layer in from A12. A first attempt instead invented `checksCompleted.isNotEmpty() && coverageGaps.isEmpty()`, which named a property that does not exist (`gaps`) and would not have compiled — and which, had it compiled, would have been a third definition of completeness. What landed: each arm pins the spelling ITS OWN surface gives it, `"INCONCLUSIVE"` from the serialized report and `"Inconclusive"` from the `data object`'s compiler-generated name, with `ColdLane` carrying both constants and the reason they differ. Both are mutant-controlled.

| lane | ratio after the extraction |
|---|---|
| `checkerColdStartPerfTest` (JVM) | **2.68×** (314.63 → 852.78 ms), with its new shape preflight live |
| `checkerColdNodePerfTest` | **3.91×** (268.71 → 1 051.13 ms) |
| `checkerColdBrowserPerfTest` | **3.82×** (220.30 → 845.40 ms) |

**A guard for the class the sibling guards could not see also landed.** `verifyConsistencyBrowserTypeScriptSources` and its interpreter twin refuse authored JavaScript, which asserts what the sources ARE and nothing about whether a compiler reads them — which is how `vite.config.ts` sat outside every `include`. `ConsumerTypeScriptCoverageGuardTest` now requires every authored `.ts` in both browser consumer projects to be covered by a config the project's `check` script actually INVOKES; a config nobody runs leaves every symptom of the original defect in place, and that is the easier mistake to make the second time. The interpreter's project was swept in the same pass and is covered, having no `vite.config.ts` at all.

**The checker's ABI goldens also got the depth control they were advertised to have.** `checkLegacyAbi` compares each golden with its own dump, so a dump that collapsed to bare type headers is recorded faithfully and passes — IRG3 measured exactly that on a KLIB dump whose JVM twin kept every member, and the only control for the checker's pair was a planted deletion run by hand once. `CheckerAbiGoldenDepthTest` now refuses any type that carries JVM members and none in KLIB, and pins `ConsistencyReport`'s `getFindings`/`findings` from each carrier's parsed member set rather than by substring, because the file text names `findings` on the accessor line too. Four mutants, four kills on four different assertions: a KLIB header collapse, `findings` renamed in KLIB, `getFindings` renamed on the JVM, and a parser mutant that drops the matched-type floor from ~150 to 0 with the message naming the parse as the thing to suspect.

## 2026-09-17 — H5-g: the cold boundary in a real browser, and the host grid closes <a id="cold-browser-2026-09-17"></a>

With the plain-Node arm below, this is the third of the three hosts §8's cold row names, so that row's grid is complete: **JVM, plain Node and browser**. What a browser adds over Node is not the engine — V8 in both — but a bundler's output instead of Node's own module graph, an HTTP transfer instead of a filesystem read, and a document context.

**Lane:** `./build.sh :adapter:checkerColdBrowserPerfTest` (`adapter.perf.CheckerColdBrowserPerfTest`), one observation per **fresh browsing context** in a system Chrome driven by Playwright, 51 interleaved N/4N pairs, the ratified paired estimator. The page imports the checker **dynamically**, so Rollup emits it as its own chunk and the fetch-and-evaluate lands inside the clock. Same machine, same synthesized workload and same generator as the two arms below; every context's rejecting-finding set is compared whole, empty at both scales, and its report shape is asserted as INCONCLUSIVE/incomplete rather than assumed. **Revision:** `01097e9ed` plus this lane.

| quantity | 200 programs | 800 programs |
|---|---|---|
| `check` median — chunk fetch and evaluation through complete report | 208.80 ms | 810.80 ms |
| `check` p95 | 224.80 ms | 842.20 ms |
| `check` min–max | 198.10–248.80 ms | 790.40–842.30 ms |
| `module` median — the dynamic import alone | 23.20 ms | 25.20 ms |
| `module` p95 | 38.30 ms | 35.30 ms |
| **median within-pair ratio** | — | **3.88×** |

**The runtime under observation here is CHROME, and this arm closes H5's pinned-runtime point by attribution rather than by a pin.** The other two arms pin theirs — `JdkIdentity` the JVM family and major, `NodeIdentity` the Node major — but this project uses the SYSTEM browser deliberately, and no build file can pin one. So every observation carries the build it ran on, the lane refuses a set that carries no single non-blank build, and the progress line names it: **Chrome/153.0.8010.48**. It deliberately does not claim to refuse two *differing* builds, which it cannot: Chrome is launched once per run, so no input can produce a second value and such a check would pass for every input the sampler can produce. The Node arm's identical-looking check is honest because each of ITS children is a separate process free to resolve a different binary. Attribution is weaker than a pin and is enough for what a pin is for here, which is that a future Chrome cannot silently re-base the figures above with nothing in the record saying which build produced them. The `node` driving Playwright is pinned all the same and is not the runtime being measured.

**The `module` column is a positive control the lane gets for free — on the timer SPLIT, and on nothing else.** The checker's chunk is the same bytes at both scales, so a correctly split timer must show a model-INDEPENDENT module load, and it does: 23.20 ms against 25.20 ms across a 4× scale-up, where `check` moves by 3.9×. Had the module figure scaled with the model, the split would have been measuring something other than what it names. What it cannot do is vouch for the `check` figure itself — a wrong `check` clock with a correct split would show exactly this. The lane additionally refuses any context whose module load is under 1 ms, because that is what an inlined chunk looks like and the page would then be timing a warm call — the cold claim would be false rather than slow.

**Subtracting the module load moves the ratio UP, and the JVM entry's caveat applies here verbatim.** `check − module` is 185.60 ms at 200 programs and 785.60 ms at 800, a ratio of **≈4.23×** for the checker's own work against the published 3.88× — the module load is a near-fixed cost, so removing it deflates the smaller scale more. Two things about that number: it is a ratio of median DIFFERENCES and therefore not the ratified paired estimator at all, a different quantity that is not what the lane asserts; and, exactly as the JVM cold entry says of its own ~130 ms subtraction, it reuses these same two medians and so cannot test itself. It is recorded because a reader comparing hosts will otherwise subtract it themselves and get a figure this page never states.

**The bar is the ratio and nothing else**, as [the ratification](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification) set for both timing boundaries. 3.88× over a 4× scale-up sits under the ratified `PerfBounds.SUB_QUADRATIC_MAX` floor of 9.0 with 2.3× of headroom. Asserting the ratified ratio on a third carrier is a scope transfer, so it rests on this measurement rather than on the other two arms' — which is the whole reason the lane exists rather than the ratio being assumed to carry.

**Node and the browser agree on the ratio to within half a percent and disagree on the durations, and only the first of those is explained here.** Node measures 3.90× and this arm 3.88×, which is what one expects of the same algorithm on the same engine family — and no more than that: two arms on one engine family agreeing is weak evidence of anything wider, which is why each still carries its own measurement rather than inheriting the other's.

The browser is then *faster* at both scales — 208.80 ms against Node's 282.06 ms at 200 programs, 810.80 ms against 1 061.01 ms at 800 — and no mechanism is offered. **What must be said is that the two arms are not cold in the same sense**, which is a limit of the comparison rather than an explanation of it: the Node arm takes each observation in a **fresh process**, while this one takes it in a fresh **context** inside a Chrome that has already served earlier observations, so Chrome's V8 may carry compiled code across contexts in a way a new Node process cannot. That asymmetry is deliberate — a fresh Chrome per sample would measure its bootstrap — and it is stated because it is a candidate cause of the gap that the reader should not have to guess at. A minified bundle and differing V8 flags are others. None is measured, and the durations are informative-only in any case.

**What a browser consumer transfers, measured on the artifact this lane served:** the page fetches **two** modules — a 78-byte entry and the code-split checker chunk — for **649 648 bytes** minified in total, of which the checker chunk is 649 570. Gzipped **per asset**, which is how a server compresses them, that is **192 108 bytes** (191 987 + 121), at gzip's DEFAULT level; `gzip -9` gives 191 286 and Node's zlib 192 336, so the level has to be named or the figure is not reproducible. Both numbers are stated over the same two-asset denominator on purpose: an earlier version of this paragraph gave the raw figure for the chunk alone and the gzip figure for both, which is a comparison of two different things reading as one. This is the bundled subset a page fetches and is *not* the packed tarball the delivery row's 1 MiB ceiling governs — two different quantities over two different artifacts, recorded together only because a reader will want both.

**Re-measured after the cold review's corrections: 3.90×** (medians 207.00 → 810.20 ms, `module` 21.00 → 24.50 ms, 51 pairs, same Chrome build). The corrections added a per-SCALE observation count — the estimator reconstructs pairs by popping per-scale queues, so a sampler emitting one extra small and one missing large keeps the total at `2 × pairs`, clears every preflight, and then dies on an empty pop with the same bare exception this lane was already fixed for once — and replaced the browser-build check, which asserted a set of size one and could not fail, with a check that the recorded build is present and non-blank. **Five independent estimates now: 3.88× accepted, 3.904/3.898/3.893 from the control, 3.90× after the corrections, spanning 0.6 %.**

**One defect in this lane's own toolchain, found by the same review and fixed here: `vite.config.ts` was type-checked by nothing.** `tsconfig.json` includes `src/**`, `tsconfig.scripts.json` roots at `scripts/`, and the file that decides what Vite *emits* sat outside both — so a typo in `rollupOptions.input` would have dropped the cold page and the lane would have 404'd at measurement time rather than failing at build time. A third `tsconfig.config.json` now covers it and joins `pnpm run check`. Control: `input: {` → `input: 42,` in the staged copy gives `vite.config.ts(14,7): error TS2769`, which the two pre-existing configs both ignore. The project's guard against authored JavaScript says nothing about whether the compiler *sees* a TypeScript file, which is [its own follow-up](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#stage-8-cold-followups).

**The control found a defect rather than confirming the assertion, and it is the kind only a control can find.** This lane reconstructs the estimator's pairs by popping per-scale queues the sampler filled in visit order — bespoke plumbing, so the floor was lowered to 2.0 to watch it refuse. It did not refuse: it died with a bare `java.util.NoSuchElementException`. The assertion helper **re-measures** on a breach, by design, to tell a slow machine slice from an algorithmic blowup; the first version sampled once *outside* the assert block, so attempt one drained the queues and attempt two popped an empty list. **The happy path would have passed forever and the lane would have crashed with an opaque exception the first time growth ever breached** — the one moment it exists for. Sampling now happens inside each attempt, which is also the only correct semantics: reusing one sample set would return the identical ratio every time, so a re-measurement would confirm itself and the helper's whole discrimination would be gone.

With that fixed, the control did its job: the lane refused across all three independent re-measurements, at **3.904×, 3.898× and 3.893×**, failing on their median. Those three being *different* is itself the evidence that each attempt genuinely re-measured, which the first version could not have produced. **Four independent estimates — 3.88× accepted, then 3.904/3.898/3.893 — span 0.6 %**, over 408 fresh contexts. The Node arm below was structured this way from the start and so never carried the defect, which is why its control produced three genuine re-measurements on the first try.

## 2026-09-17 — H5-f: the cold boundary on plain Node, the host nothing had measured <a id="cold-node-2026-09-17"></a>

§8's cold row names **three hosts** — "installed package load through preparation and first complete consistency result, on JVM, plain Node and browser" — and every H5 lane built before today lives in `:adapter` and therefore on the JVM. So "measured" and "measured on the JVM" were indistinguishable across the whole set, which is why [the plan's row 8](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#stage-8) could record that "every budget H5's required list names is now set" while naming only the five **quantities**. The host was the constant nobody had varied — the same denominator error that row had already confessed one axis over, when the resources boundary read as done on one of its four required observations.

**And the JVM arm does not cover this host even in part, for a second reason: it never used an installed package.** `:adapter:checkerColdStartPerfTest` resolves `:consistency` as a project dependency on a classpath the build assembles. This arm launches a fresh Node process whose whole program imports `a12-consistency` by package name out of a consumer project's `node_modules`, populated by `npm install` from the packed tarball — so it is the first cold observation of the artifact a consumer actually receives, on any host.

**Lane:** `./build.sh :adapter:checkerColdNodePerfTest` (`adapter.perf.CheckerColdNodePerfTest`), one **fresh Node process per observation**, launched as **51 interleaved N/4N pairs** — the ratified paired estimator, median of the within-pair ratios. Runtime **Node v24.18.0**, pinned by `checkerNodeMajor` and gated by `NodeIdentity.requireSupported`; every child reports the version it ran on and the lane refuses a set spanning two. Same machine and flat synthesized workload as the JVM rows below, from the same generator. Every process's rejecting-finding set is compared whole, empty at both scales. **Revision:** `efcdaaf1d` plus this lane.

| quantity | 200 programs | 800 programs |
|---|---|---|
| `check` median — installed package load through complete report | 282.06 ms | 1 061.01 ms |
| `check` p95 | 350.17 ms | 1 295.29 ms |
| `check` min–max | 258.20–354.87 ms | 1 011.80–1 478.79 ms |
| `process` median — from process start | 298.54 ms | 1 077.87 ms |
| **median within-pair ratio** | — | **3.90×** |

**The bar is the ratio and nothing else**, the posture [the ratification](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification) set for both timing boundaries. 3.90× over a 4× scale-up is essentially linear and sits under the ratified `PerfBounds.SUB_QUADRATIC_MAX` floor of 9.0 with 2.3× of headroom. No duration in that table is asserted or anchored, and asserting one would breach H5's ordering rule outright — the bar would be fitted to this lane's own first run. `SUB_QUADRATIC_MAX` escapes that because it was ratified as a mechanism long before this host had a lane.

**What the cross-host numbers say, and what they do not.** Three ratios now exist on one workload at one pair of scales: JVM cold **2.75×**, Node cold **3.90×**, JVM prepared **5.33×**. The JVM cold entry below explains its own low ratio as per-process fixed cost deflating it, and names the discriminating case it lacks — "a child that reports its own class-loading time directly". This arm is **not** that case and must not be read as one, for two separate reasons. A different engine moves more than one variable, so at best this is a second carrier agreeing rather than a control. And the quantity that would do the discriminating is still not isolated on either host: `process − check` measures only the **pre-check** overhead — 16.48 ms here at 200 programs against the JVM arm's 45.12 ms — while each host's *loading* cost sits INSIDE its own `check` figure by construction (class loading on the JVM, module resolution and evaluation on Node), which is exactly what makes the timer honest and exactly what neither lane separates. So the ordering of the three ratios is consistent with the fixed-cost account and tests it no better than before; the within-host discriminator the JVM entry named is still the thing that would settle it.

**One observation with no mechanism offered:** the two hosts cross over. Node is faster at 200 programs (282.06 ms against 307.79 ms) and slower at 800 (1 061.01 ms against 859.24 ms) — the figures from the two tables on this page, which an earlier version of this line did not use. Both figures are informative-only and neither is anchored, so the crossover is recorded rather than explained; nothing here measures why.

**The assertion is controlled rather than assumed.** With the floor lowered to 2.0 — under the observed ratio, the rest of the lane untouched — the lane refused across all three independent re-measurements, at 3.912×, 3.915× and 3.920×, and failed on their median, which is the helper's acceptance rule. Without a control of that shape a growth assertion can pass while computing nothing. The by-product settles IF137's point 5 by measurement: **four independent estimates of this ratio — 3.90× in the accepted run, then 3.912/3.915/3.920 — span 0.5 %**, over 408 fresh Node processes in total. That is tighter than the JVM cold lane's 1.5 % across the same number of children, and no mechanism is offered for it here.

**Re-measured after the cold review's corrections, and the quantity did not move: 3.94×** (medians 260.58 → 1026.39 ms, 51 pairs, same machine and runtime). The corrections added a report-SHAPE preflight to this lane — an empty rejecting set is also what a report with no findings at all looks like, so `status`/`complete` are now published by the child and pinned to `INCONCLUSIVE`/incomplete, as the browser arm already did. The durations sit ~8 % below the accepted run's while the ratio moves 1 %, which is the usual direction: the ratio is the quantity under assertion precisely because it survives that. **Five independent estimates of it now exist — 3.90× accepted, 3.912/3.915/3.920 from the control, 3.94× after the corrections — spanning 1.0 %.**

**Read the ratio as the median of the within-pair ratios, not as the quotient of the two medians** — the ratified estimator's definition, and on this run the two differ visibly: 1 061.01 / 282.06 is 3.76×, while the median within-pair ratio is 3.90×. The estimator is the quantity that was ratified and the one under assertion; the per-scale medians are reported for the log line.

**Two preflights ride the lane, because a timing over the wrong thing is worse than no timing.** The checker must accept the synthesized model at both scales, or these processes timed the rejection path. And the consumer's `node_modules` must hold the checker **and nothing else**: the delivery row's closure figure rests on the checker declaring no dependencies, so a second installed package would mean this load time describes a closure that figure does not. It holds at exactly one entry.

## 2026-09-17 — two shipped POMs named a coordinate nothing publishes <a id="pom-closure-2026-09-17"></a>

F's row 8 lists **Java API compatibility**, and its oracle asks for a "compiled public API comparison where relevant". Following that to the checker found something one layer below the API dumps.

| what was measured | result |
|---|---|
| `dmkits-consistency-gate`'s generated POM | declares `io.github.mbackschat.a12.dm:consistency-jvm:0.13.0` at **compile** scope |
| `dmkits-interpreter-preflight`'s generated POM | the same declaration |
| `:consistency:tasks --all` | **no publishing tasks at all** — the module had no `maven-publish` block |
| `api/dmkits-consistency-gate.api` | its public signatures name `…dm.consistency.ModelSource` and `…dm.consistency.ConsistencyReport` |

So two published artifacts had an unresolvable compile dependency, on the module whose types their public API returns. Both expose those types deliberately — `PublicApiGuardTest.API_SCOPED_PUBLICATIONS` records the reason, that restating the checker's report in a second shape is worse than naming its type — so the fix is to publish the module, not to hide it. `:consistency` now publishes `dm-consistency` (the `dm-*` convention `:interpreter` and `:predicates` use for multiplatform artifacts), and both façade POMs name `dm-consistency-jvm`.

**The estate had already met this exact constraint and written it down.** `predicates/build.gradle` says it in as many words — *"published so `:rulekit` can depend on it without an unresolvable coordinate in a shipped POM, which is precisely the constraint that blocked C1's extraction"* — and [RELEASE-SPEC](RELEASE-SPEC.md) §3 carries the consequence for mirrors. The rule was known; nothing enforced it. `verifyPublishedPomsResolveInRepoCoordinates` now reads the GENERATED POMs (a coordinate is decided by the target project's publication, so a build script says nothing about what a consumer will be asked to resolve) and refuses any in-repo dependency outside the published set. Green: **17 POMs against 17 published coordinates**; control: removing the module's publication reproduces `names io.github.mbackschat.a12.dm:consistency-jvm (compile), which nothing publishes`.

**And adding the publication tripped the trap the repository documents, then showed the guard against it was disarmed.** A `publishing { }` block without `maven-publish` binds to the ROOT project — measured here for the second time, the first being `:predicates`. `verifyPublishingIsDeclaredLocally` exists for exactly that and **reported clean**: it tested `!(text =~ /maven-publish/)`, and the COMMENT explaining that this module had no maven-publish block contained the string. A guard whose subject is a plugin must look for the plugin being APPLIED, so it now matches `id 'maven-publish'` / `apply plugin: 'maven-publish'` at line start. Control: removing the application while leaving the comment names `[:consistency]`.

## 2026-09-17 — B0's reference grid, replayed through the packaged arms <a id="b0-replay-2026-09-17"></a>

B0 hands this replay away in as many words — *"the packed Node/browser replay of these same cases belongs to F, which owns the delivery tooling, so B0 does not silently depend on a later slice's deliverable"* — and F's row 8 lists it. It had not run. `ReferenceBindingTest` drives `prepare(...).resolve(base, name)`, an **internal** API no consumer can reach, so it cannot say whether the packaged artifact carries the behaviour at all.

Five models now drive the same grid end to end, through a rule whose condition names the field, which is the only way a resolution outcome becomes observable from outside. Measured through the packed tarball:

| B0 row | model | observed |
|---|---|---|
| **the positive control** — one model-wide `Code`, fallback ON | `catalog-reference-resolved` | **INCONCLUSIVE, no findings, `complete: false`** |
| row 1 — the same bytes, fallback OFF | `catalog-reference-no-fallback` | `MVK_INVALID_ENTITY` at `/Catalog/CodeRequired`, span slicing `Code` |
| absent reference | `catalog-reference-absent` | `MVK_INVALID_ENTITY`, span slicing `Missing` |
| ambiguous fallback | `catalog-reference-ambiguous` | `MVK_FIELDNAME_NOT_UNIQUE`, `explain` naming both `/Catalog/Warehouse/Code` and `/Catalog/Store/Code` |
| declaring-group precedence | `catalog-reference-declaring-wins` | **INCONCLUSIVE, no findings** — the declaring group wins although two candidates exist |

**B0's type-level clause had to be TRANSLATED, not transferred.** B0 establishes "the slice's type cannot emit VALID" by *the absence of a VALID factory* — a claim about a Kotlin API that no JSON string can carry. The packaged form is that no row reports `VALID` and every row reports `complete: false`, asserted over the whole grid rather than over the one row a reader might check. A control asserting only "no findings" would pass on a checker that had stopped checking.

**Authoring the positive control was not trivial, and that is the useful finding.** The first version was **INVALID for a reason unrelated to references**: `MVK_ERROR_FIELD_NOT_REFERENCED`, because the rule's `errorEntityRelPath` pointed at a field its own condition never mentions. A12 requires the error field to be referenced by the condition, so "a model A12 accepts" is a conjunction, and a positive control is only a control once every other constraint is satisfied too. Had the row asserted a code rather than acceptance, it would have passed while measuring the wrong rejection.

**The control, and it is the over-rejection direction.** Replacing the declaring-group tier (`candidate in resolvableByPath` → `candidate in emptySet<String>()`) makes the model-wide fallback run first. Observed: `catalog-reference-declaring-wins` reports `MVK_FIELDNAME_NOT_UNIQUE` on a model A12 accepts — **an over-rejection, the one forbidden direction** — and it is caught twice over, by B0's own row and independently by that model's full-report parity case.

**The corpus grew while shedding a duplicate.** The parity manifest's source map now records a path relative to `consumer-tests/shared/`, so a model committed for another suite is REFERENCED rather than copied: the five catalog models joined the parity corpus for free, `storefront.dm.json` stopped existing twice, and the corpus went from 6 cases over 4 sources to **11 cases over 9 sources, 3 of them derived here**.

**And the third growth in a row exposed a maintenance defect worth naming.** Each host carried a hand-written floor on the total — `outcomes.length >= 18` in Node, `MINIMUM_CASES = 18` in the browser driver — which had to be bumped in two files every time a suite grew, three times in one day. A floor left stale is precisely the vacuity it exists to prevent, so the count moved to the suite that owns it: each runner now prepends its own reached-this-arm outcome computed from its own structure, `suites.ts` turns a suite that returned nothing into a failing outcome, and neither host names a total. Both controls fire — a suite stubbed to `[]` reports `shared suite "consumer cases" reported nothing`, and a deleted case reports `this suite has 5 cases and produced 4`. Both packaged arms now run **24** shared cases; Chrome runs all of them.

## 2026-09-17 — the MIRROR, measured absent and then built <a id="mirror-2026-09-17"></a>

F's oracle gives "ordinary evaluation is checker-independent" its own row and asks for *"the mirrored guard on the interpreter's published JVM/JavaScript production closure and shipped files, with its own forbidden-dependency positive control"*. Half of it already existed and half did not, and reading the guards would have got both halves wrong — which is why the edge was planted instead.

| the mirror's half | state before 2026-09-17 |
|---|---|
| shipped files | **already closed.** `verifyInterpreterNpmPublication` compares committed `api/npm-package-files.txt` and `api/maven-package-files.txt` by exact text equality over the full sorted list, in both carriers, so a copied checker file fails on arrival |
| forbidden dependency | **absent.** With `implementation project(':consistency')` planted on `jsMain`, `verifyInterpreterPurity` **and** `interpreterRuntimeClosurePolicyTest` both passed — the latter under `--rerun`, because the first attempt went up-to-date in 2 s and a green up-to-date task is not a measurement |

**Why the existing instruments could not see it.** `verifyInterpreterPurity` is an import-TEXT scan, so a declared-but-unimported edge is invisible to it by construction, and only its `commonMain` rule carried an allowlist — `jvmMain`, `jsMain` and `commonTest` were denylists that never named the checker. `interpreterRuntimeClosurePolicyTest` does read project components, but its subject is the runtime closure's shape, not an allowlist of who may be in it.

**Built both arms, each with a control.**

| instrument | control | observed |
|---|---|---|
| `verifyInterpreterDependencies` (new, on `check` and in push CI) | `project(':consistency')` on `jsMain` | `intra-repo project edge(s) outside its declared allowlist [:interpreter]`, on both JS configurations |
| `verifyInterpreterPurity`, forbidden set extended | `import io.github.mbackschat.a12.dm.consistency.ConsistencyStatus` in `Dec.js.kt` | `jsMain/Dec.js.kt:2: import io.github.mbackschat.a12.dm.consistency.ConsistencyStatus` |

**The new guard declares NO module allowlist, deliberately.** The interpreter's external denominator — kotlinx, `big.js`, the test stack across four configurations — is a separate slice with its own risk of a wrong row, and passing an empty list would claim a closed external denominator nobody has established. The kernel-forbidden arm supplies this guard's own positive control meanwhile. The checker's side is closed over both axes because its external set is small and already enumerated.

**The module instructions were amended on the owner's go-ahead, and verifying the proposed text found one more hole.** [`interpreter/CLAUDE.md`](../interpreter/CLAUDE.md) stated the lint's contract verbatim and was short of reality in five ways, four of them predating this work: no `javax.`, no `kotlinx.coroutines.`, no `commonTest`, no mention of `commonMain`'s ALLOWLIST — the stronger arm, since it refuses an undeclared third party rather than only a named-forbidden one — and then the checker prefix. Both bullets now state the live sets and the division of labour between the two instruments.

**The hole the verification found: both gates read RUNTIME classpaths only.** A `compileOnly` dependency never reaches one. Measured — `compileOnly project(':consistency')` on the interpreter's `jvmMain` appears on `jvmCompileClasspath` and `jvmTestCompileClasspath` and **on no runtime classpath at all** — so a runtime-only gate would have let through the one configuration that can still make code compile. Both gates now read all eight JVM/JS main-and-test compile and runtime classpaths, and the widening carries that planted control. The claim that prompted it was mine, and it was reached by reasoning: *"a test-source kernel import would fail to compile anyway, since the gate refuses the edge that would put it on the classpath"* — true of `implementation`, false of `compileOnly`, and the unmeasured link was the word "the classpath" standing for eight of them.

## 2026-09-17 — NFR #1 had no enforcement locus on the JS target, measured <a id="project-edge-2026-09-17"></a>

The checker's central contract is that it depends on neither the Kernel (H1) nor `:interpreter` (NFR #1). Two instruments carry it, and the module's own build file said so. Planting the edge showed the claim was false where it mattered most.

| what was planted | what happened |
|---|---|
| `implementation project(':interpreter')` on `:consistency`'s `jsMain` | `project :interpreter` resolved onto `jsRuntimeClasspath` **and** `jsTestRuntimeClasspath` |
| `:consistency:verifyConsistencyDependencies` | **passed** |
| `:consistency:verifyConsistencyPurity` | **passed** |

**The mechanism, and it is one line.** `verifyKernelFreeClasspath` collected only `ModuleComponentIdentifier`s, so an intra-repo edge — a `ProjectComponentIdentifier` — was never examined at all. A group allowlist could not have covered it either: every module in this repository publishes under the single group `io.github.mbackschat.a12.dm`, so admitting `:predicates` by group admits `:interpreter` on identical terms. And jdeps cannot read `.mjs`, so `verifyConsistencyPurity` was never a candidate for the JS target — which the build file's comment had claimed was fine "by construction", on the false premise that `:interpreter` is a JVM-only artifact.

**Fixed at the shared root, and swept.** The helper now takes an opt-in, fully explicit project-path allowlist and refuses any other intra-repo edge; `:consistency` declares `[':consistency', ':predicates']`. The sibling with the same blindness is `verifyPredicatesPurity`, whose `it.group != project.group` filter drops a project component *because* it carries this project's own group — the leaf that claims to depend on nothing at all. It gained its own project arm.

| control | observed |
|---|---|
| `project(':interpreter')` on `:consistency`'s `jsMain` | `intra-repo project edge(s) outside its declared allowlist [:consistency, :predicates]`, on both JS configurations |
| `project(':consistency')` on `:predicates`' `commonMain` | `resolves [project :consistency]` |

**Both arms had to be REORDERED to be reachable, and that is the transferable part.** A project edge drags its own transitive modules in, so a module-level arm running first throws on the fallout and the project arm never executes. Measured: the `:predicates` control was initially reported as *five stray kotlinx modules* rather than as one project edge. `:consistency`'s control did print the project message — but only because its allowlist happens to admit `org.jetbrains*`, so it passed by luck rather than by design. Both now run the project arm first.

**Two things this does not fix, stated rather than implied.** The mirror on the interpreter's side: `verifyInterpreterPurity` never names the checker, and only its `commonMain` rule carries an allowlist, so a `jvmMain`/`jsMain`/`commonTest` edge on `io.github.mbackschat.a12.dm.consistency` is unrefused — and its instrument is an import-TEXT scan, the shape this estate replaced with jdeps elsewhere. And `verifyPublishedClasspathsAreKernelFree` passes no project allowlist, deliberately: its claim is about raw Kernel jars, which arrive as modules even through a project edge, so the forbidden-group arm already covers it.

## 2026-09-17 — what the checker actually publishes, enumerated for the first time <a id="shipped-2026-09-17"></a>

`:consistency:verifyConsistencyShippedFiles`. The denominator had never been closed on either carrier, and closing it found three things the required-set shape it replaces could not see.

| carrier | entries | what the enumeration found |
|---|---|---|
| npm tarball | **16** files | it shipped a `devDependencies: {"typescript": "5.5.4"}` and **no licence at all** — no `license` field, no `LICENSE` file — because the pack ran over Kotlin's compiler output directory rather than over a staged set |
| jvm jar | **2** class packages, both under `io/github/mbackschat/a12/dm/consistency/` | clean, and now closed by ROOT: a subpackage is ours by construction, a package outside the root is a shaded or copied dependency |

**Why no existing guard could have found it.** `verifyConsistencyPurity` reads what the classes LINK and `verifyConsistencyDependencies` reads what a consumer RESOLVES. A file that was *copied in* declares neither, which is the reason [F's oracle](PORTABLE-CONSISTENCY-PROPOSAL.md) asks for a shipped-file enumeration in addition to both. The shape matters as much as the existence: a **required-set** check ("is what I expect present") stays green while something else rides along, which is what `verifyInterpreterNpmPackage` is. *(Corrected 2026-09-17, within the hour: this first read "and that is what `:interpreter:verifyInterpreterNpmPackage` still is", which is true of that task and false of the module — `verifyInterpreterNpmPublication` compares committed `api/{npm,maven}-package-files.txt` manifests by **exact text equality** over the full sorted list, a closed enumeration for both carriers. One guard was read and the shape attributed to its neighbours, the sibling-unread register CLAUDE.md names.)*

**Three positive controls, all fired.**

| planted | observed |
|---|---|
| `a12-dmkits-interpreter.mjs` in the distribution | `ships interpreter-owned file(s) [package/a12-dmkits-interpreter.mjs]` |
| `stowaway.mjs` in the distribution | `ships [package/stowaway.mjs], which nothing…` |
| the permitted jar root narrowed to `io/example/nothing/` | `carries class package(s) [io/…/consistency/, io/…/consistency/syntax/] outside [io/example/nothing/]` |

**The interpreter-owned check is ordered FIRST, and that ordering is what makes it exist.** Behind the closed enumeration it would be unreachable — no interpreter name is in the permitted set, so the generic check refuses such a file before the specific one runs — and an invariant nothing can enforce is the "parsed but never consumed" class. Ahead of it, it earns its place by naming which invariant broke rather than which file was unexpected.

**The staging change moved the delivery number, and the ceiling still holds**: 640 783 bytes against 1 MiB, 1.64× headroom ([the delivery entry](#delivery-2026-09-16) carries the correction).

## 2026-09-17 — the three arms compared on the FULL report, and they agree exactly <a id="parity-2026-09-17"></a>

Same host as the browser entry below. Corpus: 6 cases over 4 stored inputs, 92 kB, generated from packed `a12-consistency@0.13.0`.

| arm | how it reads the corpus | result |
|---|---|---|
| Java (Kotlin/JVM) | embedded by `:consistency:generateParityCorpus` | **6/6 identical** |
| Kotlin/JS under Node (`jsNodeTest`) | the same embedding | **6/6 identical** |
| packed Node consumer | read off disk | **6/6 identical** |
| packed browser consumer, Chrome 153.0.8010.48 | fetched over HTTP | **6/6 identical** |

**The result that was not safe to assume: the reports agree down to a THIRD-PARTY LIBRARY'S message text.** `model-unreadable`'s only content is kotlinx-serialization's own parse failure, `Unexpected JSON token at offset 5: Expected EOF after parsing, but had j instead at path: $`, and it is character-identical on Kotlin/JVM and Kotlin/JS. So is the family order in `checksCompleted` (103 entries), which is the other member a platform could plausibly have permuted — a `Set` iteration order is insertion order by convention, not by contract. Neither was predictable from source, which is why they were measured rather than argued.

**The control, and it is four-way.** `put("complete", true)` in `ReportJson` — one boolean the corpus carries in nine places and no named assertion in either consumer project mentions. It took **all four** surfaces red with the same path: `$.complete: true, expected false` from `jvmTest`, from `jsNodeTest`, from the packed Node arm, and `4 of 12 shared case(s) failed in the browser`. That the same mutant reaches four independently-wired readers is the evidence that they share one corpus rather than three that happen to agree.

**What this corpus is NOT.** It states no semantic expectation and cannot: every value in it was produced by the checker, so it cannot tell a right answer from a wrong one. Its job is the one F's oracle assigns it — the full contract across platforms — and the claim-per-case assertions stay in the module's family suites and in [`cases.ts`](../consistency/consumer-tests/shared/cases.ts). Regeneration is `./build.sh :consistency:syncCheckerParityCorpus`, and a stale corpus fails `:consistency:check`, which push CI runs.

## 2026-09-17 — F's browser arm, replayed against the packed tarball in real Chrome <a id="browser-2026-09-17"></a>

`:consistency:browserConsumerTest`, macOS 25.6 (arm64), node v24.18.0, pnpm 11.20.0, Vite 8.1.5, system **Google Chrome 153.0.8010.48**, headless. Packed `a12-consistency@0.13.0`.

| what was measured | value |
|---|---|
| shared cases replayed in Chrome | **5**, all passing |
| bundled JS | **654.88 kB**, 195.84 kB gzip, 10 modules transformed |
| bundled HTML | 0.98 kB, 0.57 kB gzip |
| host-API references in the packed ES modules (`require(`, `node:`, `process.`) | **0** across all 6 |
| end to end, cold (pnpm install + pack + bundle + Chrome) | **21 s** |
| end to end, warm store | **13 s** |

**The zero in the third row is why this arm exists and is the one number that was taken FIRST.** The packed tarball is Kotlin/JS's *Node* production distribution, so the question ahead of any wiring was whether it names a host API — one `node:` import and a browser bundle could not have loaded it. It names none, in any of the six modules, which is what made the arm a wiring job rather than a porting job.

**The mutant that controls both arms.** `put("start", located.start + 1)` in `ReportJson`'s span emission is asserted by the shared cases and by nothing else in either consumer project. It took the Node arm red (`the span sliced "oSuchField"`) and then Chrome red on the same case, `1 of 5 shared case(s) failed in the browser`. Both arms therefore fail on a real defect, and the shared module is demonstrably what each of them runs.

**What the bundle size is NOT.** It is a dated observation of a test fixture's bundle, not a delivery bar: the page is a replay harness, Vite is configured with no code-splitting or manual chunking, and F's non-goals rule out any tree-shaking assumption. The checker's absolute bars are both on what it publishes — the packed tarball ([the delivery entry](#delivery-2026-09-16)) and the JVM jar ([the closure entry](#jvm-closure-2026-09-18)) — and a fixture's bundle is neither. Read this row for the order of magnitude a browser consumer pays today, never as a ceiling anything holds.

## 2026-09-17 — the resources boundary's RETAINED-MEMORY half, which nobody had measured <a id="memory-2026-09-17"></a>

**Lane:** `./build.sh :adapter:checkerMemoryPerfTest` (`adapter.perf.CheckerRetainedMemoryPerfTest`), one JVM at a fixed 1 GiB heap under `-XX:+UseSerialGC`, the same synthesized flat workload the growth lanes use, `JdkIdentity.requireSupported` gating it. **Both growth ratios are asserted** against `PerfBounds.SUB_QUADRATIC_MAX` as of 2026-09-17; no byte count is, and none will be ([the disposition](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#memory-disposition)).

**Why it exists: the boundary was marked done on one of its four required observations.** [§8](PORTABLE-CONSISTENCY-PROPOSAL.md) asks this boundary for "retained memory, release behavior, supported peak observations and installed/downloaded production closure size", and the 2026-09-16 landing measured the last one. No checker memory task existed in any module, while [the stage's own collector table](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#stage-8) named the footprint family as this observation's owner — so the gap was named in its own record and still read as closed, because boundaries were counted rather than observations.

**The quantities are the interpreter footprint estate's, deliberately.** It reports post-GC used heap with the owner held LIVE, and thread-allocated bytes during the operation, behind a GC-viability gate and an owner-release check. Re-using that pair makes the two products comparable; a peak sampler invented here would be a third quantity with nothing to compare against, and §8 qualifies its peak clause with "where the host supports measurement".

| quantity | 200 programs | 800 programs | **growth over the 4x scale-up** |
|---|---|---|---|
| a held `ConsistencySnapshot`, retained | 1 754 984 bytes | 6 971 432 bytes | **3.972x** |
| one `check`, thread-allocated | 68 406 144 bytes | 272 550 240 bytes | **3.984x** |

**Memory is LINEAR in model size, and that is the finding.** 3.97x and 3.98x over a 4x scale-up are linear to within measurement error, on a model text that itself grows 3.99x (343 458 -> 1 371 258 chars). Reproduced over three independent runs: retained growth 3.972 / 3.974 / 3.972, allocated growth 3.984 / 3.993 / 3.993, and the 200-program retained figure came back byte-identical at 1 754 984 in all three — a determinism the timing lanes cannot offer. **The contrast with time is the useful part:** the same workload grows 5.33x in prepared time and 2.75x in cold time, so the super-linearity this checker has ever shown was a time phenomenon and never a space one. The cubic term H5-b found in 2026-09-15 had no memory counterpart.

**A held analysis is cheap and running one is not.** 1.75 MB retained against 68 MB allocated per check: holding the analysis costs about 5.1x the model text it came from, while each check churns roughly 40x what holding it costs. That is the quantitative case for L-a's snapshot, arrived at from the allocation side rather than from the clock, and it says the pressure a consumer should care about is per-check garbage rather than residency.

**The release gate is live, and it was controlled rather than assumed.** With the snapshot deliberately retained in a companion list, the lane refuses with "the snapshot was still reachable after being dropped" instead of reporting a number — which is §8's "release behavior" observation discharged by a gate rather than by a claim. Without that control the gate could pass while verifying nothing, and the same run would silently measure each sample against a heap still holding the previous one's analysis.

**The control found a defect in itself, which is the part worth carrying.** The first armed version passed the assertion helper a precomputed median, so its three "independent re-measurements" printed byte-identical values — a vacuous retry that read exactly like a working control, and visible only because the lowered-floor run prints every attempt. The estimator is now a closure that re-samples; the same control prints 3.977 / 3.984 / 3.986 and refuses on their median.

**The disclosed shortfall.** IF137's ratified footprint sampling default is >=5 forks; this lane takes 5 bracketed samples inside ONE JVM, as the four sibling checker lanes did before their dispositions. Under IF137 that default binds a number only at its promotion, so the shortfall is stated here rather than hidden — and a promotion of this number needs the fork arm, which is why none is claimed.

## 2026-09-16 — H5's COMBINED boundary: holding both prepared representations costs the sum, measured <a id="combined-2026-09-16"></a>

**Lane:** `./build.sh :adapter:checkerCombinedPerfTest` (`adapter.perf.CheckerCombinedApplicationPerfTest`), one JVM, the same synthesized flat workload the growth lanes use, `JdkIdentity.requireSupported` gating it. This is the fifth and last of H5's boundaries, and it existed as an unmeasurable row until F's package shipped. **Nothing here is asserted, and after 2026-09-17 nothing ever will be**: the boundary's bar is UNSET by [decision](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#combined-ratification) rather than by sequence, because the quantity is certified to be ~1.0 by design and the controlled arm straddles it.

**The quantity is ADDITIVITY, fixed by the proposal rather than chosen here.** [§4](PORTABLE-CONSISTENCY-PROPOSAL.md) says an application choosing both consistency and evaluation "may hold two prepared representations and pay for two parses. Measure that combined cost separately; no zero-copy integration, shared-program adapter or elimination of all cross-component duplicate parsing is promised." So the question is not milliseconds — no checker duration is certified — but whether `both` costs `consistency + evaluation`. That ratio survives a change of machine; the durations beside it do not.

| arm order within each pair | consistency | evaluation | sum | both | **additivity ratio** |
|---|---|---|---|---|---|
| `both` **last** | 62.011 ms | 10.706 ms | 72.717 ms | 71.355 ms | **0.988×** |
| `both` **first** | 54.431 ms | 8.454 ms | 62.885 ms | 61.538 ms | **0.998×** |

**The two rows ARE the control, and running only one would have published a false finding.** An uncontrolled first run — `both` always third, inheriting the other two arms' warm-up — reported **0.963×**, which reads as sub-additivity: the two preparations sharing work, exactly what §4 declines to promise. Putting `both` first removes the warm-up advantage and the ratio goes to 0.998×. So the effect was the harness's arm order, the lane now measures both orders in one run, and the honest reading is **1.0 within noise: strictly additive.** Two prepared representations cost two parses, there is no sharing, and equally there is no interference — a ratio meaningfully above 1.0 would have meant the two preparations contending, which is the finding this lane exists to be able to see.

**The two arms are not symmetric, and a reader comparing 62 ms against 11 ms would draw the wrong conclusion.** The consistency arm constructs the snapshot *and* reads its whole report, because that is what a consumer holding a snapshot pays for; the evaluation arm loads the model only, with no document evaluated. Both match what the `both` arm does, which is what makes the ratio well-posed — but the columns are not a checker-versus-interpreter comparison and must not be quoted as one.

**Revision:** `63abbc44f` plus this lane. Durations moved run to run (consistency measured 68.2 / 62.0 / 54.4 ms across three invocations of the same workload), which is the standing reason no duration on this page is a bar.

**Re-measured three more times on 2026-09-17, and the arm-order effect is itself reproducible.** Four independent runs of the unchanged lane:

| arm order | the four additivity ratios | spread |
|---|---|---|
| `both` **last** | 0.988 · 0.972 · 0.965 · 0.964 | 0.964–0.988 |
| `both` **first** (the controlled arm) | 0.998 · 1.010 · 1.006 · 0.996 | **0.996–1.010** |

The two populations do not overlap, which confirms the warm-up account from the other side: the arm-order effect is ~3 % and comes back every time, while the controlled arm sits on 1.0 within ±0.7 %. **That straddling is what settles the boundary's bar.** The controlled arm crossing 1.0 in two of four runs means a `≤ 1.0` ceiling would refuse a run in which nothing is wrong, and a ceiling above 1.0 would need a tolerance for interference that neither H5 nor [§4](PORTABLE-CONSISTENCY-PROPOSAL.md) states — so the number is **declined as a bar rather than deferred**, with its re-open trigger, in [the disposition](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#combined-ratification). Strict additivity was the prediction; four runs are the measurement; and a quantity certified to be 1.0 by design is not a quantity a bar can gate.

## 2026-09-16 — H5-e: the after-edit boundary, which finally has something to measure <a id="after-edit-2026-09-16"></a>

**Lane:** `./build.sh :adapter:checkerAfterEditPerfTest` (`adapter.perf.CheckerAfterEditPerfTest`), one JVM, the same synthesized flat workload the growth lanes use. Three arms, each measured as **interleaved pairs** of the stateful call against the stateless one that answers identically, reporting the median of the within-pair ratios; `JdkIdentity.requireSupported` gates the lane. Two preflights run outside every clock and double as the warm-up: the model must draw no rejecting finding, and the snapshot's answer must equal the stateless one — the whole `EditReport` for a draft, the whole report for a saved edit. **Revision:** `cb3456995` plus this lane. **One of the three arms now asserts.** The keystroke ratio was ratified on 2026-09-17 against a `1.0×` ceiling; the other two arms are UNSET permanently. Both decisions are below, and a collector coming first with informative output is the sequence [H5's ordering rule](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ordering) prescribes.

| arm | what each pair compares | 50 programs | 200 | 400 |
|---|---|---|---|---|
| build the analysis | `snapshotOf` against `check` | 0.986× | 0.995× | 0.968× |
| **keystroke** | `snapshot.draft` against `checkDraft` | **0.478×** | **0.474×** | **0.492×** |
| saved edit | `snapshot.withSource` against `check` | 1.000× | 1.006× | 1.064× |

Medians at 200 programs, for shape and never as a bar: building 35.66 ms against a 35.98 ms check; a keystroke 54.01 ms against 111.35 ms; a saved edit 42.86 ms against 41.74 ms.

**The keystroke halves, and it stays halved across an 8× span of model size.** 0.474–0.492 over 50→400 programs is the ratio [L-a's counter](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#l-partition) predicts from work alone — `checkDraft` runs two checks plus an admission and a preparation, `snapshot.draft` runs one check — so the measurement and the count agree. That agreement is a **consistency check and not evidence for the mechanism**: the count was the design, so a figure near 0.5 is what any implementation of that design gives, and it would take a rival explanation predicting something else to make this discriminating.

**The saved edit is a wash, and that is the result rather than a disappointment.** `withSource` re-prepares and re-checks by construction — the `Reuse` doc says so, because the source set changed and the old fact base describes a model that is no longer the one being asked about — so its only reuse is the source parses, and those are free beside a check at any scale measured here. A consumer therefore gets nothing from holding a snapshot across SAVES, and everything from holding one across keystrokes. The 1.064× at 400 programs is inside this lane's run-to-run spread and is not read as a cost.

**And building the analysis costs what checking it costs.** 0.97–1.00× says admission and preparation disappear beside the check at every scale measured, which is the same thing the 2026-09-15 profile said from the other side when it put the cost in the declaration walk. So the up-front price of holding an analysis is one check — a consumer pays for the first answer either way.

**The keystroke ratio was ratified as a bar on 2026-09-17, and it is the only arm here that is.** Seven further independent estimates at 200 programs — 0.498 · 0.490 · 0.499 · 0.484 from accepted runs, then 0.490 · 0.480 · 0.502 from the control's re-measurements — put **eight estimates across a 5.9 % span, 0.474–0.502×**. The ceiling is `1.0×`, derived from L-a's work count rather than from any figure on this page, and deliberately not tightened toward the measurement; the [six-point disposition](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#keystroke-ratification) records why point 4 had to be translated for a checker number and why no p95 is required for a ratio. A control run with the ceiling lowered to 0.4 refuses across three re-measurements and names both arms of each, which is the only evidence the assertion is live.

**The other two arms are UNSET permanently, which is a result and not a gap.** Both were ~1.0 by construction, so a ceiling on either could only be read off this lane's own spread — and the saved-edit arm measured 1.006 · 0.958 · 0.987 · 0.988 across four runs, straddling 1.0, so such a bar would refuse healthy runs.

*Amended 2026-09-18: all three ratios moved, and the "~1.0 by construction" reasoning no longer holds for two of them. The RATIOS below are provisional under that day's banner; the reasoning is not, because it follows from the code — a snapshot records and the stateless call it is paired against no longer does.* Re-measured at 200 programs after the stateless path stopped recording what a cell is keyed on ([why](#cell-cost-2026-09-18)): **build 1.274x, keystroke 0.639x, saved edit 1.299x**, against 0.995 / 0.474 / 1.006 here. The mechanism is the change itself rather than a regression — a snapshot must now do strictly MORE than the stateless call it is compared against, because it records and the stateless call does not — so the two unset arms are no longer ~1.0 by construction but ~1.0 *plus the bookkeeping the store needs*. **The keystroke arm's certified 1.0x ceiling still passes and is still the only bar here** (0.639x), but its margin halved: the benefit now measured is reuse against a cheaper comparand. The decision to leave the other two arms unset is untouched — if anything it is better supported, since a ceiling read off the old ~1.0 would now refuse every healthy run.

## 2026-09-16 — H5-d: the cold curve gets a bar, and it is the looser of the two <a id="cold-paired-2026-09-16"></a>

**Lane:** `./build.sh :adapter:checkerColdStartPerfTest` (`adapter.perf.CheckerColdStartPerfTest`), one **fresh JVM per observation**, now launched as **51 interleaved N/4N pairs** instead of two sequential campaigns — the ratified paired estimator, median of the within-pair ratios. Same machine, JDK and flat workload as the 2026-09-15 rows below; `JdkIdentity.requireSupported` gates the lane and every process's rejecting-finding set is compared whole, empty at both scales. **Revision:** `fc1605c28`.

| quantity | 200 programs | 800 programs |
|---|---|---|
| `check` median | 307.79 ms | 859.24 ms |
| `check` p95 | 376.98 ms | 1 015.82 ms |
| `check` min–max | 297.50–500.31 ms | 793.46–1 258.63 ms |
| `process` median | 352.91 ms | 901.34 ms |
| **median within-pair ratio** | — | **2.75×** |

**The bar is the ratio and nothing else.** 2.75× over a 4× scale-up sits under the ratified `PerfBounds.SUB_QUADRATIC_MAX` floor of 9.0 with 3.3× of headroom. No duration in that table is asserted or anchored, and none ever will be here: a cold millisecond figure has no basis that survives another machine, which is why [the ratification](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification) gave this boundary a ratio and gave delivery the absolute bars — one per published carrier.

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

**Corrected 2026-09-17, twice in one day: the packed tarball is now 640 783 bytes.** Declaring the JS target's missing `browser()` — the prerequisite [F's third acceptance arm](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#stage-8-browser) needs — adds Kotlin's `kotlin-dom-api-compat` module to the package, which is **+4 317 bytes, +0.68 %**. A second change the same day added the two files an MIT package cannot publish without — a `LICENSE` and a `README.md`, neither of which the package had ever carried — for a further **+2 023 bytes**. The ratified ceiling still holds with **1.64× headroom** against 1.65× before, and the rows below remain the measurement taken at `153ae86a2`, which is what a dated entry is for.

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
