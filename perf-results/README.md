# `perf-results/` — the committed performance record

Every run of the routine performance gate writes one **text** file here. Nothing in this tree is generated output: it is read back *across* runs, which is why it is committed and not under `build/` (CLAUDE.md, "never store surviving data under `build/`"; `SurvivingDataNotInBuildGuardTest` enforces it).

**Layout.** `perf-results/<environment>/<utc-timestamp>.jsonl`, one file per run. The environment key is `<os>-<arch>[-ci]`, so a laptop and a hosted runner never mix: absolute timings are only comparable within one of them.

**Format.** JSON Lines, no database, no binary. Line 1 is the run header, then one line per observation.

```
{"kind":"run","environment":"darwin-aarch64","capturedAt":"2026-08-11T190455Z","commit":"1845f8af",
 "os":"Mac OS X","arch":"aarch64","jdk":"21.0.12","processors":8,"loadAverage":2.41,
 "ci":false,"runId":null,"runnerName":null}
{"kind":"observation","environment":"darwin-aarch64","key":"<lane>|<scenario>@<v> fingerprint=<sha256>|n=<N>|interpreter|cpu",
 "scenario":"diagnostic/B1-per-row@1 fingerprint=…","lane":"B1 sub-quadratic check","n":5000,
 "engine":"interpreter","clock":"cpu","medianMs":9.6,"loadAverage":2.44}
```

**Why the conditions are recorded, per observation and not just per run.** The same commit measured on a busy laptop and on a 2-vCPU hosted runner produces numbers that are not comparable, and a run that becomes busy halfway through otherwise shows up later as an unexplained outlier. `loadAverage`, `processors`, `jdk`, `ci`, `runId` and `runnerName` are what make a historic row interpretable instead of merely numeric.

**The key is content-addressed.** It carries the lane, the scenario's `id@version fingerprint=<sha256>`, the scale, the engine and the clock. The fingerprint covers the model, the generated placements, the scale and the runtime bindings, so **changing a scenario's content changes its key** and a new measurement is never silently compared against an old, different workload.

## Reading it

| you want | run |
|---|---|
| a historic table for one lane or scenario | `scripts/perf-history.sh <pattern>` |
| every lane's latest values, per environment | `scripts/perf-history.sh` |
| to fold runs into the committed anchor | `scripts/perf-anchor.sh refresh perf-results/<environment>/*.jsonl` (per-key median over every run given) |

## Writing it

A local run on a fixed machine is the reliable instrument: `scripts/perf.sh gate`. Hosted runs also record, and the nightly uploads its file as the `perf-observations` artifact; folding a hosted run in requires downloading that artifact, since a hosted runner's numbers can only be authored from a hosted run.
