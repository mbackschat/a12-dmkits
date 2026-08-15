# Interpreter performance — results log

> **What this is.** The dated evidence for interpreter performance: one `### Checkpoint <date> — <topic>` entry per
> measurement session, newest first. Its method half — the performance contract, the NFR certification protocol, the
> harness, the optimization catalog and the regression gate's mechanics — is
> [`INTERPRETER-PERFORMANCE.md`](INTERPRETER-PERFORMANCE.md), and the two were one file until 2026-08-15.
>
> **Why they split.** [`DOC-DISCIPLINE`](DOC-DISCIPLINE.md) says specs hold the stable method and measured results
> live in a dated log; one file holding both was a standing violation of its own rule, and the log had grown to
> 57 % of it. Nothing was deleted in the move: 28 findings cite these checkpoints and 30 of the 37 cite a finding
> back, which is the two-way traffic that rule is designed to produce.
>
> **What belongs here, and what does not.** An entry earns its place by recording a CONCLUSION — a hypothesis and
> its discriminator, a refutation, a decision and what licensed it. The raw per-run observations are **not** here
> and have not been since 2026-08-11: they live in [`../perf-results/`](../perf-results/README.md), one JSON-Lines
> file per run, read by `scripts/perf-report.sh` and its siblings. So an entry that only says "the gate was green
> on date D" is duplicating the record and does not belong; cite the record instead of pasting it.
>
> **Why it stayed in `docs/`.** The three doc guards are rooted at `docs/`: moving this file to `perf-results/`
> would co-locate it with the data and cost cross-doc anchor resolution for every inbound finding link, index
> enforcement, and its place in the docs router. The cleaner boundary is by AUTHOR, not by subject — `perf-results/`
> is machine-written and self-pruning, this is hand-written and guard-verified.

### Checkpoint 2026-08-15 — what ONE unchanged commit costs on a hosted runner, measured from the committed record

Read from `perf-results/linux-amd64-ci/` only; nothing was run for it. The record holds two triplets of nightly runs of a **single** commit, which is the only shape that can answer "how far apart can identical code land here?".

