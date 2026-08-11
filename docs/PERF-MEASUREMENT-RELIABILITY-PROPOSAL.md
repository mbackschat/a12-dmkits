# Proposal — flag performance regressions, gate on beating the kernel

> **Status: proposal. Revision 3 (2026-08-11).** Revisions 1 and 2 designed a statistically elaborate gate. The owner then narrowed the requirement to *"flag performance regressions and anything where the kernel is better, including scaling"*, and measurement shows that requirement needs **no statistics at all**. Revision 3 is therefore much smaller than revision 2, and the machinery revision 2 accumulated is preserved only as a **contingency with a trigger** (§5). One part is **already built** rather than proposed: the committed record and its table view (§2.3).
>
> **The requirement.** (1) **Gate:** the interpreter must be faster than both kernel strategies, and must not scale worse. (2) **Flag:** performance regressions are reported, not failed. (3) The nightly must never fail on noise.
>
> **Scope.** The decision rule and measurement conditions of the routine performance gate (`:adapter:perfGateTest`, `scripts/perf.sh gate`, the nightly workflow). **Not** in scope: the NFR certification protocol and its ratified numbers (IF137), the benchmark catalog's content, the correctness differential, the local developer loop.

## 1. Why this is easy, and why the current gate is nonetheless failing

The interpreter and both kernel strategies execute **in the same JVM, on the same vCPUs, in the same run**. A comparison between them is dimensionless: machine speed, VM generation, JDK patch level, co-tenant interference and thermal state divide out, because both engines experience them. Measured on three sequential hosted runs of one commit:

| comparison | median | worst run-to-run noise | distance to the `1.0` boundary |
|---|---|---|---|
| level, `interp/groovy` | 0.12–0.14 | 2.16× | 5–8× |
| level, `interp/java` | 0.20–0.22 | 1.78× | 5× |
| **per-unit cost**, `interp/groovy` | 0.150 / 0.073 | **1.10×** | 6.7× / 13.7× |
| **per-unit cost**, `interp/java` | 0.145 / 0.059 | 1.23× / **1.41×** | 6.9× / 17.0× |

At the tightest row the margin is `6.7×` against worst-case noise of `1.41×`: a **4.7× safety factor**. The per-unit-cost ratio is *steadier* than either absolute (`1.10×` against `1.24×`), which is the same-vCPU effect made visible. **Both gates the owner asked for are decidable with the crudest possible bar, on any machine, at current sampling.**

**So what has been failing the nightly is neither of the owner's requirements.** Every false red came from an **absolute growth floor** — a single-engine shape bar with no kernel in it: `MSC-multi-star-correlation` at `9.11×` and `COLD first-call` at `9.27×` against a `9.0×` floor. Those bars sit a few percent from their trip line while the comparative bars sit 470 % from theirs. Replacing them is the entire fix.

## 2. Design

### 2.1 Two gates, and they are the only things that fail the build

Both are per **row × scale × kernel strategy**, decided from medians measured in one run, one JVM. No aggregation across rows, because the invariant is universally quantified: one row losing is a loss.

| gate | quantity | boundary | measured margin |
|---|---|---|---|
| **the kernel is not faster** | `interp` median vs each strategy's median | ratio `< 1.0` | 5–8× |
| **the kernel does not scale better** | `interp` **per-unit cost** `(t_large − t_small)/(n_large − n_small)` vs each strategy's | ratio `< 1.0` | 6.7–17× |

Per-unit cost, not growth factor, is the cross-engine scaling quantity, and that is already settled: over a 4× step a linear engine's measured growth factor is `4 − 3f` in the fixed cost's share `f`, so a growth-factor comparison ranks engines by fixed-cost share and the leanest engine loses deterministically (IF279).

**An early-warning band, not a tighter gate.** Because the gates fire only at parity, they say nothing until a catastrophe. So each gate additionally **flags** when its ratio exceeds `0.5`, which is still 2× from failing and far outside the `1.41×` noise. Erosion gets noticed long before a red build.

### 2.2 Everything else is a flag

Flags report in the job summary and never change the build's status, so their false-positive cost is attention rather than a red pipeline.

| flag | source |
|---|---|
| regression against the previous release, and against a pinned performance epoch | paired same-run comparison in alternating isolated forks |
| growth-exponent drift per lane | the existing paired N/4N estimator, reported as a number instead of a bar |
| absolute-time drift per lane | the committed anchor, **on the fixed local machine only** |
| margin erosion on either gate | §2.1's `0.5` band |
| environment quality | steal time, cgroup throttling, GC and JIT activity in the window, recorded as fields |

The hygiene signals are **recorded, never used to reject a sample**. Nothing is being gated on a near-boundary quantity, so no rejection predicate is needed, which also removes the laundering risk that a rejection rule creates: an allocation regression causes more GC, so excluding GC windows would censor exactly the evidence of the regression.

