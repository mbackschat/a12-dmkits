# DG102-D interpreter footprint qualification, 2026-09-28

These ten original worker records were captured at clean commit `ca9f48a486873a8166d2c0953d185763220bb35d`. Five JVM and five Node processes use the unchanged standing-anchor scales 200/400/800/1600, warmup 2, repetitions 3 and 512 MB heap arguments. Node was pinned to 22.0.0 with the same temporary Gradle init override documented in the [numeric-reader capture](../2026-09-28-numeric-tokens/README.md); the first attempt used the default Node 24 and was rejected as incomparable. No production source, anchor or worker changed between attempts.

The comparable capture validates semantic outcomes, cardinalities, GC viability and owner release. All 24 JVM rows and twenty Node rows meet the unchanged +5% baseline. The standing-anchor gate remains red on exactly these four previously accepted Node compute-overlay allocation identities; each median is below the corresponding [owner-accepted capture](../2026-09-28-numeric-tokens/README.md). This does not rebase the anchor or grant a permanent exception.

| Scale | Allocated bytes, median | Delta to standing anchor | Delta to owner-accepted capture |
|---|---|---|---|
| 200 | 46,459,984 | +9.037% | −0.496% |
| 400 | 92,771,696 | +10.143% | −0.746% |
| 800 | 185,267,680 | +10.607% | −0.585% |
| 1600 | 360,098,440 | +10.423% | −0.693% |

Collection: `./build.sh --console=plain :interpreter:interpreterFootprint -PfootprintForks=5 -PfootprintCommit=ca9f48a486873a8166d2c0953d185763220bb35d -PfootprintScales=200,400,800,1600 -PfootprintWarmup=2 -PfootprintRepetitions=3 -PfootprintJvmHeap=512m -PfootprintNodeHeapMb=512 -I /tmp/a12-ig159-node22.gradle -x :kotlinStoreYarnLock`. The init file sets `gradle.rootProject.extensions.findByName('kotlinNodeJs').nodeVersion = '22.0.0'` inside `gradle.projectsEvaluated`.

Recheck without collecting again: `./build.sh --console=plain :adapter:verifyNfr1StandingAnchor -Pnfr1Collectors=jvm-footprint,node-footprint -Pnfr1FootprintRawDir="$PWD/perf-results/footprint/2026-09-28-dg102"`. The capture log was `/tmp/a12-dg102-footprint-node22.log`; the comparison log was `/tmp/a12-dg102-anchor.log`. The structured JSON is authoritative for these custom tasks, which `test-evidence.sh` does not recognize as test executions.

These workloads establish the standing footprint checkpoint; they contain no declared patterns and do not measure regex compilation cost. The separately recorded pattern-heavy N/4N test establishes evaluation growth, not a 5% absolute timing or regex-compilation allocation bound. Neither result is a universal performance guarantee. [CONFORMANCE-PLAN](../../../docs/CONFORMANCE-PLAN.md) owns the remaining DG102 mechanisms and runtime-oracle assessment.
