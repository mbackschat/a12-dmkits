# Proposal — making the performance gate both sensitive and false-positive-free

> **Status: proposal, nothing implemented.** It exists because the two standing requirements are currently in conflict: the interpreter must **always** be faster than both kernel strategies (the project's #1 performance rule, [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) "Standing development acceptance rule"), and the nightly gate must **never** fail on noise. Every red the gate has produced was a measurement artifact, and every widening of a threshold to stop that bought blindness to the regressions the gate exists to catch.
>
> **Scope.** The decision rule and the measurement conditions of the routine performance gate (`:adapter:perfGateTest`, `scripts/perf.sh gate`, the nightly workflow). **Not** in scope: the NFR certification protocol and its ratified numbers (IF137), the benchmark catalog's content, the correctness differential, and the local developer loop, which is fine as it is.

## 1. What is measured today, and what it costs

| mechanism | quantity | decision |
|---|---|---|
| 18 growth lanes | interleaved N/4N paired estimator, median of within-pair ratios, CPU clock (17 lanes) or wall clock (1, `COLD first-call`) | fail if the median exceeds a `9.0×` catastrophe floor, after re-measuring up to `GROWTH_RETRY_ATTEMPTS` and taking the median of attempts |
| cross-engine rows | `interp`, `groovy`, `java` medians in one JVM at `warmup=5, iters=5` | fail if the interpreter does not beat both, or costs more per unit (IF279) |
| absolute anchor | each growth lane's accepted median against a committed per-runner-class number | fail above `1.8× + 1 ms`; unarmed on CI, where no block exists (IG120) |

The growth floor is an exponent bar in disguise: over a 4× step a floor of `9.0×` is an exponent of `log 9 / log 4 = 1.585`, and linear is `4.0×`.

## 2. The measurements this proposal rests on

All of the following are the **same commit** (`1845f8af`) on `ubuntu-latest` (`processors=2`), 36 anchor observations per run. Full dated evidence: [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) "Checkpoint 2026-08-11".

**(a) Absolute times are unusable as a hosted gate.** Three runs' whole-run levels stood at `1.00 / 1.58 / 1.94`, and `10 of 36` observations moved more than the ratified `1.8× + 1 ms` tolerance between two of them. Dividing out the whole-run level takes that to `0 of 36` while leaving the multiplicative spread at `2.47×`, which identifies the term as a common-mode level rather than per-lane noise. The same 36 observations on the fixed local machine span `0.92×–1.36×`.

**(b) The growth ratio straddles its own floor on the same commit.** The `COLD first-call` lane, the one lane still on the wall clock, measured `4.13×, 6.46×, 6.74×, 8.39×, 9.27×` across five hosted runs against a `9.0×` floor, versus `5.05×` locally. The two **sequential** runs produced the two worst numbers and one failed the build, while the three **concurrent** runs all passed: contention inflates the small end of the ratio and therefore flatters it. A ratio whose failure probability *falls* under load is not measuring what its floor claims.

**(c) A failing lane also destroys the run's usefulness.** A breached growth floor throws before the lane records its observation, so the failing run produced 34 of 36 observations and `scripts/perf-anchor.sh` correctly refused it. Roughly half of hosted runs cannot author or refresh an anchor.

**(d) At current sampling, a cross-engine ratio is not more stable than an absolute, and `min` is worse than the median.** Three sequential runs, the 6 cross-engine rows present in all three:

| quantity | median spread | worst |
|---|---|---|
| `interp` absolute | 1.24× | 2.08× |
| `interp/groovy` | 1.15× | 2.16× |
| `interp/java` | 1.35× | 1.78× |
| `min`-of-N in place of the median | 1.55× | 1.93× |

A ratio cancels common-mode noise and amplifies independent noise; at `warmup=5, iters=5` the independent term dominates. `min` is an extreme order statistic whose variance is high at `N=5`.

**(e) The tail lives in the short measurements.** Of those 12 observations, both cases above `2×` are interpreter regions under 21 ms, and nothing at or above 30 ms exceeds `1.30×` (`under 30 ms`: median `1.24×`, worst `2.08×`; `30 ms and up`: median `1.18×`, worst `1.30×`). At n=6 per bucket this is suggestive on the tail, not established on the median.

**(f) The arithmetic of 36 nightly decisions.** 18 lanes × 2 scales is ~36 gating decisions per night. A month without a false red needs a per-decision false-alarm probability below about `6 × 10⁻⁴` (`1 − (1−p)^1080 ≈ 0.5` at that p). No hand-set threshold on a quantity with a `1.24×` median spread reaches that.

## 3. Design

Four items, in this order, because each earlier one changes what the later ones should be.

### 3.1 (Required) Sampling before statistic

**The contract.** Before any decision rule changes, the gate's sampling is raised until the interpreter's timed region is tens of milliseconds rather than 3 to 20, and §2(d)'s table is re-measured on three sequential hosted runs plus three local runs. The decision statistic (absolute, cross-engine ratio, `min`, trimmed mean) is then **selected from that measurement**, and the selected statistic is recorded with the measurement that chose it.

**Why this is first.** §2(d) refuted two statistic choices that were argued for on theory, in opposite directions. Sampling is the only lever that moves all of them at once, and the nightly currently spends 7 to 12 minutes of a 60-minute budget, so the headroom exists. This item deliberately produces no new bar.

**Falsifiable claim.** If the re-measured spreads do not improve materially with longer regions, §2(e) was an artifact of n=6 and the whole ordering below has to be reconsidered.

### 3.2 (Required) Sample hygiene, and a third outcome

**The contract.** Each timed observation carries metadata about the conditions it was taken under, and the decision has three outcomes rather than two:

| outcome | when | effect on the build |
|---|---|---|
| pass | the bar holds | green |
| **inconclusive** | the sample's hygiene metadata says the environment interfered | **green**, reported, not counted as evidence |
| fail | the bar is exceeded on a hygienic sample | red |

Hygiene signals, all cheap and already exposed by the platform: hypervisor **steal time** (`/proc/stat`), **cgroup throttling** (`cpu.stat` `nr_throttled` / `throttled_usec`), **GC** activity in the window (`GarbageCollectorMXBean` deltas), **JIT compilation** in the window (`CompilationMXBean.getTotalCompilationTime` delta), and the run's own **whole-run level** against its canary.

**The rule that makes this unbiased: reject on metadata, never on value.** Discarding a sample because it is slow biases the estimate. Discarding a sample because the cgroup throttled during it does not.

**Why this is independent of everything else.** It can only ever remove false reds, whichever statistic §3.1 selects, so it is the one item that cannot be invalidated by the open questions.

### 3.3 (Required) Decide the runner

**The choice.** Either run the gate on a **self-hosted runner on the fixed machine** already used to author the local anchor (`mac-mini-m1`), or keep it on hosted runners and accept that no absolute bar can be armed there.

**The basis.** §2(a): `0.92×–1.36×` on the fixed machine against a `1.94×` whole-run level and a `2.47×` spread on hosted, with §2(d) showing 2× worst-case even when hosted runs are strictly sequential. A fixed machine additionally makes the OS-level controls available that hosted runners do not expose: CPU governor, turbo, SMT, `taskset` pinning, THP defrag policy.

**The cost, stated.** A self-hosted runner is a machine that must exist, stay online, and be trusted with repository workflows; it also serializes the perf lane behind itself. This is the standard arrangement for projects whose performance is a release contract, and it is a decision rather than a technique.

### 3.4 (Required) Gate on aggregates, detect on trends

**The contract.** Per-lane thresholds stop being the gating mechanism. Instead:

- **The gate** fails only on the cross-engine invariant, which is the actual #1 rule: the interpreter must beat both kernel strategies and cost no more per unit. It fails on the **conservative bound** of a confidence interval over paired samples, so that noise widens the interval and makes failure **less** likely, rather than inflating a point estimate and making it more likely. A failure additionally requires a **confirmation run on a fresh runner**, because the dominant noise term is per-run and cannot be averaged away inside one VM.
- **Detection** of the uniform and constant-factor regressions that no per-run bar can see moves to a **trend over the accumulated observation series** (the `perf-observations.jsonl` the nightly already uploads), keyed by `(lane, fingerprint, N, engine, clock, runner class)`. It **reports**, and never fails a build.
- **Shape** regressions are decided over ≥4 scales by the exponent of a log-log fit with a confidence interval, failing only when the interval's lower bound exceeds the bar, and reporting **inconclusive** when the fit is poor. Today's `9.0×` floor translates directly to an exponent bar of `1.585`.

**Why.** §2(f): with 36 independent decisions a night, per-lane gating cannot be made quiet by tuning. §2(b): a two-point ratio is the least reliable available estimator of shape, and it is where the failures are.

## 4. Required, optional, excluded

**Required:** §3.1 through §3.4.

**Optional, each independently useful and none load-bearing:**
- **Deterministic work counters** as a zero-noise shape oracle, on the seams that already exist (`PreparationObserver` / `PreparationWork`, the eval-time `CacheObserver`). A count is a pure function of the input, so it cannot false-positive; it cannot see constant-factor regressions.
- **`ThreadMXBean.getThreadAllocatedBytes`** per operation, a near-deterministic regression signal requiring no product instrumentation.
- **Measurement-JVM flags** for reproducibility: `-Xms == -Xmx`, `-XX:+AlwaysPreTouch`, `-XX:CICompilerCount=1` and `-Xbatch` on a 2-core runner, `-XX:+UseSerialGC` or Epsilon, `-XX:+PerfDisableSharedMem`, AppCDS for the cold lane.
- **Fork per scenario**, against cross-scenario profile pollution: measuring several scenarios in one JVM can leave a call site megamorphic and slow a later scenario. This is a correctness concern about the harness, not only a noise concern.

**Excluded, with reasons:**
- **Arming an absolute anchor on hosted runners.** §2(a). The mechanism stays for the fixed machine.
- **Normalizing by another interpreter scenario.** It divides out a genuine uniform slowdown, which is the blindness the bar exists to remove. A reference that does not move when we regress (the kernel, a synthetic canary) is not covered by this exclusion.
- **Raising the `9.0×` floor.** Every widening buys blindness and leaves the estimator untouched.
- **Migrating to JMH now.** Its techniques are adopted above; a harness migration is a separate decision and would not be settled by this evidence.

## 5. Open questions

1. **Runner (§3.3).** Self-hosted on the fixed machine, or hosted with no absolute bar? This is the only item that is a policy decision rather than a measurement.
2. **What sampling is enough (§3.1)?** The target of "tens of milliseconds per region" is derived from n=6 and should be set by the re-measurement, not by this doc.
3. **Does the `COLD first-call` lane keep a growth bar at all?** Its ratio divides two cold singletons. Its unique coverage is the super-linear index build that the warm harness structurally cannot see ([INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) optimization #7), so dropping the bar loses real coverage. A deterministic work counter over the index build may be the honest replacement.
4. **Where does the accumulated series live** (§3.4): committed in-repo, a workflow artifact retention window, or an external store?

## 6. What was already refuted, so it is not re-proposed

| idea | why it fails | evidence |
|---|---|---|
| CPU clock removes contention | cache and bandwidth interference inflate cycles, which CPU time counts; it excludes the compiler threads' cost, not the interference | §2(a), §2(d) |
| cross-engine ratio is inherently steadier | true only against common-mode noise; at current sampling the independent term dominates and the ratio is worse | §2(d) |
| `min`-of-N is the least-polluted estimator | high variance as an extreme order statistic at `N=5` | §2(d) |
| retry-and-take-the-median defends against false positives | the dominant term is per-run, and retries stay inside the same VM | §2(a) |
| dispatching runs concurrently to save wall time | contends them against each other; the level tracked wall time monotonically | "Checkpoint 2026-08-11" |