### 2.3 The local run on the fixed machine is the recording instrument

Absolute time only means something where the machine is fixed: `0.92×–1.36×` across 36 observations locally, against whole-run levels of `1.00 / 1.58 / 1.94` on hosted. So **regression-against-history is a local measurement**, run on demand by whoever is at the machine, and it is what makes drift detectable at all.

**Built 2026-08-11, ahead of the rest of this proposal, because it is useful on its own and blocks nothing.** Each gate run writes one JSON-Lines file to the **committed** [`perf-results/<environment>/<utc-timestamp>.jsonl`](../perf-results/README.md), and [`scripts/perf-history.sh`](../scripts/perf-history.sh) renders it as a text table, newest runs as columns, one table per runner class so environments are never mixed. [`scripts/perf-anchor.sh`](../scripts/perf-anchor.sh) consumes the same files unchanged, folding any number of them at their per-key median.

**Every run records the conditions it ran under**, which is what makes a historic row interpretable rather than merely numeric: environment key, commit read straight from `.git`, os/arch/JDK, processor count, CI run id, and the **system load average sampled per observation**. The first local run recorded a load average of 2.54 on an 8-core machine, so the concern is not hypothetical.

**It replaced a record under `build/`**, which `clean` deleted, so a run could be held against one committed anchor but never against its own history. That class of mistake is now refused by `SurvivingDataNotInBuildGuardTest` (CLAUDE.md: never store surviving data under `build/`).

**Two consequences worth stating.** This makes the **hosted** anchor block unnecessary rather than merely blocked, since absolute comparison belongs on the machine where it is valid; if this proposal is accepted, IG120's remaining item is *dropped*, not completed. And the series must be segmented on JDK, OS and hardware identity when read, because a platform change re-bases every number in it and would otherwise read as a regression.

**What is still proposal, not built:** the trend/change-point detection over this series (§3.5), and everything that depends on the owner decisions in §1.

### 2.4 Two prerequisites, both cheap and independent

- **Record every observation before evaluating any threshold**, with a status flag (`accepted` / `breached`). Today a breached lane throws before recording, so the worst observations are exactly the ones missing from the series: a trend flag built on that file is biased toward health. The anchor refresh keeps consuming only `accepted` records, preserving its anti-ratchet property.
- **Retire the absolute growth floors as gates.** They become §2.2 flags. This is what stops the false reds, and it is a deletion rather than a mechanism.

### 2.5 What this deliberately does not do

