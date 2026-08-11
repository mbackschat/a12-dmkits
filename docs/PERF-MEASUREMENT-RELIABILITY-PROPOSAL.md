# Proposal — making the performance gate both sensitive and false-positive-free

> **Status: proposal, nothing implemented. Revision 2 (2026-08-11), rewritten after an external adversarial review returned 41 findings.** Revision 1 claimed to reconcile the two standing requirements and did not: it moved the uniform-regression class to a non-failing detector while asserting the #1 rule was enforced, and it phrased the cross-engine invariant as an aggregate when that invariant is universally quantified. Both are corrected below, and the review's other accepted findings are folded in where they land. What revision 1 got right is kept: the evidence leads, and the refuted table stays so nobody re-proposes what the data killed.
>
> **The requirements this must satisfy.** (1) The interpreter must **always** be faster than both kernel strategies and cost no more per unit; a repeatable loss is an active defect ([INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) "Standing development acceptance rule"). (2) The nightly gate must **never** fail on noise.
>
> **Scope.** The decision rule and measurement conditions of the routine performance gate (`:adapter:perfGateTest`, `scripts/perf.sh gate`, the nightly workflow). **Not** in scope: the NFR certification protocol and its ratified numbers (IF137), the benchmark catalog's content, the correctness differential, the local developer loop.

## 1. The two requirements cannot both be absolute, and saying so is part of the design

For a true ratio `r = interp/kernel` arbitrarily close to `1`, no finite test can have both zero false-failure probability and reliable detection. Any design that claims otherwise is hiding the trade rather than making it. So the first deliverable is not a mechanism, it is three declared numbers:

| quantity | what it is | who sets it |
|---|---|---|
| **indifference margin `δ`** | the smallest loss worth failing a build over, per invariant | owner |
| **familywise false-red target** | probability of zero false reds over a month of nightly runs | owner |
| **power at `δ`** | probability of catching a real regression of size `δ` | derived, then reported |

Until those exist, "sensitive" and "quiet" are adjectives, and every threshold argument is unfalsifiable. The decision rule then has **three** outcomes, and a loss smaller than `δ` is reported rather than failed.

**Corrected arithmetic for the familywise target.** With `k` gating decisions per night over 30 nights, `P(no false red) = (1-p)^(30k)`. Revision 1 said a clean month needs `p` below about `6e-4`; that is the **50 % break-even** (`0.5230` at `k=36`), not a clean month. Verified targets at `k=36`:

| target `P(no false red per month)` | exact per-decision `p` | Bonferroni bound |
|---|---|---|
| 0.95 | `4.75e-5` | `4.63e-5` |
| 0.99 | `9.31e-6` | `9.26e-6` |

**And `k=36` is itself wrong.** Revision 1 counted observations as decisions. The real denominator must be enumerated from the implementation and includes, at minimum, 18 growth decisions, 18 anchor decisions where armed, every cross-engine row × engine comparison (≥12 today), and every per-unit-cost decision. **Independence is also falsified by our own data:** all lanes share the same whole-run level (§2a), so decision errors are dependent. Use a method that does not assume independence (Bonferroni, or an empirically estimated joint null from repeated unchanged-commit runs).

## 2. The measurements, and what each does *not* establish

Same commit (`1845f8af`), `ubuntu-latest`, `processors=2`, 36 anchor observations per run. Dated evidence: [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) "Checkpoint 2026-08-11".

**(a) Absolute times are unusable as a hosted gate at the current stratification.** Three runs' whole-run levels: `1.00 / 1.58 / 1.94`. Ten of 36 observations moved more than `1.8× + 1 ms` between two of them. The same 36 observations span `0.92×–1.36×` on the fixed local machine.
*Does not establish:* that **no** cloud absolute bar is possible. A deliberately broad catastrophe anchor, or one stratified by hardware fingerprint, is untested. Revision 1's blanket exclusion overreached. *Also circular where revision 1 leaned on it:* dividing each run by a level computed from those same 36 observations removes the common component **mechanically**, so "0 of 36 after removing the level" cannot distinguish an environmental level from a genuine uniform interpreter change. It bounds neither.

