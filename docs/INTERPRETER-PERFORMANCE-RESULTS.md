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

### Checkpoint 2026-09-28 — call-owned interpreter callbacks qualify IG159

The later IG159 simple-validation/200 allocation crossing persisted on two clean Node captures. Moving the validation call's invalidity and index-column callbacks out of each rule row, and the computation equivalents to their call/overlay-step owners, removes that changed breach signature while keeping IF319's reading-context parameter. A one-fork rejection screen initially failed its predeclared exact-anchor target even though it passed the owner's 5% tolerance; the candidate was restored, then tested in a separately frozen qualification partition against the actual unchanged contract. [The plan's matrix and miss](CONFORMANCE-PLAN.md#ig159-callback-ownership-experiment-matrix-frozen-before-candidate-run-2026-09-28) preserve both decisions.

The final five-fork capture has 0 JVM and 4 Node anchor breaches across 48 footprint rows. The four Node compute-overlay allocation medians were all in the owner's accepted `325871e4a` capture and each is lower here; simple-validation/200 falls to 5,181,840 bytes (+2.669% against its anchor), and its other three scales also pass. A fresh same-source five-worker timing control avoids using the pre-IG155 `timing-copies` arm; all ten candidate B1/B2 **validation** elapsed/CPU medians improve by 0.289–5.416%. The three existing compute-overlay performance controls pass their growth gates ([raw run](../perf-results/darwin-aarch64/2026-09-28T050124Z.jsonl)), but no absolute compute-time A/B was measured, so the 5% timing conclusion is limited to B1/B2. This supports local closure of IG159's changed allocation signature without a new exception. It does not assign the historical allocation cost to the message fix or explain the four surviving compute-overlay breaches. [IF347](INTERPRETER-FINDINGS.md#if347) names the code change; [the raw qualification record](../perf-results/footprint/2026-09-28-ig159-callback/README.md) carries every worker and the commands.

### Checkpoint 2026-09-28 — field-message qualification and per-row configuration storage

<a id="checkpoint-2026-09-28-config-storage"></a>

The clean field-message fix at `6e621795b` still exceeded the standing allocation anchor in eight Node rows, including a newly crossing simple/200 row. Restoring just the four pre-fix message source files reduced that row to +4.267%, so the prediction that it would also breach without the fix was not confirmed. Candidate/control differences over all 24 Node medians range from −1.439% to +0.867%; this pair establishes neither a repeatable message-fix effect nor an instrumentation explanation. A second clean Node capture at `88ce5b704` repeats the new simple/200 crossing at +5.603% (5,329,904 bytes) with the same Node 22.0.0 and workload identity; its exact five samples and all raw records are in [the field-message captures](../perf-results/footprint/2026-09-28-field-messages/README.md). Counts alone do not make a changed set of eight breaches an accepted exception set, so IG159 remains open.

A diagnostic Node 22 fork retained all twelve V8 sampling trees. None contains a ModelLoader or renderPatternFieldMessage named frame; sampled absence is not proof of zero execution. At scale 1600, evaluation-Interpreter constructor stacks account for 5,629,944 bytes (simple), 5,594,856 (equality) and 30,728,536 (compute). The simple/200 tree also attributes 1,279,224 bytes to the inspector post frame, so its marginal crossing cannot be assigned to application code from the total alone. A proposed dictionary-property explanation was refuted: V8 reports fast properties for the actual 26-property generated instance. The Kotlin 2.2→2.4 transition remains an unmeasured historical confound, not an established cause.

The finite storage experiment reads fifteen immutable properties from the existing EvalConfig instead of copying them into each row Interpreter. It adds no owner, cache or shared parser. The exploratory fork lowered all allocation rows but missed its retained-heap prediction at simple/1600 (+12.12%); the subsequent five-fork median is 6,396,480 bytes, within the original anchor. All 48 JVM/Node footprint medians in the qualification capture fit the unchanged 5% anchor, worst +4.160% at Node compute/800 allocation. This removes the measured breaches without changing their threshold; it does not identify which historical change introduced them. [The storage capture](../perf-results/footprint/2026-09-28-config-storage/README.md) carries the raw workers and the paired timing qualification. The candidate is rejected and production sources restored: the initial Node B2 elapsed medians regress by 8.379%/7.067% and CPU by 12.786%/8.376%. Repeating only B2 with identical getter source gives elapsed +3.362%/+5.119% and CPU +2.559%/+4.690%; the larger elapsed row still exceeds 5%. JVM B1 and both Node B1 rows improve in the first pair. The extra indirection is not established as the cause: the unfiltered Sum path reads configuration outside its cell scan. Neither the byte gains nor a near-threshold timing miss authorize acceptance. No production change, anchor change or new exception is retained.

### Checkpoint 2026-09-27 — numeric-token correction: routine gate and isolated charset controls

<a id="checkpoint-2026-09-27--numeric-token-correction-routine-gate-and-isolated-charset-controls"></a>

The M3-N numeric-reader candidate passed 57 of 58 routine performance tests; its BMP charset row breached the existing anchor at N=8000 (retry median 12.89 ms, limit 12.20 ms). The [complete run](../perf-results/darwin-aarch64/2026-09-27T213726Z.jsonl) retains that failure. The discriminator restored only Parser.kt from `a28d06d1e`, ran the failed method, then restored the reviewed candidate and ran the identical method. Both isolated runs passed: [original parser](../perf-results/darwin-aarch64/2026-09-27T214521Z.jsonl), [candidate parser](../perf-results/darwin-aarch64/2026-09-27T214551Z.jsonl). These dirty-tree records identify the base revision; the only production difference between the controls is the numeric-reader change documented by [IF344](INTERPRETER-FINDINGS.md#if344).

The fixture constructs a ModelDef with no rules and times string-field validation, so it does not exercise numeric parsing. The controls do not establish a cause for the initial breach or a 5% timing guarantee: their N=8000 medians are 9.067/9.690 ms and N=32000 medians 44.745/45.938 ms, with recorded foreign CPU demand at the latter endpoints of 0.734/0.565. No anchor or threshold changed, and the failed run is not relabelled green. The restored candidate passes the isolated existing gate; finer timing qualification remains inconclusive. The JVM/JS WorkLedger controls passed separately. The footprint outcome and owner disposition are recorded below. The first clean-commit footprint attempt at `2d29b728b` stopped at Node launch because its configured heap argument was a Groovy GString; direct conversion of all eight configured task arrays reproduced it. Three String-concatenation corrections restore argument conversion and an actual Node worker. That diagnostic worker used Node 24, while the anchor requires Node 22; it supplies launch evidence only. The subsequent full capture used the anchor’s runtime identity.


The clean `325871e4a` capture, with Node 22.0.0 and the anchor’s exact heap settings, passes all 24 JVM and twelve Node retained-heap rows but fails eight Node allocation rows (+5.823% to +11.257%). The original-reader control still breaches seven allocation rows, including all four compute-overlay scales. Across all 24 Node heap medians the candidate differs from that control by −0.626% to +1.857%. Thus seven standing-anchor breaches also exist without the numeric correction; their allocation mechanism is not established. [Both native worker captures and provenance](../perf-results/footprint/2026-09-28-numeric-tokens/README.md) are retained. [IF346](INTERPRETER-FINDINGS.md#if346) records the resolved IG160 disposition and the standing-anchor exceptions. The owner accepted these eight measured breaches on 2026-09-28 provided they are the only exceptions; re-enumerating all 48 raw five-fork medians confirms exactly eight, with the other 40 within 5%. This is bounded acceptance of this capture, not a baseline rebase, a permanent waiver for those rows, or a guarantee about unmeasured timing.

| Node allocated-byte row | Baseline bytes | Corrected reader bytes | Increase | Original reader bytes | Original increase |
|---|---:|---:|---:|---:|---:|
| `compute-overlay/1600` | 326107776 | 362610176 | +11.193% | 362236744 | +11.079% |
| `compute-overlay/200` | 42609520 | 46691696 | +9.580% | 46542064 | +9.229% |
| `compute-overlay/400` | 84228408 | 93469432 | +10.971% | 93444320 | +10.942% |
| `compute-overlay/800` | 167501400 | 186357784 | +11.257% | 187036296 | +11.663% |
| `equality-correlation/200` | 7367080 | 7796032 | +5.823% | 7653912 | +3.893% |
| `simple-validation/1600` | 29572408 | 31611832 | +6.896% | 31811000 | +7.570% |
| `simple-validation/400` | 7497352 | 7972768 | +6.341% | 7910264 | +5.507% |
| `simple-validation/800` | 14534608 | 15458712 | +6.358% | 15474808 | +6.469% |

All eight concern allocated bytes, not retained heap. Seven also exceed 5% with the original reader; the additional equality-correlation/200 breach moves by +1.857% against that control, the largest candidate/control difference. The compute-overlay increases persist across all four scales. These observations separate the standing-anchor issue from a greater-than-5% numeric-reader regression on this pair; they do not identify the earlier allocation mechanism or prove that sampling variability caused the eighth crossing. No additional exception appeared among the 48 measured rows. The restored candidate’s [current-revision charset receipt](../perf-results/darwin-aarch64/2026-09-27T222648Z.jsonl) passes the isolated existing timing gate; it is one method, not a rerun of the complete routine gate.

### Checkpoint 2026-09-20 — the NATIVE lane's contention instrument, measured on the real binary: the same arithmetic, a different quantity, because the child's own CPU is unreadable on this platform

**The platform fact that decided the design, and it refuted the plan that had already been written down.** [IG154](INTERPRETER-GAPS.md)'s remainder was going to subtract the child process's own CPU and judge what was left. It cannot: `ProcessHandle.Info.totalCpuDuration()` returns **empty** on darwin for a child — measured while the child ran, in a poll loop immediately before it exited, and after it exited. So the parent can only form `system − parent`, which leaves the child's work in the remainder. That makes it **unattributed** demand rather than foreign demand: same formula, different meaning, because "this process" is now a parent waiting rather than the process doing the work.

**The arms, on the real `dmtool` binary** (darwin-aarch64, 8 cores, 8 samples per arm, applied load the only variable, spinners owned by the measuring process and force-killed between arms, plus a return control):

| applied load | wall median | unattributed readings | unusable |
|---|---|---|---|
| none | 77.9 ms | 0.257 – 0.327 | 5 / 8 |
| 4 spinners | 101.5 ms | **0.883 – 0.928** | 6 / 8 |
| 16 spinners | 100.6 ms | 0.993 – 0.998 | 5 / 8 |
| none (return control) | 72.0 ms | 0.184 – 0.412 | 3 / 8 |

No overlap between quiet (worst 0.412) and contaminated (best 0.883), and the control returns to the unloaded band. **Floor 0.70**, inside that gap, as its own constant — numerically equal to the sibling lanes' today and deliberately separate, since the two judge different quantities from different arms and re-deriving one must not move the other. Wall moved 77.9 → 101.5 ms under four spinners, which is the contamination the reading exists to make visible.

**The readings are SPARSE, and the count is part of the verdict rather than metadata beside it.** Only 2 to 5 of every 8 samples produced a usable reading, because the parent spends each window blocked on the child and the accessor's own window is correspondingly short. So `contendedSamples=1` means one of one or one of five, which are different claims; the report carries `demandReadings` beside it, and `contended` is stated only on a **majority** of the readings — a single burst of foreign load lands in one window and should not condemn a run this thin. With no readings at all the verdict is `false` with `demandReadings: 0`, which says nothing was observed rather than asserting a quiet machine.

**Verified end to end on the real lane, not inferred from unit tests** — `:adapter:interpreterNativePerformance` against the shipped binary, 8 samples, on a quiet machine:

```
"demandReadings": 5, "contendedSamples": 0, "contended": false
unattributedCpuUtilisation: [0.202, 0.141, 0.123, 0.177, 0.191, null, null, null]
```

Five readings, all well under the floor, three nulls preserved as absence rather than written as zeros. *(The binary in this checkout predates the current source, which does not matter here: what is being verified is the report's shape and the verdict's arithmetic, neither of which depends on what the binary computes. A performance claim about the interpreter would need a re-built binary and the mtime gate that enforces it.)*

**This lane reports and does not gate, which is a property of the lane rather than a compromise.** Its report is `informativeOnly` — there is no anchor and no bar a contended sample could corrupt. What a contended sample corrupts is the report's standing as evidence, so the reading rides every sample, the verdict sits in the summary, and a contended run prints a flag naming the count and the floor. Nothing is dropped: a contended record is evidence of the condition that produced it, the same reason the interpreter protocol refuses at aggregation rather than at the write.

### Checkpoint 2026-09-20 — IG154's selected instrument PROBED before being trusted: it SEPARATES the contamination occupancy is blind to, clips where occupancy still ranks, and intermittently returns an impossible zero

**Why this was run, and it is a correction of process as much as a measurement.** The checkpoint below revised IG154 (closed the same day, graduated to [IF330](INTERPRETER-FINDINGS.md#if330))'s closure plan to "system CPU demand sampled across the measurement window" — a **capability claim about a running surface**, which this project's own rule says is settled by executing the thing and never by reading the API that feeds it. It was written as *selected* before it had been run. So it was run: JDK 21.0.12.1 (the pinned toolchain), darwin-aarch64, 8 cores, `com.sun.management.OperatingSystemMXBean`, one thread burning CPU inside every window so a zero reading is physically impossible.

| probe | windows | usable | returned `0.0` | notes |
|---|---|---|---|---|
| interleaving arms (250 ms) | 18 | 17 | 1 | reading `getProcessCpuLoad()` in the same window is **not** the cause — 0 of 6 in that arm |
| window-length sweep (100–2000 ms) | 35 | 35 | 0 | window length is **not** the cause either |
| rate probe (200 ms) | 60 | 47 | 13 | usable range 0.132–0.256 of eight cores |
| first probe (250 ms) | 6 | 3 | 3 | the observation that started this |

**Finding 1 — the quantity is available and its usable readings are coherent.** `getCpuLoad()` returns a system-wide utilisation fraction, and across the 47 usable readings of the rate probe **not one fell below the calling process's own load** (`getProcessCpuTime` delta over wall over cores), which is the cheapest available sanity oracle for a system-wide figure. So the instrument IG154 needs does exist on this machine class, in the JDK, with no native code and no Linux-only file.

**Finding 2 — it intermittently returns `0.0`, which cannot be true, and the incidence is BURSTY rather than a rate.** 17 of 119 windows overall — but 0 of 35 in one sweep against 13 of 60 in another, and 3 of 6 in the first. Two candidate mechanisms were tested and both refuted: short windows (0 zeros at 100 ms) and interleaving the process-load call (0 zeros in that arm). The cause is therefore **unknown and recorded as unknown** — what matters for the design is settled either way. **A `0.0` must be recorded as ABSENT, never as "no foreign load"**, exactly the absence-versus-zero rule `PerfEnvironmentQuality` already applies to its Linux-only counters; a gate judges only windows that produced a reading, and may never read a zero as evidence of a quiet machine. A threshold designed against the 22 % figure alone would have been designed against a number that is 0 % in a neighbouring sweep.

**Finding 3 — load average overstates demand by 3–4×, measured side by side, which is the existing claim's first direct number.** In the same windows where system demand read **0.132–0.256** of eight cores, `getSystemLoadAverage()` reported **3.49–5.03** — that is 0.44–0.63 runnable threads per core against a true utilisation of 0.13–0.26. [`scripts/perf-anchor.sh`](../scripts/perf-anchor.sh)'s 1.0-per-core refusal admits both readings, so the pair is a worked instance of what [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md)'s `loadAverage` section argues from mechanism: the lagging one-minute average is not a usable idleness gate, and the instrument that replaces it has to be the windowed one.

**Finding 4 — it SEPARATES at the magnitude the occupancy ratio misses, measured the same day, and the two instruments turn out to be complementary rather than rival.** Four arms in one process, foreign busy processes the only variable, 24 wall-bounded 200 ms windows each, spinners owned by the measuring process and force-killed between arms:

| applied load | utilisation median | utilisation range | unusable | occupancy median |
|---|---|---|---|---|
| none | 0.176 | 0.138 – 0.350 | 6 / 24 | 1.0001 |
| 4 spinners | **0.675** | **0.644 – 0.841** | 0 / 24 | 0.9988 |
| 16 spinners | 1.000 | 1.000 – 1.000 | 2 / 24 | 0.4063 |
| none (return control) | 0.177 | 0.128 – 0.435 | 9 / 24 | 1.0001 |

The four-spinner distribution does **not overlap** the unloaded one (0.644 against a worst unloaded 0.435) while that arm's occupancy — 0.9988 against 1.0001 — is indistinguishable from quiet. So utilisation detects precisely the contamination class the ratio is blind to, and the return-to-none control reproduces the unloaded median to 0.001, which is what says the separation is the applied load rather than drift. **The converse also holds and is the reason to keep both:** at two-times oversubscription utilisation **clips at exactly 1.000** and can no longer rank severity, where occupancy reads 0.406. One instrument sees moderate foreign load, the other sees starvation; neither subsumes the other. *(The windows here are wall-bounded — fixed elapsed time, variable work — so this probe says nothing about wall inflation and its occupancy column is not comparable to the fixed-work arms of the checkpoint below. It measures one thing: whether the utilisation reading discriminates.)*

**One further observation about the zeros, recorded as observation only.** They cluster on a quiet machine — 6, 2 and 9 of 24 on the unloaded and starved arms against **0 of 24** under four spinners. That is a correlation with no mechanism attached and no discriminating case run; the operational rule is unchanged either way, because a zero is treated as absent regardless of why it appeared.

**Finding 5 — the gated quantity is FOREIGN demand, so it was measured directly rather than derived, and the floor rests on this table and not on Finding 4's.** `system − process` is what a gate can act on, and subtracting an *assumed* process share from Finding 4's system column would have been a projection dressed as a measurement. So the arms were re-run reading both accessors per window:

| applied load | foreign demand | unusable | occupancy median |
|---|---|---|---|
| none | 0.080 – 0.488 | 4 / 24 | 1.0001 |
| 4 spinners | **0.851 – 0.896** | 1 / 24 | 0.9988 |
| 16 spinners | 0.903 – 0.959 | 0 / 24 | 0.4381 |
| none (return control) | 0.193 – 0.591 | 2 / 24 | 1.0001 |

Still no overlap, and the separation is wider on this quantity than on the raw system column. **The floor is 0.70** — 18 % above the worst healthy reading (0.591) and 18 % below the best contaminated one (0.851), with `ForeignDemandEnforcementTest` asserting both directions plus the vacuity control that the constant sits strictly inside that gap, so a re-derivation on a quieter machine cannot silently land outside it.

**And the two runs DISAGREE on the four-spinner system reading, which is worth stating rather than smoothing.** Finding 4 measured system utilisation at 0.644–0.841 on that arm; here `system − process` is 0.851–0.896, so system must have been near saturation. Both runs were internally controlled and each has a return-to-none control that reproduces its own baseline, so neither is refuted — the likely account is that background indexing differed between them, which is a *hypothesis* and not something either run controlled. What survives either way is the only claim the floor needs: within one run, with applied load the single variable, the distributions do not overlap. The absolute level of a foreign-demand reading is a property of the machine at that moment and is not comparable across runs, which is precisely why re-deriving the floor is a measurement on the machine in question.

### Checkpoint 2026-09-20 — the occupancy floor was reading ALLOCATION, not contention: the plain ratio INVERTS the ranking, and the corrected one is blind to moderate load

**Why this was run.** [IF329](INTERPRETER-FINDINGS.md#if329) shipped a contention floor on `cpu / wall` the day before, and `PerfEnvironmentQuality`'s own rule — never filter on a post-treatment variable, because an allocation regression CAUSES GC — aimed squarely at it. The magnitude was unmeasured, so this session measured it. One scenario (`jvm-b1-100000-rows-warm-throughput`), three fresh processes per arm, warmup 12, 9 repetitions, pinned worker order. A first pass on 2026-09-19 was discarded: the owner disclosed ~20 % standing external load, so "idle" was an unnamed constant under every row, and all arms were re-run after the owner reported the box quiet.

**What "idle" means in the rows below, named rather than assumed — because the whole point of this instrument is to stop machine state being an unnamed constant, and the first draft of this checkpoint made exactly that mistake twice.** It means **no deliberate load applied**, not a quiesced machine. This is a workstation: probed hours later with `com.sun.management.OperatingSystemMXBean.getCpuLoad()` over sixty 200 ms windows, background demand read **0.132–0.256 of eight cores** — roughly one to two cores, from OS metadata indexing and a shell statusline — while `getSystemLoadAverage()` simultaneously reported **3.49–5.03**. So the honest claim is narrower than "idle" and the rows carry their own evidence for it: the corrected occupancy of **0.996–1.000** on every unloaded arm is what says the measuring thread was not starved. Finding 3 is the reason this does not weaken the result — moderate foreign load barely moves the quantity, so an unquiesced baseline makes the sensitivity bound *conservative*, and a floor set below an observed minimum taken under background load stays safe when the box is quieter.

| arm | wall ms | cpu ms | gc ms | `cpu/wall` (shipped) | `cpu/(wall−gc)` (corrected) |
|---|---|---|---|---|---|
| idle, heap 1 g | 219.4 | 191.9 | 27.0 | 0.8745 | 0.9972 |
| idle, heap 320 m — **only the heap differs** | 315.5 | 216.7 | 98.0 | **0.6868** | 0.9962 |
| 4 of 8 cores busy, heap 1 g | 301.7 | 250.5 | 40.0 | 0.8302 | 0.9571 |
| 16 busy loops (2× oversubscribed), 1 g | 766–887 | 278–291 | 41–49 | 0.304–0.330 | 0.313–0.348 |

Per-sample medians — the quantity the floor actually judges, since it gates `sample.occupancy()` and not a pooled region:

| arm | shipped | corrected |
|---|---|---|
| idle 1 g | 0.878 / 0.873 / 0.886 | 0.9992 / 1.0002 / 0.9995 |
| idle 320 m | 0.707 / 0.682 / 0.697 | 0.9953 / 0.9924 / 0.9961 |
| 4 cores busy | 0.845 / 0.837 / 0.836 | 0.9747 / 0.9963 / 0.9955 |
| starved | 0.323 / 0.330 / 0.304 | 0.3383 / 0.3476 / 0.3130 |

**Finding 1 — the shipped bar ranked the arms BACKWARDS, and that is a live defect rather than a risk.** A healthy capture at a tight heap reads **0.6868**, under the 0.70 floor of the day, so the aggregate refuses it and tells the reader to re-measure on a quieter machine — when its own corrected ratio says the thread was running 99.6 % of its unpaused wall time. A capture whose wall time really was inflated **+37.5 %** reads 0.8302 and passes. Allocation was driving the bar, not contention. Two of the three 320 m samples tripped the floor with no applied load.

**Finding 2 — the correction is close to exact.** With collection out of the denominator, a **3.6×** change in GC per region (27 → 98 ms) moves the quantity by **0.001** (0.9972 → 0.9962). The ratio stops tracking allocation and goes back to answering the question it is for.

**Finding 3 — but it sees about a TENTH of a real contamination, and this is what bounds the whole instrument.** Against the idle 1 g arm, four busy cores inflated wall **+37.5 %** while CPU rose **+30.5 %** — stalled cycles are charged to the measuring thread, so they land in the numerator too — leaving the corrected ratio down just **−4.0 %**. Two of that arm's three samples sit INSIDE the idle band, so the distributions overlap and no floor on this quantity can separate moderate contention from a quiet machine. This is the same mechanism [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md)'s `loadAverage` section already asserted about CPU-time clocks, now measured on the corrected quantity.

**What it CAN do, cleanly: starvation.** Two-times core oversubscription measured 0.313 – 0.348 against a healthy 0.833 – 1.000 across the bundle's 12 JVM scale-samples — a 2.4× gap with nothing in between.

**The floor that follows, and the constraint that set it.** JVM **0.75** on the corrected quantity. The binding sample is not a throughput one (the 6 warm-throughput samples across the two heaps read 0.9924 – 1.0002) but a **cold first answer**: the first fork of the bundle's first scenario measured **0.8328** with `gc = 0`, the thread blocked on class loading rather than starved, while its sibling forks read 0.9944 and 0.9921. A floor must admit that, so 0.75 sits ~10 % under it and still 2.2× above the starved band. Node keeps **0.85**, re-derived the same day over 15 samples at **1.041 – 2.250, median 1.154** (its numerator counts every thread, so it reads above 1.0 and never needed the correction).

**Locks.** `PerformanceProtocolTest` — `theMeasuredStarvationArmIsRefused` carries three verbatim wall/cpu/gc triples from the starvation arm and proves the aggregate refuses them by name; `anAllocationHeavyCaptureIsAcceptedAlthoughItsPlainRatioWouldNotBe` is the regression lock for Finding 1 and asserts its own fixture sits under the live floor on the plain ratio, so it keeps meaning if the floor moves; `occupancyTakesGcOutOfTheDenominator` and `aRegionFullyAccountedForByCollectionIsNotReadAsContention` pin the formula. Rationale: [IF329](INTERPRETER-FINDINGS.md#if329). The residual exposure — moderate contention, invisible here — is IG154 (closed the same day, graduated to [IF330](INTERPRETER-FINDINGS.md#if330)), whose instrument this measurement also selects.

### Checkpoint 2026-09-19 — IG153 closed: the bundle reproduces under pinned order, and every sample of the interpreter protocol now carries its own contention evidence (its floor SUPERSEDED next day — see 2026-09-20)

**The bundle now reproduces, which is the harm IG153 named.** Three invocations of `:interpreter:interpreterPerformance` on `3078b902f`, compilation deliberately outside the timed invocations, worker order pinned:

| row | run 1 | run 2 | run 3 | spread |
|---|---|---|---|---|
| `jvm-192-rules-500-rows-first-answer` | 114.80 | 131.00 | 115.10 | **1.141×** |
| `jvm-48-rules-500-rows-first-answer` | 86.45 | 85.38 | 81.07 | 1.066× |
| `jvm-b1-100000-rows-first-answer` | 859.68 | 853.38 | 860.81 | 1.009× |
| `jvm-b1-100000-rows-warm-throughput` | 224.26 | 216.64 | 221.17 | 1.035× |
| `node-b1-10000-rows-first-answer` | 295.65 | 287.49 | 293.86 | 1.028× |
| the four Node throughput rows | — | — | — | 1.013–1.034× |

Median row-level spread **1.028×**, worst **1.141×**. The unpinned pair that opened the row put that same worst row at **1.61×** between invocations (184.97 against 114.53) and disagreed with isolated reruns by 27–42 points. All three invocations executed the identical 21-task sequence, verified from the build logs rather than from `--dry-run`. **What this does and does not establish:** the defect does not reproduce with the ordering variable removed, which is the operational answer. It is *not* an identification of the mechanism — there are three observations of the pinned condition against one of the unpinned, so the ordering hypothesis remains unproven and is recorded as such.

**A rival mechanism I proposed was refuted by the same data.** From four hand-picked values it looked as though the first fork of an invocation systematically paid its warm-up, which would mean a median-of-three could be skewed by a contaminated member. Across all 27 scenario-invocations the median first-fork excess is **−0.6 %**, and the distribution is symmetric from −9.0 % to +26.0 %. There is no first-fork penalty; the four values were pattern-matching. The individual excursions up to ±26 % are real, and they are what the median-of-three exists to absorb — which it does, holding row-level spread at 1.028× while single forks move 26 %.

**Every sample of the interpreter protocol now carries its own contention evidence, and the single-floor design was refuted by its first measurement.** *(Corrected 2026-09-19, hours later: this read "every timed sample", which is a reach claim and was false — `:adapter:perfGateTest` and `:adapter:interpreterNativePerformance` do not share this protocol. What they carry instead, and why neither can simply adopt occupancy, is [IG154](INTERPRETER-GAPS.md).)* `PerformancePlatform.cpuNanoTime` records the measuring thread's CPU alongside each region's wall time, so a sample's *occupancy* — median CPU-over-wall — says whether the region ran or waited. The intended design used one floor for both carriers. Measured on an idle machine, one bundle invocation:

| carrier | numerator | occupancy range | median | floor set |
|---|---|---|---|---|
| JVM (12 scale-samples) | measuring **thread** CPU | 0.859 – 0.994 | 0.903 | 0.70 |
| Node (15 scale-samples) | **whole-process** CPU | **1.041 – 2.355** | 1.155 | 0.85 |

Node exceeds 1.0 because `process.cpuUsage()` counts the libuv pool and V8's background GC and compiler threads; Node exposes no per-thread CPU clock in stable form. A comment asserting Node was single-threaded and could share the JVM's floor was written and then falsified by this measurement — a shared floor would have been either blind on Node or permanently red on the JVM. Both quantities fall when the process is starved of cycles, which is the signal, so the floor is keyed by host with ~18 % margin below each carrier's observed minimum.

**It is proven to fire on real contention, not only on synthetic numbers.** A bundle run against a background load of roughly 53 % of the machine drove `jvm-b1-100000-rows-warm-throughput` to **65 % occupancy** and `performanceAggregateReport` refused the report by name: *"JVM occupancy 65% is below the 70% floor … its timings are the machine's, not the code's"*. The same bundle on an idle machine passes. That load is far milder than the 213 % `mediaanalysisd` pass which contaminated two capture sessions earlier the same day, so the instrument is sensitive well below the contamination that has actually cost measurements here. The refusal sits in `aggregate` rather than `validateFork` on purpose: a contended record is legitimate evidence of what that process saw, and must survive — what must not happen is that it becomes a *comparison*.

> **Superseded 2026-09-20, and read the two paragraphs above as the record of one day rather than as the live contract.** Every figure in them is on the **plain `cpu / wall`** quantity, which the 2026-09-20 checkpoint at the top of this file shows *inverts* the ranking of a healthy allocating capture against a contaminated one. The live quantity is `cpu / (wall − gc)`, the JVM floor is 0.75 against a re-derived idle band of 0.8328–1.0000, and the "65 % under real load" figure is not evidence for the live bar — that arm's contamination was never separated from its collection. The Node column survives unchanged in kind (no correction applies there), re-measured at 1.0407–2.2500.

### Checkpoint 2026-09-19 — IG152: three candidate routes to a 5 % timed bar, ALL THREE refuted — and the third was refuted by the very capture meant to instantiate it

**Name the quantities first, because three different ones are in play and an earlier pass compared two of them by accident.** *Worst-against-median spread* is per key `max/median − 1` over that key's accepted non-CI observations in [`../perf-results/darwin-aarch64/`](../perf-results/README.md), one machine; it answers *how far does a single run stray from this key's own centre*. *Null 95 % ratio interval* is a 20 000-iteration bootstrap of `(median of n draws) / (median of n draws)` with both arms drawn from the **same** commit's own samples; its upper bound is what a one-sided 5 % regression bar faces as a false-positive rate, so a bar is safe on a row exactly when that bound sits under +5 %. Every figure below is re-derived at `f5905bf9a`; the corpus is mutable, so figures carry their commit ([the method note](INTERPRETER-PERFORMANCE.md) on why).

**Route A — filter the gate-lane corpus on `gcCountInWindow` instead of averaging across it: REFUTED.** The checkpoint below established GC as *a* driver of the widest lane's two modes, and the obvious next move was to let a bar judge only the quiet observations. It buys nothing. Filtering halves the evidence and leaves the spread where it was, and **not one key crosses into gateable territory at any threshold**:

| filter | keys with ≥3 runs | median worst-against-median | worst key | keys under 5 % |
|---|---|---|---|---|
| none | 66 | 28.5 % | 173.2 % | **0 of 66** |
| `gc ≤ 40` | 48 | 28.5 % | 173.2 % | **0 of 48** |
| `gc ≤ 20` | 39 | 27.8 % | 173.2 % | **0 of 39** |
| `gc ≤ 6` | 36 | 28.5 % | 173.2 % | **0 of 36** |
| `gc == 0` | 30 | 27.3 % | 156.9 % | **0 of 30** |

**Route B — raise the per-side sample count on those same lanes: REFUTED, and this one is worth the arithmetic because it is the move everyone reaches for.** Every one of the 66 eligible keys carries 17–26 distinct runs (median 24), so the bootstrap resamples a genuinely rich population rather than extrapolating from three points. Even so the noise is so wide that averaging cannot get under the bar at any fork count a person would run:

| forks per side | median null 95 % width | median upper bound | keys where a 5 % bar is safe |
|---|---|---|---|
| 1 | 69.7 pp | +40.7 % | 0 of 66 |
| 3 | 47.5 pp | +26.3 % | 0 of 66 |
| 5 | 37.8 pp | +21.0 % | 0 of 66 |
| 10 | 26.2 pp | +14.7 % | 1 of 66 |
| 20 | 18.8 pp | +9.8 % | 9 of 66 |
| 40 | 13.7 pp | +7.1 % | 18 of 66 |

At **40 forks per side — 80 fresh processes for one comparison — 48 of the 66 keys still cannot carry 5 %.** That settles the question these lanes have been carrying since the row opened: their `1.665×` anchor is not a tolerance anyone chose, it is the resolution the instrument has, and no amount of repetition converts it into 5 %.

**Route D — the library throughput collector `performanceJvmThroughput`: REFUTED.** *(Corrected 2026-09-19, hours after this entry first read "SUCCEEDS, and it is the first timed row in the estate that can carry the bar". The correction and its cause are the block below; the original observation is kept here unedited because it is what was measured, and because the way it misled is the transferable part.)* Ten consecutive invocations at one commit, `-PperformanceWarmup=12 -PperformanceRepetitions=9`, JVM heap 1g, one fresh process each. Fork medians 212.91 / 212.73 / 210.06 / 213.24 / 212.99 / 213.09 / 219.15 / 211.36 / 211.71 / 222.51 ms — **spread 1.059×, stdev 3.83 ms, CV 1.79 %**:

| forks per side | null 95 % ratio | upper bound | verdict at 5 % |
|---|---|---|---|
| 1 | [0.9515, 1.0510] | +5.1 % | fires on noise |
| 3 | [0.9585, 1.0435] | +4.3 % | safe |
| **5** | **[0.9715, 1.0293]** | **+2.9 %** | **safe** |
| 10 | [0.9724, 1.0284] | +2.8 % | safe |

**The structural reason it resolves where the gate lanes cannot is that it medians nine repetitions INSIDE each process.** Its own 90 within-run samples spread 1.465× — as noisy as anything in the gate lanes — but the per-process median of nine absorbs that before the number ever leaves the fork, which is the step the gate lanes have no equivalent of. So the difference is not that this lane is quieter; it is that it reports a statistic rather than a sample.

**`n = 5` rather than 3, on the measured margin.** Both clear the bar, but n=3 leaves 0.7 pp of headroom against it and n=5 leaves 2.1 pp, and these ten forks came from one quiet session while the cross-session forks taken earlier the same evening spread 2.9–4.5 %. The extra two processes cost about 16 seconds and buy the difference between a gate that survives a mediocre day and one that does not.

**THE REFUTATION, and it came from the capture that was supposed to instantiate the gate.** With the instrument built and the machine idle, ten fresh processes were taken for the anchor itself — five on `ad4d5ff64` in the parked clone, five on `421bf7c6d` here, back to back under the same protocol (warmup 12, repetitions 9, heap 1g). The two arms agree about the *code* — 221.77 ms against 219.86 ms, **−0.86 %** — and disagree with the study about the *instrument*:

| population | processes | spread | CV | null upper bound at n=5 |
|---|---|---|---|---|
| the study above, one tight session, one tree | 10 | 1.059× | **1.79 %** | +2.9 % → safe |
| the anchor capture, two arms, two trees | 10 | 1.184× | **5.98 %** | **+12.3 % → fires on noise** |

At the capture's variance a 5 % bar needs **n = 40 per side** to reach +3.2 %, which is 80 processes and about 25 minutes for one comparison. So the gate was not built, the anchor was not committed, and `performanceJvmThroughput` stays on its absolute anchor with the rest.

**Two distinct errors produced the wrong conclusion, and only the second is interesting.** The first is the ordinary one: the study's ten runs were consecutive invocations in one warm session in one tree, so "one tight session" was a constant across every row of the set, and the account "this lane has CV 1.79 %" was never tested against it. Measuring on a second occasion — the first time the question was asked — gave 4.13 % in the *same* tree and 7.05 % in the clone, so this is not a clone-versus-repo carrier effect; the session is implicated and the study was the outlier. **The second error is the one to carry forward: a bootstrap cannot manufacture the independence its input lacks.** Resampling n = 20 or n = 40 from ten observations drawn minutes apart reported +0.2 % and +0.1 %, numbers no timing instrument on a shared machine produces, and their absurdity was visible in the original table and went unremarked. The route-B bootstrap was sound for exactly the reason route D's was not — its keys carried 17–26 independent runs, which was noted at the time as a strength and not applied as a test to the sibling analysis in the same entry.

**What would settle the lane honestly, stated so the next attempt does not repeat this one.** Thirty or more processes spread across several sessions and at least two days, sized *before* any gate is designed, with the session treated as a factor rather than held constant. Until that exists the lane's variance is unknown rather than small, and the difference between those two is the whole of this correction.

**And the collector that the bundle driver reports must NOT feed it: one invocation in two was uniformly inflated 35–60 % with nothing in the output saying so.** `scripts/interpreter-performance.sh` runs all sixteen workers in a single Gradle invocation. Two such invocations 16 seconds apart — HEAD `191ad9f9b` then baseline `ad4d5ff64` in the parked clone — disagree with the *same commits'* isolated per-task reruns like this:

| row | bundle Δ | isolated Δ | disagreement |
|---|---|---|---|
| `jvm-192-rules-500-rows-first-answer` | **−38.08 %** | +3.95 % | 42.0 points |
| `jvm-48-rules-500-rows-first-answer` | **−40.80 %** | −1.31 % | 39.5 points |
| `jvm-b1-100000-rows-first-answer` | **−24.38 %** | +2.86 % | 27.2 points |
| `jvm-b1-100000-rows-warm-throughput` | −19.79 % | −1.84 % | 18.0 points |
| `node-b2-…-warm-throughput@10000` | −6.41 % | −0.69 % | 5.7 points |
| `node-b1-…-warm-throughput@10000` | +3.26 % | −0.34 % | 3.6 points |

The bundle manufactured a **24–41 % improvement** on three rows that isolation puts inside ±4 %. The inflation sits entirely in the baseline arm, is sustained across all nine of its forks (1251 / 1078 / 1145 ms where isolation reads 824 / 837 / 839), and does not decay — so it is not one slow fork that a median would absorb.

**One uncontrolled variable is source-verified, and it is not a hypothesis: the workers carry no mutual ordering constraint, and the two invocations ran them in different orders.** `performanceAggregateReport dependsOn performanceWorkers` ([`interpreter/build.gradle:3770`](../interpreter/build.gradle)) is the only edge in the graph, so the scheduler is free to order the bag as it likes — and it did. HEAD ran the five Node tasks first and the ten JVM tasks after; the baseline ran the ten JVM tasks first and the Node tasks after. The arm whose JVM family was scheduled **first** is exactly the inflated one. **That the ordering CAUSED the inflation is a hypothesis, not a finding** — there is one invocation per order, so the correlation is 1-for-1 on a sample of two, and the discriminator nobody has run is to force each order and re-measure. The fix does not wait on it either way: an uncontrolled variable in a comparison instrument is a defect whether or not it is *this* run's culprit.

### Checkpoint 2026-09-19 — NFR #1's LATENCY half, measured against the pre-plan checkout for the first time: no regression, and the lane's two modes are a GC property of the run

**The question was whether the interpreter's latency is regressed at HEAD against the fixed pre-plan baseline, and the answer is no.** Four captures on one machine in one 25-minute window, alternating trees, `:adapter:perfGateTest` in each: HEAD `191ad9f9b` against `ad4d5ff64` in the parked measurement clone, both gating the identical committed anchor (65.16 ms for `charset grapheme@32000`, verified in both trees before the run). Over the **168 comparable observation keys** the median delta is **−0.90 %** and the mean **−0.38 %**, with **28 keys above +5 % and 28 below −5 %** — a symmetric scatter rather than a shift, which is what distinguishes noise from a regression, and 112 keys sit inside ±5 %. The per-key range (−15.4 % … +21.5 %) is the run-to-run spread [IG152](INTERPRETER-GAPS.md) records, and it is why two runs per arm cannot resolve a 5 % bar even when the totals are clean.

**The lane's two modes are discriminated by GC COUNT, not by the commit — a within-commit control on each arm separately.** This is the mechanism the previous entries declined to offer, and the HEAD pair is the case that settles it because load barely moves across it while GC moves from nothing to a storm:

| arm | commit | loadAverage | `gcCountInWindow` | `charset grapheme@32000` | gate |
|---|---|---|---|---|---|
| HEAD | `191ad9f9b` | 2.26 | **0** | **62.52 ms** | accepted |
| HEAD | `191ad9f9b` | 3.37 | **36** | **110.56 ms** | breached |
| baseline | `ad4d5ff64` | 3.88 | **4** | **67.88 ms** | accepted |
| baseline | `ad4d5ff64` | 6.90 | **35** | **122.57 ms** | breached |

Both commits produce both modes, so the slow mode cannot have been introduced by any plan-era change — it is present at the NFR baseline itself. Within HEAD the swing is **1.77×** at an essentially unchanged load, which contention does not predict and a GC storm does. The archive agrees retrospectively: every darwin run at `gc` 35–76 sits at 101–138 ms and every run at `gc` 0–6 sits at 48–92 ms. In **both** regimes HEAD is the faster arm — −7.9 % clean (62.52 vs 67.88), −9.8 % in storm (110.56 vs 122.57) — so the direction of the residual also favours HEAD.

**What this does NOT establish, stated because the same overreach already cost a day.** GC is shown to be *a* driver, not the sole one: four captures taken earlier the same afternoon recorded `gc=0` and still measured 88–119 ms, which no GC account explains and which external CPU load does. So there are at least two inflation paths and this window separates only one of them. The baseline breach at 122.57 is therefore a machine-state event, not evidence about `ad4d5ff64`'s cost, and the anchor is left where the 2026-08-23 capture put it. Records: `perf-results/darwin-aarch64/2026-09-19T17{5213}Z.jsonl` and `2026-09-19T18{1055}Z.jsonl` here, `2026-09-19T17{5824}Z.jsonl` and `2026-09-19T18{0426}Z.jsonl` in the measurement clone. Each capture carried a 5-second per-core idleness sample across its own window; the minimum idle was 26–31 % on every run, which is dominated by the measurement itself and so bounds external load only loosely — the instrument [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) asks for still does not exist.

### Checkpoint 2026-09-19 — re-derived: the `group-star expansion` lane's anchor is not too loose but too TIGHT, and every figure IG139 recorded was wrong

**Re-derived from the committed runs alone, no new measurement.** IG139 was opened on 10 darwin runs; there are now 22. Restricted to `lane=group-star expansion`, `engine=interpreter`, `clock=cpu`, `status=accepted`:

| key | runs | observed | spread | live anchor | stale floor | ceiling |
|---|---|---|---|---|---|---|
| `n=8000` darwin, post-step regime | 14 | 22.02 – 37.13 ms | 1.69× | 22.23 | 12.34 | **37.68** |
| `n=2000` darwin, post-step regime | 18 | 5.12 – 9.40 ms | 1.84× | 5.48 | 2.29 | 10.04 |

**Contention does NOT explain the slow mode — the obvious rival mechanism, tested and refuted.** Pairing each observation with its own run's recorded `loadAverage` (an OS sample, not a quantity derived from the timing, so the correlation could genuinely have failed): the **quietest run in the whole set**, 0.17 runnable threads per core, produced 108.59 ms, while a 1.38 run produced 63.85 ms. The fast group spans 0.23–1.38 and the slow group 0.17–2.20, so the two are interleaved rather than separated. Only the single slowest value (137.92 at 2.20) is plausibly contention-affected. The lane is genuinely bimodal, and re-levelling remains the remedy.

**That check did expose an unenforced field, now enforced.** `loadAverage` rides every run and observation record and was compared **nowhere** — `git grep loadAverage` over `*.kt`/`*.java`/`*.sh`/`*.ts`/`*.py` returns 20 sites, every one a writer, a test fixture literal, or prose, and a search for any threshold comparison returns zero. Six of the 30 committed darwin runs sit above 1.00 runnable threads per core, the worst at 3.34, and one of them is folded into the live anchors. `perf-anchor.sh` now refuses a LOCAL input above that bar (`ALLOW_CONTENDED=1` overrides; hosted classes are exempt because all ten committed CI runs sit at 1.01–1.43, a 2-core VM being at capacity from the job itself). The script's existing median fold is not a substitute: it bounds one contended run among several, not a capture session where every input is inflated alike.

**The band is two-sided, so an anchor is admissible only inside a window** — `[(hi − slack)/factor, (lo + slack)/staleFraction]`, non-empty exactly when the spread is under `factor/staleFraction` = `1.65/0.6` = **2.75×**. For `n=8000` that window is `[21.90, 38.37]` and the live anchor sits at its very bottom, which settles the STALE side (the floor is 9.7 ms below the lowest observation ever recorded) and leaves the ceiling side at **1.5 % of headroom**: 37.68 against a measured 37.13.

**So the lane's risk inverted, and four recorded figures were wrong.** The anchor was 22.23, not the 45.28 the row carried — `9e4226b0b` re-levelled the darwin block onto OpenJDK 21 and IG139 was never revisited. The `@2000` sibling is not quiet at ±1.6 %; it spreads 1.84× over its own post-step regime. And the load-bearing claim that *re-anchoring cannot fix this at any value* was false even on the row's own numbers, whose window `[42.06, 45.17]` was narrow but non-empty with the stranded anchor 0.11 ms outside it. A negative existential was asserted without the enumeration it needs; `PerfStaleAnchorTest.anAnchorWindowIsNonEmptyExactlyWhenTheSpreadIsUnderTheBandsRatio` now runs that enumeration against the real ceiling and stale functions.

### Checkpoint 2026-09-19 — the interpreter's evaluation work is exactly linear in scale, so a count carries the bar a clock could not

**Measured on both carriers** (`:interpreter:check`, JVM 1320 tests and Node 1298, 0 failures), with a `WorkLedger` installed on each footprint scenario at scales 200 / 400 / 800 / 1600. Every count is an exact integer multiple of the scale — the intercept is zero on every row, so scale 200 costs exactly 200 and never 201:

| scenario | condition nodes | value-expression nodes | validation rule rows |
|---|---|---|---|
| `simple-validation` | 1 · scale | 0 | 1 · scale |
| `equality-correlation` | 3 · scale | 0 | 1 · scale |
| `compute-overlay` | 3 · scale | 9 · scale | 0 |

The two zeros are real and were checked rather than assumed. `equality-correlation` resolves no value expressions because it fires on presence and duplicate predicates, which read no operand values; `compute-overlay` instantiates no *validation* rule row because the compute path iterates instances instead, and that iteration shows up as its expression work.

**One instrumentation defect this measurement caught, which is why it was a probe before it was an assertion.** Counting at `evalCond` — the dispatch that looked like the one every condition node passes through — reported a 200-message validation as doing **zero** condition work. Validation does not walk there: `firedType` needs each node's polarity, so it uses `evalTypedLazy`, a separate walk. Had the per-row constants been written from reasoning instead of a run, the guard would have locked three zeros and passed forever.

**Kill proof.** Making validation walk its condition tree twice (`evalTypedLazy(cond).also { evalTypedLazy(cond) }`) fails the guard at `simple-validation` scale 200 with `expected:<200> but was:<400>`.

### Checkpoint 2026-09-19 — the trend reader is ruled out for NFR #1's timed rows, and the margin does not rescue them either

**The question was whether any existing instrument could carry a 5 % bar on the timed rows, and the answer is no — on the quietest quantity the estate has, not merely on the obvious one.** The checkpoint below establishes that absolute times spread far past 5 %. The natural rebuttal is that the estate does not gate absolute times: the early-warning band judges the **margin**, the kernel/interpreter ratio, and [`scripts/perf-trend.sh`](../scripts/perf-trend.sh)'s own header argues that a ratio's noise is not its operands' because both engines run in the same JVM on the same vCPUs, so a load excursion moves them together and largely cancels. That argument is **correct and still insufficient**.

Measured over the committed `darwin-aarch64` record, one machine, non-CI, as a **within-row** control — the same paired rows read both ways, so the comparison moves one variable:

| quantity | rows with ≥3 runs | median spread | p90 | worst | rows within 5 % | within 15 % |
|---|---|---|---|---|---|---|
| margin (kernel ÷ interpreter) | 68 | **17.2 %** | 40.3 % | 131.3 % | **0** | 24 |
| absolute interpreter median, same rows | 100 | 33.0 % | 122.1 % | 363.3 % | 0 | 8 |

So the ratio halves the noise — 33.0 % → 17.2 % at the median, and the within-15 % count triples from 8 to 24 — which confirms the cancellation claim quantitatively. It still leaves **zero of 68 rows** inside 5 %. And the trend reader would not report such a step anyway: its `MIN_SHIFT` defaults to `1.15`, so a sustained 5 % change is below the threshold at which it speaks, by design rather than by oversight.

**Recall limit, stated because it is the obvious objection.** Every cross-engine observation in the record is `unasserted` — the status the harness writes outside the isolated single-fork lane, where an absolute time is a coin flip — so no isolated-lane MARGIN series exists to measure. What does exist is the isolated lane's own absolute rows (`accepted`, anchor-asserted), and those sit at 28.8 % median. Both available series are an order of magnitude above 5 %.

**What this leaves, and the candidate worth naming.** The byte anchor works because bytes are deterministic, not because memory matters more than latency. The same move is available for time: bar a **deterministic counter of the work done** rather than the clock that measures it — which is exactly the arrangement the owner already accepted for the checker's growth invariant, where `Sweep.comparisons` decides deterministically on every carrier and in every lane, and a budget was declined as redundant to it. Tracked in [IG152](INTERPRETER-GAPS.md); no such counter exists for the interpreter's evaluation path today, so this is a named direction and not a plan.

### Checkpoint 2026-09-19 — a 5 % bar cannot ride the timed rows, so NFR #1's standing anchor carries bytes

**The question was whether H4's whole row set could go under the converted 5 % bar, and the answer is no — measured rather than judged.** The proposal converts NFR #1's per-row rule into a permanent anchor over "the frozen H4 row set" at gate N, and that set names timed collectors alongside the byte ones. Across the 30 committed run files in [`../perf-results/`](../perf-results/README.md), restricted to one machine (`Macmini9,1 Apple M1`), non-CI, `status=accepted`: **66 observation keys appear in three or more distinct runs, and every one of the 66 already spreads more than 5 % run-to-run** — worst-against-median **28.8 %** at the median key, **60.3 %** at p90, **160.5 %** at the worst. A 5 % per-row bar there would fire on noise on every row it covered, which is not a stricter gate but a broken one. The worst key is the `group-star expansion` lane that [IG139](INTERPRETER-GAPS.md) independently records as too noisy to carry an absolute bar — corroboration for that row, not for the distribution.

**So the conversion splits by what the quantity can resolve, and H4's own wording licenses the split.** It bars regression in any *comparable* measurement row and says inconclusive evidence cannot pass; a row whose own noise is 28.8 % is not comparable at 5 %, and asserting it anyway would manufacture precisely the inconclusive evidence that clause rejects. The byte rows go under the standing anchor; the timed and native rows keep the `1.665×` absolute anchor in `anchors.json`, and the residual — the blind band, still open for time — is [IG152](INTERPRETER-GAPS.md)'s narrowed lead.

**First run of the instantiated bar, on `3b884ebff`: no regression.** 48 heap rows against the baseline's own medians — worst **+0.860 %** (`node-footprint/equality-correlation/200/retainedBytes`), none above 1 %, best −5.231 %. Measured through the public path (`scripts/interpreter-footprint.sh 5 200 2 3`), which now invokes `:adapter:verifyNfr1StandingAnchor` itself, under the identity the anchor pins: 5 forks, warmup 2, repetitions 3, scales 200/400/800/1600; OpenJDK 21.0.12.1 at 512 MB with SerialGC and `thread-allocated-bytes`; Node v22.0.0 at 560 MB with `v8-inspector-sampling-1024`.

### Checkpoint 2026-09-18 — H4's memory and delivered-size halves, measured against the pre-plan checkout: no regression on either

**H4 asks that the plan preserve the interpreter's latency, MEMORY and DELIVERED SIZE; latency was settled in the checkpoint below and these are the other two.** Both are byte quantities rather than durations, so neither needs a quiet machine and neither carries the timing caveats that entry does. Comparand is the `../a12-rulekit-measure` checkout at `ad4d5ff64`, main at `ae1c83599`, both at project version `0.13.0` so the asset names line up.

**Delivered size: +11 825 bytes over 18 assets, +0.079 % of the total.** Both trees ran `:interpreter:generateInterpreterReleaseChecksums` and every asset named in the resulting `SHA256SUMS` was measured with `stat -f%z`. Eleven of the eighteen are byte-identical; the seven that moved are below, and the worst single row is +0.407 % against NFR #1's 5 % per-row allowance.

| asset | `ad4d5ff64` | `ae1c83599` | delta |
|---|---|---|---|
| `npm/a12-interpreter-0.13.0.tgz` | 1 309 002 | 1 311 139 | +2 137 (+0.163 %) |
| `dm-interpreter-jvm-0.13.0.jar` | 1 696 422 | 1 697 475 | +1 053 (+0.062 %) |
| `dm-interpreter-js-0.13.0.klib` | 1 819 531 | 1 821 443 | +1 912 (+0.105 %) |
| `dm-interpreter-0.13.0.jar` | 147 679 | 148 264 | +585 (+0.396 %) |
| the three `-sources.jar`, summed | 1 530 859 | 1 536 997 | +6 138 (+2 046 each, +0.394 %/+0.402 %/+0.407 %) |
| **total** | **14 993 093** | **15 004 918** | **+11 825 (+0.079 %)** |

**Retained and allocated heap on the JVM: 24 rows, worst +0.577 %, no row over the allowance.** Five independent `footprintJvmFork*` workers per tree at a fixed 512 MiB heap under `-XX:+UseSerialGC`, `-PfootprintScales=200,400,800,1600 -PfootprintWarmup=2 -PfootprintRepetitions=3`, median across the five forks per (scenario, scale, metric). Three scenarios × four scales × {retained, allocated} = 24 rows; 17 of them improved.

| scenario | retained, worst row | allocated, worst row |
|---|---|---|
| `simple-validation` | +0.533 % at 400 | +0.227 % at 400 |
| `equality-correlation` | +0.017 % at 800 | −0.306 % at 1600 (best −4.697 % at 800) |
| `compute-overlay` | +0.577 % at 200 | −0.074 % at 200 |

**One limit on this reading, stated rather than left for a reader to find.** The comparand `ad4d5ff64` is an ANCESTOR of H4's named restart baseline `c8e3b997`, with one interpreter production commit between them, so this measures a superset window — conservative for a no-regression claim, but not literally the commit the NFR names. **The Node carrier is measured too, and getting to it took fixing a defect that had made the arm unrunnable on BOTH sides of this window.** 24 rows, worst **+1.152 %** (`simple-validation`/400 allocated), no row over the allowance; retained moves by at most 0.226 % anywhere.

| scenario | retained, worst row | allocated, worst row |
|---|---|---|
| `simple-validation` | +0.200 % at 200 | +1.152 % at 400 |
| `equality-correlation` | +0.226 % at 400 | +0.643 % at 400 |
| `compute-overlay` | +0.145 % at 400 | +0.059 % at 800 |

**Three things stood between the lane and a number, and only the first was the machine.** <a id="node-footprint-unblocked"></a>

| what was tried | where it stopped | what settled it |
|---|---|---|
| `scripts/interpreter-footprint.sh 5 200 2 3` | `:kotlinNpmInstall`, yarn "trouble with your network connection" ×5 | the machine was offline — `registry.yarnpkg.com` timed out with AND without the tool sandbox. It later returned, and the retry succeeded |
| the fork task directly | `:kotlinStoreYarnLock` — "Lock file was changed" — **after `kotlinNpmInstall` succeeded** | **measured with a control**: generated `build/js/yarn.lock` is **2 077** lines with `-PfootprintForks=5` and **byte-identical to the committed 524** without it, so the five `jsTarget.testRuns.create` registrations are what add the entries. The script now excludes that gate, because the lane is opt-in exactly so `check` and CI stay unchanged |
| the worker itself | `TypeError: Session_… is not a constructor` at its first allocation measurement | `@JsModule` on an external CLASS emits a **default** import, and `node:inspector`'s ESM default export is the module object rather than `Session`; `@JsName` names the declaration, not the binding. Rebinding the module as an `external object` — the shape its five sibling `node:` bindings already use — emits `import * as` and resolves the nested class |

**The defect predates the plan and was invisible by construction.** `ad4d5ff64` carries the identical binding and already had `useEsModules()`, so the Node footprint arm produced no number on either side of this window; the lane is local opt-in, so no gate ran it, and the yarn-lock refusal stopped execution before the worker could throw. *(Method note: the baseline arm was measured by applying the same instrument fix to the parked checkout, taking the reading, and restoring it — the change is `jsTest`-only and cannot move a production byte, and an A/B needs the same instrument on both arms. The tree is back at `ad4d5ff64` clean.)* The JVM forks depend only on `jvmTestClasses`, which is why that half ran throughout without touching any of this.

### Checkpoint 2026-09-18 — the portable-consistency plan took NO interpreter measurement, and closing that gap cost one false regression

**The hypothesis was that the plan regressed the interpreter, and it is refuted.** The plan ran 2026-09-11 → 2026-09-18 under NFR #1 ("the interpreter's performance is never compromised", 5 % per row against restart baseline `c8e3b997`, and **missing or inconclusive evidence fails rather than passes**) — and `perf-results/darwin-aarch64/` jumps from `2026-08-24` straight to today. Seven days of work, no interpreter evidence, against a requirement whose own clause says silence fails. That gap is the finding; the refutation below is what closing it produced.

**The discriminator was a second checkout, not a second opinion.** `../a12-rulekit-measure` is an independent clone parked at `ad4d5ff64` (2026-09-08, pre-plan) and resolves to the same `.gradle-isolated` home and pinned JDK, so the same lane runs on one machine against two trees with the identical committed anchor (`charset-grapheme@1` fingerprint `098088bc…`, n=32000, 65.16 ms × `ANCHOR_TOLERANCE`).

| tree | estimates (ms) | median | breaches |
|---|---|---|---|
| pre-plan `ad4d5ff64` | 82.109 / 62.350 / 71.064 | **71.064** | none |
| current `5a54058de` | 122.934 / 57.432 / 65.265 | **65.265** | one |

**The single breach was a GC storm, and the counters say so rather than the story.** The 122.934 observation carries `gcCountInWindow=76`, `gcMsInWindow=261`; every other observation on either tree carries 0–6 collections and 0–6 ms. Its load average (11.22) was *lower* than the baseline reading it was first compared against (14.42), so contention of the ordinary kind does not explain it either. The lane spans 57→123 ms across clean-and-dirty runs, which is why `ANCHOR_TOLERANCE` is ~1.665 rather than tight.

**What this cost, and the rule it re-proves.** On one estimate each side the comparison read `82.109 → 122.934`, "+49.7 %, ten times NFR #1's allowance", and was reported as a probable shipped breach. Two more estimates per side reversed the sign. The estate's own clause — *"a red exit is an event rather than a number"* — was written for exactly this and was quoted while the single-estimate claim was being made. **A breach is a hypothesis until it reproduces; three estimates is the cheapest thing that settles it.**

**The comparison moves TWO variables and its result must be read accordingly.** `PerfHarness.kt` changed by 115 insertions / 26 deletions between `ad4d5ff64` and HEAD, part of it this session's own `median` promotion — so the instrument is not held fixed. The honest reading is **"no regression detected"**, not "no regression": a harness that had become systematically more favourable could mask one, and that direction was not checked. Separating them needs the current interpreter measured under the baseline harness.

**Corrected 2026-09-18, hours later: the unmeasured window is not the plan's, it is the RECORD's, and it holds 23 commits rather than one.** This entry first scoped the gap to the plan (`31aa785ae..HEAD`), which is where the *question* came from and not where the *silence* starts. Measurement stops at `8ab6d1885` (2026-08-24), so `git rev-list 8ab6d1885..2777359ce -- interpreter/src/{commonMain,jvmMain,jsMain}` returns **23** production commits, of which the plan contributed one — 22 were already unmeasured before it began. `:adapter:interpreterMeasurementCurrencyGuardTest` keys on the last RECORD for exactly this reason, and would have flagged all 23. Scoping a gap to the work that prompted you to look at it is how a backlog hides behind an incident.

**Source evidence, as the independent arm.** Exactly one commit touched interpreter production source (`commonMain`/`jvmMain`/`jsMain`) in the plan window — `2cae70192`, reading `fieldRefByShortNameAllowed` the way A12's POJO binding does — and it lands on the model LOAD path, not evaluation. The other four commits under `interpreter/src` touched test sources only.

**Two enforcement gaps stay open and are NOT closed by this checkpoint.** H4's mechanical discriminator — the interpreter's resolved production dependency set plus its built-artifact digests, which the plan says "decides whether a checker-only edit requires an interpreter sweep" — **does not exist**: no task, no guard, only the prose describing it. And nothing makes the `@Tag("perf-bench")` kernel-ratio tier owed when interpreter production source changes; the release gate covers `@Tag("perf")` only, by design. Records: `perf-results/darwin-aarch64/2026-09-18T19{2650,3451,3826,3834}Z.jsonl` here and `2026-09-18T19{3617,3925,3933}Z.jsonl` in the measure clone.


### Checkpoint 2026-08-23 — the build JDK changed under us, and it read as an interpreter regression for a day

`scripts/perf.sh gate` breached on `charset-grapheme@1` at n=32000. The lane had measured **53.9–68.1 ms** across six full gates on 2026-08-14/16 and now measured **119.4** and **108.6 ms**, against a 92.53 ms budget. Nothing about the framing was right at first: the machine was idle (load 1.40), the observation denominator was identical (355 records every run), and three retries clustered tightly, so it read as a genuine step change in our own code.

What it actually was: `a12_pinned_jdk21_home` was `/usr/libexec/java_home -v 21`, which returns the **highest-versioned** JDK 21 installed. `brew install openjdk@21` (21.0.12.1) landed on **2026-08-19 01:23** — between the last green gate and the first red one — and displaced Oracle GraalVM 21.0.12 as the build JDK with no commit in this repository. Those are different JIT compilers, and the run records had said so all along: every green run carries `jdk: "21.0.12"`, both red ones `jdk: "21.0.12.1"`.

The first control was same commit `f559b5b3`, same machine, same day, 96 timed observations each: GraalVM **66.07 ms** accepted against OpenJDK **105.88 ms** breached, and across 91 common lanes a median **1.15×** with 45 lanes slower and **10 faster**.

**Corrected 2026-08-24, twice — the second time by a cold review of the first.** The single pair above overstated its precision: three fresh OpenJDK runs of this lane returned 101.13, **63.85** and 109.27 ms, so one run per side cannot carry a ratio. The distribution below replaces it, recomputed from **every committed `darwin-aarch64` run with a full observation set**, so it can be re-derived rather than believed:

| runtime | full-gate runs | range | median |
|---|---|---|---|
| Oracle GraalVM 21.0.12 (Graal JIT) | 13 | 48.51 – 79.76 ms | **55.45** |
| Homebrew OpenJDK 21.0.12.1 (HotSpot C2) | 9 | 63.85 – 119.39 ms | **102.44** |

Ratio of medians **1.85×**. Six of the nine OpenJDK runs exceed 100 ms — and **three do not**, sitting at or below GraalVM's slowest run, which is the lane's bimodality and not a measurement error. (Excluded: `2026-08-13T231207Z`, whose 8.18 ms for this key measures a **different scenario** — its fingerprint is `3d3772ca…` where every run counted here is `098088bc…`. Corrected 2026-08-24: this sentence previously called that run a partial capture, which is false — it carries 166 observations, the same count as its neighbour `2026-08-14T011320Z`. The exclusion stands; only its reason was wrong. The GraalVM row's samples are **confounded with revision** — twelve of thirteen predate 2026-08-23 — so read the row as "this lane's cost under Graal JIT across the summer", not as a controlled comparison; the controlled comparison is the 2×2 below.)

**The 2×2 that settles the CODE question (completed at the owner's insistence).** The cell that answers "is the new code fine?" — HEAD under GraalVM — had never been measured, because the gate added in this same slice refuses GraalVM and obtaining it needed a deliberate temporary bypass:

| commit | GraalVM 21.0.12 | OpenJDK 21.0.12.1 |
|---|---|---|
| `f559b5b3` (last known green) | 66.07 ms — green | 105.88 ms — breached |
| HEAD `4c472d66e`(-DIRTY) | **55.45 ms — green, 0 breaches of all 66 anchors** | 101.13 / 63.85 / 109.27 ms — breached in 2 of 3 |

At HEAD under GraalVM the **whole gate passes** — all 66 anchors, every one captured during the GraalVM era, zero breaches, this lane at 55.45 ms against its 55.47 ms anchor. **The interpreter changes since `f559b5b3` cost nothing measurable, and that is the load-bearing conclusion.** Every cell of the HEAD row is stamped `-DIRTY` (the bypass), which is disclosed here rather than smoothed over.

**What this does NOT support, corrected after review.** "Flipping only the JDK breaches" is false one run in three, by this table's own cell. And load does not separate the outcomes in the direction one would guess: the passing GraalVM cell was measured at load **1.98**, while a **green 66/66 OpenJDK run** (`2026-08-23T222121Z`) was measured at load **11.04**, the noisiest in the set. The honest statement is the weaker one the data does support: *the interpreter is not the cause; this lane is bimodal on OpenJDK, its fast mode overlaps GraalVM's band, and its slow mode does not.*

**The recapture, and the one anchor weaker than the rest.** `darwin-aarch64` was refreshed from three clean gate runs at `8ab6d1885` (`perf-anchor.sh refresh`); it reproduces to the cent from those three inputs, no key appeared or vanished (66 → 66), and 65 of 66 rest on all three samples. Two keys are worth naming: `group-star expansion@8000` fell 45.28 → 22.23 ms, which corrects the stale anchor [IG139](INTERPRETER-GAPS.md) recorded rather than any JDK effect, and the largest upward mover was `$ equality correlation@1600` at 2.34 → 3.65 ms (**1.560×**) — named because an upward re-level is the direction that can hide a regression. Half the keys (33 of 66) moved more than ±10 %, so "a re-levelling, not a rewrite" describes the median (1.033×, p25 0.968, p75 1.125) and not the spread (0.491× – 1.560×).

**`charset-grapheme@32000` rests on ONE sample of three**, because `perf-anchor.sh` folds `accepted`, `unanchored` and `flagged` records and skips the rest ([`perf-anchor.sh:121`](../scripts/perf-anchor.sh)) — the two slow runs were `breached` against the *old* 55.47 ms anchor as they were captured, so only the fast one survived the fold. Its new anchor is 65.16 ms, and the block records `runs: 3` with no per-key sample count, so a reader cannot see which key is thin. That lane is **bimodal on OpenJDK**: three runs at one commit gave **102.44 / 106.17 / 65.16 ms**, clustering near ~104 or ~65 with nothing between. State its margin honestly rather than as headroom: the ceiling is 65.16 × 1.665 + 1 = **108.5 ms**, and of the nine OpenJDK values on record **two already exceed it** (109.27, 119.39) with two more within 2 % (106.17, 108.59). The anchor is centred on the fast mode, not the distribution, so about one run in two will sit near the ceiling until a refresh taken when both modes record as `accepted` re-centres it; the one subsequent passing run (78.49 ms) is weak evidence, not vindication. Recorded rather than fixed, because re-anchoring a bimodal lane is what [IG139](INTERPRETER-GAPS.md) already establishes does not work at any single value. *Corrected 2026-09-19: that citation is withdrawn, and so is the conclusion resting on it. IG139 established no such thing — the claim was refuted on its own numbers the same day, and the band's arithmetic says an anchor exists for any observation set whose spread is under `1.65/0.6` = 2.75×. Controlled for runtime by each run's own `jdk` field, this lane's 13 runs on the pinned `21.0.12.1` spread **2.40×** (57.43–137.92), so its admissible window `[82.98, 97.38]` is **non-empty**. The anchor is 65.16, which sits BELOW that window's floor — which is why five of the 13 are recorded `breached` rather than merely near the ceiling. The lane is mis-anchored, not un-anchorable, and its remedy is the same capture IG139 now names.*

**Only `darwin-aarch64` was ever contaminated, and the version string invites the opposite conclusion.** CI and the nightly perf job install **Temurin** (`actions/setup-java`, `distribution: temurin`; no GraalVM anywhere in either workflow), so `linux-amd64-ci`'s recorded `jdk: 21.0.12` is Temurin 21.0.12 — OpenJDK throughout, needing no recapture. That string is *identical* to the GraalVM the Mac was using, which is the same indistinguishability this entry is about, one level up: reading the CI block's `21.0.12` and concluding "GraalVM-era" is a mistake made while writing this account. Check the workflow, not the version.

**Two things were wrong, and both were ours.** The pin fixed only the major version, so an unrelated Homebrew install could re-point the build; and the anchor block records `jdk` but **nothing ever read it** — the "parsed but never enforced" class this estate has a rule against. `java.version` could not have carried the check anyway: GraalVM 21 reports `21.0.12` and Homebrew OpenJDK `21.0.12.1`, which differ by coincidence, not by family.

Both are closed. The pin now selects OpenJDK and skips GraalVM, refusing rather than falling back ([`scripts/selftest-jdk-pin.sh`](../scripts/selftest-jdk-pin.sh)); and `JdkIdentity.requireSupported()` on the shared record seam refuses to produce a number on anything but OpenJDK 21, naming the JVM it found and pointing at the pin rather than the anchors (`PerfJdkGateTest`, plus an executed refusal of a real lane under GraalVM). GraalVM stays reserved for `:cli:nativeCompile` via `GRAALVM_HOME`, which is a separate GraalVM 25 toolchain and was never involved.

*Method note for anyone reading a future breach: the first two "clean" re-measurements were 60.6 and 73.1 ms in ISOLATION, which looked like exoneration and was actually a second uncontrolled variable — this lane depends on how much work precedes it in the JVM. The comparison that settled it held the entry point, the observation count, the machine and the day fixed, and varied only the JDK.*

### Checkpoint 2026-08-17 — the v0.13.0 published-claim refresh: every public number re-measured in one sweep

The four public surfaces carried figures frozen on 2026-08-03 (latency, kernel ratios) and 2026-08-07 (memory). This re-measures every one of them on `690a3b9c`, each lane run **alone** on the local `darwin-aarch64` machine, so a reader comparing two claims in one paragraph is comparing one sweep.

**Browser first answer** — `measureExternalInterpreterBrowserFirstAnswer`, Apple M1, Chrome 151.0.7922.138, one fresh Chrome process per sample, zero discarded.

| Scenario | Published (08-03) | Run 1 | **Run 2 — published** | Warm, validation only |
|---|---|---|---|---|
| 200 rows, 0 findings | 10.5 ms | 12.15 ms (50 samples) | **11.55 ms** (10) | **1.1 ms** |
| 10,000 rows, 4,900 findings | 203.6 ms | 176.1 ms (10) | **166.1 ms** (10) | **53.3 ms** |

**Two runs, because the second added the warm collector** (below), and the published figures are that second sweep so every number in a README comes from one run. The two cold runs sit **1.05× and 1.06×** apart, which is the ordinary run-to-run spread of this lane and the reason a single sample is never the claim.

**Against 08-03 it moved in both directions, which is the finding.** Stress improved ~18 %; the representative case *regressed* ~10 % and is the noisier of the two — run 1's series tripped the adaptive expansion to 50 while the stress series did not. A refresh that had only checked the headline stress figure would have reported an unambiguous win. The 250 ms release bar passed.

**The warm, validation-only lane is new here** ([`src/performance.ts`](../interpreter/consumer-tests/browser/src/performance.ts)). The collector previously produced only a first answer — construction inside the clock, fresh process — which is not the region the JVM lane measures, so no browser figure could be compared with a JVM or Kernel one. It now also builds the interpreter *outside* the clock and times 20 repeated `validateFull` calls in the same process, asserting the finding count on every warm run so a degraded engine cannot report a fast time. That makes one cross-runtime comparison legitimate, on the same 10,000-row Document and the same timed region:

| Engine and runtime | validation only, warm |
|---|---|
| Interpreter — JVM | **17.5 ms** |
| Interpreter — browser (Chrome) | **53.3 ms** |
| A12 Kernel generated Java — JVM | 695.4 ms |
| A12 Kernel dynamic Groovy — JVM | 794.7 ms |

The interpreter in a browser is ~13× the Kernel's generated Java on the JVM, and ~3× its own JVM figure. *(An earlier draft of the public tables paired the browser **first answer** with these JVM numbers and explained the gap as "dominated by" construction and cold start — a mechanism that was never measured. The row was removed and the warm lane built instead.)*

**Cross-engine, vs the kernel's generated validator compiled as Java** (`perf.sh bench` / `crossover` / `huge`; codegen outside the clock; speedups recomputed from raw millisecond columns because the logged `ratioJava` is rounded to two decimals and distorts the fast end):

| Suite | Published | **Measured** |
|---|---|---|
| Default inventory, 15 cases | 2.1×–12.5× | **2.8×–17.1×** |
| Repeated-document, 2 cases | 10×–16.7× | **12.2×–16.8×** |
| Huge-document edge, N=20 000 | ≈155× | **≈152×** (24.94 ms vs 3 786.77 ms) |
| Hardest case, 384 concentrated low-firing rules | 1.32× | **1.73×** |

All 15 inventory cases and all 14 crossover points are interpreter wins; the narrowest anywhere is 1.73×. Against dynamic Groovy the inventory span is 4.0×–19.7×.

**Resident memory** — `EngineFootprintReportTest`, `hold=12`, JDK 21.0.12, `totalB` = heap + Metaspace + Compressed Class Space, every arm gated on an agreed message-code multiset first.

| | Published (08-07) | **Measured** |
|---|---|---|
| vs generated Java | 3.8×–9.2× | **4.2×–8.1×** |
| vs dynamic Groovy | 22×–41× | **18.5×–37.9×** |
| Absolute, prepared model | — | **54 kB–325 kB** |

**Not re-derived:** the `1.4 kB/field + 1.55 kB/rule + 2.3 kB/group` sizing fit is still the 2026-08-07 regression; the public README now says so inline rather than letting it inherit this sweep's date. Re-deriving it needs per-model field/rule/group counts, and one of the six models is synthesized rather than a file.

**Why this was a hand sweep at all.** The gzip footprint has both halves of a no-rot pair — `verifyInterpreterBrowserSizeDocumentation` *derives* the claim and `syncInterpreterBrowserSize` rewrites all three surfaces. The timing and memory claims have only the guard half, and it asserts **hardcoded literals**, so the published numbers can only be advanced by editing four surfaces and the test together. That asymmetry, not a decision, is why they sat 14 days and 10 days stale respectively.

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

### Checkpoint 2026-08-13 — what the routine gate costs once it runs the whole routine tier

Measured on `Macmini9,1 Apple M1`, 8 processors, JDK 21.0.12, isolated (nothing else running), `scripts/perf.sh gate`:

| gate scope | classes | test methods | skipped | wall |
|---|---|---|---|---|
| enumerated two-class `filter` (before) | 2 | 39 | 0 | 3m57s |
| tag-selected `@Tag("perf")` routine tier (after) | 9 | 54 | 0 | **4m44s** |

Five classes entered an automated lane for the first time and two moved out of the broad parallel-fork suite into it — 15 test methods in all, for **+47 s**. That is the whole cost of the coverage change, and it lands well inside the nightly workflow's 60-minute timeout and is cheap enough to sit in front of every release. **`skipped=0` is the load-bearing column:** the `@EnabledIfSystemProperty` canaries would otherwise have reported as skipped inside a green gate, which is a pass that advertises a bar that never ran — they are `perf-bench` and excluded instead. No newly gated bar breached, which was the open risk: the five tagged classes the old filter omitted had never been executed by any lane, so their trip lines were unverified against current code.

Method: three **strictly sequential** `gh workflow run 'Nightly performance' --ref main` dispatches of `15b3ed0`, each waited to completion before the next (the isolation rule of [IF280](INTERPRETER-FINDINGS.md#if280)); artifacts committed under [`perf-results/linux-amd64-ci/`](../perf-results/README.md) as `2026-08-13T095150Z`, `T100152Z`, `T101123Z`. All three: JDK `21.0.11`, 2 processors, 36 accepted observations, run ids `31688252078` / `31689198281` / `31689900737`.

**The runner class spans CPU models within one hour.** Runs 1 and 2 measured on `AMD EPYC 7763 64-Core Processor`, run 3 on `AMD EPYC 9V74 80-Core Processor`. `scripts/perf-anchor.sh` refused the fold at the time, so no block was authored. *(Superseded 2026-08-16: a hosted class is now treated as machine-less and folds across models — [IF293](INTERPRETER-FINDINGS.md#if293). The models stay recorded **here** because this is the dated evidence log and they are the conditions this measurement ran under; what was removed is every place a model drove a DECISION.)*

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

### Checkpoint 2026-08-12 — the key-mechanic day: twelve sessions, collapsed

*Collapsed 2026-08-16 from **twelve** same-day checkpoints — 36 % of this log, more than the entire pre-August history — into one. They were twelve sittings of a single workstream (profile a key mechanic → fix it → re-measure on an isolated machine), and read end to end they restate their method twelve times over. What each one **established** is below with its reference. This loses nothing recoverable: every per-run number survives independently in [`perf-results/darwin-aarch64/`](../perf-results/README.md), which is the machine-readable record and the reason a prose checkpoint may be compressed at all. The two entries the optimization catalog links to are kept addressable below.*

| what the session established | measured | reference |
|---|---|---|
| The cross-engine bar extended from 2 scenarios to **17**, and what that did to the margin | ratios are `interp/kernel`, lower is better; each row takes the worse kernel strategy, over 38 scenario×scale rows | [IF281](INTERPRETER-FINDINGS.md#if281) |
| Each parity row's **own** ratio noise, measured rather than borrowed from another quantity | three sequential isolated runs of the 17 parity lanes, one revision, quiet machine | [IF300](INTERPRETER-FINDINGS.md#if300) |
| The **first pair is measured cold on every engine** — the noise was not in the small arm as the prior session concluded | interpreter @200 across three runs: `2.09× / 1.88× / 2.34×` | [IF282](INTERPRETER-FINDINGS.md#if282) |
| The `darwin-aarch64` anchor refreshed as a **median of three**; the binding quantity for a tolerance is one run's distance from the anchor, not the anchor's own precision | three isolated runs at `087c39f5`, all 36 keys in every run, zero flags | [IF300](INTERPRETER-FINDINGS.md#if300) |
| Keying three nested memos **by the cell cost more than the check they cached** — a negative result, reverted | 34 of 36 anchored observations fell | [IF284](INTERPRETER-FINDINGS.md#if284) |
| The list-in-key sweep: one memo measured cold, **four not exercised**, the live ones elsewhere — and a table first published as "all five are cold" corrected after review | `perf-keys.sh validate` + `compute` at `73bc4a04` | [IF284](INTERPRETER-FINDINGS.md#if284), [IF286](INTERPRETER-FINDINGS.md#if286) |
| The cascade overlay hashed **one key three times**; the key mechanics on the compute path are gone after the fix | before/after presence over ~1,600 samples each, three isolated runs | [IF286](INTERPRETER-FINDINGS.md#if286) |
| The warm outcome and the measurement cost **enter the record** | first carrying run `2026-08-12T124426Z`; 17 `cost` rows summing to 98.1 s for the parity tier — deliberately not comparable to IF282's 71.5 s | [IF282](INTERPRETER-FINDINGS.md#if282) |
| The cross-engine margin's **low tail**, read over 27 runs instead of three: a three-run basis structurally cannot see a tail, and a floor sized from the median is sized from the wrong statistic | 68 margin rows = 34 scenario-scale rows × 2 kernel strategies | [IF300](INTERPRETER-FINDINGS.md#if300) |
| The PARTIAL-validation key profile: one of IG126's four closed, and a **12 % member nobody had listed** (`isRelevantWithin`, 165 samples). *Its mechanism reading was wrong and was superseded the next day — the list key was ~2.8 % and the dominant cost was prefix-string building* | `SI-cliff` at N=1,000, 1,374 `ExecutionSample`s | [IF284](INTERPRETER-FINDINGS.md#if284), superseded by [IF289](INTERPRETER-FINDINGS.md#if289) |

#### Catalog #11 — the compute-overlay read path, after the tightest row was optimized <a id="evidence-11"></a>

The 17 parity lanes re-run after the three compute-overlay fixes, one isolated run, same machine. Record: `perf-results/darwin-aarch64/2026-08-11T231938Z.jsonl`. Over all 38 rows the interpreter lost **0** before and **0** after.

#### Catalog #12 — the parallel row context, profiled and memoized <a id="evidence-12"></a>

Chosen because, after the parity bar reached all 17 gated scenarios, the thinnest VALIDATE rows were `PF-framed` (`0.39`) and `complexRealWorld` (`0.38`). Formal-error checking was the hot claim under test: `FormalResultCache.resultAt` at **26.4 %** inclusive on `PF-framed` @1000 and **47.1 %** on `complexRealWorld` @240. Shipped as [IF283](INTERPRETER-FINDINGS.md#if283).

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

### Checkpoint 2026-07-08 … 2026-07-27 — the pre-August record, compressed

*Compressed 2026-08-16 from **eight** checkpoints (175 lines). Unlike the 2026-08-12 collapse above, **the raw data for these does not survive**: `perf-results/` retains back to 2026-08-11 only, so this prose is the sole record of these measurements and the decisive numbers are kept in full rather than summarised away. What went is the surrounding narrative and the intermediate tables; what stayed is every figure another document cites or could re-derive nothing from.*

| date | what it established | the decisive numbers | reference |
|---|---|---|---|
| 2026-07-27 | The packed-browser B1 release bar ratified and enforced | pinned Apple M1 / Chrome 150.0.7871.186, `browser-b1-10000-rows-first-answer` v1 (10,000 rows, 4,900 `HQ` findings); 50-process calibration on `69e8985d` median/p95 **193.45 / 204.4 ms** and **195.9 / 218.4 ms** | [IF225](INTERPRETER-FINDINGS.md#if225) |
| 2026-07-22 | IG101 resolved — depth per-row **favours the interpreter**, no crossover | decomposing `time = fixed + perRow·N` over 4,000→16,000 rows: interpreter **0.047 ms/row** < generated Java **0.078** < dynamic Groovy **0.084**; at 16,000 rows 712.6 ms vs 1519.9 vs 1905.1 | [IF205](INTERPRETER-FINDINGS.md#if205) |
| 2026-07-21 | The G52 compact/depth synthesized-model sweep — compact passes both rules, depth keeps a large absolute lead but **loses the paired-growth criterion** | compact/validate interp `1.34 → 2.02 ms` (**1.48×**) against Groovy 1.59× and Java 2.22×; depth/validate interp `16.494 → 31.836 ms` (2.05×) against Groovy **1.43×** — a 4.5–9× absolute lead on validation, 1.6–3.4× on computation | [IF199](INTERPRETER-FINDINGS.md#if199) → [IF205](INTERPRETER-FINDINGS.md#if205) |
| 2026-07-21 | The profile-derived **breadth** validation path, after the curve rule first failed it | breadth 100→400 leaf rows: interpreter `23.04 → 35.90 ms` (**1.43×**) vs Groovy 1.53× and Java 1.84× — 5.5× faster than Groovy and 2.3× than Java at 100 rows, 5.6× and 3.1× at 400 | [IF196](INTERPRETER-FINDINGS.md#if196) |
| 2026-07-19 | **IG98's discriminator: the interpreter was WORSE than the kernel** — the naive multi-star `$`-correlation scan, and the optimization it owed | interp `21.2 → 135.9 ms` (**×6.41**) against kernel-Java `41.7 → 112.2` (×2.69); the interp/kernel ratio **0.51 → 1.21**, widening ×2.37 over ×4 Lines. After the first-star-scope hash join: interp **6.8 → 16.2 ms (×2.37, linear)** | [IF144](INTERPRETER-FINDINGS.md#if144) |
| 2026-07-19 | The compute-overlay scenarios against **both** kernel baselines, after the per-step overlay-view cache | `ratioJava` — CO index **0.16 / 0.17 / 0.09** at N=200/800/3200, CO correlation **0.83 / 0.75 / 0.69**; growth per ×4 rows: CO index 800→3200 **×2.30** (Java ×4.50), CO correlation **×4.54** (Java ×4.95) | [IF143](INTERPRETER-FINDINGS.md#if143) |
| 2026-07-10 | The gate's **first live catch**: a real O(rows²) — the per-lookup index-column rebuild, which hung `semanticIndexStaysSubQuadratic` for 20+ minutes at N=20,000 <a id="evidence-10"></a> | — | [IF116](INTERPRETER-FINDINGS.md#if116) |
| 2026-07-08 | The standing gate after the compute rebuild: no regression, and the **rule-count crossover** that started the optimization catalog | `ratioJava` by shape: aggregates **0.02–0.04** (25–50×), huge doc **0.02 → 0.01** (~50× → ~165×), wide sibling-groups 0.06–0.07, deep+rich 0.09–0.12, combined real-world **0.19**; sub-quadratic growth 5,000→20,000: tiered compute 3.34×, B1 4.54× | [IF85](INTERPRETER-FINDINGS.md#if85), [IF86](INTERPRETER-FINDINGS.md#if86) |

**The rule-count crossover is kept in full, because it is why the optimization catalog exists.** `KernelEdgeTest.ruleCountCrossover`, N=1000 Lines × 5 Subs, median of 8 after 12 warmups, codegen excluded — the **naive tree-walker** before any optimization:

| rules | interp | kernel-Groovy | kernel-Java | interp/java |
|---|---|---|---|---|
| 6 | 28.0 ms | 118.2 ms | 110.3 ms | **0.25** |
| 24 | 78.4 ms | 152.0 ms | 148.9 ms | 0.53 |
| 96 | 272.1 ms | 284.7 ms | 274.4 ms | **0.99 ← parity** |
| 192 | 543.2 ms | 502.9 ms | 414.2 ms | **1.31 ← the naive interpreter LOST here** |

It reached parity at ~96 rules and lost at 192 (≈1.31× firing, ≈1.70× low-firing). Every later entry in this log measures work that exists because of that row. **Post-optimization, the same shapes invert:** concentrated firing improves *with* rule count (0.47 → 0.34 from 96 to 384 rules, the codegen's per-firing cost growing faster), and distributed "wide form" stays flat at ~0.34–0.37 through 1,000 rules.