- **A shared slowdown that hits both engines equally is invisible to a ratio.** Our code cannot slow the kernel, so this can only come from a shared dependency (JDK, platform, hardware), which is not our regression and which the §2.2 flags surface.
- **A small self-regression does not fail the build.** That is the owner's decision: it is flagged. §5 says what would have to change if it ever needed to be a gate.
- **`COLD first-call` loses its growth bar.** Its unique coverage is the super-linear index build the warm harness structurally cannot see ([INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) optimization #7). If that needs a *gate* rather than a flag, the honest instrument is a **deterministic work counter** over the index build on the existing seams (`PreparationObserver`/`PreparationWork`, the eval-time `CacheObserver`), which is a pure function of the input and cannot false-positive. Timing a single cold call and dividing by another single cold call cannot be made reliable, which is why that lane failed on the two cleanest runs of five.

## 3. The measurements this rests on, and what each does not establish

Same commit (`1845f8af`), `ubuntu-latest`, `processors=2`. Dated evidence: [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) "Checkpoint 2026-08-11".

- **The comparative margins** of §1, from three sequential runs. *Limit:* 6 rows, and only 2 scenarios carry two scales, so the per-unit-cost figures rest on 2 scenarios × 3 runs. The margins are large enough that this is not a close call, but the number of rows is small.
- **Absolute times are unusable as a hosted gate at the current stratification.** Whole-run levels `1.00 / 1.58 / 1.94`; ten of 36 observations moved more than `1.8× + 1 ms`; the same 36 span `0.92×–1.36×` on the fixed machine. *Limit:* this does not show that **no** cloud absolute bar is possible, only that no tight one is, unstratified. And the "divide out the level and the exceedance vanishes" demonstration is arithmetic, not evidence about the level's cause.
- **A growth floor straddles its own floor on one commit.** `COLD`: `4.13×, 6.46×, 6.74×, 8.39×, 9.27×` against `9.0×`; `5.05×` locally. The two sequential runs produced the two worst numbers. *Limit:* "contention inflated the small-N end" is a **hypothesis**; n=2 against n=3 with dispatch mode confounded with time.
- **A breached lane destroys its own evidence** (34 of 36 observations), which §2.3 fixes.
- **Statistic choice is contingent, not settled.** At `warmup=5, iters=5` a cross-engine ratio is not steadier than an absolute (`1.15×`/`2.16×` against `1.24×`/`2.08×`) and `min`-of-N is worse than the median (`1.55×`). Neither result generalizes above that sampling, and neither matters for §2.1, whose margins swamp both.

## 4. Excluded

- **A tight absolute bar on hosted runners** at the current stratification. The anchor mechanism stays, on the fixed machine, as a flag.
- **Normalizing by another interpreter scenario.** It divides out a genuine uniform slowdown. A reference that does not move when we regress (the kernel, the previous release, a validated canary) is not covered by this exclusion.
- **Raising the `9.0×` floor.** It is being retired as a gate instead, which is strictly better than widening it.
- **A statistical gate on a near-parity quantity.** Not needed at a 4.7× safety factor. See §5.

## 5. Contingency: what changes if a row ever approaches parity

The gates in §2.1 are cheap **because** the margin is 5× to 17×. If any row's ratio climbs past the `0.5` warning band, that row stops being a crude comparison and becomes a near-boundary decision, and then the following applies **to that row only**. It is recorded here so it does not have to be rediscovered, and it is deliberately not built now.

- **Boundaries differ by comparator.** Cross-engine: boundary `1.0`, pass when the upper bound is below it, fail when the lower bound reaches it. A **self**-comparison cannot use `1.0`: at a true ratio of `1.00` the interval always straddles it, so the verdict is permanently inconclusive. It needs `1 + δ` with `δ` above the interval half-width (measured: 1.9 % at `σ=0.02, n=24`; 3.9 % at `σ=0.04, n=24`), plus a pinned epoch carrying the cumulative bound so per-release margins cannot ratchet.
- **Power is specified at an alternative beyond the boundary**, e.g. `1 + δ + 5 %`, and reported rather than promised.
- **Multiplicity:** Holm within the night at a per-night familywise budget of `0.01/30 = 3.33e-4` for a 0.99 clean 30-night period (exact: `3.350e-4`). Holm needs no independence assumption, which matters because our lanes share a whole-run level. Family size costs almost nothing: widening from 36 to 200 hypotheses moves the sample requirement about 13 %.
- **Sample size is quadratic in the within-fork paired `σ`** and only logarithmic in `α`: `σ=0.02` needs ~6 forks per cell, `σ=0.04` ~24, `σ=0.06` ~53. A pilot's single deliverable is that `σ` and its distribution across cells.
- **Adaptive sampling requires alpha spending** (Lan-DeMets O'Brien-Fleming). "Add forks while the interval crosses the boundary" is optional stopping and inflates the error budget being controlled. *An attempt to measure the inflation failed:* at nominal `α=3.3e-6`, 200 000 simulated trials expect 0.67 events and produced 1, so no magnitude can be claimed from it. The mechanism is sufficient reason on its own.
- **The replication unit is the fork**, not the inner iteration; iterations inside one JVM share host, JIT, GC and ordering state. Engine order must be counterbalanced across forks, warmup equalized by state rather than by iteration count, and each timed region must consume a correctness checksum outside the clock with data-dependent inputs.

## 6. Refuted, so it is not re-proposed

| idea | status | basis |
|---|---|---|
| an absolute growth floor is a usable gate on a shared runner | **retired.** A few percent of margin against 2× noise; both nightly false reds came from here | §3, §1 |
| gate the cross-engine invariant on an aggregate | **wrong in kind.** A universally quantified invariant cannot be aggregated | review finding 3 |
| a growth **factor** compares engines fairly | **false.** `4 − 3f` ranks by fixed-cost share; the leanest engine loses | IF279 |
| CPU clock removes contention | **false as stated.** Cache and bandwidth interference inflate cycles, which thread CPU counts | §3 |
| GC/JIT activity is a valid sample-rejection predicate | **no.** Post-treatment: an allocation regression causes GC, so rejecting GC windows censors the evidence | review finding 4 |
| `δ=0` for a self-comparison | **retracted by its author.** Permanently inconclusive at a true ratio of 1.00 | §5, measured half-widths |
| "add forks while the interval crosses the boundary" | **retracted by its author.** Optional stopping without calibration | §5 |
| a cross-engine ratio is inherently steadier | **contingent, currently false** at `warmup=5, iters=5`; irrelevant to §2.1's margins | §3 |
| `min`-of-N is the least-polluted estimator | **contingent, currently false** at `N=5`; legitimate at large fixed `N` | §3 |
| retry-and-take-the-median is no defense | **overstated.** `3q²−2q³` under independence, a 556× reduction at `q=6e-4`; the real objection is same-VM correlation | verified arithmetic |
| a fresh-runner confirmation squares the false-alarm rate | **not automatic.** Runners share image, provider epoch, CPU class, temporal load | review finding 14 |
| concurrent dispatch caused the level spread | **hypothesis.** Monotone with wall time, but n=2 vs n=3, confounded with time and host | "Checkpoint 2026-08-11" |
| a fixed runner solves the problem | **repeatability, not portability.** It cannot see an architecture-specific regression | review finding 30 |