**(b) A growth ratio straddles its own floor on one commit.** `COLD first-call`: `4.13×, 6.46×, 6.74×, 8.39×, 9.27×` across five cloud runs against a `9.0×` floor; `5.05×` locally. The two sequential runs produced the two worst numbers and one failed; the three concurrent runs passed.
*Does not establish* the mechanism. "Contention inflated the small-N end" is a **hypothesis**; host CPU class, classload state, JIT state, and provider load fit the same data, and the comparison is n=2 against n=3 with dispatch mode confounded with time. Settling it needs the separate N and 4N times retained plus randomized co-tenant load.

**(c) A breached lane destroys its own evidence.** The floor throws before the lane records, so a failing run yielded 34 of 36 observations. This is **missing-not-at-random censoring**: the worst observations are exactly the ones absent, so any trend baseline built from these files is biased toward health. Revision 1 noted the symptom (an anchor cannot be refreshed) and missed the consequence.

**(d) At current sampling a cross-engine ratio is not steadier than an absolute, and `min` is worse than the median.** Three sequential runs, the 6 rows present in all three:

| quantity | median spread | worst |
|---|---|---|
| `interp` absolute | 1.24× | 2.08× |
| `interp/groovy` | 1.15× | 2.16× |
| `interp/java` | 1.35× | 1.78× |
| `min`-of-N in place of the median | 1.55× | 1.93× |

*Contingent, not general:* both results hold **at `warmup=5, iters=5`** and say nothing about a ratio or a minimum at the sampling item 3.4 would establish. `min` at `N=5` is an extreme order statistic; it is a legitimate best-case estimand at large fixed `N`.

**(e) The tail lives in the short regions.** Both cases above `2×` are interpreter regions under 21 ms; nothing at or above 30 ms exceeds `1.30×`.
*Does not establish causation:* duration is confounded with scenario, n=6 per bucket. Randomized batch sizes **within the same rows** would settle it.

**(f) Replication unit.** Five timed iterations inside one JVM share host, JIT, GC, code-cache and ordering state. They are **not** independent samples, so any interval computed over inner iterations understates uncertainty. The independent unit for cloud uncertainty is at least the **fork**, often the runner.

## 3. Design

Ordered so that no item's work is invalidated by a later one. This ordering is the review's, not revision 1's, and the argument is decisive: calibrating sampling on cloud runners and then moving to fixed hardware discards the calibration.

### 3.1 (Required, first) Declare the estimand, the margins, and the family

§1's three numbers, plus, per invariant: the null hypothesis, the direction, the confidence level, and the explicit pass / inconclusive / fail regions. "Fail on the conservative bound" is not a decision rule until the direction is fixed: failing when the interval's **lower** bound reaches `1+δ` avoids false reds and leaves near-threshold losses inconclusive; failing when the **upper** bound reaches `1` manufactures them near equality. Also enumerate the family (§1) and pick the dependence-robust correction.

### 3.2 (Required, second) Stop censoring the evidence

Every observation is recorded **before** any assertion, with a status flag (`accepted` / `breached` / `inconclusive`). The anchor refresh consumes only `accepted` records, which preserves the existing anti-ratchet property, while the trend series consumes all of them. Without this, §3.5's baseline is built from a biased sample and no amount of later statistics repairs it.

### 3.3 (Required, third) Decide the runner, or time-box the experiment

Either a **self-hosted runner on the fixed machine**, or cloud with no absolute bar. Basis: §2(a), plus §2(d) showing 2× worst-case even when cloud runs are strictly sequential. A fixed machine also unlocks the OS controls cloud does not expose (governor, turbo, SMT, `taskset`, THP policy).

**Two costs, both stated.** It is a machine that must exist, stay online, be trusted with repository workflows, and it serializes the perf lane. **And it buys repeatability, not portability:** a stable Apple Silicon runner cannot see an x86-specific JIT or code-generation regression, so if x86 is a shipping target the gate needs coverage on both architectures, which is a second decision rather than a detail.

