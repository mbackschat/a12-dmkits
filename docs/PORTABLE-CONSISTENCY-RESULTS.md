# Portable consistency checker — measured results

Dated measurements of the Kernel-free checker, newest first. **Every number here is informative and uncertified.** [H5](PORTABLE-CONSISTENCY-PROPOSAL.md#hard-acceptance-conditions-confirmed-by-the-owner) requires a budget to pass IF137's six-point gate — owner ratification included — *before* the implementation it gates is measured, and no checker number has been through that gate. Nothing on this page is a bar, a regression floor, or an acceptance criterion; the lanes that produce these rows deliberately write no anchor, because an anchor is a bar. The ordering decision behind that posture is [the plan's H5 record](PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md#h5-ordering).

Results never land in a spec, and a measurement's home is not its explanation: the mechanism paragraphs below are marked as hypotheses where they are hypotheses.

## 2026-09-15 — the curve was a cubic term, and it is gone <a id="hoisted-2026-09-15"></a>

Same lane, same machine, same workload, same preflight as the section below; the only difference is the fix described under it. **Median `check`, before → after:**

| programs | before | after | factor |
|---|---|---|---|
| 100 | 330.58 ms | 217.30 ms | 1.52× |
| 200 | 552.13 ms | 300.91 ms | 1.83× |
| 400 | 1 562.90 ms | 503.48 ms | 3.10× |
| 800 | 8 567.77 ms | **908.17 ms** | **9.4×** |

**The shape changed, which is the part that matters.** Per-doubling ratios were 1.67× / 2.83× / 5.48× and are now 1.39× / 1.67× / 1.80×. Take out the fixed ~130 ms of cold class loading and the checker's own work goes 87 → 171 → 373 → 778 ms, which is 1.97× / 2.18× / 2.09× per doubling: linear, within the noise of a cold lane. The p95 spread collapsed with it — 856.70–944.88 ms at 800 programs against 4 404.28–9 970.16 ms before — so the wide tail that earlier read like memory pressure was the cubic term meeting a scheduler, not the heap.

**The locus, named from the flight recorder rather than from a reading.** 95 % of the samples on a 400-program model sat in two places: `declaredReferences` with its `resolveRelative`, and `computationSelfReadFindings` with its `computedTargetOf`. `declaredReferences` is a whole-model walk resolving one reference per element, and it resolved each by scanning the resolvable elements — quadratic. Fifteen families call it, and `computationSelfReadFindings` called it **per element**, which made the whole check cubic in declarations. The fix is two derived values on `PreparedModel`: the walk computed once, and the resolvable elements keyed by path. No family changed, and none had to remember to hoist anything — a rule no guard could have enforced, which is how the cubic arrived unnoticed.

**What still scales linearly per reference.** About sixty other sites resolve a path with `elements.firstOrNull { it.path == … }`, so any family hot on a different model shape can still contribute an n² term. None of them showed in this profile, and the post-fix curve is linear on this workload, so they are left measured-and-untouched rather than swept on suspicion.

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
