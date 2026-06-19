# PROBE-SPEC — proactive, deep DX analysis of the shipped product

The **probe** is a second experience instrument, distinct from the self-sufficiency eval ([`EVAL-SPEC.md`](EVAL-SPEC.md)). The eval asks whether the *self-describing CLI surface* lets a cold agent author and read rules (bare-tool, scored, pass@k). The probe asks a different question entirely:

> When a real agent uses the **shipped product** — the **native binary** + the **skill** — over a realistic **multi-prompt** session, how does it actually go? Where does it hit a **false success**, **friction**, or get **misled**?

This is the *method* doc — the fixed contract (why the instrument exists, the capture mechanism, the analysis dimensions, the proactive loop). The **how-to** (run steps, layout) lives in [`../probe/README.md`](../probe/README.md); the scenario **inventory** is the markdown under [`../probe/tasks/`](../probe/tasks/); the **findings** are the dated reports under `../probe/reports/`. Same split as `EVAL-SPEC` ↔ `eval/`.

## Why a second instrument — the blind spots the eval cannot see

The probe exists because every defect that has bitten the shipped product was structurally invisible to the eval. Each lived in a layer the eval doesn't exercise:

| defect that shipped | layer it lived in | why the eval missed it |
|---|---|---|
| v0.1.0 native reflection crash on date operators (`Today`/`YearFromDate`/`AddYears`) | the **native** image | the eval runs the freshly-built **JVM** CLI — it never executes the native binary |
| stale-binary + version-unaware plugin cache | the **delivery** layer (hook/cache) | the eval never installs or upgrades the plugin |
| `field modify` silently dropped metadata; typo'd spec keys parsed-and-ignored | a **multi-step** authoring flow | the eval is **single-shot** (one rule / field / edit) — no task builds a model over several prompts, and the metadata path was on no task |

A pattern runs through all three: the eval scores the **final artifact** (kernel-valid + gold-equivalent, pass@k) and discards the **trajectory** — yet the trajectory is where false successes, friction, and misconceptions are visible. The probe is built to read the trajectory.

## What the probe is — and is not

| | `eval/` | `probe/` |
|---|---|---|
| Goal | CLI self-sufficiency (does the surface teach the agent?) | shipped-product DX (how does a real session go?) |
| Binary | freshly-built **JVM** CLI | the **native** image (`cli/build/native/nativeCompile/dmtool`) |
| Tasks | single-shot (one rule/field/edit) | **multi-prompt** single sessions (a BA iterating) |
| Repetition | **pass@k** | **no k** — one **deep** session per task |
| Output | a score (kernel-valid + gold-equivalent) | a **diagnostic report** — correctness-vs-gold + trajectory mining |

It is **not** the eval: no k, no gold-equivalence scoring, no edits to `eval/`. It **complements** the eval.

### Scope boundary — the delivery layer is out

The probe runs the native binary **directly** (via the logging shim), not through the real `SessionStart` hook. So the hook/cache/download/version-pin layer — where defect #2 lived — is **out of scope here**. That layer needs its own small **delivery smoke** (an isolated install/upgrade simulation asserting *delivered-version == plugin-version*), tracked separately. The probe covers native + skill + multi-step authoring DX; it does not cover delivery.

## The keystone — objective trajectory capture

An agent will not self-report a false success it never noticed — so we don't ask it to. The capture is **objective and independent of the agent**:

```
       agent  ──calls──►  dmtool (shim on sandbox PATH)
                              │  tees: command + stdout + stderr + exit
                              ▼
                       dmtool-calls.log  ◄── the analysis reads THIS
                              │  then replays the real binary's output
                              ▼
                    cli/build/native/.../dmtool  (the shipped artifact)
```

`prep-probe.sh` installs a logging `dmtool` shim on the sandbox PATH that records every invocation's **command + stdout + stderr + exit code**, then execs the real native binary and replays its output (so `dmtool … | jq` still works). The analysis reads that log **plus the resulting model files** (plus the agent's narrative) — so a silently-dropped edit surfaces regardless of what the agent believed happened.

