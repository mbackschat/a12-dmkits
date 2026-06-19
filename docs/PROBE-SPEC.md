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

## Gold from existing models — the correctness anchor

Each task is reverse-engineered from a model that **already encodes a correct, kernel-valid end-state** — a committed fixture under `cli/src/test/resources/models/`, or a domain-transferred workspace model (license-safe: own domain, **no tax/gov** — insurance/e-commerce). That source model **is the answer key**, which gives the deep analysis an **objective correctness anchor** ("did the agent build the right thing?") *underneath* the qualitative trajectory mining — without turning the probe into a pass/fail score.

Gold is matched **semantically, not literally** (read → re-render → compare, like the eval judge): many valid expressions enforce one intent (`DifferenceInYears` vs an `AddYears` formulation for age; a field-list vs a group-scope quantifier). The bar is "the agent's model enforces the same thing, at least as completely as the reference." The kernel stays the validity oracle.

This also makes the task backlog cheap: every rich fixture is a candidate scenario, and it naturally covers the operator/structure surface that model already exercises.

## The analysis method — the deep report (no score)

From the objective shim log + the final model files (+ the agent's narrative), each report is structured around these dimensions:

1. **Correctness vs the gold** *(the objective anchor)* — re-read the final model and verify, semantically, that it enforces the same rules / fields / structure / includes as the gold (right polarity, right scope), at least as completely. This is what catches **false successes / silent drops** — a label the agent "set" that isn't there.
2. **Friction** — tool calls per sub-goal, retries, rejections hit and whether recovered, dead-ends.
3. **Misconceptions** — did the agent reason about constraints that don't exist (e.g. "JVM startup is slow" → over-batching, when the native image starts in <100 ms)?
4. **Diagnostic quality** — when it erred, did the diagnostic's `fix` text get it unstuck?
5. **Self-describing-surface gaps** — did it guess? Did `manifest` / `schema` / `operators` mislead it?
6. **Concrete tool-defect findings** — ranked, actionable, → root-cause fixes.

A report that finds nothing states **"clean session — no DX defects on this task"** explicitly; that is itself a useful signal, not a non-result.

## The proactive loop — discover → fix at root → graduate into a guard

The probe's job is to find **unknowns**; deterministic guards enforce **knowns**. So a finding isn't done when it's fixed — it's done when its *class* can't regress:

```
probe discovers a defect class  ──►  fix at root  ──►  graduate the class into a deterministic guard
   (qualitative, one session)                            (e.g. the "applied ⇒ effected" round-trip sweep)
```

The canonical first guard this feeds is the **"applied ⇒ effected" round-trip sweep**: a matrix test over every writable attribute (FieldSpec / rule / computation keys) that writes → reads back → asserts the effect actually landed — the deterministic complement that would have caught the `field modify` silent drop without a bug report.

## Running it

The runner is the **main assistant loop**, not a script — setup and the deep analysis-and-report are judgment; the subagent is the agent-under-observation. The steps (build the native binary → `./probe/prep-probe.sh` → drive a general-purpose subagent through a task's prompts **in order, as one session** in the sandbox → read the shim log + model files → write `probe/reports/<date>-<task>.md`) are detailed in [`../probe/README.md`](../probe/README.md).
