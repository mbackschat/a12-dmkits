# `perf-results/` — the committed performance record

Every run of the routine performance gate writes one **text** file here. Nothing in this tree is generated output: it is read back *across* runs, which is why it is committed and not under `build/` (CLAUDE.md, "NEVER store data that must survive under `build/`"; `SurvivingDataNotInBuildGuardTest` enforces it).

**Coverage starts where each environment's first file does** — `linux-amd64-ci/2026-08-11T183803Z`, `darwin-aarch64/2026-09-27T222648Z`. Seven `darwin-aarch64` runs cited in the docs, dated 2026-08-11 to 2026-08-13, are **gone**: they were measured while the gate still emitted to `adapter/build/perf-observations.jsonl`, so `clean` took them. Those citations keep the run id as a plain code span and carry no link, which is why `DocRelativeLinkGuardTest` is green rather than excusing them. This is the concrete loss the "never store surviving data under `build/`" rule was adopted to stop, and it is why a record here is committed the moment it is written.

The separate [footprint capture](footprint/2026-09-28-numeric-tokens/README.md) retains native worker JSON for the standing heap anchor and its original-reader control. It is not a routine JSON-Lines run and is not consumed by the timing history tools.

**Layout.** `perf-results/<environment>/<utc-timestamp>.jsonl`, one file per run. The environment key is `<os>-<arch>[-ci]`, so a laptop and a hosted runner never mix: absolute timings are only comparable within one of them.