## Material boundary & the anti-cheat audit (a hard requirement)

A probe's findings are only trustworthy if the agent-under-observation stayed inside its **material boundary**. The boundary is exactly what a real shipped-product user has, and nothing else:

| the agent MAY use | the agent MUST NOT |
|---|---|
| the skill at `<sandbox>/SKILL.md` | read this repo (gold fixtures, source, docs, `eval/`, `examples/`) |
| the `dmtool` binary, via the shim | read the A12 Tools workspaces or the `a12-kernel*` checkouts |
| files it itself created under `<sandbox>/work` | read or write **any** path outside the sandbox |
| | hand-edit a **model file** (every model mutation must go through `dmtool` — the tool is what's under test) |

If the agent peeks at a committed gold model it can reproduce the answer without the tool teaching it anything (the trajectory becomes a copy, not a measurement); if it hand-edits the JSON it bypasses the very surface we're probing. Either contaminates the run. **The probe instruction tells the agent its knowledge must come only from the skill + the tool's self-description — but an instruction is not enforcement.** Enforcement is [`../probe/audit-probe.sh`](../probe/audit-probe.sh): a deterministic gate, run after the session and **before the findings are trusted**, that inspects the subagent's transcript + the shim log and fails loud (`VERDICT: SUSPECT`, exit 1) if the boundary was crossed. It checks three things:

1. **No forbidden material** — no repo / workspace / kernel path in any Bash command or file op.
2. **File ops confined to the sandbox** — every `Read`/`Write`/`Edit`/`Glob`/`Grep` path is under the sandbox.
3. **Tool-only mutations** — the model files (mined from the call log's `-m`/`-o`/`export`/`--reference`) were never a `Write`/`Edit` target, i.e. the agent never hand-authored a model.

The gate is itself verified to *fire* (a synthetic cheating transcript must yield `SUSPECT`, not just the real run yielding `CLEAN`) — a gate that only ever passes is worthless. Every report records the verdict; a `SUSPECT` run is discarded, not analyzed. This is the same "scripts for deterministic checks, agents for judgment" split the eval uses — and the same material-boundary discipline as the eval's clean-subagent rule.

## What "gold" is — the correctness anchor

**Gold = a task's answer key: a specification of what a *correct* end-state must enforce** (the fields, groups, rules — right polarity, right scope, right locale). It is the objective anchor that turns "the agent finished" into "the agent built the *right* thing," sitting *underneath* the qualitative trajectory mining — not a pass/fail score.

Gold comes in **two forms**, by how the task was authored:

| task origin | gold form | where it lives |
|---|---|---|
| **hand-built** (own domain, e.g. the `01`-family person/address) | **prose** — the task `.md`'s "Gold reference" section lists what must be true; there is **no** gold model file | `tasks/<n>.md` |
| **reverse-engineered** from a rich model (a committed fixture under `cli/src/test/resources/models/`, or a license-safe domain-transferred workspace model — own domain, **no tax/gov**) | **that source model** *is* the answer key | the referenced fixture |

Either way, gold is matched **semantically, not literally** (read → re-render → compare, like the eval judge): many valid expressions enforce one intent (`DifferenceInYears` vs an `AddYears` formulation for age; a field-list vs a group-scope quantifier; `[Age] < 18` stored-number vs a birth-date derivation). The bar is **"the agent's model enforces the same thing, at least as completely as the reference."** The kernel stays the validity oracle — gold says *what* should be enforced, the kernel says whether the agent's expression of it is *valid*.

Gold is **never shown to the probe agent** (it is on the far side of the material boundary, §above) — it is the reviewer's key, not the agent's input. The reverse-engineered form also makes the task backlog cheap: every rich fixture is a candidate scenario, covering the operator/structure surface that model already exercises.

## The analysis method — the deep report (no score)

From the objective shim log + the final model files (+ the agent's narrative), each report is structured around these dimensions:

1. **Correctness vs the gold** *(the objective anchor)* — re-read the final model and verify, semantically, that it enforces the same rules / fields / structure / includes as the gold (right polarity, right scope), at least as completely. This is what catches **false successes / silent drops** — a label the agent "set" that isn't there.
2. **Efficiency & friction** — a **quantified trajectory breakdown** the reader can audit, not a vibe. Every report carries: the headline (total invocations · distinct · agent tokens · wall-clock · nonzero exits · `apply`/`batch` *executed* vs merely *explored*); a **purpose-bucketed** call table (**orient/discover · author/mutate · verify**, with the per-verb histogram) that exposes how much was *undo/retry* vs net-new; an **attribution** mapping the failures + retries to their distinct causes (each a finding); and an **"efficient-run" baseline** (what the same task should take, e.g. "build each model in one `apply` ≈ 12–15 calls") so the gap is explicit. A long session is a finding — name where the calls went. The deterministic part (counts, buckets, failed-by-verb) is emitted by [`../probe/digest-probe.sh`](../probe/digest-probe.sh); the attribution to causes is the report's judgment.
3. **Misconceptions** — did the agent reason about constraints that don't exist (e.g. "JVM startup is slow" → over-batching, when the native image starts in <100 ms)?
4. **Diagnostic quality** — when it erred, did the diagnostic's `fix` text get it unstuck?
5. **Self-describing-surface gaps** — did it guess? Did `manifest` / `schema` / `operators` mislead it?
6. **Concrete tool-defect findings** — ranked, actionable, → root-cause fixes.

**Every finding is investigated to its root cause, never left at the symptom.** A symptom is "`rule add` printed a Java stack trace"; the root cause is "the kernel throws `IllegalArgumentException` on a duplicate element name and `RuleAddCommand` doesn't catch/map it to a diagnostic." The report names the root cause (ideally the `file:line`) and the fix it implies — a probe that only logs symptoms just relocates the bug report inside the repo. This mirrors the standing "find the root cause, fix at root" rule for bug reports.

A report that finds nothing states **"clean session — no DX defects on this task"** explicitly; that is itself a useful signal, not a non-result.

**Every report embeds the produced document model(s) verbatim (the agent's actual output JSON).** The models the agent built live only in the ephemeral sandbox — so the report captures them inline (a fenced ```json block per model) for **objective review**: the reviewer reads what was *actually* produced (locale, `conditionLanguage`, every rule's stored `errorCondition`, field metadata) against the gold, rather than trusting the agent's prose summary or a since-wiped sandbox. This is the same "objective over self-report" principle as the shim log — the produced artifact is evidence, so it ships with the finding. (When a re-run validates a fix, embed the *new* output so the before/after is visible in one place.)

## The proactive loop — discover → root-cause → generalize → fix the class → graduate into a guard

The probe's job is to find **unknowns**; deterministic guards enforce **knowns**. A finding isn't done when the one symptom is fixed — it's done when its whole *class* is fixed and can't regress:

```
probe discovers a symptom ──► find the root cause ──► GENERALIZE: sweep every sibling site of the same
                                                      class ──► fix them all at the shared root ──►
                                                      graduate into a deterministic guard
```

**Generalize before you fix.** Once the root cause is known, sweep for every place the same class can occur — never patch the single symptom. P1 (a raw stack trace from a duplicate rule name) was *not* "a `rule add` bug": the root cause was `RuleValidator.check` letting a kernel conversion exception escape, a path shared by **six** callers (`rule add`, `computation add`, `rule check`, `patterns`, `model eval`). So the fix went in the shared validator, and the guard tests cover a sibling (`computation add`), not just the reported verb — a symptom-only patch would have left five other verbs able to leak the same way. (This is the standing "root-cause generalization" rule in [`../CLAUDE.md`](../CLAUDE.md).)

The canonical first guard this feeds is the **"applied ⇒ effected" round-trip sweep**: a matrix test over every writable attribute (FieldSpec / rule / computation keys) that writes → reads back → asserts the effect actually landed — the deterministic complement that would have caught the `field modify` silent drop without a bug report.

## Recurring patterns across reports — what the probes have taught us

A single report captures one run; the *patterns* only show up across runs, so they live here (the method doc), refreshed as new reports land. Each was seen in **≥2 reports** and is the lens to read the next one through.

1. **The friction is structural *composition*, not rule authoring.** Where the surface is exercised most — polarity, operators, guarding, the lint, check-before-write — it works *well* (`01`'s "what worked well"; `01-de`'s age rule, right once the locale bug was gone). The thrash is always **model structure**: includes, mounts, group scope, extract, cross-group references. The skill and the eval both center rule authoring, which is *why* that side is strong and composition is under-supported — and why the probe (build-then-refactor, multi-turn) is the only instrument that sees it.

2. **Turn 2 (evolve an existing model) is the hotspot.** Turn 1 (build from scratch) is smooth; the friction concentrates in turn 2 — `01` P4 (extract leaves a stale name), `01-de` (mount-field break + cross-group refusal + workspace confusion). Refactor/reuse (`extract`/`include`/mounts/`move`) is the least-supported, highest-thrash operation, and it's the most common real BA task.

3. **Every deviation from the uniform shape is a thrash point — they're one class, not N bugs.** The agent learns `dmtool -m <model> <target> <op>` + the uniform result envelope, then trips on each exception: `export` (positional model, ignored `-m` — fixed), `workspace` (positional dir, ignored `-w` — Finding 5), `rule read` (reads one, no list affordance — fixed via read-unification), the `ok`/`valid` asymmetry (`01` P3). **Uniformity is the agent's only reliable prior**, so each exception costs calls — fix the *class* (audit for any verb that deviates from `-m`/`-w`/the envelope), not the instance.

4. **The dangerous defect is *silent* divergence; loud refusals are fine — even good.** Loud + correct (the cross-group `RK_OUTWARD_REFERENCE`, the safe-delete `where-used` gate, the unguarded-number lint) cost a few recovery calls but *worked* — not defects. The damage is always **silent**: the mount-field break (`field add` succeeds, the include silently stops resolving), `01` P4 (stale `modelInfo.name`), and the original motivating bugs (field-modify drop, value-shape coerce). The recurring high-value finding is "looks fine / reports success but diverged unflagged"; the fix pattern is always **detect → be loud (refuse or advise)**. Bonus: a silent divergence also *inflates orientation* (the agent defensively re-reads state it can't trust — 44% of `01-de`'s calls), so fixing one pays twice.

5. **"Success" hides thrash; a capable agent masks surface defects.** Both `01` and `01-de` produced kernel-valid, gold-matching models — the **eval would score both PASS** — yet `01` leaked a stack trace and `01-de` burned ~50 recovery calls. The better the model, the more a surface defect hides as *extra calls* rather than a failure. So the metric is **not "did it finish"** (it will) but **"how much thrash did the surface force"** — which is why the quantified efficiency breakdown (§2) is a report convention. *(Harness idea: a deliberate weaker-model run would fail where a strong one merely thrashes, surfacing what gets papered over.)*

**Where these route:** composition/uniformity gaps are fixed in the **surface** (consistent flags, corrective diagnostics that explain extract/mount/scope) — **not** the skill, per the standing "self-explaining surface over skill" rule (the skill stays minimal N6 material). Silent divergences graduate into the "applied ⇒ effected" guard family above.

## Running it

The runner is the **main assistant loop**, not a script — setup and the deep analysis-and-report are judgment; the subagent is the agent-under-observation. The pipeline:

```
re-capture metadata (if :cli src/tests changed) → rebuild native → native-gate   ← #1 precondition (below)
  → ./probe/prep-probe.sh                              # sandbox + logging shim + skill   (script; FRESHNESS-GATED)
  → spawn subagent: agent-preamble.md + task turns     # single-sourced preamble + the task's prompts, one session
  → ./probe/audit-probe.sh <agentId>                   # integrity gate — STOP if SUSPECT  (script)
  → ./probe/digest-probe.sh                            # deterministic trajectory facts + RED FLAGS  (script)
  → analyze vs gold (judgment) → probe/reports/<date>-<task>.md
```

> **#1 precondition — NEVER probe a stale native image.** A probe measures the *shipped product*; a stale binary measures a fiction, and its findings are worthless (worse: misleading). Two ways it goes stale, both must be closed before every run: **(1) code** — any `:cli`/`src` change → rebuild; **(2) reflection metadata** — the GraalVM capture is **test-suite-driven** ([`../scripts/capture-native-config.sh`](../scripts/capture-native-config.sh)), so a *new or changed test* alone stales it (its reflective surface may be uncaptured) — re-capture, *then* rebuild, *then* [`native-gate.sh`](../scripts/native-gate.sh). This is **not** only the verb/POJO/`operators.json` trigger list in [`../CLAUDE.md`](../CLAUDE.md); with a test-suite-driven capture the practical rule is: *touched `:cli` source or tests since the last capture? re-capture before rebuilding.* `prep-probe.sh` **enforces** this — it refuses (exit 3) if the binary is older than any `:cli`/`src` `.java`/`.json` or the bundled metadata, naming the re-capture + rebuild commands. (Learned the hard way: several runs this session rebuilt the binary on metadata captured a session earlier — the diff later proved it stale.)

The per-step detail (sandbox env, the no-look-ahead instruction, the audit, the digest) is in [`../probe/README.md`](../probe/README.md).

### Repeatability — every step is a script, a single-sourced template, or a committed artifact (never ad-hoc)

A probe is only useful if it can be re-run and compared; an analysis done by hand once isn't reproducible and silently misses things the second time. So the harness is built so that **nothing load-bearing is improvised**:

| step | repeatable form |
|---|---|
| sandbox + shim | [`prep-probe.sh`](../probe/prep-probe.sh) (script) |
| the subagent's framing (boundary, env, no-look-ahead, report format) | [`agent-preamble.md`](../probe/agent-preamble.md) — **single-sourced**; only the task's turns vary, so runs are comparable and the boundary can't drift |
| the integrity check | [`audit-probe.sh`](../probe/audit-probe.sh) (script, verified to fire) |
| the **mechanical** analysis | [`digest-probe.sh`](../probe/digest-probe.sh) (script) — counts, nonzero exits, repeated calls, and the **auto RED-FLAG for a raw exception escaping a verb** (the P1 class), so it's never again "found by eye"; exits 1 on a red flag, so it gates too |
| the scenario | a committed `tasks/<n>.md` (prompts + gold) |
| the findings | a committed `reports/<date>-<n>.md` |
| a **root-cause repro** | the finding's TDD guard test (or a committed repro script) — *the repro is the test*, runnable by anyone/CI, not a one-off command |

Only the genuinely judgment-bound parts stay in the agent's hands: the subagent's actual session (the thing under observation) and the deep correctness/misconception analysis. Everything else is committed and re-runnable. (Same principle as the eval's `prep-sandbox.sh` + `benchmark.workflow.js`: scripts for the deterministic skeleton, agents only where judgment is irreducible.)

### How a probe is triggered

Today a probe is **started manually** — the user asks ("run probe task 01") and the main loop orchestrates the pipeline above. There is **no scheduled or CI trigger**, by design: unlike the eval (which produces a numeric score from a headless `benchmark.workflow.js` and can gate CI), the probe's deliverable is a *qualitative deep read* that needs an agent in the loop, so it is a "run-and-read" instrument, not a red/green gate. What *does* become automated is the **graduation step** — once a probe surfaces a defect class, the deterministic guard it feeds (e.g. the "applied ⇒ effected" sweep) runs in CI forever after. So: the probe is human-triggered; the guards it spawns are machine-enforced. (A periodic/scheduled probe cadence is possible later, but it would still hand its report to a human/agent to read — the analysis is the point.)