### 3.4 (Required, fourth) Sampling and blocking, then select the statistic on held-out data

Raise sampling so timed regions are tens of milliseconds, replicate at the **fork** level (§2f), **counterbalance engine order across forks** (in one process, an earlier engine changes code cache, heap occupancy, compiler queues and thermal state for a later one), and **equalize warmup per engine by state rather than by iteration count** — a fixed `warmup=5` compares engines at different compilation tiers, which is unfair in the direction the #1 rule cares about.

Then select the decision statistic by **train, freeze, evaluate on held-out runs**, scoring A/A false-alarm rate and power against injected regressions of size `δ`. Revision 1 proposed choosing it from the same data used to compare candidates, which is selection bias with no power measurement.

**Two exemptions.** `COLD first-call` must **not** be batched into a long region: repeating a first call stops it being a first call. Cold measurement belongs to the existing fresh-process protocol (one sample per fresh JVM, many processes), not to this item. And every timed region must consume a correctness checksum **outside** the clock with data-dependent inputs, so that JIT elimination or an unintended cache cannot quietly reduce the measured work.

### 3.5 (Required, fifth) One gate per invariant, and honest coverage for the uniform class

Revision 1's central defect: a uniform 20 % interpreter slowdown that still beats both kernels passes the cross-engine gate, leaves every growth exponent unchanged, and would only be *reported* by a trend detector. That is the class the anchor existed for, and the project's rule calls a repeatable per-unit loss a defect. So:

| invariant | gate | why this instrument |
|---|---|---|
| **faster than both kernels, per row** | per-row paired comparison, decided per §3.1, confirmed on a fresh runner before failing | The invariant is universally quantified over scenarios and scales, so it **cannot** be aggregated. Revision 1's "gate on aggregates" was simply wrong here: one row at `1.2` among five at `0.5` passes an aggregate while the invariant is false. Aggregation applies to *detection*, never to a universally quantified claim. Every row × engine × scale therefore enters the §1 family. |
| **no uniform regression against ourselves** | **paired A/B against the previous release** in alternating isolated forks on the same runner, same block | A reference that does not move when we regress, unlike a self-normalizer. This is the gate revision 1 lacked. Alternating isolated forks rather than two classloaders in one JVM, for state separation. |
| **shape / complexity** | exponent of a log-log fit over ≥4 scales, failing when the interval's lower bound exceeds the bar | A two-point ratio is the least reliable estimator of shape and is where the failures are. Today's `9.0×` floor is exactly exponent `1.585`. **Poor fit must escalate, never pass:** a cliff at the largest scale produces a bad fit *because* the regression is severe, so `inconclusive` there requires an explicit largest-scale check. |
| **resource cost** | process- or cgroup-level CPU and allocation, not measuring-thread CPU | Work shifted to compiler, GC or helper threads makes thread CPU **fall** while true cost rises. The "cost no more per unit" invariant cannot be enforced on a thread clock. |

Trend detection over the accumulated series remains, as **reporting only**, for drifts below `δ`. It needs its own change-point and multiplicity policy and must be segmented by environment identity (commit, JDK, runner image, hardware fingerprint), or it will confound platform changes with code regressions.

### 3.6 (Required, alongside) Hygiene that cannot launder a regression

`inconclusive` is the most dangerous idea in this proposal, because a predicate that swallows samples turns requirement 1 off while the board stays green. Four rules, all from the review:

- **Exogenous predicates only.** Hypervisor steal time and cgroup throttling are external to our code. **GC and JIT activity are post-treatment variables:** an allocation regression *causes* more GC, so excluding GC windows censors exactly the evidence of the regression. Record and model them; never exclude on them.
- **Predeclared, never tuned after seeing values.** Reject on metadata, never on the value.
- **Quorum.** A verdict requires the power-derived `n_min` accepted samples **and** at least 80 % of scheduled samples accepted.
- **Inconclusive is not a resting state.** Two consecutive nights, or three of seven, on the same lane produces a distinct non-green `HARNESS_UNHEALTHY` result, and scheduled / accepted / rejected counts are published by reason. If no infrastructure-red is permitted at all, nothing prevents permanent laundering.

