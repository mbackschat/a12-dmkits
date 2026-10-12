# Sync reconciliation performance evidence, 2026-10-12

The interpreter repairs are committed as `bcdfbef86`, `7f6608e62` and `2293025d6`. These records preserve the measured source variants without rewriting their original identity fields. Workloads, fingerprints, standing anchors and tolerances are unchanged. [IF391](../../../docs/INTERPRETER-FINDINGS.md#if391) names the implementation and semantic controls; [the performance protocol](../../../docs/INTERPRETER-PERFORMANCE.md) defines the acceptance limits.

| Evidence | Source and scope |
| --- | --- |
| `initial/` | Five JVM and five Node footprint workers on clean `10031a3e5`; 40 of 48 medians exceeded the unchanged 5% standing anchor. |
| `final/` | Five JVM and five Node workers identifying base `7f6608e62`, plus the exact `ExprEval.filteredRows` coordinate shortcuts committed in `2293025d6`. This is an explicit source-variant capture whose production bytes equal `2293025d6`, not a clean-base capture. All 48 medians pass; the largest increase is 3.784% (JVM simple-validation/200 retained bytes). |
| `timing-getters/` | Five Node B2 workers identifying base `10031a3e5`, with the allocation-repair candidate later committed as `bcdfbef86`, before its final successor-sequence fusion. The getter file is byte-identical to `bcdfbef86`. |
| `timing-copies/` | The same candidate with its eighteen immutable configuration aliases restored to copied properties, measured after the getter arm. These are paired source variants, not clean `10031a3e5` measurements. |
| [kernel-benchmark.xml](kernel-benchmark.xml) | Original JUnit output from the complete `PerfBenchTest` run on clean `bcdfbef86`: three tests, no skips or failures, 19 timing rows including `computeWithSupport`; interpreter/Java ratios at most 0.22 and interpreter/Groovy at most 0.18. The subsequent small repair has its own focused semantic and routine timing controls. |

Footprint settings are identical across the two retained arms: five fresh workers per runtime, scales 200/400/800/1600, warmup 2, repetitions 3, 512 MB heaps, JDK 21.0.12.1 and Node 22.0.0. The measurement-only Gradle init sets `kotlinNodeJs.nodeVersion` to `22.0.0`; it does not change the project default. Verify the final arm with `./build.sh --offline --console=plain :adapter:verifyNfr1StandingAnchor -Pnfr1Collectors=jvm-footprint,node-footprint -Pnfr1FootprintRawDir="$PWD/perf-results/footprint/2026-10-12-sync-closure/final"`.

The Node B2 timing pair uses five fresh processes per arm, warmup 12, repetitions 9 and a 1024 MB heap. Median-of-worker elapsed medians increase 4.74%/1.40% at 2500/10000 rows with getters; CPU medians increase 2.72%/decrease 0.47%. This qualifies the current candidate within 5% for this pair, unlike the separately retained September experiment. Sequential arm order does not exclude environmental change; this is not a zero-effect claim.

Routine timing evidence remains in its native JSONL files:

- [Initial complete routine](../../darwin-aarch64/2026-10-12T115512Z.jsonl), clean `bcdfbef86`: 57/58 tests pass; compute-overlay CPU/1500 fails its 39.18 ms limit on all three attempts (41.31/41.19/41.05 ms).
- [JFR diagnostic](../../darwin-aarch64/2026-10-12T120252Z.jsonl), `bcdfbef86` with flight recording enabled: profiling evidence, not final acceptance.
- [Affected-method verification](../../darwin-aarch64/2026-10-12T121457Z.jsonl), dirty `bcdfbef86` with the three production-file changes above: all eleven affected methods pass, including compute-overlay CPU 36.192/145.411 ms at 1500/6000.
- [Complete routine after the admission repair](../../darwin-aarch64/2026-10-12T122828Z.jsonl), clean `7f6608e62`: 57/58 tests pass; the same CPU/1500 limit still fails at 39.73/39.25/39.39 ms. This full-context failure prevents treating the earlier focused pass as closure.
- [Sparse-filter cost verification](../../darwin-aarch64/2026-10-12T123809Z.jsonl), dirty `7f6608e62` with the `ExprEval.filteredRows` coordinate shortcuts: CPU 31.441/127.916 ms at 1500/6000; both kernel comparisons pass. The whole-suite context is checked separately.
- [Final complete routine](../../darwin-aarch64/2026-10-12T124213Z.jsonl), clean `2293025d6`: 58 tests, no failures or skips, all twelve result XMLs fresh; CPU/1500 is 37.056 ms and CPU/6000 is 148.119 ms, both accepted in the complete-suite context.
- [October 9 comparand](../../darwin-aarch64/2026-10-09T192141Z.jsonl), `59b94e1e5`: historical observation, not a replacement standing anchor. Exact keys include workload fingerprint, scale and clock.

The final routine, memory and deterministic-work guards pass unchanged. Historical timing differences remain observations: the documented clock instrument cannot certify a strict 5% non-regression bound, and neither a green gate nor these records establish that every difference is noise.
