---
name: run-rulekit-eval
description: Run the a12-rulekit self-sufficiency eval / Layer-2 benchmark correctly. Use this whenever you (the main assistant) are about to run, resume, or score the N6 eval in this repo — e.g. the user says "run the benchmark", "run the eval", "re-run T4". It loads the protocol guardrails (clean subagent, material boundary, neutral prompts, pinned model, cost model, targeted re-runs) and the run procedure. This is the OPERATOR'S playbook — it is NOT shipped material and must never be given to the eval's subagents (those get only the CLI + catalog + the N1 skill).
---

# Running the a12-rulekit self-sufficiency eval (N6)

Operator's playbook. **The guidelines live once in [`docs/EVAL-SPEC.md`](../../../docs/EVAL-SPEC.md) — read §5 (protocol) and §8a/§8b/§8c (cost, targeted re-runs, architecture) before running.** This file is the in-context checklist + the non-negotiables; the spec carries the detail and rationale. Results go in [`eval/RESULTS.md`](../../../eval/RESULTS.md).

## Two things not to confuse

- This is the **operator's** playbook (main loop). **Never** give it, `docs/`, or source to the eval's subagents — they get ONLY the CLI + the catalog (`src/main/resources/catalog/operators.json`) + the N1 skill ([`skill/SKILL.md`](../../../skill/SKILL.md), which **is** the material under test).
- Run it **in the main loop, not a sub-agent** — orchestration needs shared context (write `RESULTS.md`, track cost, `resumeFromRunId`) and agent nesting is restricted; the isolation the eval needs is provided one layer down by the Workflow's clean subagents. Layers + data flow: EVAL-SPEC §8c.

## Guardrails — violating any silently invalidates the run (detail: EVAL-SPEC §1–§5)

1. **Clean subagent** is the instrument — never self-evaluate.
2. **Material boundary** — the subagent reads only the CLI + catalog + N1 skill + the model file under test; never source, `docs/`, or tests.
3. **Neutral prompt** — state the requirement, never hint at the technique; keep skill examples structurally distinct from tasks.
4. **Pin the model** (`opus`/`sonnet`/`haiku`) explicitly; label results by tier.
5. **Require a GAPS section** — that, not pass/fail, is the deliverable.

## Run checklist

1. `./gradlew :cli:installDist`, then smoke-test: `cli check <model> --field … --condition … --code …` → `{"valid": true}`.
2. Bundle tasks: read `eval/tasks/*.json`, trim each to `id/family/model/intent/gold/rules/expected`, pass as `args.tasks` — **actual JSON, not a string**.
3. Estimate cost (EVAL-SPEC §8a) and tell the user — it's opt-in / token-heavy, only on explicit request.
4. Launch: `Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks } })` → Produce → Recheck (`cli batch`) → Judge → pass@k.
5. **Selective re-run after a fix** (EVAL-SPEC §8b): subset `args.tasks` / `args.models` at low `k`. Material changed ⇒ **fresh** run, not resume.
6. Record pass@k + analysis in `eval/RESULTS.md` (date **+ time-of-day** via `date`, newest first; label targeted vs full).

## Reading results

`pass = kernel-valid AND judge-equivalent` (authoring) / `judge-correct vs the rubric` (comprehension). **A 0/k cell is not automatically a material gap** — read the judge's rationale and separate *real gaps* (fix the skill/catalog) from *eval-design artifacts* (fix the harness: an over-specified gold, or a judge lacking model context). See EVAL-SPEC §3 and the `RESULTS.md` log for worked cases. Flakes happen at low `k`.
