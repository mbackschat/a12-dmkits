# Numeric-reader footprint evidence, 2026-09-28

These are the original worker JSON records, consumed directly by `Nfr1Collector`; no aggregate copy or normalized format is stored. [The performance results log](../../../docs/INTERPRETER-PERFORMANCE-RESULTS.md#checkpoint-2026-09-27--numeric-token-correction-routine-gate-and-isolated-charset-controls) owns conclusions; [CONFORMANCE-PLAN](../../../docs/CONFORMANCE-PLAN.md) owns execution status.

- `candidate/`: five JVM and five Node workers from clean commit `325871e4a99f94d65ebb7009e55146cbedbcd6e5`. Full aggregation passed; the unchanged standing anchor failed eight Node allocation rows.
- `original-reader/`: five Node workers, current build configuration with only Parser.kt replaced by its `a28d06d1e` bytes. The record’s commit field identifies that original production-source revision, not a clean checkout: this is explicitly a dirty-tree causal control. The candidate source was restored after execution.

Both use scales 200/400/800/1600, warmup 2, repetitions 3, and a 512 MB heap argument. Kotlin’s default Node 24 was overridden for these invocations to match the anchor’s Node 22.0.0. The temporary init script sets `gradle.rootProject.extensions.findByName('kotlinNodeJs').nodeVersion = '22.0.0'` inside `gradle.projectsEvaluated`; raw records independently carry the effective runtime and maximum heap. No anchor, project runtime default or measurement worker was changed.

To recheck the candidate against the standing anchor, run `./build.sh --console=plain :adapter:verifyNfr1StandingAnchor -Pnfr1Collectors=jvm-footprint,node-footprint -Pnfr1FootprintRawDir="$PWD/perf-results/footprint/2026-09-28-numeric-tokens/candidate"`. For the control, select only `node-footprint` and the `original-reader` directory. This compares existing evidence; it does not take a new measurement.
