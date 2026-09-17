# Portable consistency checker — measured results

Dated measurements of the Kernel-free checker, newest first. **Every LATENCY here is informative and uncertified, and always will be**: [H5](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner) requires a budget to pass IF137's six-point gate before the implementation it gates is measured, and a millisecond figure taken on one machine has no basis that survives another. No duration on this page is a bar, a regression floor or an acceptance criterion, and the lanes that produce them write no anchor, because an anchor is a bar.

**Four quantities ARE certified — three by the owner's 2026-09-16 ratification and the fourth on 2026-09-17** — the [split by what each quantity can honestly bear](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification), which carries each number's six-point disposition: the **delivery byte ceiling** (1 MiB on the packed npm tarball, held by `:consistency:verifyConsistencyDeliveryCeiling` in push CI) and the **prepared growth ratio** (`PerfBounds.SUB_QUADRATIC_MAX`, held by `:adapter:checkerGrowthPerfTest`). The **cold growth ratio** is certified on the same footing and asserted since H5-d, by `:adapter:checkerColdStartPerfTest`. Its durations are not, and never will be. The fourth is the **keystroke reuse ratio** (`1.0×`, held by `:adapter:checkerAfterEditPerfTest` since 2026-09-17), and it is the only one whose ceiling comes from a feature's work count rather than from a measurement. So read a table below for its curve and its shape, never for permission to be slower.

**Every OTHER boundary is now UNSET by decision rather than by sequence**, which is the difference between this page today and on 2026-09-16: the after-edit lane's two remaining arms and the combined lane's additivity ratio each sit at ~1.0 by construction, so a bar on any of them could only be read off the lane's own spread. The three dispositions are in the [ratification record](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ratification); none of them is waiting on a further run.

Results never land in a spec, and a measurement's home is not its explanation: the mechanism paragraphs below are marked as hypotheses where they are hypotheses.

## 2026-09-17 — what the checker actually publishes, enumerated for the first time <a id="shipped-2026-09-17"></a>

`:consistency:verifyConsistencyShippedFiles`. The denominator had never been closed on either carrier, and closing it found three things the required-set shape it replaces could not see.

| carrier | entries | what the enumeration found |
|---|---|---|
| npm tarball | **16** files | it shipped a `devDependencies: {"typescript": "5.5.4"}` and **no licence at all** — no `license` field, no `LICENSE` file — because the pack ran over Kotlin's compiler output directory rather than over a staged set |
| jvm jar | **2** class packages, both under `io/github/mbackschat/a12/dm/consistency/` | clean, and now closed by ROOT: a subpackage is ours by construction, a package outside the root is a shaded or copied dependency |

**Why no existing guard could have found it.** `verifyConsistencyPurity` reads what the classes LINK and `verifyConsistencyDependencies` reads what a consumer RESOLVES. A file that was *copied in* declares neither, which is the reason [F's oracle](PORTABLE-CONSISTENCY-PROPOSAL.md) asks for a shipped-file enumeration in addition to both. The shape matters as much as the existence: a **required-set** check ("is what I expect present") stays green while something else rides along, and that is what `:interpreter:verifyInterpreterNpmPackage` still is.

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

**What the bundle size is NOT.** It is a dated observation of a test fixture's bundle, not a delivery bar: the page is a replay harness, Vite is configured with no code-splitting or manual chunking, and F's non-goals rule out any tree-shaking assumption. The checker's one absolute bar remains the packed tarball's byte count ([the delivery entry](#delivery-2026-09-16)). Read this row for the order of magnitude a browser consumer pays today, never as a ceiling anything holds.

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

**The other two arms are UNSET permanently, which is a result and not a gap.** Both are ~1.0 by construction, so a ceiling on either could only be read off this lane's own spread — and the saved-edit arm measured 1.006 · 0.958 · 0.987 · 0.988 across four runs, straddling 1.0, so such a bar would refuse healthy runs.

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