| commit | runs | shared keys | median spread | p90 | worst |
|---|---|---|---|---|---|
| `15b3ed0` (sequential, ~10 min apart) | 3 | 138 | 1.13× | 1.85× | **2.29×** |
| `1845f8af` (concurrently dispatched — contended against each other, [IF280](INTERPRETER-FINDINGS.md#if280)) | 3 | 34 | 1.18× | 1.53× | 1.98× |

Spread is each key's max/min of **absolute medians** across the triplet. The concurrent triplet is the *lower* of the two worsts, which is the opposite of what its contention would suggest and is why the sequential figure is the one used: 138 keys against 34 is a far better sample of the tail, and the concurrent set simply never drew its own worst case.

**What it licensed.** The 2.29× is the boundary the hosted flag's two tiers now split on ([IG137](INTERPRETER-GAPS.md) piece a) — below it, a breach is inside what identical code has actually done here; at or above it, the observation is outside anything the record contains for unchanged code. **Recall limits, stated because the figure is load-bearing:** two triplets, one contended, one runner class, and a max/min statistic that is at least as large as either run's own deviation from the true level — so the band over-tolerates by construction. It is a report boundary and deliberately not a bar.

### Checkpoint 2026-08-15 — the starred-group expansion lane, and what its own guard refused

**The lane.** `GroupStarExpansionPerfTest` measures `NumberOfFilledFields(/Order/Items*)` over a four-field repeatable group, on the local darwin-aarch64 machine, single-fork, each gate run alone. **Six runs of the same commit**, reported as a set because no single one of them is the number:

| n | samples (CPU, ms) | median | spread |
|---|---|---|---|
| 2000 | 7.79 · 11.42 · 12.00 · 15.51 · 15.78 · 18.03 | **13.75** | 2.31× |
| 8000 | 26.12 · 37.77 · 41.51 · 49.04 · 53.99 · 70.42 | **45.28** | 2.70× |

Observed median-growth ranged 2.49×–3.26× against linear ≈ 4 and the 9.0 catastrophe floor, so **the expansion needs no caching** — the question [IG136](INTERPRETER-GAPS.md)(a) asked. The mechanism is consistent and directly readable rather than inferred: `expandGroupStars(args)` is a single call at the head of `filledFieldsValue`, so the expansion runs once per evaluation, not once per row.

**The spread is the finding, and it changed how the lane was armed twice.** These are six sequential isolated runs of one commit on one machine — the local analogue of the hosted-runner spread in [IF280](INTERPRETER-FINDINGS.md#if280). The anchor was first set from the median of **two** runs (14.72 / 47.75); the very next isolated run measured 7.79 ms and the gate emitted `[perf][STALE] … 0.53× of it, so the ceiling this lane is held to sits at 1.9× the real cost and cannot fail`. Four more samples put the six-run median at 13.75 / 45.28 — within 7 % of the two-run figure, so the warning was reporting **one fast sample, not a wrong anchor**. The anchor now carries the six-run median.

**`status: unasserted` does NOT mean "unguarded", and the name invites exactly that misreading.** A full gate run records 168 observations: 66 anchored absolutes, 102 marked `unasserted`. All 102 belong to the **kernel-parity** lane (34 scenarios × 3 engines), and that lane is guarded three ways — 17 `assertBeatsBothKernelStrategies` ratio assertions, a per-row absolute ceiling on the interpreter, and the IF292 early-warning band against **62 committed margin floors** on darwin (68 on linux-amd64-ci). The status means only *"this absolute time is recorded for history rather than anchored"*, which is deliberate: the lane's decidable quantity is the **ratio**, and a ratio needs no committed baseline to be judged. Recorded because a reader who greps statuses and stops there concludes that 102 of 168 observations are decorative — one session did, and it took reading `KernelParityGateTest.record` to see the three bars sitting one scroll away.

**A second, sharper witness from the same session: four anchors breached and none reproduced.** One full gate run (`215934Z`) recorded 59 accepted / 4 breached — `evalCandidate` n=2000 at **4.70×**, `applyComputations` n=150 at **3.55×**, `computeField` n=600 at **2.04×** (all `wall`), and `charset BMP` n=32000 at **1.80×** (`cpu`). The two full runs earlier that day were 64 accepted / 0 breached, and a re-run on the same commit (`222857Z`) was **66 accepted / 0 breached**. So all four were run-state, not code. The `cpu`-clock one is the finding: that clock was adopted so a growth bar would not have to absorb load, and it bounds *scheduling* noise — not thermal or page-cache state, which a 1.80× excursion on an otherwise idle laptop demonstrates it does not cover. *(How this was found is worth one line, because it nearly was not: the run's failure was first read off the build log with a narrow grep, which surfaced only the unanchored lane and hid all four breaches. The record file carries a `status` per observation and is the artifact to read — `perf-anchor.sh` excludes `breached` from intake deliberately, so a diff reporting a lane as "not measured in this run" can mean "measured and rejected".)*

**What this exposes about the anchor model on a noisy lane, and it is not fixed by more sampling.** With an inherent 2.31× spread, a fast run sits at ~0.57× of *any* correct central estimate, so the staleness warning fires on a healthy lane whenever the machine is quiet — while the `1.65× + 1 ms` ceiling has to be loose enough to admit the slow tail. A single scalar cannot be both. That is the same tension [IG137](INTERPRETER-GAPS.md)(a) records for hosted breaches, now with a local witness: the lane is correctly armed and will still cry stale roughly whenever it runs fastest.

**Why this lane exists at all, given `GroupScopedFillPerfTest` looks like it covers the same ground.** It does not. `AtLeastOneFieldFilled(G*)` dispatches onto `EvalContext.fillScope` and never reaches `expandGroupStars`; `NumberOfFilledFields(G*)` opens with `for (a in expandGroupStars(args))`. [IG136](INTERPRETER-GAPS.md)(a) had been closed on the claim that the first scenario drove the second path, which is why it was re-opened — the closure named a path no scenario executed.

**The reachability proof is the eligibility check, not a separate assertion.** An engine that skips the expansion reads cells at a path holding no value and counts zero ([IF287](INTERPRETER-FINDINGS.md#if287)'s fifth instance, measured), so the rule's `> 0` bar never fires and `assertSemanticAgreement` fails against both kernel strategies at both sizes. Agreement is therefore only obtainable if the expansion ran.

**The guard refused the first run, correctly, and the refusal is the transferable part.** A new observation in an *already-anchored* runner class fails closed (`UnanchoredObservationException`) rather than recording an un-bounded lane — "that is how a bar silently disappears". Arming it then exposed a second edge: `scripts/perf-anchor.sh` folds a run's observations into the anchor, so refreshing from a **targeted** gate (`perf.sh gate '<pattern>'`) would have added these two keys and removed the ~64 keys that simply did not run. The script refuses that too. The consequence for anyone adding an anchored lane: a targeted run is enough to *develop* the scenario, and a **full gate run is required to arm it** — there is no cheaper path, and both refusals are the system working.

### Checkpoint 2026-08-14 — the group-operand expansion work costs nothing measurable, and one scenario already exercised it

Seven interpreter changes landed this day in the group-operand expansion class (`expandGroupStars`/`expandGroupOperands` and its shared owner, `vlqOperands`, `overlapCells`, `fillScope`, `fillScopeInfo`, `PolarityWalk`, `minMaxOperandVals`). Routine gate, run in isolation on the local 8-processor machine (`darwin-aarch64`, 4m41s, `BUILD SUCCESSFUL`, zero anchor breaches, record `perf-results/darwin-aarch64/2026-08-14T151017Z.jsonl`):

- **All 18 sub-quadratic growth observations land in 3.96×–5.28×** against the 9.0 catastrophe floor (linear ≈4, n·log n ≈4.65). Widest: `NDV-distinct-count` 5.28×, `CO-index` 5.21×, `complexRealWorld-1233f-1017r` 5.06×.
- **Every cross-engine ratio stays far below 1**: 0.06–0.26 of the Groovy baseline, e.g. `Number-index-validate` at N=2000 `interp=6.62ms` vs `groovy=119.11ms` (0.06), `CO-correlation` 0.20.

**CORRECTED after a cold review: no scenario in the gate exercises `expandGroupStars` at all.** This checkpoint first claimed that `GroupScopedFillPerfTest` "routes through the expansion on every row" and closed [IG136] item (h) on that basis. It does not. `AtLeastOneFieldFilled` with a starred operand dispatches to `fillScopeInfo` → `EvalContext.fillScope`, never to `expandGroupStars`, whose three callers are the compute-path fill scan, `NumberOfFilledFields`, and `NumberOfValueInFields`; the only `NumberOfFilledFields` scenario in the estate is over a starred FIELD, where the guard early-returns the same list instance. So the per-row `Expr.Star` allocation item (h) predicted is **unmeasured**, and what the gate did measure on that lane is a different per-row group expansion — `fillScope`'s `kinds.keys.filter { … }.sorted()` — which is linear and cheap but is not what a hoist would remove.

**What therefore stands, and what does not.** Stands: no regression anywhere from the day's interpreter changes, and the group-scope fill lane at **3.99×** growth with the interpreter at **0.18×** of the kernel. Does not stand: any claim that the expansion path itself was measured. Item (h)'s re-open trigger is accordingly a perf scenario whose rule is `NumberOfFilledFields(<group>*)` or a compute-path group fill — until one exists, the negative is "no regression observed elsewhere", not "the expansion is free".

**Re-measured after five further interpreter fixes landed the same day** (`starScanCells`' bind-depth cap, `fillScope`'s bind-depth parameter, the multi-operand date extremum, and the context fix at five operand sites): 22 growth observations spanning **3.76×–6.37×** against the 9.0 floor, zero anchor breaches, and the group-scope fill lane at **3.76×**. So the seam changes — including giving `fillScope` a new parameter consumed on every fill-quantifier call — cost nothing measurable either.

**Two provenance notes on the record itself.** The committed JSONL stores endpoint medians rather than growth ratios, so the band quoted above is re-derivable only up to the paired-estimator/endpoint distinction (endpoint ratios span 3.96×–5.21×; `group-scope fill` computes to 3.90× against the 3.99× paired figure). And that lane records **no kernel-engine observation**, so its `0.18×` lives in console output only — the exact shape [IF296](INTERPRETER-FINDINGS.md#if296) was written about. The run also logged `loadAverage: 3.90` on an 8-processor machine, which is the reviewer agent's own greps rather than a second build, but "run in isolation" overstates it.

### Checkpoint 2026-08-13 — what the routine gate costs once it runs the whole routine tier

Measured on `Macmini9,1 Apple M1`, 8 processors, JDK 21.0.12, isolated (nothing else running), `scripts/perf.sh gate`:

| gate scope | classes | test methods | skipped | wall |
|---|---|---|---|---|
| enumerated two-class `filter` (before) | 2 | 39 | 0 | 3m57s |
| tag-selected `@Tag("perf")` routine tier (after) | 9 | 54 | 0 | **4m44s** |

Five classes entered an automated lane for the first time and two moved out of the broad parallel-fork suite into it — 15 test methods in all, for **+47 s**. That is the whole cost of the coverage change, and it lands well inside the nightly workflow's 60-minute timeout and is cheap enough to sit in front of every release. **`skipped=0` is the load-bearing column:** the `@EnabledIfSystemProperty` canaries would otherwise have reported as skipped inside a green gate, which is a pass that advertises a bar that never ran — they are `perf-bench` and excluded instead. No newly gated bar breached, which was the open risk: the five tagged classes the old filter omitted had never been executed by any lane, so their trip lines were unverified against current code.

Method: three **strictly sequential** `gh workflow run 'Nightly performance' --ref main` dispatches of `15b3ed0`, each waited to completion before the next (the isolation rule of [IF280](INTERPRETER-FINDINGS.md#if280)); artifacts committed under [`perf-results/linux-amd64-ci/`](../perf-results/README.md) as `2026-08-13T095150Z`, `T100152Z`, `T101123Z`. All three: JDK `21.0.11`, 2 processors, 36 accepted observations, run ids `31688252078` / `31689198281` / `31689900737`.

**The runner class spans CPU models within one hour.** Runs 1 and 2 measured on `AMD EPYC 7763 64-Core Processor`, run 3 on `AMD EPYC 9V74 80-Core Processor`. `scripts/perf-anchor.sh` correctly refused the fold ("two machines cannot be folded"), so no block was authored.

**The finding is not the machine split — it is that the split does not matter next to the noise.** Per-key ratios over the 36 lanes all three measured:

| comparison | median | max | `> 20 ms` lanes (n=20) |
|---|---|---|---|
| run 3 vs median(run 1, run 2) — **different** machines | 1.07× | 2.01× | median 1.06×, max 2.01× |
| run 1 vs run 2 — the **same** machine | 1.04× | 2.05× | median 1.02×, max 2.05× |

The same-machine spread is as large as the cross-machine one, and its outliers sit in the **large** lanes rather than in sub-millisecond ones, so this is not an absolute-resolution artifact. Consequence for the bar: `PerfBounds.ANCHOR_TOLERANCE` is `1.65×` + 1 ms slack, which is **below** the measured same-machine run-to-run spread of this runner class — so an absolute assertion there would flag on ordinary noise, independent of which machine served the job. Two conclusions follow, and only the first is a measurement: the hosted numbers support a **trend line**, and a hosted absolute **bar** has no tolerance that is both meaningful and quiet. This is [IF280](INTERPRETER-FINDINGS.md#if280)'s own re-open trigger firing on evidence ("if the recorded hosted models show the runner class spans several machines, decide whether that block is a bar or only a trend line"); the disposition it led to is [IF293](INTERPRETER-FINDINGS.md#if293).

### Checkpoint 2026-08-13 — the last three list-in-key memos, on a scenario that runs them

Method: `scripts/perf-keys.sh validate-memos` (new mode, driver `EvalProfileTest.loopPartialMemoProbeForProfiler` over `PerfCatalog.partialMemoProbe`), `Macmini9,1 Apple M1`, 1,297 `ExecutionSample`s. The scenario carries a STARRED aggregate and a `RepetitionNotUnique` rule under `validatePart` — the constructs no existing scenario had, which is why these three read 0 before ([IF290](INTERPRETER-FINDINGS.md#if290) closes IG126).

| memo's enclosing frame | present in | reading |
|---|---|---|
| `DmInterpreter.starRelevantOf` (holds `decided`) | **5 samples, 0.39 %** | measured cold; frame present through two callers |
| `DmInterpreter.groupRelevanceOf` (holds the group cache) | **1 sample, 0.08 %** | measured cold; frame present |
| `RepetitionNotUniqueEvaluator` (holds the row cache) | **534 samples, 41.17 %** | measured HOT |

**Read the denominator before the 41 %:** this probe runs two rules and is built to stress RNU, so that share describes the probe, not a realistic workload. What it isolates is the *shape* of RNU's cost, which no mixed model would separate — and the shape splits into two mechanisms, only one of which is the class being tracked: `ArrayList.hashCodeRange` encloses **16.11 %** of all samples (the two `groupBy` keys), while `MutableBigInteger.divideOneWord` + `divideKnuth` + `BigDecimal.<init>` account for **~14 %** (`rnuNumericKey` re-normalizing every NUMBER key cell per call). The second is not a key mechanic and would never have appeared in the view that opened this class — the same blind spot the 2026-08-13 relevance-query checkpoint records.

**Both were then classified rather than optimized, and one attempt was falsified** ([IF291](INTERPRETER-FINDINGS.md#if291)). `outcomes` runs once per pass and hashes each row's two `groupBy` keys once each, so the cost is proportional to rows rather than multiplied per consultation — a different shape from every other member of this class. A call-local `raw → Dec` memo for the `BigDecimal` half measured **9,921 → 9,666** calls in 20 s, i.e. slightly worse, because RNU checks UNIQUENESS and its key columns are therefore mostly distinct: the memo misses on nearly every row and adds a write to each. Reverted, not banked.

### Checkpoint 2026-08-13 — the relevance query: 3.34x, and an instrument that ranked the wrong member

The IG128 slice ([IF289](INTERPRETER-FINDINGS.md#if289)). Machine `Macmini9,1 Apple M1` (8 processors), JDK 21.0.12, `scripts/perf-keys.sh validate-partial` (`SI-cliff` at N=1,000).

**Throughput, the primary measure** — the driver's own loop count over a fixed 20 s window, both arms run **without** JFR so the pair is symmetric (the first after-reading, 10,611, carried recording overhead and is discarded in favour of a like-for-like one):

| | `validatePart` calls in ~20 s |
|---|---|
| before | **3,329** |
| after | **11,108** |
| | **3.34x** |

**Where it went.** `EffectiveRelevanceIndex.isRelevantWithin`'s whole subtree fell from **72.84 %** (1,070 of 1,469 samples) to **33.79 %** (513 of 1,518).

| self frame | before | after |
|---|---|---|
| `java.lang.StringBuilder.append` | **16.95 %** | 1.84 % |
| `joinTo` + `joinToString` + `StringBuilder.toString` | 9.94 % | absent from the top 18 |
| `CollectionsKt.take` / `StringsKt.split` | 3.81 % | absent from the top 18 |
| `EffectiveRelevanceIndex.isRelevantWithin` itself | 11.16 % | 5.27 % |
| `EffectiveRelevanceIndex.projectedKey` | — (did not exist) | **8.23 %**, now the top frame |
| `ArrayList.hashCodeRange` + `equalsArrayList` | 2.80 % | 7.24 % |

**The second half, same day: the probe key itself.** With the string building gone, `projectedKey` was the top frame, so the tuple became an `IntArray`-backed key with a REUSED probe instance:

| | `validatePart` calls in ~20 s |
|---|---|
| original | 3,329 |
| after the level plan | 11,108 |
| after the primitive key | **11,880** (**3.57x** cumulative) |

`projectedKey`, `ArrayList.hashCodeRange` and `ArrayList.equalsArrayList` all leave the top-18 self frames; `RepKey.reset` + `RepKey.hashCode` cost **6.57 %** where those three cost **15.47 %**.

**The method note is the durable part of this checkpoint.** IG128 was written from `perf-keys.sh`, which reports the nearest interpreter caller of a `java.util.*` **key mechanic**. `isRelevantWithin` topped that view at 12 %, and the row recorded the list key as the cost. It was not: that instrument filters by mechanism, so it ranks callers **within** the hashing family and is blind by construction to the string building that turned out to dominate. A hot frame it surfaces still needs a plain self-time profile before its cost is explained — the two views answer different questions and only the second one apportions.

The list-key mechanics were proportionally larger once the string work was gone, which is what promoted them to the top frame and made the second half worth doing the same day.

### Checkpoint 2026-08-13 — the over-repetition prelude, and a load asymmetry that inverted the reading

The IG127.1 fix ([IF288](INTERPRETER-FINDINGS.md#if288)). Machine `Macmini9,1 Apple M1` (8 processors), JDK 21.0.12.

**1. The mechanism, paired on one machine in one session.** `scripts/perf-keys.sh validate` (`complexRealWorld @ N=240`), the working tree against its own parent rather than against the committed anchor's revision — the anchor is at `03594677` and predates both [IF286](INTERPRETER-FINDINGS.md#if286) and `e1a7fdd6`, so it is not "before this change" for any lane.

| frame, by nearest caller | before (1,674 samples) | after (1,712 samples) |
|---|---|---|
| `ModelPathIndex.info` via `exceededBound` | **10.33 %** (173) | **0.41 %** (7) |
| `info` via `anyExceedsBound` (the new scan) | — | 0.06 % (1) |
| `ModelPathIndex.info`, all callers | 11.35 % | 2.34 % (now dominated by `ancestorsOf`, untouched) |

**Two live magnitudes exist for what reads as one quantity, and they are NOT reconciled.** The list-in-key sweep below records `16.90 %` for `info` under `exceededBound` on `73bc4a04`; the same instrument on the same scenario measures `10.33 %` on `f8cc5f3f`. Nothing in the intervening work explains a 1.6x fall (IF286 is compute-only and cannot reach this frame). Either a single 20 s `ExecutionSample` run is a coarser magnitude estimator than two decimal places suggest, or an intervening commit moved it. **Only the paired pair above may be quoted for this change**; the older figure belongs to its own revision and is not differenced against this one. The direction (173 -> 7 samples) is far outside sampling noise either way.
| `ValueAdmissionCache.resultAt` present anywhere | 33.75 % | 18.87 % |

A first version of the scan probed per CELL rather than per path and showed up as `Document.valuedFields` at 2.95 % self plus `anyExceedsBound`'s ancestor at 2.16 %; the path-major form is what the table above measures. Recording it because the intermediate state is the instructive one: an optimization whose own instrument costs a fifth of what it saves is easy to bank without noticing.

**2. The lane comparison is DIRECTIONAL ONLY, and the reason is in the record.** Three isolated `perfGateTest` runs per side, folded at their per-key median, both sides against the same anchor so its staleness cancels. Median across 36 lanes **0.945×**, spanning `0.721×` (`constrained-fields @5000`) to `1.093×` (`B1-per-row @5000`). Seven lanes read above 1.0 and every one is the SMALL member of its scale pair, which is the signature of a fixed per-call cost — but it is not what happened here:

| trio | per-observation `loadAverage`, median of each run |
|---|---|
| after (`f8cc5f3f-DIRTY`) | **9.53 / 3.85 / 4.62** |
| before (`f8cc5f3f`) | **3.03 / 3.23 / 4.88** |

The after side was measured on the busier machine and still won the median, so the improvement is understated. **The seven small-N readings are CONFOUNDED, not explained**, and the distinction is the point: this change does introduce a fixed per-call cost (the scan is `O(|entityPaths| + |distinct placed paths|)` per public call, independent of document size), so a rival mechanism lives inside the diff and predicts exactly the same seven readings that the load asymmetry does. A load table cannot discriminate between them. Settling it needs one matched-load trio pair, or a direct measurement of the scan on the smallest scenario; neither was run, because the landing decision did not turn on it. This is the `loadAverage` field added by IG121 piece 3 doing the job it was added for; without it the honest conclusion would have been "possible small-scale regression, unresolved". **No per-lane number here should be quoted as clean.** A clean pair would need both trios re-run under matched load, which the landing decision did not require.

**The six run records are NOT committed.** Every pre-existing record under `perf-results/darwin-aarch64/` is cited by some checkpoint, and the tree is at its 30-run cap, so committing these would have evicted cited records to make a DIRECTIONAL comparison recomputable — the wrong trade, and the first attempt at it broke two markdown links and falsified a neighbouring checkpoint's "every figure below is recomputable" claim. To reproduce: three isolated `:adapter:perfGateTest` runs per side, folded with `scripts/perf-anchor.sh show <the three files>`, both sides against the same anchor so its staleness cancels.

**The anchor was deliberately NOT refreshed.** Refreshing from the contended trio is the compounding the committed anchor exists to prevent, and no lane fell below the `0.6 ×` stale-band floor (the largest drop is `constrained-fields @5000` at 0.89× of anchor), so nothing is disarmed by leaving it.

### Checkpoint 2026-08-12 — the PARTIAL-validation key profile: one of IG126's four closed, and a 12 % member nobody had listed

The first profile of `validatePart` (`scripts/perf-keys.sh validate-partial`, new driver `EvalProfileTest.loopPartialSemanticIndexForProfiler`, `SI-cliff` at N=1,000, 1,374 `ExecutionSample`s). IG126 was reopened because four memos read 0 on scenarios that structurally could not execute them; this is the path that does.

| frame | samples | share | reading |
|---|---|---|---|
| `EffectiveRelevanceIndex.isRelevantWithin` | 165 | **12.01 %** | **the largest key-mechanic caller here, and it was on no list** |
| `IndexIdentityKt.resolveIndexIdentity` | 25 | 1.82 % | — |
| `ValueAdmissionCache.resultAt` | 19 | 1.38 % | IG127's prelude, **1.38 % here against 16.90 % in a full validation** |
| `Interpreter.sharingIndexColumns$lambda…` | 5 | **0.36 %** | `ValidationPass.indexColumns`, now genuinely **reached and cold** |
| `CascadeOverlay.rowsAt` | 0 | 0.00 % | not exercised — a compute-mode path, so this 0 is again by construction, not evidence |

Top self frames are the mechanic itself: `ArrayList.hashCodeRange` 1.46 %, `ArrayList.equals` 1.24 %, `HashMap.putVal` 1.09 %.

**`indexColumns` is closed as cold, and this time the 0.36 % is a measurement**: the frame is present, so the memo was consulted, which is exactly what its previous 0 could not establish. **`decided`, the group-relevance map, and `rows` remain unmeasured** — no frame carries them on this run, and `rows` needs an `RepetitionNotUnique`/index-group model this driver does not build. `CascadeOverlay.rowsAt`'s 0 is stated rather than folded in, because reading it as "cheap" would repeat the precise error that reopened IG126.

**The 12 % member is not a memo key at all.** `isRelevantWithin` walks each path prefix and, for every relevance pattern at that depth, builds a **fresh `List<Int>`** (`mapIndexed`) and probes a `HashSet<List<Int>>`. So the cost is `depth × patterns` allocations-plus-list-hashes per relevance question, and relevance is asked per cell. It is [IF284](INTERPRETER-FINDINGS.md#if284)'s shape with yet another multiplier, on a path no profile had covered — the third time this class's real members turned up by profiling rather than by enumeration. *(Superseded 2026-08-13: this mechanism reading is wrong — the list key was ~2.8 % and the dominant cost was prefix-string building. See [IF289](INTERPRETER-FINDINGS.md#if289) and the checkpoint of that date; both halves have since shipped.)*

### Checkpoint 2026-08-12 — the cross-engine margin's low tail, over 27 runs instead of three

*Conclusion:* [IF300](INTERPRETER-FINDINGS.md#if300) — a three-run basis structurally cannot see a tail, and a floor sized from the median is sized from the wrong statistic.

The parity gate's documented margins were medians over **three** isolated runs. Reading the whole committed series (`scripts/perf-trend.sh --ratio`, 27 runs, `darwin-aarch64`, one machine and JDK) keeps every median and adds the tail that a three-run basis structurally cannot see.

| quantity (68 margin rows = 34 scenario-scale rows × 2 kernel strategies) | measured |
|---|---|
| median margin | **7.80×** (the 3-run figure was 8.50×) |
| rows whose **median** margin clears 2.5× | **66 of 68** |
| rows whose **worst observed** margin clears 2.5× | **55 of 68** |
| lowest worst-case margin in the record | **1.35×** — `CO-correlation @200` vs generated Java |
| median / widest per-row margin spread (MAD-based) | **±19.7 % / ±35.3 %** |

The five tightest rows by worst observed margin are `CO-correlation @200` (1.35×), `CO-correlation @800` (1.58×), `CO-correlation @200` vs Groovy (1.67×), `complexRealWorld @60` (1.85×) and `Berlin-AddDays @250` (1.88×) — every one of them against generated Java, which is the fair algorithmic arm.

**Nothing here is a breach**, and no bar moved: every row stayed above parity in all 27 runs. What changed is the *headroom* the gate is understood to have on its tightest row — 35 %, not the ~99 % that pairing a 1.99× median with a 1.036× spread implies.

**Two method notes, both of which cost a round when they were missing before.** The margin's spread is **not** its operands' spread: both engines share a JVM, vCPUs and load slice, so an excursion largely cancels in the ratio — a synthetic row whose absolutes swing 2× at a fixed ratio reports ±1.0 % (`PerfTrendScriptTest.theMarginSpreadIsTheRatiosOwnAndNotItsOperands`). And the older `1.036×`/`1.134×`/`1.544×` figures are a **different statistic** from the MAD-based `±%` here; they are not to be reconciled with each other, only the margins are comparable across the two bases.

### Checkpoint 2026-08-12 — the warm outcome and the measurement cost enter the record

The first run to carry them is [`2026-08-12T124426Z`](../perf-results/darwin-aarch64/2026-08-12T124426Z.jsonl) (`Macmini9,1 Apple M1`, `loadAverage` 4.96, commit `813c553a-DIRTY` — the feature *is* the recording, so it was measured on the working tree that added it): **138 observations, 102 warm outcomes, 17 cost rows**, and **0 warm rows that fail to join** an observation in the same run. All 102 warms in that run ended `flattened` — no budget cap, which is the state [IF282](INTERPRETER-FINDINGS.md#if282) set the 8 s cap to reach, now recorded rather than asserted from a log.

Its 17 `cost` rows sum to **98.1 s** for the parity tier. That is **not** comparable to the 71.5 s IF282 quotes: this is the sum of each lane's own measured span (agreement checks, six warms, timed pairs, and any re-measurement), while the earlier figure came from a build log's task time. The point of recording it is that from here the number has one definition and a history; the two should not be differenced.

**Coverage, stated because it is narrower than the feature sounds:** the 102 rows are `kernel parity`'s 17 scenarios × 6 instances. The two Number-index lanes and the synthesized-model lane call the same warm protocol without passing a lane, so they still only print their outcome — including the exact instance whose cap set the 8 s budget (IF282's `Number index validate` Groovy arm at N=2,000). That remainder is IG121's, not fixed here.

### Checkpoint 2026-08-12 — the cascade overlay's three hashes of one key

Method: `scripts/perf-keys.sh compute` before and after, then three isolated `perfGateTest` runs on the same machine (`Macmini9,1 Apple M1`, 8 processors), nothing else running, folded at their per-key median through `scripts/perf-anchor.sh show`. Records [`2026-08-12T120652Z`](../perf-results/darwin-aarch64/2026-08-12T120652Z.jsonl) / [`T121253Z`](../perf-results/darwin-aarch64/2026-08-12T121253Z.jsonl) / [`T121706Z`](../perf-results/darwin-aarch64/2026-08-12T121706Z.jsonl), `loadAverage` 8.32 / 6.07 / 4.38 — all three `-DIRTY`, because they measured this change's working tree before it landed, which is what that marker is for.

The anchor they are compared against is the committed `darwin-aarch64` block at **`03594677`** (`capturedAt 2026-08-12T092018Z`, a median of three), whose own input runs carried per-observation load **4.31–8.44**. *(Corrected 2026-08-12: this first read `80a4b99e … loads 2.8–7.1`. Both were wrong — the block names `03594677`, and folding that trio reproduces all 36 anchor values while the `80a4b99e` trio reproduces none. The load contrast that supports reading the validate movement as contention is therefore 4.31–8.44 against 4.58–10.57, which is thinner than the original sentence implied and is the honest basis.)*

**The key mechanics on the compute path are gone** ([IF286](INTERPRETER-FINDINGS.md#if286)). Presence anywhere in the stack, before → after, on ~1,600 samples each. The two `ArrayList` rows are **nested, not independent** — `perf-keys.sh` matches a frame by substring, and `ArrayList.hashCode` is a prefix of `hashCodeRange`, so the first row contains the second (measured 108 against 107 samples before the change):

| frame | before | after |
|---|---|---|
| `kotlin.Pair.hashCode` | 9.97 % | **0.25 %** |
| `java.util.ArrayList.hashCode` (contains the row below) | 6.95 % | **0.18 %** |
| `ArrayList.hashCodeRange` | 6.89 %, 3rd hottest self frame | **0.18 %** present |
| the lookup itself | `OverlayContext.stateOf` present in 29.67 %, innermost-mechanic caller for 16.09 % | `CascadeOverlay.rowsAt` present in 4.32 %, 2.16 % self |

**The four compute-overlay lanes and the tiered-compute lane fell; nothing breached.** Median of three against the anchor:

| lane | anchor → median | |
|---|---|---|
| `CO-correlation @6000` | 128.88 → 104.84 ms | **0.81×** |
| `CO-correlation @1500` | 32.18 → 26.47 ms | **0.82×** |
| `CO-index @6000` | 33.91 → 28.88 ms | **0.85×** |
| `tiered compute @20000` | 57.54 → 52.11 ms | **0.91×** |
| `CO-index @1500` | 6.17 → 5.75 ms | **0.93×** |

**The validate lanes read 1.03×–1.34× higher, and this is recorded as unexplained-but-bounded rather than as no-change.** No lane breached (the gate reported no breach, no cap stop and no stale anchor across all three runs), every value sits inside this machine's measured `1.42×` run-to-run band, and the largest moves are the *small* scales (`B1 @5000` 1.34×, `FVNU @5000` 1.25×, `COLD first-call` 1.18 % wall) — the signature of a shorter measurement on a busier machine, which these runs were. Separately, and as a mechanism rather than an observation: `OverlayContext` has exactly one construction site, in `ComputationEngine`, so no validate path can reach this code at all. A quiet machine would be needed to read those lanes as anything but noise, and the anchor was deliberately **not** refreshed from these runs — refreshing would raise the validate ceilings from a contended measurement, which is the compounding the committed anchor exists to prevent.

One run of the three printed `[perf][note] tiered compute: interp@1000 ran out of warm budget after 60 runs (run-cap)`. The other two did not. Recorded rather than smoothed: the run cap is the `maxRuns` guard, not the 8 s time cap [IF282](INTERPRETER-FINDINGS.md#if282) set, and at the time of these runs the warm outcome was not yet in the committed record, so there was no history to say whether this lane had done it before. It is recorded from the next checkpoint onward, which is what IG121's fourth piece shipped for.

### Checkpoint 2026-08-12 — the list-in-key sweep: one memo measured cold, four not exercised, the live ones elsewhere

Method: `scripts/perf-keys.sh validate` and `scripts/perf-keys.sh compute` on `73bc4a04`, machine `Macmini9,1 Apple M1` (8 processors), one 20 s single-scenario loop each under `jdk.ExecutionSample`, nothing else running. Validate = `complexRealWorld @ N=240`, **1,615 samples**; compute = `CO-correlation @ N=200`, **1,554 samples**. A profile is a proportion, not a bar: these are read against their own totals, and a contended machine moves them. *(The recordings live under `build/` and are transient. The compute baseline no longer exists: `perf-keys.sh` wrote one fixed filename per mode, so profiling `compute` again after the [IF286](INTERPRETER-FINDINGS.md#if286) fix overwrote it. The script now timestamps each recording so a baseline cannot be clobbered again; the before figures below were taken from that lost run and are reproducible only by re-profiling the parent commit.)*

**1. What the five memos [IG126] named actually measured — and for four of them it is NOT "cold".** *(Corrected 2026-08-12 after a cold review. This table first read "the five memos are cold on both scenarios"; that claim is unsupported for four of the five, and the correction is the interesting part.)* Two distinct reasons a row reads 0:

| memo | validate | compute | what the 0 means |
|---|---|---|---|
| `AggregateMemoKey(expression, scopePrefixes)` | 2 samples (0.12 %) | 0 | **measured.** It is a `data class`, so its `hashCode`/`equals` appear as frames and a hot one would be visible |
| `ValidationPass.indexColumns` | 0 | 0 | **not exercised.** Its own KDoc says "Empty and unread in a FULL validation", and both profiled scenarios are full validations — the 0 was guaranteed by construction |
| `DmInterpreter.decided` | 0 | 0 | **not exercised.** The enclosing `starRelevantOf` appears in 0 samples anywhere in either profile |
| group-relevance `HashMap<String, HashMap<List<Int>, …>>` | 0 | 0 | **not exercised.** Same: its enclosing function never appears |
| `RepetitionNotUniqueEvaluator.rows` | 0 | 0 | **not exercised.** The feature does not run in either scenario |

The four are also invisible to this instrument even if hot: they are *fields*, not classes, so no frame carries their name and only the enclosing function can be attributed. Establishing whether they are cheap needs a scenario that engages each — partial validation for `indexColumns` and `decided`, a model with an `RepetitionNotUnique`/index group for `rows`, a relevance-scoped call for group relevance. That is reopened as IG126.

**2. The class's live members were not on that list.** The shape is common enough that a count is a judgement call rather than a fact — the query below yields **22 declaration sites**, a looser one 74, so what is published is the query, not a number:

```
grep -rnE '(val|var) [a-zA-Z]+(: [^=]+)? = (HashMap|LinkedHashMap|HashSet|LinkedHashSet)<(Pair<String, List<Int>>|Triple<String, Int, List<Int>>|List<Int>|String, (HashMap|Map)<List<Int>)' \
  interpreter/src/commonMain --include='*.kt' | grep -v '/build/'
```

Most of those are **accumulators** — one entry per computed cell, written about once per unit of work, where key hashing is proportional to the work rather than multiplied by it. The ones that are memos consulted repeatedly are `Document.kt:307` (`valueByKey`, the hottest member of the class and already nested for exactly this reason), `Document.kt:803`, `ValidationPass.kt:55`, `DmInterpreter.kt:1111`, and `OverlayContext.kt:248/251`.

| site | measured | mechanism |
|---|---|---|
| `AutoChecks.overRepetitionResultAt` → `ModelPathIndex.exceededBound` → `info(path)` | **20.25 %** of validate samples (327 of 1,615); `exceededBound` present in 22.91 %, of which `info`'s `HashMap<String, PathInfo>` lookup is 16.90 % | an **unmemoized prelude**: `ValueAdmissionCache.resultAt` runs it before consulting its memo, on every consultation by every consumer. IF284 moved it out of the memo to make the key value-shaped and assumed it cheap |
| `OverlayContext.stateOf` | present in **29.67 %** of compute samples; **16.09 %** of all samples are a key mechanic innermost with `stateOf` nearest (`displayValue` 16.54 %, `field` 6.95 %, `cellState` 6.18 %) | exactly IF284's shape: **three** lookups (`poisoned`, `cleared`, `overlay`) of one `Pair<String, List<Int>>`, so the repetition list is walked three times per logical read. `ArrayList.hashCodeRange` is the third-hottest self frame in the compute profile at 6.89 % |

Two more leads, recorded and not pursued: `AdmittedGroupContentIndex.build$addAncestors` is 26.87 % of validate samples through one `validateWith` lambda (an index **build**, not a key), and `PredicateOp.byName` spends 3.59 % in a map lookup that a resolved-once handle would remove.

**3. A one-slot memo on the prelude's path lookup was built and FALSIFIED the same day.** The attempt resolved `path → PathBounds` once and cached the handle in the call-local `ValueAdmissionCache`, compared by identity, on the hypothesis that the multiplicity making this hot — emission, suppression, RNU and computation each consulting the same cell — puts the same path in *consecutive* calls. It does not: after the change the identical map probe still appeared in **11.36 %** of samples under its new name (`ModelPathIndex.boundsAt` ← `AutoChecks.overRepetitionBoundsAt`), against 16.90 % before, so the memo missed nearly every time. Those consultations happen in different *passes*, which interleave paths row-major. The ~5 pp difference is one profile pair and not a basis for keeping the added type and two indirections, so the change was reverted rather than banked. What it rules out: any per-path memo is the same probe, so the only zero-hash option is to attach the resolved bounds to the per-cell record the formal walk already iterates — a model-into-document coupling that needs its own design, not a fourth guess.

### Checkpoint 2026-08-12 — the stacked formal-result memos, and the tightening the anchor was supposed to unlock

Two results, one of them negative. Method: three isolated `perfGateTest` runs per state on the same machine (`Macmini9,1 Apple M1`, 8 processors), nothing else running, `loadAverage` recorded per observation. Records `2026-08-12T091153Z`/`T091605Z`/`T092018Z` (after the change, `loadAverage` 4.3–8.4) against the committed block at `087c39f5`, then `T093210Z`/`T093617Z`/`T094119Z` (2.8–7.1) against the refreshed block.

**1. Keying three nested memos by the cell cost more than the check they cached ([IF284](INTERPRETER-FINDINGS.md#if284)).** 34 of the 36 anchored observations fell:

| lane | anchored (`087c39f5`) | measured | ratio |
|---|---|---|---|
| `DT-Berlin` @5000 / @20000 | 34.67 / 157.37 ms | **17.19 / 73.45 ms** | 0.50× / **0.47×** |
| `B1-per-row` @5000 / @20000 | 8.64 / 49.27 ms | **5.58 / 27.28 ms** | 0.65× / 0.55× |
| `Berlin-AddDays` @1000 / @4000 | 9.05 / 39.79 ms | **5.10 / 22.52 ms** | 0.56× / 0.57× |
| `SI-cliff` @5000 / @20000 | 11.57 / 60.22 ms | **8.13 / 35.77 ms** | 0.70× / 0.59× |
| `complexRealWorld` @250 / @1000 | 56.93 / 282.26 ms | **38.19 / 182.97 ms** | 0.67× / 0.65× |
| `deepRich` @250 / @1000 | 11.30 / 64.93 ms | **8.42 / 38.66 ms** | 0.75× / 0.60× |
| `constrained-fields` @5000 / @20000 | 4.62 / 21.24 ms | **2.92 / 13.84 ms** | 0.63× / 0.65× |
| `PI-parallel` @5000 / @20000 | 15.80 / 89.53 ms | 13.15 / 67.33 ms | 0.83× / 0.75× |
| `CO-correlation` @1500 / @6000 | 33.41 / 131.59 ms | 32.18 / 128.88 ms | 0.96× / 0.98× |
| `PF-framed` @5000 / @20000 | 32.09 / 144.72 ms | 31.44 / 131.49 ms | 0.98× / 0.91× |
| `tiered-compute` @5000 / @20000 | 12.89 / 65.41 ms | 12.02 / 57.54 ms | 0.93× / 0.88× |
| `CO-index` @1500 / @6000 | 5.93 / 30.62 ms | 6.17 / 33.91 ms | 1.04× / **1.11×** |

`CO-index`'s `1.11×` is the one row that got *slower*; it is inside this machine's measured run-to-run band and is recorded rather than argued away. **What the ordering does and does not establish.** The lanes that improved most are the validate-heavy ones and the four that barely moved are compute-overlay or compute-tiered — consistent with a per-cell formal cache being hit by cell reads rather than by overlay arithmetic. It does **not** discriminate that reading from the rival one: "the gain tracks each scenario's per-cell formal read count" predicts the same table, and `PF-framed` — a validate lane — moved *less* than `CO-correlation @1500` did. A discriminating case would need the read count varied independently of the scenario family, which was not run, so the mechanism above stands on the profile that motivated the change and on the leaf breakdown, not on this table. *(Recorded after a cold review pointed out that the first version of this paragraph asserted "the compute-overlay rows are the two that do not move" while omitting `PF-framed` and `tiered-compute` from the table, which are as unmoved.)* Cross-engine, the parity envelope improved from a median margin of ~7.1× to **8.50×** while the tightest row is unchanged at **1.99×** (`CO-correlation @200`, spread `1.036×`), 32 of 34 rows at 2.5× or better, and the parity tier now costs **71.5 s** against 82 s.

**2. The tolerance is tightened `1.8 → 1.65`, and the per-class tolerance queued behind it is refused.** The 2026-08-11 note said tightening needed the noise reduced rather than the number lowered, and named "anchor from the median of several runs" as the way; both blocks are now medians of three. **The quantity that decides it is not `observed/anchor`.** A breach is `observed > anchor × factor + slack`, so a factor must clear `observed/anchor − slack/anchor`, and the slack term is 3.7× the anchor on the smallest lane and 0.4 % of it on the largest. Over 322 observations in three sets:

| set | observations | worst `observed/anchor` | worst `observed/anchor − slack/anchor` | margin of 1.65 |
|---|---|---|---|---|
| local, in-sample (`loadAverage` 4.3–8.4) | 108 | 1.420 | 1.276 | 1.29× |
| local, **out-of-sample** against the block it did not author (2.8–7.1) | 108 | 1.471 | **1.352** | **1.22×** |
| hosted, 2 vCPU | 106 | 1.345 | 1.303 | 1.27× |

Zero of the 322 would breach at 1.65 — nor at 1.50, so the number is set by the ≥1.2× margin rule rather than by the first value that passes. *(Corrected the same day after a cold review: the first version of this section concluded the tightening was **refused**, comparing `observed/anchor` against the factor and citing `FVNU@5000` as a lane that `1.45` "would have red-lined". That is arithmetically false — the lane anchors at 6.95 ms, so its ceiling at 1.45 is `6.95 × 1.45 + 1 = 11.08 ms` and its worst observation of 9.87 ms passes with 11 % to spare. Comparing a ratio against a factor whose bar carries an additive term is the "name the quantity before you compare numbers" trap, one layer in.)*

One conclusion of that first attempt survives: a **per-class** tolerance has no basis, because the local class is the noisier of the two on both quantities. So one number serves both classes.

**A method note, since it cost two batches of runs.** The first attempt at these numbers was contaminated twice over — a leftover build from a cancelled batch was still executing beside the new one, and the agent's own file reads were landing inside the timed windows. That run put `PI-parallel @20000` at `2.175×` its anchor, against a `1.147×` quiet-machine spread, i.e. it looked exactly like a large regression. The rule in CLAUDE.md ("a task carrying a timing assertion gets the machine to itself") covers the agent's own tool calls, not merely other builds; the measurement was retaken after waiting for the machine to go quiet, and the contaminated records stay committed with their `loadAverage` so they read as what they are.

### Checkpoint 2026-08-12 — the `darwin-aarch64` anchor, refreshed as a median of three

*Conclusion:* [IF300](INTERPRETER-FINDINGS.md#if300) — the binding quantity for a tolerance is one run's distance from the anchor, not the anchor's own precision.

Three sequential isolated `perfGateTest` runs at `087c39f5` on a quiet machine (`loadAverage` 3.3 on eight processors, all 36 keys measured in every run, zero flags), folded at the per-key median: records `2026-08-12T015324Z`, `T015814Z`, `T020249Z`. The block moves from **one run's sample at `4ea0bf3a`** to a **median of three at the current revision**. *(Corrected 2026-08-12, later the same day: this closed by saying the median-of-three is "what makes tightening `ANCHOR_TOLERANCE` possible later". It does not. Tightening was attempted on that basis and refused — the binding quantity is a single run's distance from the anchor, not the anchor's precision, and it measures `1.421×` on this machine under ordinary load. See the checkpoint above.)*

**What the diff showed, and why it matters more than the refresh itself.** Absolute anchors are the only bar in this estate that sees a *uniform* slowdown, and a stale block silently loosens it. Four families had drifted far:

| lane | anchored | measured | the ceiling had been |
|---|---|---|---|
| `PF-framed` @5000 / @20000 | 88.46 / 369.69 ms | **32.09 / 144.72 ms** | 2.8× / 2.6× the real cost |
| `CO-correlation` @1500 / @6000 | 52.14 / 306.36 ms | **33.41 / 131.59 ms** | 1.6× / 2.3× |
| `PI-parallel` @5000 / @20000 | 27.21 / 138.01 ms | **15.80 / 89.53 ms** | 1.7× / 1.5× |
| `tiered-compute` @5000 | 17.39 ms | **12.89 ms** | 1.3× |

The `PF-framed` and `PI-parallel` rows are catalog #12 carrying to the growth scales — the parity tier measured the same change at a tenth of the scale — and `CO-correlation` is catalog #11, which landed after the block was captured and was never folded in. The remaining 29 keys move within `0.82×`–`1.08×`, which is a median-of-three replacing a single sample rather than a change in cost. **The lesson is the trigger, not the numbers: an optimization must refresh the anchors of the scenarios it speeds up, in its own commit, or it leaves a bar that cannot fail.**

### Checkpoint 2026-08-12 — the parallel row context, profiled and memoized (catalog #12, [IF283](INTERPRETER-FINDINGS.md#if283))

**Why these two scenarios.** After the parity bar was extended to all 17 gated scenarios, the thinnest VALIDATE rows were `PF-framed` (`0.39`) and `complexRealWorld` (`0.38`), and the two optimization slices queued behind them had both been derived from a single COMPUTE profile of `CO-correlation`. A hot path found on one scenario is not a shared one, so both were profiled through the same driver (`EvalProfileTest`, JFR, ~1,400–1,650 samples each).

| claim under test | `PF-framed` @1000 | `complexRealWorld` @240 |
|---|---|---|
| formal-error checking is hot | `FormalResultCache.resultAt` **26.4 %** inclusive | **47.1 %** inclusive |
| String-key work is hot | `split`+`trim`+`parts` **≈ 12 %** self | `HashMap` get/hash/put/resize **33 %** + `ArrayList` hash/equals **11.5 %** self |

**The basis of every percentage here, since two are in play.** *Inclusive* figures are the share of whole-run samples whose stack passes through the frame; *self* figures are the share of whole-run samples whose LEAF is that frame. IG122's breakdown of the formal cache uses a third basis — shares **of** `resultAt`'s own 47.1 %, not of the run — which is why its `35.2 %` and this row's `33 %` are not the same quantity (`35.2 % × 47.1 % = 16.6 %` of the run, and `16.6 % ≤ 33 %` as it must be, the difference being the `HashMap` work outside the formal cache).
| *(not previously suspected)* | `ParallelRowContext.repsOf` **63.6 %** inclusive, `ArrayList.add` behind it **16.7 %** self | — (no parallel context) |

Both queued premises are confirmed and shared. The third finding was larger than either and had a two-line cause — see catalog #12 for the fix and its numbers: `PF-framed` **−62 %** at both scales, `PI-parallel` **−34 %/−39 %**, and `PF-framed`'s margin over the kernel `2.5× → 6.5×`.

**A caveat on the untouched rows, recorded rather than argued away.** Four of the 30 scenarios that construct no parallel context read between +5 % and +13 % after the change. They cannot be caused by it, and the same five runs put `CO-correlation @200`'s own spread at `1.47×` — against `1.07×` measured earlier the same evening on a quieter machine. The band is the machine's, not the code's; it is also a reminder that a run-to-run spread is a property of the conditions and has to be re-measured, not carried forward.

### Checkpoint 2026-08-12 — the first pair was measured cold, on every engine ([IF282](INTERPRETER-FINDINGS.md#if282))

The checkpoint below concluded that the parity noise sat in the small arm and prescribed more sampling there. Tracing the individual observations (`-Dperf.trace=true`, which prints what the medians reduce away) says it is not a *scale* effect at all: **the first pair is measured on cold engines, at both scales.** Pair 0 divided by pair 2, `CO-correlation`, three sequential isolated runs:

| series | run 1 | run 2 | run 3 |
|---|---|---|---|
| interpreter @200 | 2.09× | 1.88× | 2.34× |
| Groovy @200 | 2.11× | 2.03× | 1.84× |
| Java @200 | 1.75× | 1.80× | 1.80× |
| interpreter @800 | 1.15× | 1.11× | 1.19× |
| Groovy @800 | 1.70× | 1.51× | 1.60× |
| Java @800 | 1.51× | 1.46× | 1.54× |

**All eighteen are above 1.0**, which is the discriminator: contention is symmetric and would put about half of them below. The small arm looked noisier only because it warms slowest — it runs the shared methods a quarter as often per evaluation. And because the three engines warm at *different* rates, none of it cancels out of a cross-engine ratio the way it cancels out of one engine's growth factor.

`PerfHarness.warmToSteadyState` now brings each of the six instances to steady state once, before the first pair, adaptively (batches of three, stop after two consecutive flat probes, capped at 60 runs or 1 s per instance). Afterwards the same drift spans `0.94×`–`1.28×` and is no longer one-signed. Like-for-like, **three sequential isolated runs of all 17 lanes before and after** *(caveat on the AFTER records: they are stamped `dfeadae8` with no `-DIRTY` marker because the marker itself landed later, at `69b85692` — they were in fact measured on `dfeadae8` plus the then-uncommitted warm. Every record from `69b85692` onward is labelled correctly; these three are the reason a series read by revision must not trust a bare label from before that commit.)* (records `2026-08-11T233842Z`/`T233949Z`/`T234053Z` against `2026-08-12T001016Z`/`T001139Z`/`T001258Z` under [`perf-results/darwin-aarch64/`](../perf-results/darwin-aarch64/)):

| quantity | before | after |
|---|---|---|
| worst per-row ratio spread | **1.90×** (`CO-correlation @200`) | **1.52×** (`CO-index @200`) |
| that row's spread | — | `CO-correlation @200`: **1.07×** |
| worst single observation, all 34 rows × 3 runs | **0.743** — margin `1.35×` | **0.512** — margin `1.95×` |
| thinnest row's margin ÷ its own spread | **0.71×** | **1.83×** |
| rows at margin ≥ 2.5× | 29/34 | 33/34 |
| median per-row spread | 1.203× | 1.152× |
| parity tier wall time | ~65 s | ~82 s |

**What this does and does not buy.** It removes the systematic component, which was concentrated precisely in the rows that had it worst: every one of the six noisiest rows improved, and the tightest went from `1.90×` spread to `1.07×`. It does nothing for the residual sampling noise — the median spread barely moves and 13 of 34 rows are marginally worse, which at three samples per row is what an unchanged quantity looks like. The point is the thinnest row: its margin used to be `0.71×` of its own noise and is now `1.83×` of it, which is the difference between a number that can be judged and one that cannot.

**The measured level is also more honest, and in one place less flattering.** `CO-correlation @200` reads `0.509` warm against `0.460` cold: generated Java was the engine furthest from steady state, so the old figure credited the interpreter with part of the kernel's warm-up. Most rows move the other way (`PI-parallel @250` `0.305` → `0.161`, `MSC @150` `0.142` → `0.095`, both per-row medians of the three runs each side) — whichever engine was more under-warmed decides the direction, which is exactly why a cold ratio means nothing.

### Checkpoint 2026-08-12 — each parity row's OWN ratio noise, measured instead of borrowed

*Conclusion:* [IF300](INTERPRETER-FINDINGS.md#if300) — a noise figure is only valid for the quantity, scenario and machine class it was measured on.

**Three sequential isolated runs of the 17 parity lanes, quiet local machine, same revision** (records `2026-08-11T233842Z`, `T233949Z`, `T234053Z` under [`perf-results/darwin-aarch64/`](../perf-results/darwin-aarch64/)). Motivated by a comparison that was wrong twice over: the tightest row's margin had been held against `1.78×`, a run-to-run figure measured on *different scenarios* on a *different machine class*, and then described as "below" it when `1.9 > 1.78`. Borrowing a noise figure across scenarios is the same trap as borrowing one across quantities. So this measures each row's own.

| quantity | value |
|---|---|
| rows measured in all three runs | 34 |
| worst per-row ratio spread | **1.90×** — `CO-correlation @200` (`0.39`, `0.74`, `0.46`) |
| its worst observation | **0.74**, a margin of only **1.4×** — against `0.54` in the single run reported before |
| that row's margin ÷ its own spread | **0.71×** — the noise is LARGER than the margin |
| next four thinnest (margin ÷ own spread) | `DT-Berlin @250` 1.6×, `complexRealWorld @60` 1.7×, `PF-framed @250` 1.8×, `Berlin-AddDays @250` 2.0× |

**Two conclusions, both actionable.**

1. **The single-run figure was optimistic, and the retry is load-bearing rather than belt-and-braces.** With samples `0.39 / 0.74 / 0.46` the accepted median is `0.46`, which passes comfortably — but any one of them alone could have been reported as the row's ratio, and `0.74` is one contended slice away from parity. `PerfHarness.acceptOnMedianOfRetries` is what stands between this lane and a false red; that is now measured rather than argued.
2. **Noise concentrates at the SMALL scale of each pair, not in particular scenarios.** Every thin row above is the small arm — `@200`, `@250`, `@60` — while their large arms are nearly noise-free (`CO-correlation @800` spreads `1.05×`, `complexRealWorld @240` `1.00×`, `PF-framed @1000` `1.10×`). *(Corrected 2026-08-12, same day: the observation holds, the explanation and the prescription that followed from it do not. It read the small arm's larger fixed-cost share and fewer iterations as the cause, and prescribed **more sampling on the small arm only**. Tracing the individual observations found the cause is that the first pair is measured **cold on every engine at both scales** — the small arm simply warms slowest. More sampling would have averaged the cold pair in more precisely rather than removing it; the fix is a steady-state warm before the first pair, and the checkpoint above measures both. Recorded rather than deleted because the wrong prescription was reached from a correct observation, which is the failure mode the "match the oracle to the CLAIM KIND" rule exists for: the observation was measured, the mechanism was inferred.)*

### Checkpoint 2026-08-12 — the compute-overlay read path, after the tightest row was optimized (catalog #11)

The same 17 parity lanes re-run after the three compute-overlay fixes, one isolated run, same machine. Record: [`perf-results/darwin-aarch64/2026-08-11T231938Z.jsonl`](../perf-results/darwin-aarch64/2026-08-11T231938Z.jsonl).

| over all 38 rows | before | after |
|---|---|---|
| rows the interpreter loses | 0 | **0** |
| worst | 0.63 (`1.6×`) | **0.54 (`1.9×`)** |
| median | 0.17 (`5.9×`) | **0.14 (`7.1×`)** |
| best | 0.02 (`50×`) | 0.02 (`50×`) |
| growth band, 18 lanes | `4.19×–6.40×`, median `4.95×` | **`3.77×–5.43×`, median `4.83×`** |

`CO-correlation` itself, the row that motivated the work: at N=800 the interpreter went **34.39 ms → 23.49 ms** and its worse-strategy ratio **0.63 → 0.42**, so that row moved from `1.6×` to `2.4×`. The estate's worst row is now `CO-correlation @200` and `complexRealWorld-111f-82r @60`, both at `0.54`.

**A single run understates how thin this is** — see the noise checkpoint below, which measures it properly. What keeps the lane from false-redding is the re-measurement of a comparative breach, not the margin. The same profile names the next two candidates — formal-error checking (≈16 % of samples) and the String-keyed `reps`/`placeOf` lookups (≈15 %) — neither of which is specific to this scenario.

### Checkpoint 2026-08-12 — the cross-engine bar extended from 2 scenarios to 17, and what that did to the margin (IF281)

One isolated `scripts/perf.sh gate` run with the machine to itself, Apple Silicon (`darwin-aarch64`, 8 processors, JDK 21.0.12). The record is [`perf-results/darwin-aarch64/2026-08-11T224400Z.jsonl`](../perf-results/darwin-aarch64/2026-08-11T224400Z.jsonl), so every figure below is recomputable rather than quoted.

**Cross-engine (the gate).** Ratios are `interp/kernel`, so **lower is better and 1.00 is parity**; each row takes the worse of the two kernel strategies.

| over all 38 scenario×scale rows | value |
|---|---|
| rows the interpreter **loses** | **0** |
| worst | **0.63** — `CO-correlation @800` against generated Java (`1.6×` margin) |
| median | 0.17 (`6.1×`) |
| best | 0.02 (`50×`) |

The eight tightest, since they are what any future bar has to clear:

| scenario | N | interp/groovy | interp/java | margin |
|---|---|---|---|---|
| `CO-correlation` | 800 | 0.52 | 0.63 | 1.6× |
| `CO-correlation` | 200 | 0.42 | 0.56 | 1.8× |
| `Berlin-AddDays-historical` | 250 | 0.46 | 0.53 | 1.9× |
| `PF-framed` | 1000 | 0.40 | 0.45 | 2.2× |
| `PF-framed` | 250 | 0.38 | 0.42 | 2.4× |
| `complexRealWorld-111f-82r-low-firing` | 240 | 0.35 | 0.40 | 2.5× |
| `complexRealWorld-111f-82r-low-firing` | 60 | 0.28 | 0.37 | 2.7× |
| `Berlin-AddDays-historical` | 1000 | 0.31 | 0.34 | 2.9× |

**Self-referential (the growth floors).** All 18 lanes over their 4× step: **4.19×–6.40×**, median **4.95×** — implied exponents `n^1.03`–`n^1.34` against linear `n^1.00`, the `9.0×` floor at `n^1.58` and quadratic at `n^2.00`. **Zero lanes at or above the floor**, and none within reach of quadratic.

**The tightest cross-engine row's margin is smaller than this estate's own measured noise for that quantity, and that is the finding.** Checkpoint 2026-08-11 put worst run-to-run noise on the **level** ratio at `1.78×` against generated Java and `2.16×` against Groovy. `CO-correlation`'s `1.6×` sits below both. The `1.41×` figure that first appeared beside it is the noise of a **different quantity** — per-unit cost — and comparing the two is the "name the quantity before you compare numbers" trap this project has an explicit rule about. The consequence is not that the bar is wrong but that it needed the growth floor's protection: a comparative breach is now **re-measured** and accepted on the median of the attempts, so one contended slice cannot red the nightly (`PerfHarness.acceptOnMedianOfRetries`).

**Prior coverage, for the record.** Before this the bar existed on **two** scenarios (`Number-index` validation and computation), and those are not growth-gated — so all 17 growth-gated scenarios were uncovered by it. Their only relative bar was `RATIO_CEILING`, `interp/groovy < 5.0`, which tolerates the interpreter being five times *slower*, and nothing measured generated Java at all. The 17 parity lanes add **≈55 s** to the gate.

### Checkpoint 2026-08-11 — the hosted runner class measured, and a concurrent-dispatch method error (IF280 / IG120)

First measurements of the `linux-amd64-ci` runner class, taken to author its absolute-time anchor block. Three `workflow_dispatch` runs of `1845f8af` on `ubuntu-latest` (`processors=2`, JDKs `21.0.12`/`21.0.11`/`21.0.11`), each recording all 36 gate observations. Evidence, not a promoted bar.

**Every number in the table below comes from the CONCURRENTLY dispatched runs and is contaminated** (the method error is explained under it). The corrected sequential runs are the ones committed under [`perf-results/linux-amd64-ci/`](../perf-results/linux-amd64-ci/); their result is the second table. The two sets are not comparable, and reading a figure from one against a figure from the other is precisely the mistake this labelling exists to prevent — it has already been made once.

| quantity | measured (concurrent — contaminated) |
|---|---|
| whole-run level, pairwise medians over the 36 lanes | `run2/run1 = 1.58×`, `run3/run1 = 1.94×`, `run3/run2 = 1.19×` |
| gate step wall time, same order | `6m37s`, `9m27s`, `11m57s` |
| absolute run2/run1 envelope, CPU clock | `0.97×–2.38×`, median `1.58×` (local `darwin-aarch64`: `0.92×–1.36×`) |
| observations above the ratified `1.8× + 1 ms`, runs 1 vs 2 | **10 of 36** |
| …after dividing out the whole-run level | **0 of 36** (spread unchanged at `2.47×`) |
| growth ratios, all 18 lanes over the three runs | `2.48×–7.98×` (local: `4.00×–5.99×`); the worst is 89 % of the 9.0 floor |
| `FVNU-uniqueness` growth, the previously documented "control" | `5.85×`, `3.94×`, `6.10×` — a `1.55×` spread, so it is not a stability control here |
| hosted/local absolute ratio, run 1 vs the committed block | `0.58×–1.59×` (a `2.74×` spread, cf. the `2.07×–2.69×` cross-class drift recorded above) |

**The method error, recorded because every number above is contaminated by it.** The three runs were dispatched **concurrently**: they started within 12 s of each other, and the per-run level ordering tracks the gate wall time monotonically (`1.00 / 1.58 / 1.94` against `6m37s / 9m27s / 11m57s`). Hosted runners are VMs on shared physical hosts, so three of one's own jobs evict each other's cache lines and consume each other's memory bandwidth — spent in **user-mode** cycles, so it inflates a CPU-clock median as readily as a wall-clock one, and `getCurrentThreadUserTime` would not have filtered it either. **`gh workflow run` ×N is not isolation just because each job gets its own VM**: this is the remote form of CLAUDE.md's rule that a task carrying a timing assertion gets the machine to itself, traded away to save wall time. The interleaved N/4N paired estimator gives a *growth* figure some protection, since both halves of a pair meet similar conditions; an *absolute* median has none, and the anchor needs absolute medians.

**The correction: three strictly sequential runs of the same revision, each dispatched only after the previous finished.** They are committed as the record under [`perf-results/linux-amd64-ci/`](../perf-results/linux-amd64-ci/) and every figure here is recomputed from those files.

| quantity | measured (sequential) |
|---|---|
| whole-run level, pairwise per-key medians | `1.003×`, `0.923×`, `0.927×` — no run-level shift at all, against `1.58×`/`1.94×` concurrent |
| per-key spread, same pairs | `0.74–1.98×`, `0.66–1.91×`, `0.60–1.24×` |
| pairwise observations above `1.8× + 1 ms`, either direction | **0**, over all three pairs |
| observations above the ceiling of the anchor a refresh would write | **0 of 106** (the folded anchor is the per-key median of the three runs) |
| tightest observation against that ceiling | **73 %** — `Berlin historical AddDays @1000`, 31.97 ms against a 23.77 ms anchor |

**This settles IG120 in favour of arming the hosted block**, reversing the disposition recorded above: with the dispatch pattern fixed, the ratified `1.8× + 1 ms` tolerance holds on the hosted class with 27 % of margin at its tightest point, so the class's run-to-run variance is bounded by measurement rather than assumed. The contaminated figures stay recorded because the method error, not the numbers, is the durable lesson.

**One lane did not survive all three runs, and it is the informative one.** Run 2 recorded 34 of 36 observations: both scales of `COLD first-call (startup)` are absent, and that is the estate's only **wall-clock** lane. Those imports predate the record-on-both-paths fix, so a breached lane contributed nothing at all — which is consistent with the cold path being the most contention-sensitive measurement in the gate, exactly as its clock predicts. It is why the growth-floor split keeps COLD gated only in the isolated lane.

### Checkpoint 2026-08-08 — the façade's quadratic allocation, and why a timing guard missed it (IF256)

The public `Interpreter` façade copied a pointer list the engine had deliberately **shared** across the findings of a dense `RepetitionNotUnique` cluster ([IF191](INTERPRETER-FINDINGS.md#if191)), so one N-element list was copied N times. Fixed by removing the copy: the engine publishes an immutable snapshot and the façade recognizes it ([IF261](INTERPRETER-FINDINGS.md#if261)).

**The first remedy was a one-slot-per-channel reuse cache, and it was a bound asserted rather than measured** — it held for a single cluster and missed on *every* message of two interleaved ones, restoring the full quadratic. Nothing bounds how many duplicate clusters a document interleaves, so no slot count was defensible; enlarging it would only have moved the discriminator. **When a fix depends on a cache hitting, ask what makes it miss before asking how big it should be** — here the answer was to make the invariant a property of the value instead.

**Do not use a timing guard for an allocation defect.** Measured with the defect fully present: 500→2,000 rows grew **2.68×**, and 1,000→4,000 grew **3.80×** against a 5.4× trip line — both green. At 8,000 rows the façade produced ~64 million pointer references and killed the test JVM with `OutOfMemoryError`. `arraycopy` is cheap, so N² copies barely register on a clock while being fatal to the heap; worse, any growth guard tuned to a scale that *fits* in memory necessarily sits in the range where the defect is invisible. The guard therefore asserts the invariant directly — findings the engine gave the same list must share one list — at 40 rows, with no clock. **When the suspected cost is allocation, assert the allocation property, not a time ratio.**

### Checkpoint 2026-08-08 — partial validation repaired, and a harness that was charging the kernel twice (IF255)

**Partial validation, `SumOfProducts` under a complete concrete enumeration** (`laws/PartialSumOfProductsPerfTest`, Apple M1): **5.40×** the kernel at 250 rows, **7.17×** at 1000, growth **3.92×** for 4× rows. The path had been quadratic for its whole existence and was measured at parity with the kernel before the fix.

**The number this replaces was inflated ~2.7×, and the cause is a reusable trap.** At the time of the measurement, `RuntimeLaws.messagesPart` expanded the model, constructed both service factories, and converted V1→V2 **inside every call**, while the interpreter reused its prepared model, so each iteration charged the kernel a whole engine construction. It reported 19.57× at 1000 rows. The helper now reuses a bounded exact-model engine, but it still reconstructs the document per call and therefore remains the wrong timed baseline when the interpreter reuses a prepared document. The rule this yields: **a comparison must build BOTH engines once, and the reviewer's question is not "is the timed region small" but "does each side pay the same setup".** `RuntimeLaws.KernelEngine.partialMessageCount` is the build-once seam; relevant-pointer conversion deliberately stays inside the clock because the interpreter re-indexes its own relevant set per call, so paying it on both sides is what makes the comparison symmetric rather than merely fast.

**The published sweep figures are NOT affected, and this is the basis rather than an assurance.** Every headline number in the READMEs and the release notes — 2.1×–12.5× across the default inventory, 10×–16.7× repeated, 1.32× at the hostile point, ~155× on the huge-document edge — comes from `PerfBenchTest`/`KernelEdgeTest` via `KernelPerfEngine`, which calls `RuntimeLaws.kernelEngine(...)` **once per scenario** outside every timed region. The defect was confined to the one partial-validation guard, and no published surface cites a partial-validation ratio. The 2026-08-03 sweep also remains current on its own terms: the IF255 gates exist only on the `validatePart` path, so full validation and compute are untouched by them.

**Cost of the correctness repair.** Deciding completeness per iteration row costs a row-keyed memo: partial validation at 1000 rows moved from ~10 ms to ~15 ms while staying linear. Exactness is not traded for speed, and a linear path several times the kernel has the headroom.

**Read the ratios above as single-run snapshots, not constants.** A re-measurement on the same machine returned 4.79× / 10.00× / growth 3.17× against the 5.40× / 7.17× / 3.92× recorded here. The conclusion — linear, and several times the kernel — is what the guard asserts and what survives re-running; the individual figures move by tens of percent between runs and no published surface should quote one as a bar.

### Checkpoint 2026-08-08 — the completeness gate rebuilt as a union cover, still linear (IF259/IF260)

A second review of the IF255/IF257 repair found both gates still wrong: completeness was decided **per wildcard pattern** (suppressing a relevant set whose union is complete), and the shared row-scoping helper bound a **multi-star** operand at the path's deepest repeatable level instead of its first star, firing on rows the caller had never made relevant. Both are correctness defects; the interest here is that the fix for the first is an algorithm, not a predicate.

Completeness now takes the cover over the union of every addressing entity — each becomes a box over the levels the star iterates, and the boxes must cover the full cross-product. Recursing on the *wildcarding* boxes first is what keeps it cheap: when they alone cover the remaining levels every index is covered and no per-index descent happens, and otherwise the descent is bounded by the concrete indices actually present rather than by the declared repetition counts. So the enumerate-every-row shape — the one that made the relevant set grow with the document — stays linear in the entity count.

**Measured after the change** (`laws/PartialSumOfProductsPerfTest`, Apple M1, isolated single-class run): 250 rows **5.25×** the kernel, 1000 rows **9.41×**, growth **3.26×** for 4× rows against a linear ≈4. A strictly more general cover, and no worse than the per-pattern predicate it replaced.

### Checkpoint 2026-08-03 — release sweep and row-invariant aggregate repair (IF246)

All figures in this checkpoint are same-machine Apple M1 medians from the repository harness after exact result preflight; generated-Kernel code generation is outside every timed region. `scripts/perf.sh bench` covered the 15-case default inventory: `ratioJava` ranged from 0.08 to 0.47, so the interpreter was 2.1× to 12.5× faster than generated Java. The two repeated-document lanes measured 0.06 and 0.10, a 10× to 16.7× lead. `scripts/perf.sh crossover` reached 384 concentrated rules without a crossover: firing measured 0.36 and the closest low-firing point measured 0.76, a 1.32× lead. The complete 13-case `KernelEdgeTest` also had no eligible Kernel win; its largest ratios were 0.64 against generated Java and 0.63 against dynamic Groovy. These statements are bounded to the named scenarios and scales, not universal claims.

The release sweep found one unacceptable curve that a diagnostic had printed but never guarded: a per-row rule reread the same row-invariant `Sum(Lines* / Amount)` for every row. The new does-fire guard measured 43.4→746.2 ms at 500→2,000 rows before the fix, 17.12× for 4× input. The final routine gate measured 0.6→2.9 ms, 4.51×. The final isolated tri-engine discriminator measured interpreter 0.74→3.01 ms (4.07×), generated Java 29.66→433.80 ms (14.63×), and dynamic Groovy 35.23→475.94 ms (13.51×). The implementation shares only unfiltered, uncorrelated aggregate results within one validation call and an identical complete star-scope key; sibling scopes and compute overlays remain separate.

The first full adapter regression run also exposed a measurement-harness defect rather than a product CPU-work curve: despite owning the machine at the process level, the task scheduled `CharsetPerfTest` beside other classes in up to four JVM forks and its BMP N/4N elapsed-time ratio reached 10.22×; the focused class passed. The interpreter-only charset and custom-type algorithmic guards now measure current-thread CPU time, excluding time outside the measuring thread. Their `<9×` floor and paired estimator are unchanged, and the unchanged full estate then executed all four methods with no failure or skip. Multi-fork scheduler contention is the mechanism basis; the measured conclusion is narrower: the excess was elapsed time, not interpreter-thread CPU work. Cross-engine throughput and browser first-answer latency remain wall-clock measurements.

A single bounded JFR follow-up tested whether the closest 384-rule low-firing point could economically be pushed beyond a 2× lead. Reusing an interpreter through nested hash maps regressed the measured point from the canonical 214.66 ms / 0.76 ratio to 308.26 ms / 1.07, so the change was reverted. A post-revert control restored the lead. This rejects that local caching design; reaching a reliably greater-than-2× margin would require broader strategy work and is not part of the current contract.

The packed-browser measurement from clean commit `961d56af` produced a **232,151-byte gzip JavaScript entry**, 10.5 ms median for the representative 200-row Document, and 203.6 ms median for the 10,000-row/4,900-finding stress Document. Both series used 10 independent fresh Chrome 150.0.7871.187 processes and zero discarded samples; the stress result remains below the 250 ms release bar. The collector's v4 report records the complete Git object id and `worktreeDirty:false`, so a future report cannot silently attribute modified source to `HEAD`.

### Checkpoint 2026-07-27 — packed-browser B1 release bar ratified and enforced (IF225)

The browser release scenario is `browser-b1-10000-rows-first-answer` v1: 10,000 flat rows, exactly 4,900 `HQ` findings, workspace preparation and Document materialization outside timing, then `createInterpreter()` through complete semantically checked `validateFull`. A routine series uses 10 fresh system-Chrome processes with zero discarded samples and reports the median. If the observed range is greater than half the median, the collector extends the same series to 50 samples before applying the bar.

On the pinned Apple M1 / Chrome 150.0.7871.186 runner, the original 50-process calibration series on `69e8985d` measured median/p95 193.45/204.4 ms and 195.9/218.4 ms; the first policy-enforced packed-`0.9.1` series on `b644b1e3` measured 190.65/209.3 ms. Corrected by the owner on 2026-07-27: p95 and an unconditional 50-process series were more ceremony than this smoke needs. The active bar is median ≤250 ms under the adaptive 10→50 protocol above. The first corrected-protocol series measured the representative 200-row, 23.1 kB, zero-finding Document at 10.2 ms median and the 10,000-row stress Document at 177.1 ms median; both stopped after 10 stable fresh-process samples. The earlier one-time 50-process characterization measured 10.6 ms and 178.8 ms medians respectively. The 0.11.0 release series on `3cee57e1`, under Chrome 150.0.7871.187, measured 10.0 ms and 187.7 ms medians respectively and also stopped after 10 stable fresh-process samples. The 2026-08-03 clean post-repair series on `961d56af`, under the same Chrome, measured 10.5 ms and 203.6 ms. The current report is written to `build/reports/interpreter-browser-performance/r5.2.json`; policy and negative controls run in ordinary browser verification, while the process collector remains an explicit measurement task.

### Checkpoint 2026-07-21 — G52 compact/depth synthesized-model sweep (partial; IF199/IG101→IF205)

The seed-42 compact and depth-shaped artifacts passed exact complete-result preflight at N=100 and N=400 against the interpreter, dynamic Groovy, and generated Java after IF198 corrected empty-string ingestion. Timings are same-run warm medians on Apple Silicon; growth is IF137's median of within-pair N/4N ratios, so it need not equal the quotient of the two displayed row medians. These artifacts predate the in-flight profile-fidelity regeneration and are diagnostic stress evidence, not a final calibration certificate or a promoted NFR bar.

| profile / operation | engine | N=100 | N=400 | paired growth |
|---|---|---:|---:|---:|
| compact / validate | interpreter | **1.34 ms** | **2.02 ms** | **1.48×** |
|  | dynamic Groovy | 6.59 ms | 11.70 ms | 1.59× |
|  | generated Java | 2.83 ms | 6.55 ms | 2.22× |
| compact / compute | interpreter | **0.03 ms** | **0.05 ms** | **0.99×** |
|  | dynamic Groovy | 2.82 ms | 4.33 ms | 1.52× |
|  | generated Java | 0.78 ms | 2.26 ms | 2.80× |
| depth / validate | interpreter | **16.494 ms** | **31.836 ms** | 2.05× |
|  | dynamic Groovy | 149.147 ms | 212.924 ms | **1.43×** |
|  | generated Java | 96.114 ms | 143.707 ms | 1.51× |
| depth / compute | interpreter | **19.943 ms** | **52.050 ms** | 2.58× |
|  | dynamic Groovy | 67.732 ms | 120.408 ms | **1.78×** |
|  | generated Java | 34.843 ms | 83.626 ms | 2.50× |

Compact validation passes both the absolute and growth development rules. Before IF199, the compact computation curve rebuilt the immutable input-placement lookup inside every timed projection: interpreter N=100→400 measured 0.151→0.188 ms with 1.71× paired growth versus Groovy's 1.30×, and N=1,000→4,000 measured 0.475→1.704 ms with 3.34× growth versus Groovy's 2.88×. IF199 moved only that input-static lookup outside repeated timing while retaining computation, complete result materialization, filtering, and sorting inside the clock. Compact computation then passed both rules; the larger N=1,000→4,000 confirmation measured interpreter 0.03→0.03 ms (1.37×), Groovy 6.29→18.99 ms (3.02×), and Java 4.46→17.32 ms (3.88×).

Depth keeps a substantial absolute lead—4.5–9× for validation and 1.6–3.4× for computation—but loses the paired growth criterion to both kernel strategies for validation and narrowly to generated Java as well as Groovy for computation. JFR attributes the shared residual to deep repetition-coordinate construction/hash/equality. The cached-hash wrapper and primitive-coordinate/key attempts did not improve the decisive curve, so IG101 recorded a deferred owner gate. **Superseded by the 2026-07-22 checkpoint below: a clean full-warmup sweep to N=16,000 shows the growth-ratio loss is a fixed-cost-dilution artifact, not a per-row defect — IG101 is resolved (IF205), no redesign.**

The breadth artifact was **not eligible for timing**. Its stale pre-fidelity synthesized form concentrated enough generated rules for dynamic Groovy code generation to fail with `MethodTooLargeException` before the timed region. That is neither a kernel performance result nor permission to omit the strategy. Breadth must be regenerated after structure/rule fidelity lands, then pass exact tri-engine preflight before a curve is recorded.

### Checkpoint 2026-07-22 — IG101 resolved: depth per-row favours the interpreter (no crossover) (IF205)

The G52 depth "growth-ratio loss" above prompted a clean full-warmup sweep to large N to settle whether it is a real per-row defect (which would imply a crossover where a kernel strategy overtakes) or a metric artifact. **It is an artifact — IG101 is closed (IF205), no redesign.** Clean depth/validate, warmup 12, Apple Silicon, one run:

| N | interpreter | generated Java | dynamic Groovy | interp vs Java | interp vs Groovy |
|---:|---:|---:|---:|---:|---:|
| 4,000 | 153.1 ms | 583.4 ms | 896.4 ms | 3.81× | 5.86× |
| 16,000 | 712.6 ms | 1519.9 ms | 1905.1 ms | 2.13× | 2.67× |

Decomposing `time = fixed + perRow·N` over 4,000→16,000: **interpreter 0.047 ms/row < generated Java 0.078 < dynamic Groovy 0.084** — the interpreter has the *lowest* per-row cost of all three engines. Therefore there is **no crossover at any N** (measured directly to 16,000 rows, the interpreter is still 2.13×/2.67× faster); the interp-vs-Java speedup asymptotes to ~0.078/0.047 ≈ **1.67×** (>1×) and the *absolute* gap widens with N. The kernel's flat growth *ratio* (~1.4×) is its enormous per-call fixed cost (Java ≈ 583 ms at N=4,000 vs the interpreter's ~12 ms) diluting its per-row cost, while the interpreter's tiny fixed cost makes its (smaller) per-row work a larger *fraction* — inflating its ratio. The interpreter is strictly linear (per-row ~0.04–0.05 ms/row across 100→16,000). The two prior representation attempts failed because there is no per-row inefficiency to remove.

Diagnostic evidence (warmup 12, medians), not an IF137 promotion. **The known limitation this checkpoint recorded is fixed (2026-08-11, IF279):** `synthesizedModelPerfTest` used to fail the depth shape on the *raw* N→4N growth bar (`interpreter growth 3.28× must be no worse than Groovy 1.30× / Java 1.43×`), which is the wrong lens for a lean engine on a fixed-cost-heavy comparison. Both callers share `pairedScenarioGrowth`, so teaching it the per-unit lens closed the parked guard-design decision for this lane and the routine gate together.

### Checkpoint 2026-07-21 — IF196 profile-derived breadth validation path preparation

The exact breadth-shaped synthesized model (`a12-synth-breadth-bulk.dm.json`, selected leaf `/G1/G17/G18/G19`) first failed the standing curve rule at 100→400 leaf rows: repeat runs put interpreter growth at 1.73–1.82× versus dynamic Groovy at 1.59–1.69×. An interpreter-only JFR located repeated path split/join work in over-repetition checks and IF193 admitted-content ancestor propagation, plus an unnecessary construction of evaluated row contexts in structural over-repetition emission. IF196 prepares model path prefixes/bounds once and reads structural rows directly.

Same-run corrected evidence on Apple Silicon used the fixed clock and custom callback, exact generated documents, complete interpreter/dynamic-Groovy/generated-Java signatures, three interleaved pairs, four warmups, and two timed iterations:

| engine | 100 rows | 400 rows | growth |
|---|---:|---:|---:|
| interpreter | 23.04 ms | 35.90 ms | **1.43×** |
| dynamic Groovy | 127.15 ms | 200.31 ms | 1.53× |
| generated Java | 53.90 ms | 109.59 ms | 1.84× |

The interpreter is 5.5× faster than dynamic Groovy and 2.3× faster than generated Java at 100 rows, 5.6× and 3.1× faster at 400 rows, and its curve is now better than both. This is diagnostic evidence under the standing development rule, not an IF137 numerical promotion.

### Checkpoint 2026-07-19 — IG98 multi-star `$`-correlation scaling discriminator (interp WORSE than the kernel → optimization owed)

The IG98 decision measurement: is the interpreter's naive multi-star `$`-equality-correlation scan (`MoreThanOneFieldFilled(/Perf/Lines* then Subs* then Cost Having [Cost] == [$Cost])`, per-Sub, star spans all Lines) *worse* than the kernel's complexity class, or does the kernel also scan it? Apple Silicon, `validateFull`, min-of-4 after 3 warmups, N and 4N Lines (×2 Subs each):

| engine | 150 Lines | 600 Lines | growth |
|---|--:|--:|--:|
| interpreter (naive multi-star scan) | 21.2 ms | 135.9 ms | **×6.41** |
| kernel-Java | 41.7 ms | 112.2 ms | **×2.69** |
| **interp/kernel ratio** | **0.51** | **1.21** | ratio ×2.37 |

**Reading.** The interp starts **2× faster** than kernel-Java (0.51) but ends **1.2× slower** (1.21) — the ratio *widens* ×2.37 over ×4 Lines, which means the interpreter is in a **worse complexity class than the kernel** for this shape (the kernel's ×2.69 is ~linear; the interp's ×6.41 is a soft quadratic). This FAILS IG98's "no worse than the kernel" criterion, so the multi-star hash-join optimization is owed (it is NOT a no-op / parity-by-construction). Legality was settled separately (`MultiStarOuterCorrelationDiffTest`: kernel MVK-accepts + interp matches on a cross-Line correlation).

**After the fix (IF144, the first-star-scope hash join, same scales):** interp **6.8 ms@150 → 16.2 ms@600 (×2.37, linear)**, kernel-Java 40.1 → 103.0 (×2.57), **interp/kernel ratio 0.17 → 0.16** — the ratio is now STABLE (marginally improving) and the interp is **~6× faster than kernel-Java** at both scales. The interp-only growth gate over N=5000/20000 Lines confirms ×4.26 (linear). So IF144 moved the multi-star `$`-correlation from a worse-than-kernel soft-quadratic into the kernel's complexity class, ~6× ahead in absolute terms. The follow-on 4-level differential locks bind depths 0 and 2, closing the former evidence residual.

### Checkpoint 2026-07-19 — IF143 compute-overlay scenarios vs the kernel (against-kernel scaling curve)

After the IF143 per-step overlay-view cache landed, measured both compute-overlay scenarios against BOTH kernel baselines at N = 200 / 800 / 3200 Rows (Apple Silicon, `runScenario` warmup 10 / iters 6, codegen primed out; `compute` op; interp == groovy == java asserted before timing). `ratioJava` = interp / kernel-**Java** (the fair, Groovy-tax-free comparator; < 1 = interp faster).

| scenario | N | interp | groovy | java | ratioJava |
|---|--:|--:|--:|--:|--:|
| **CO index** (`[Src For "K1"]` under an active overlay) | 200 | 2.26 ms | 18.50 ms | 13.97 ms | **0.16** |
| | 800 | 5.68 ms | 44.72 ms | 32.85 ms | **0.17** |
| | 3200 | 13.07 ms | 164.99 ms | 147.68 ms | **0.09** |
| **CO correlation** (`$`-equality self-correlation under an active overlay) | 200 | 11.16 ms | 16.27 ms | 13.44 ms | **0.83** |
| | 800 | 36.66 ms | 55.73 ms | 48.68 ms | **0.75** |
| | 3200 | 166.40 ms | 263.53 ms | 241.20 ms | **0.69** |

Per-4×-Rows growth factors (linear ⟹ ×4, quadratic ⟹ ×16):

| step | interp | groovy | java |
|---|--:|--:|--:|
| CO index 200→800 | ×2.51 | ×2.42 | ×2.35 |
| CO index 800→3200 | **×2.30** | ×3.69 | ×4.50 |
| CO correlation 200→800 | ×3.29 | ×3.43 | ×3.62 |
| CO correlation 800→3200 | **×4.54** | ×4.73 | ×4.95 |

**Reading.** (1) The **interpreter is faster than kernel-Java across the whole curve** — the index scenario by **~6–11×** (13 ms vs 148 ms at 3200), the correlation scenario by **~1.2–1.5×** — and faster than kernel-Groovy by more. (2) The **index path is linear-or-better** and its top-step growth (×2.30) beats kernel-Java's (×4.50), confirming the IF143 cache holds the index-column view O(rows). (3) The equality-correlation path's ~4.5×–5× growth per 4× rows is near-linear and shared by all three engines, confirming that the IF143 per-step cache prevents a rebuild cliff. This historical equality measurement says nothing about whether another correlation shape deserves a general scan; IF159 independently indexes the single-comparator class and keeps only explicit unsupported shapes on fallback.

### Evidence inspection 2026-07-18 — current Node scale report, not NFR-S/P certification

The existing `jsNodeTest` report for the current build records B1 per-row at **140.39 ms** and B2 star-aggregate at **60.88 ms** for 10,000 rows. This is useful plausibility evidence for the 350 ms initial first-answer ceiling, 250 ms design target, and 1,000 ms warm-throughput bar, but it is one shared test process with engine/document construction outside the clock—not a 50-process nearest-rank p95 run or the 2,500→10,000 paired growth protocol. It therefore certifies none of NFR-S2/P1 by itself.

### Checkpoint 2026-07-10 — full `-PrunPerf` lane GREEN after IF116 (the gate's first live catch)

The Set-E master-gate perf re-run caught a REAL O(rows²) — the IF107/IF115 per-lookup index-column rebuild (`semanticIndexStaysSubQuadratic` hung 20+ minutes at N=20,000, twice, deterministically) — fixed by the document-level `IndexColumn` memo (catalog #10, IF116). Post-fix, the full tagged perf lane (the routine growth guards plus the benchmark rows that now live in `PerfBenchTest`) took **7 m 51 s** and was green: all twelve growth checks were in the linear band (B1 4.31×, **semantic-index 4.03×**, NumberOfDifferentValues 4.07×, deep-nested Having 4.07×, FieldValuesNotUnique 2.71×, tiered compute 3.00×, wide groups 4.21×, constrained fields 4.33×, parallel iteration 3.45×, framed 4.19×, first-validation growth 3.37×, complex real-world 1233f/1017r 3.88×); the benchmark ratios were healthy (B2 star-aggregate ratioG 0.10, tiered compute 0.02, B6 many-rules 0.44).

### Checkpoint 2026-07-08 — `PerfGateTest` GREEN after the IF85 compute rebuild (no regression)

Standing WI-T perf checkpoint (`scripts/perf.sh gate`, Apple Silicon; the IF85 compute poison-on-read rebuild + the IF86 index-aggregate fix had not been perf-measured). **`BUILD SUCCESSFUL` — every ratio ceiling + sub-quadratic guard held.** The compute path (the IF85 territory) is unregressed and strong; sub-quadratic growth (≈4× for 4× data ⟹ linear, far under the quadratic≈16 trip) holds on every axis. Ratios are `ratioG` = interp / kernel-**Groovy** (the gate's baseline; lower = interp faster).

| scenario | scale | interp (min) | groovy | ratioG |
|---|--:|--:|--:|--:|
| **tiered compute** | 3000 firings | 3.10 ms | 134.1 ms | **0.02** |
| **B7 compute** | 600 | 3.48 ms | 102.9 ms | **0.04** |
| B1 per-row | 3000 (1470 fire) | 12.2 ms | 114.6 ms | 0.12 |
| B2 star-aggregate | 3000 | 10.8 ms | 99.8 ms | 0.11 |
| B6 many-rules | 600 (3548 fire) | 19.9 ms | 73.4 ms | 0.29 |
| combined real-world | 1233f/1017r, 60 rows | 12.1 ms | 66.3 ms | 0.19 |

Sub-quadratic growth (5000 → 20000, min-growth): tiered compute **3.34×**, constrained-field formal check **2.11×**, B1 4.54×, parallel-iteration 3.71×, NDV 4.13×, semantic-index 4.10×, deep-nested Having 4.05×, complex real-world 4.20× — all linear-class. **Tooling gap fixed in the same pass:** `scripts/perf.sh` never passed `-PrunPerf`, so every mode reported "No tests found" (the perf tests are `@Tag("perf")`, excluded by default) — added to `run_gradle`.

### Historical fair-Java run (2026-06-27; superseded by the 2026-08-03 checkpoint)

All ratios are **`ratioJava` = interp_median / kernel-Java_median** (the *fair* algorithmic comparison — same kernel logic, Groovy execution tax removed; **lower = the interpreter is faster**). Measured on the real `Document`/kernel evaluation path (never the spike `RepDoc`). "interp speed" = 1 / ratioJava.

| scenario | scale | ratioJava | interp speed |
|---|---|---|---|
| **combined real-world** (deep + wide + rule-heavy + data-axis at once) | 1233 fields / 1017 rules, 3000 charge rows | **0.19** (both regimes) | **~5×**; N→4N ×3.45–3.72 (sub-linear) |
| rule-count sweep, **concentrated** (firing) | 96 → 384 rules @ 1000×5 | 0.47 → 0.34 | 2–3× (lead *improves* with count) |
| rule-count sweep, **concentrated** (low-firing) | 96 → 384 rules @ 1000×5 | 0.37 → 0.64 | 1.6–2.7× (lead narrows, stays <1) |
| **distributed** rules ("wide form", low-firing) | 192 → 1000 rules @ N=100 | 0.35 → 0.34 | ~3× (flat) |
| aggregates (B2/B5/B7 — `Sum` / nested-star / compute) | 3000 rows | 0.02 → 0.04 | 25–50× |
| deep + rich (4-level, cross-level / `Having` / nested-star) | 100 → 1000 vendors | 0.09 → 0.12 | ~10× |
| wide sibling-groups (breadth axis) | 25 → 200 groups | 0.06 → 0.07 | ~15× |
| huge doc (data axis) | 10k → 20k rows | 0.02 → 0.01 | ~50× → ~165× |

**Bottom line for this recorded run:** the interpreter beat generated Java at every measured count—concentrated through 384 rules and distributed through 1,000—and the named data-axis scenarios showed linear-class growth. That is evidence for those points, not proof at every runnable count or NFR certification.

**Coverage caveat—never infer fairness from operator count alone.** The earlier “interpreter is faster because it omits field checks” concern was closed for the then-known per-cell constraints and baseline formal families. The audits nevertheless found legal subdomains outside the measured estate—timezone domain/history (IG83), JS regex semantics (closed by IF164), decimal host exactness (IF163), combined charset graphemes (IF151), custom-validator code/context/cardinality/message semantics (IF150/IF152), composed RNU (IF149), unsupported-result loss (IF139), and computation tolerance metadata plus normalization/polarity (IF148/IF174). A comparative sample is eligible only after the outcome-bearing API proves zero unsupported and the interpreter/dynamic/generated-Java results satisfy the ratified Groovy-dynamic anchor (IF136); IF140 supplies the derived capability × strategy × coverage-tier accounting.

### Historical rule-count crossover + distributed (one consistent run, 2026-06-27)

`KernelEdgeTest.ruleCountCrossover{,LowFiring}` + `distributedRuleScaling` via [`scripts/perf.sh`](../scripts/perf.sh); N=1000 Lines × 5 Subs (concentrated) / N=100 rows (distributed); median of 8 after 12 warmups; **codegen excluded** from every timed region; interp == kernel asserted before timing. interp_ms, kernel-Java_ms and the ratio are **all from the same run**, so an absolute can't contradict its ratio.

**Concentrated** (`manyRules` — `count` rules anchored on ~3 error fields, the codegen's worst packaging case; see the benchmark catalog):

| rules | firing: interp / java → ratioJava | low-firing: interp / java → ratioJava |
|--:|:--|:--|
| 96  | 116 / 249 ms → **0.47** | 74 / 199 ms → **0.37** |
| 192 | 168 / 420 ms → **0.40** | 109 / 210 ms → **0.52** |
| 384 | 277 / 821 ms → **0.34** | 213 / 335 ms → **0.64** |

**Distributed** (`manyRulesDistributed` — 1 rule per field, the realistic "wide form"; low-firing, N=100):

| rules | interp / java → ratioJava |
|--:|:--|
| 192  | 32 / 91 ms → **0.35** |
| 512  | 82 / 224 ms → **0.37** |
| 1000 | 156 / 459 ms → **0.34** |

**Read:** the interpreter wins at every measured count in both shapes. The concentrated **firing** ratio *improves* with rule count (0.47→0.34 — the codegen's per-firing-row message-building grows); the concentrated **low-firing** ratio *degrades* (0.37→0.64) but stays <1 through 384, and the concentrated generator hits its method-size wall around 512–768; the **distributed** ratio is flat at ~0.34–0.37 through 1,000 rules. The naive crossover is closed over this measured range.

### Naive baseline (the pre-optimization yardstick — 2026-06-25)

`KernelEdgeTest.ruleCountCrossover`, N=1000 Lines × 5 Subs, median of 8 after 12 warmups; codegen excluded. This is the *before* the optimization catalog ([INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md)) is read against:

| rules | interp | kernel-Groovy | kernel-Java | interp/java |
|--:|--:|--:|--:|:--:|
| 6 | 28.0 ms | 118.2 ms | 110.3 ms | **0.25** |
| 12 | 41.9 ms | 128.8 ms | 118.5 ms | 0.35 |
| 24 | 78.4 ms | 152.0 ms | 148.9 ms | 0.53 |
| 48 | 140.6 ms | 201.4 ms | 175.2 ms | 0.80 |
| 96 | 272.1 ms | 284.7 ms | 274.4 ms | **0.99 ← parity** |
| 192 | 543.2 ms | 502.9 ms | 414.2 ms | **1.31 ← the naive interpreter LOST here** |

The naive tree-walker reached parity at ~96 rules and **lost at 192** (≈1.31× firing, ≈1.70× low-firing) — the real crossover. The optimization catalog in [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md) (indexed `Document`, row-prefix index, the `repsOf` memo, the per-row resolution cache, the lazy-polarity walk, allocation interning) roughly halved the per-operation cost and turned 192 into the ~2–2.5× win in the current run — pushing the interpreter ahead at every count the code generator can run.

### Checkpoint 2026-08-13 — what the routine tier records once every lane goes through the recording seam

Measured on `darwin-aarch64` (Macmini9,1 Apple M1, 8 processors, JDK 21.0.12) while wiring [IF296](INTERPRETER-FINDINGS.md#if296). **The machine was not idle:** these are back-to-back 4-minute gate runs over roughly an hour at load ~9, which is a confound the numbers below carry rather than a condition they were measured under.

| | before | after |
|---|---|---|
| observation rows per gate run | 138 | **166** |
| distinct lanes | 20 | **33** |
| routine-tier classes contributing a row | 2 of 9 | **11 of 11** |
| armed anchor keys (`darwin-aarch64`) | 36 | **64** |

**Run-to-run stability, three sequential isolated gates.** Per-key spread (`max/min`) medians **1.14×** across all 64 keys. The two lanes fed by `KernelAdvantage` were the worst before their estimator changed from a mean to a median — `computeField` **1.89×** on `[3.13, 5.84, 3.09]`, `applyComputations` **1.82×** — and left the worst-five list afterwards. Four keys still reach the `1.65× + 1 ms` tolerance: `custom-type validation` **2.86×** at n=40000 (`[6.85, 2.83, 2.39]`) and **2.10×** at n=10000, `partial-SOP/complete-concrete` **1.78×**, `group-scope fill` **1.68×**. All six gate runs passed, so no false red occurred; the open question is whether those four are noisy estimators or a noisy hour, and it is IG134.

**Cost of arming.** Six gate runs at 4m07s–4m25s, plus five anchor refreshes. Two of those runs existed only because `assertWithinBudget` rethrew on the first unanchored key and left the lane's second endpoint unrecorded — fixed in the same change, so a new lane now arms in one run.

### Checkpoint 2026-08-14 — what makes a routine-tier lane noisy: magnitude, not the clock

*Conclusion:* [IF298](INTERPRETER-FINDINGS.md#if298) — lane noise tracks the measurement's magnitude; batching lifts a lane out of the sub-10 ms band.

Read from the committed record with `scripts/perf-trend.sh` over 174 keys on `darwin-aarch64` (27 runs, 2026-08-12 .. 2026-08-13). The question was which lanes are too noisy for the `1.65× + 1 ms` anchor tolerance to be a real bar; the first attempt answered it from the `max/min` of three runs and got both the set and the reason wrong.

**Estate median noise: ±7.3%.** Fourteen keys sit at or above ±20% with at least 7 runs.

| hypothesis | discriminator | verdict |
|---|---|---|
| wall-clock lanes are contention-exposed where CPU-clock lanes are not | median noise by clock: **wall ±6.9%** (n=124) vs **cpu ±9.4%** (n=50) | **falsified** — CPU lanes are the noisier population, and carry 8 of the 14 noisy keys from a quarter of the rows |
| noise is a function of the measurement's magnitude | noisy keys by current median: `0-2 ms` 1/34 · `2-5 ms` 6/37 · `5-10 ms` 7/29 · `10-30 ms` **0/41** · `30 ms+` **0/33** | **confirmed** — every noisy key in the estate is under 10 ms, and nothing above it is noisy |

The corrective follows from the mechanism rather than from the list: lift a lane's measured median out of the sub-10 ms band, by batching inside the timed block or by a larger N. Both cost nothing in fidelity, since a growth ratio is a quotient and a constant factor on both scales cancels — but a batch changes *what one block measures*, so it belongs in the observation key, while warmup and iteration counts do not.

**A note on the statistic.** `max/min` over three samples routinely reaches 1.5–1.7× on a distribution whose true spread is ±20%, which is why the first diagnosis produced four plausible names that were mostly not the worst offenders. The record's own instrument was already computing the right quantity over 20+ runs.

### Checkpoint 2026-08-14 — batching every sub-10 ms lane takes the over-tolerance count to zero

Each stage measured by three **sequential** isolated gate runs on `darwin-aarch64` (Macmini9,1 Apple M1 ×8, JDK 21.0.12), 64 armed keys, spread as `max/min` per key against the `1.65× + 1 ms` anchor tolerance. Mechanism and rationale: [IF298](INTERPRETER-FINDINGS.md#if298).

| stage | keys at/over 1.65× | median spread | max |
|---|---|---|---|
| before | 5 | 1.19× | 1.89× |
| more warmups + iterations | **7** | 1.17× | 2.86× |
| growth lanes batched (`PerfGateTest` ×4, charset, group-scope fill, custom-type) | 2 | 1.09× | 2.97× |
| `KernelAdvantage` lanes batched (`testRule`, `evalCandidate`, `computeField`, `applyComputations`, partial-SOP) | 1 | 1.10× | 1.68× |
| depth lane sized to its ~0.015 ms per call; custom-type warm 3 → 10 | **0** | **1.10×** | **1.52×** |

The second row is the informative one: raising warmups and iteration counts made the count *worse*, which is what fixed the diagnosis. Each figure is already a median of 35 samples (`warmup=5, iters=7` inside `pairedGrowth`'s 5 pairs), so within-run sampling was never the constraint — the variance is between JVM runs.

**A note on reading these numbers.** The third run of the final stage was measured after an interrupted gate left a **truncated** record (140 observations, 38 accepted, against 166/64 for a complete run). Folding it in would have reported "0 over tolerance" from 38 keys rather than 64 — a vacuous pass. The three runs above are all complete; a spread claim has to state its denominator.

**Gate cost** is unchanged at roughly 4m10s–4m50s: the batched lanes are a small fraction of a run dominated by kernel codegen and the parity tier.
