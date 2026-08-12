# `perf-results/` — the committed performance record

Every run of the routine performance gate writes one **text** file here. Nothing in this tree is generated output: it is read back *across* runs, which is why it is committed and not under `build/` (CLAUDE.md, "never store surviving data under `build/`"; `SurvivingDataNotInBuildGuardTest` enforces it).

**Layout.** `perf-results/<environment>/<utc-timestamp>.jsonl`, one file per run. The environment key is `<os>-<arch>[-ci]`, so a laptop and a hosted runner never mix: absolute timings are only comparable within one of them.

**Format.** JSON Lines, no database, no binary. Line 1 is the run header, then one line per record. Three kinds follow it, told apart by `kind`: an **`observation`** (one engine's median for one lane/scenario/scale/clock), a **`warm`** outcome (how one engine instance's steady-state warm ended), and a **`cost`** row (what one lane spent measuring one scenario).

```
{"kind":"run","environment":"darwin-aarch64","capturedAt":"2026-08-11T190455Z","commit":"1845f8af",
 "os":"Mac OS X","arch":"aarch64","jdk":"21.0.12","processors":8,"machine":"Macmini9,1 Apple M1",
 "loadAverage":2.41,"ci":false,"runId":null,"runnerName":null}
{"kind":"observation","environment":"darwin-aarch64","key":"<lane>|<scenario>@<v> fingerprint=<sha256>|n=<N>|interpreter|cpu",
 "scenario":"diagnostic/B1-per-row@1 fingerprint=…","lane":"B1 sub-quadratic check","n":5000,
 "engine":"interpreter","clock":"cpu","medianMs":9.6,"loadAverage":2.44}
{"kind":"warm","environment":"darwin-aarch64","lane":"kernel parity","scenario":"diagnostic/CO-correlation@1 fingerprint=…",
 "n":800,"engine":"groovy","runs":15,"stop":"time-cap","loadAverage":4.96}
{"kind":"cost","environment":"darwin-aarch64","lane":"kernel parity","scenario":"CO correlation","wallMs":9214.6,"loadAverage":4.96}
```

**A `warm` row QUALIFIES the medians measured on that instance, which is why it is recorded rather than printed.** `stop` is `flattened`, `run-cap` or `time-cap`, and only the first means the instance actually reached steady state — a still-falling kernel arm reads higher than its settled cost, so a ratio measured against it looks better than it is. It carries the observation's own `(lane, scenario, n, engine)`, so the two join; `PerformanceRecordShapeGuardTest` fails a row that joins nothing, which is what a renamed field or a shorthand engine name silently produces. This existed only as a `[perf][note]` on stdout until 2026-08-12, so the 8 s budget cap's justification lived in a `build/` log that `clean` deletes, a returning cap stop failed nothing, and no later run could compare against it.

**A `cost` row is what a lane spent measuring one scenario** — agreement checks, six warms, the timed pairs, and any re-measurement a breach forced. Summing a run's `cost` rows gives the tier's wall time, the other number that previously existed only in a run log. A lane that **breaches** records its cost too — the row is written in a `finally`, because the run whose cost is most worth having is the one that failed.

**`commit` carries a `-DIRTY` suffix when the working tree did not match the revision it names** (everything outside this directory, since a run always leaves a new file inside it). A measurement taken mid-edit did not measure the commit it cites, and a record's only value is that a later run can be compared against it — `scripts/perf-anchor.sh` has marked this since it was written, and `PerfObservationWriteTest` now holds the record to the same rule.

**Why the conditions are recorded, per observation and not just per run.** The same commit measured on a busy laptop and on a 2-vCPU hosted runner produces numbers that are not comparable, and a run that becomes busy halfway through otherwise shows up later as an unexplained outlier. `loadAverage`, `processors`, `jdk`, `ci`, `runId` and `runnerName` are what make a historic row interpretable instead of merely numeric.

**`machine` names the hardware, which `environment` does not.** The environment key is an os/arch *family*, and an absolute time is a property of the machine: two Apple Silicon generations, or two products of the same generation with different cooling, differ materially at the same `darwin-aarch64` key. The field is probed from the operating system (`sysctl hw.model` + the CPU brand string on macOS, `/proc/cpuinfo`'s `model name` on Linux, `PROCESSOR_IDENTIFIER` on Windows) because the JVM exposes no CPU identity at all. `scripts/perf-anchor.sh` writes it into the block it authors and refuses inputs that disagree on it, and the enforcing lane asserts only against a block captured on the machine it is running on (IF280).

**The key is content-addressed.** It carries the lane, the scenario's `id@version fingerprint=<sha256>`, the scale, the engine and the clock. The fingerprint covers the model, the generated placements, the scale and the runtime bindings, so **changing a scenario's content changes its key** and a new measurement is never silently compared against an old, different workload.

## Reading it

| you want | run |
|---|---|
| a historic table for one lane or scenario | `scripts/perf-history.sh <pattern>` |
| every lane's latest values, per environment | `scripts/perf-history.sh` |
| whether a row actually STEPPED, when, and its run-to-run noise | `scripts/perf-trend.sh [pattern]` |
| to fold runs into the committed anchor | `scripts/perf-anchor.sh refresh perf-results/<environment>/*.jsonl` (per-key median over every run given) |

## Writing it

A local run on a fixed machine is the reliable instrument: `scripts/perf.sh gate`. Hosted runs also record, and the nightly uploads its file as the `perf-observations` artifact; folding a hosted run in requires downloading that artifact, since a hosted runner's numbers can only be authored from a hosted run.

## What gets committed, and what does not

**Every gate run writes a file here; most of them should never be committed.** A run is worth keeping only when something else refers to it:

- it is an **input to an anchor refresh** (`scripts/perf-anchor.sh` folds several runs at their per-key median, and the block should be traceable to the runs it came from), or
- it is a **checkpoint measurement** a doc cites.

An ad-hoc run from an inner loop is neither. It stays untracked and goes away with `git clean -f perf-results` — expected, not a mess to tidy up.

**The bound is enforced, not advised.** `PerformanceRecordShapeGuardTest` fails when one environment carries more than 30 committed runs, which is about a month of nightlies and well past `perf-history.sh`'s default 8-run window. Beyond that horizon the durable references are the committed anchor and the checkpoints, not the raw runs of superseded revisions — so pruning the oldest files is the fix, and it loses nothing that is still cited. The cap exists because an unbounded committed tree is how a text record turns into a liability that someone eventually deletes wholesale.