Note the ceiling on this item: **steal time can read zero while co-tenant cache and memory-bandwidth contention still slows scheduled execution.** These signals are coarse, so they reduce false reds without bounding them.

## 4. Optional, and excluded

**Optional, none load-bearing:** deterministic work counters on the existing seams (`PreparationObserver`/`PreparationWork`, the eval-time `CacheObserver`) as a zero-noise complexity oracle, which should be load-bearing for *complexity* invariants but never presented as a latency oracle; `getThreadAllocatedBytes` per operation, scoped narrowly since it misses helper-thread, native and retained-heap growth; measurement-JVM flags (`-Xms == -Xmx`, `AlwaysPreTouch`, `CICompilerCount=1`, `-Xbatch`, SerialGC or Epsilon, `PerfDisableSharedMem`, AppCDS for cold) with the caveat that each changes the system under measurement, so rankings and sensitivity must be compared under production flags too; hardware counters on a self-hosted runner as secondary evidence; JMH for one representative row as a **secondary oracle** rather than a migration.

**Excluded, with reasons:** a **tight** absolute bar on cloud runners at the current stratification (§2a, and the exclusion is now scoped to "tight" and "current stratification"); normalizing by another **interpreter** scenario, since it divides out a genuine uniform slowdown, though a reference that does not move when we regress (kernel, previous release, a validated canary) is not covered; raising the `9.0×` floor, which buys blindness and leaves the estimator untouched.

## 5. Open questions

1. **The three numbers of §1** (`δ` per invariant, familywise target, power). Nothing downstream is well-posed without them.
2. **Runner (§3.3),** including whether x86 coverage is required, which makes it two runners rather than one.
3. **Budget and power.** 18 lanes × ≥4 scales × fork-level replication × 3 engines × true cold processes × fresh-runner confirmation may exceed the 60-minute nightly budget. A power calculation and a timed dry run are needed before the matrix is fixed; no such calculation exists yet.
4. **Does `COLD first-call` keep a growth bar?** Its unique coverage is the super-linear index build the warm harness structurally cannot see ([INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) optimization #7). A deterministic work counter over the index build may be the honest replacement for a ratio of two cold singletons.
5. **Where the series lives** (§3.5): in-repo, artifact retention, or an external store.
6. **Is a canary worth it at all?** It must be validated on held-out runs to track the disturbance without tracking our own regression; "independent of the interpreter" is necessary and not sufficient.

## 6. Refuted, so it is not re-proposed

| idea | status | basis |
|---|---|---|
| CPU clock removes contention | **false as stated.** Cache and bandwidth interference inflate cycles, which thread CPU counts; frequency and SMT effects too. Our runs do not identify contention as *the* cause | §2(a), §2(d) |
| gate the cross-engine invariant on an aggregate | **wrong in kind.** A universally quantified invariant cannot be aggregated | review finding 3 |
| trend detection covers the uniform class | **insufficient as a gate.** It reports; the rule calls a repeatable loss a defect | review finding 2 |
| a cross-engine ratio is inherently steadier | **contingent, currently false** at `warmup=5, iters=5`; open above that sampling | §2(d) |
| `min`-of-N is the least-polluted estimator | **contingent, currently false** at `N=5`; legitimate at large fixed `N` | §2(d) |
| retry-and-take-the-median is no defense | **overstated.** Under independent attempts it is `3q²−2q³`, a 556× reduction at `q=6e-4`; the real objection is that same-VM retries are correlated | verified arithmetic |
| a fresh-runner confirmation squares the false-alarm rate | **not automatic.** Runners share image, provider epoch, CPU class and temporal load | review finding 14 |
| concurrent dispatch caused the level spread | **hypothesis, not established.** Monotone with wall time, but n=2 vs n=3 and confounded with time and host allocation | "Checkpoint 2026-08-11" |
| a fixed runner solves the problem | **repeatability, not portability.** It cannot see an architecture-specific regression | review finding 30 |
