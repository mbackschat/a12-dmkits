# Field-message footprint evidence, 2026-09-28

Original worker JSON records are preserved here; [CONFORMANCE-PLAN](../../../docs/CONFORMANCE-PLAN.md) owns qualification status and the pre-run predictions. No standing anchor or exception is changed by retaining these measurements.

- `candidate/`: five JVM and five Node workers from clean commit `6e621795b6dc5e347991e7549c62f962910f8651`.
- `original-message-sources/`: five Node workers with only IG159's four changed interpreter commonMain files (`AutoChecks.kt`, `FieldMessage.kt`, `ModelLoader.kt`, `Model.kt`) restored to their `1a6e7d54fde6351e69c6ad4cba28b4ec30454823` bytes. Current tests and build configuration were retained. The record's commit field identifies the source revision, not a clean checkout. All four candidate files were restored byte-for-byte after the control completed.

Both arms use scales 200/400/800/1600, warmup 2, repetitions 3, and a 512 MB heap argument. Node 22.0.0 matches the standing anchor; the candidate JVM workers use OpenJDK 21. Raw records carry effective runtime and heap settings. The temporary Gradle init script sets `gradle.rootProject.extensions.findByName('kotlinNodeJs').nodeVersion = '22.0.0'` inside `gradle.projectsEvaluated`, without changing the project's runtime default.

Recheck existing candidate evidence with `./build.sh --console=plain :adapter:verifyNfr1StandingAnchor -Pnfr1Collectors=jvm-footprint,node-footprint -Pnfr1FootprintRawDir="$PWD/perf-results/footprint/2026-09-28-field-messages/candidate"`. For the control select `node-footprint` and `original-message-sources`. These commands compare the preserved records without taking new measurements.

The completed pre-fix-source control has all 24 Node scenario/scale/metric medians (five records per median). Candidate/control deltas range from -1.439% (equality-correlation/400/retainedBytes) to +0.867% (simple-validation/200/allocatedBytes). The latter is 5,308,112 bytes in the candidate and 5,262,464 in the control; the control is +4.267% versus the unchanged 5,047,128-byte anchor. Thus the prediction that this newly marginal row would also breach without IG159 was not confirmed by this capture. Neither a repeatable source effect nor an allocation-instrument explanation is established by this pair; IG159 qualification and IG160/M3-F diagnosis remain open, without a new exception.
