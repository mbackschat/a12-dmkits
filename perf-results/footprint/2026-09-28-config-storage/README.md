# Per-row configuration storage, 2026-09-28

The source experiment replaces fifteen copied immutable EvalConfig values in Interpreter with getters and changes the one affected ExprEval null check to a single safe invocation. [CONFORMANCE-PLAN](../../../docs/CONFORMANCE-PLAN.md) carries the pre-run M3-F2 predictions; [the performance checkpoint](../../../docs/INTERPRETER-PERFORMANCE-RESULTS.md#checkpoint-2026-09-28-config-storage) carries conclusions and limits.

All records identify base commit `85aedb673fee940047aac3234f263f081807a855`. These are explicitly source-variant measurements in the existing checkout, not clean-revision captures. The `getters` variant changes only `Interpreter.kt` and `ExprEval.kt` in interpreter commonMain as described above. The `copies` variant restores both files byte-for-byte to that commit; tests/build inputs stay unchanged. After the failed timing qualification, both production files were restored byte-for-byte to HEAD. No experimental production change is retained.

- `getters/`: five original JVM and five original Node footprint workers; scales 200/400/800/1600, warmup 2, repetitions 3, 512 MB arguments, OpenJDK 21 and Node 22.0.0.
- `timing-getters/`: five original workers per existing JVM B1 and Node B1/B2 throughput scenario; warmup 12, repetitions 9, JVM 1 GB and Node 1024 MB arguments.
- `timing-getters-repeat/`: five additional B2 workers only, identical getter source and settings, after the control; the repeat retains its failure.
- `timing-copies/`: the same timing settings with unchanged sources, run after the getter arm on the same machine. A sequential arm order cannot exclude every environmental change.

The temporary init script sets `gradle.rootProject.extensions.findByName('kotlinNodeJs').nodeVersion = '22.0.0'` inside `gradle.projectsEvaluated`. Runtime, scenario fingerprints, settings, cardinalities, support and semantic outcomes remain in each original record. No allocation sampling tree or diagnostic V8 flag enters production.

Recheck the preserved footprint capture with `./build.sh --console=plain :adapter:verifyNfr1StandingAnchor -Pnfr1Collectors=jvm-footprint,node-footprint -Pnfr1FootprintRawDir="$PWD/perf-results/footprint/2026-09-28-config-storage/getters"`. Timing comparisons use the median of each process's sample median, independently per scenario and scale; no cross-row compensation. Timing qualification failed: Node B2 elapsed medians initially rose 8.379%/7.067%; the identical-source repeat after the control rose 3.362%/5.119%. The 10000-row observation still breaches the unchanged allowance. All original arms are retained; no result is relabelled green.