**Format.** JSON Lines, no database, no binary. Line 1 is the run header, then one line per record. Four kinds follow it, told apart by `kind`: an **`observation`** (one engine's median for one lane/scenario/scale/clock), a **`warm`** outcome (how one engine instance's steady-state warm ended), a **`cost`** row (what one lane spent measuring one scenario), and a **`margin`** row (one kernel arm's cross-engine headroom, against its committed floor).

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
{"kind":"margin","environment":"darwin-aarch64","key":"kernel parity|…|n=200|java|wall","lane":"kernel parity","n":200,"engine":"java","clock":"wall","margin":2.99,"floor":2.6514,"status":"ok"}
```

**A `warm` row QUALIFIES the medians measured on that instance, which is why it is recorded rather than printed.** `stop` is `flattened`, `run-cap` or `time-cap`, and only the first means the instance actually reached steady state — a still-falling kernel arm reads higher than its settled cost, so a ratio measured against it looks better than it is. It carries the observation's own `(lane, scenario, n, engine)`, so the two join; `PerformanceRecordShapeGuardTest` fails a row that joins nothing, which is what a renamed field or a shorthand engine name silently produces. This existed only as a `[perf][note]` on stdout until 2026-08-12, so the 8 s budget cap's justification lived in a `build/` log that `clean` deletes, a returning cap stop failed nothing, and no later run could compare against it.

**A `margin` row is the cross-engine early-warning band's verdict** (IG121 piece 1): `margin` is `kernel_median / interpreter_median`, so parity is `1.00` and bigger is more headroom, and `floor` is the value committed for that row in `adapter/src/test/resources/perf/margin-floors.json`. `status` is one of `ok`, `warn`, `lost` (at or below parity — the comparative gate owns that and has already failed) or `unbanded`. **`floor` is present exactly when the status is not `unbanded`**, and the guard enforces that pairing: a floor with no verdict, or an `ok` with no floor, would both read as coverage that is not there. Six `darwin-aarch64` rows are deliberately unbanded because their floor would not clear the minimum a band must reach to be able to fire at all. **Both** runner classes are banded: `linux-amd64-ci` carries 68 rows, authored 2026-08-13 from all six committed hosted runs — margins are ratios measured inside one run, so they divide out machine and contention and stay comparable across both, which absolute times do not ([IF293](../docs/INTERPRETER-FINDINGS.md#if293)). **The band never fails a build** — no candidate floor reaches zero historical firings, because a margin cannot separate a real regression from a contended run, so it warns and the existing re-measurement backstop decides.

**A `cost` row is what a lane spent measuring one scenario** — agreement checks, six warms, the timed pairs, and any re-measurement a breach forced. Summing a run's `cost` rows gives the tier's wall time, the other number that previously existed only in a run log. A lane that **breaches** records its cost too — the row is written in a `finally`, because the run whose cost is most worth having is the one that failed.

**`commit` carries a `-DIRTY` suffix when the working tree did not match the revision it names** (everything outside this directory, since a run always leaves a new file inside it), checked twice: when the build's task graph is ready, before anything compiles, and when the record's header is written. The revision named is the one the build started from. A measurement taken mid-edit did not measure the commit it cites, and a record's only value is that a later run can be compared against it — `scripts/perf-anchor.sh` has marked this since it was written, and `PerfObservationWriteTest` now holds the record to the same rule.

**Why the conditions are recorded, per observation and not just per run.** The same commit measured on a busy laptop and on a 2-vCPU hosted runner produces numbers that are not comparable, and a run that becomes busy halfway through otherwise shows up later as an unexplained outlier. `loadAverage`, `processors`, `jdk`, `ci`, `runId` and `runnerName` are what make a historic row interpretable instead of merely numeric.

**Environment quality per observation — recorded, and NEVER used to reject a sample.** Each observation also carries `windowSince` plus `gcCountInWindow`, `gcMsInWindow`, `jitMsInWindow`, `stealJiffiesInWindow`, `cgroupThrottledUsecInWindow` and `cgroupThrottledPeriodsInWindow`. They exist so a historic outlier can be explained instead of merely doubted, and they must never appear in a bar, a floor, or a filter: GC and JIT are **post-treatment** variables, so discarding windows that contain GC would censor exactly the evidence an allocation regression produces, and steal time correlates with the load a run competed against, so excluding busy windows quietly selects the flattering ones. `PerformanceRecordShapeGuardTest.noQualityFieldIsReadOutsideTheRecorder` makes that structural — a read anywhere in the perf estate outside the producer fails the build, because a policy this convenient to break needs a guard rather than a comment.

Two conventions carry the meaning. **`windowSince`** says which span the deltas cover: `jvm-start` for the first observation of a run, `previous-observation` afterwards. That window *contains* the measurement plus whatever harness work ran between the two writes — in the parity lane, the other engines' arms — so it is a qualifier on a measurement's neighbourhood, never an isolate of it, which is why no field is named as though it were. And **`null` means unobservable, not zero**: steal time and cgroup throttling are Linux-only and stay absent on macOS, while a counter that went *backwards* (a bean reset, a rotated cgroup file) is recorded as `null` rather than as a negative delta that would read like an improvement. Rows written before 2026-08-12 have none of these fields, and are not retrofitted — rewriting a record to satisfy a newer guard would forge measurements.

**`machine` names the hardware, which `environment` does not.** The environment key is an os/arch *family*, and an absolute time is a property of the machine: two Apple Silicon generations, or two products of the same generation with different cooling, differ materially at the same `darwin-aarch64` key. The field is probed from the operating system (`sysctl hw.model` + the CPU brand string on macOS, `/proc/cpuinfo`'s `model name` on Linux, `PROCESSOR_IDENTIFIER` on Windows) because the JVM exposes no CPU identity at all. `scripts/perf-anchor.sh` writes it into a **non-hosted** block it authors and refuses inputs that disagree on it; a **hosted** block records no machine and folds across models, because that class does not have one, and the enforcing lane asserts only against a block captured on the machine it is running on (IF280).

**The key is content-addressed.** It carries the lane, the scenario's `id@version fingerprint=<sha256>`, the scale, the engine and the clock. The fingerprint covers the model, the generated placements, the scale and the runtime bindings, so **changing a scenario's content changes its key** and a new measurement is never silently compared against an old, different workload.

## Reading it

| you want | run |
|---|---|
| a historic table for one lane or scenario | `scripts/perf-history.sh <pattern>` |
| every lane's latest values, per environment | `scripts/perf-history.sh` |
| whether a row actually STEPPED, when, and its run-to-run noise | `scripts/perf-trend.sh [pattern]` |
| to fold runs into the committed anchor | `scripts/perf-anchor.sh refresh perf-results/<environment>/*.jsonl` (per-key median over every run given) |

**Aggregating one of these files by hand? `export LC_ALL=C` first.** Every number in this tree is *written* in `Locale.ROOT` — `PerfNumberLocaleContractTest` holds the producing side to it — but a *reader* inherits the shell's locale, and this estate is developed on a `de_DE` one. There, `awk` parses `0.57` as `0` and renders `3.0` as `3,000`; `sort -n` truncates at the dot. Neither malforms anything or fails, so the wrong number simply travels. The scripts above are unaffected (they delegate every decimal to the JVM and do no arithmetic in shell), which is exactly why the exposure is the ad-hoc pipeline you type instead — and that has already produced one wrong growth median.

## Writing it

A local run on a fixed machine is the reliable instrument: `scripts/perf.sh gate`. Hosted runs also record, and the nightly uploads its file as the `perf-observations` artifact; folding a hosted run in requires downloading that artifact, since a hosted runner's numbers can only be authored from a hosted run.

## What gets committed, and what does not

**Every gate run writes a file here; most of them should never be committed.** A run is worth keeping only when something else refers to it:

- it is an **input to an anchor refresh** (`scripts/perf-anchor.sh` folds several runs at their per-key median, and the block should be traceable to the runs it came from), or
- it is a **checkpoint measurement** a doc cites.

An ad-hoc run from an inner loop is neither. It stays untracked and goes away with `git clean -f perf-results` — expected, not a mess to tidy up.

**The bound is SELF-MAINTAINING, not merely enforced.** The record prunes itself to 30 runs per environment as it writes (`PerfObservations.pruneToRetentionBound`), counting only committed runs and skipping any run a document cites — so the tree can never cross the horizon in the first place, and a citation the prune would break survives its turn in the queue. That belongs in the writer because the guard cannot see the breach it guards: a perf lane runs alone and therefore last, its record is written by that run, and the record is committed *after* the session's estate check, so the run that crosses the horizon shipped red by construction (2026-08-14, `darwin-aarch64` at 31). `PerformanceRecordShapeGuardTest` remains as the backstop and fails when one environment carries more than 30 committed runs, which is about a month of nightlies and well past `perf-history.sh`'s default 8-run window. Beyond that horizon the durable references are the committed anchor and the checkpoints, not the raw runs of superseded revisions — so pruning the oldest files is the fix, and it loses nothing that is still cited. An uncommitted run is never pruned, because it is still awaiting the decision to keep it: when every committed run was cited, the oldest uncited run was the newest one, and on 2026-10-21 a filtered diagnostic run deleted the full gate run written half an hour earlier. When every committed run is cited the prune therefore deletes nothing and instead prints a warning naming the oldest cited runs to retire, since committing the new run would carry the environment past 30 and fail the guard; retire them by hand, and their citations keep the run id as a plain code span. The cap exists because an unbounded committed tree is how a text record turns into a liability that someone eventually deletes wholesale.
