---
name: run-rulekit-eval
description: Run the a12-rulekit self-sufficiency eval / Layer-2 benchmark correctly. Use this whenever you (the main assistant) are about to run, resume, or score the N6 eval in this repo — e.g. the user says "run the benchmark", "run the eval", "re-run T4". It loads the protocol guardrails (clean subagent, material boundary, neutral prompts, pinned model, cost model, targeted re-runs) and the run procedure. This is the OPERATOR'S playbook — it is NOT shipped material and must never be given to the eval's subagents (those get only the CLI + catalog + the N1 skill).
---

# Running the a12-rulekit self-sufficiency eval (N6)

The operator's playbook for running the Layer-2 benchmark. The full method, rationale, glossary, and task suite are in [`docs/EVAL-SPEC.md`](../../../docs/EVAL-SPEC.md) (read §1–§5 and §8 if anything here is unclear); measured results go in [`eval/RESULTS.md`](../../../eval/RESULTS.md).

## This skill is NOT the thing under test

- It is **your** playbook (the main loop). It is **not** shipped material. **Never** hand this skill, `docs/EVAL-SPEC.md`, source, or `docs/` to the clean subagent under test — doing so breaks the material boundary and invalidates the result.
- Do **not** confuse it with [`skill/SKILL.md`](../../../skill/SKILL.md) (the `a12-rules` skill) — that one **is** the material under test (the N1 BA-authoring skill the subagent is allowed to read).

## Run this in the main loop — NOT inside a sub-agent

Follow this skill in the **main conversation** (the `Skill` tool runs here). Do **not** delegate the orchestration to a sub-agent: orchestration must stay in the main loop so it can write `eval/RESULTS.md`, track cost, and `resumeFromRunId` (all session-scoped), and because agent nesting is restricted (the Workflow can't nest; a sub-agent spawning sub-agents is unsupported, so it likely couldn't launch the run). The **isolation the eval needs is provided one layer down** — the Workflow's clean subagents, the first-time consumers that *are* the instrument. So: main loop orchestrates → the Workflow fans out → clean subagents are isolated. Full layer diagram, data flow, and parameterization: **EVAL-SPEC §8c**.

## Guardrails — violating any silently invalidates the result

1. **Clean subagent is the instrument.** Spawn a fresh agent (or use the Workflow runner, which does). Never self-evaluate — the main loop already knows the design.
2. **Material boundary.** The subagent gets ONLY the shipped material: the installed CLI launcher + the operator catalog (`src/main/resources/catalog/operators.json`) + the N1 skill (`skill/SKILL.md`). It must NOT read source, `docs/`, tests, or other `*.md`. The one allowed input is the model file under test.
3. **Neutral prompt — no teaching to the test.** State the business requirement and constraints; never hint at the technique (the error-scenario polarity, the inversion, which operator). Keep skill examples structurally distinct from the tasks.
4. **Pin the model.** Always set the `Agent`/`Workflow` `model` explicitly (`opus`/`sonnet`/`haiku`); never inherit the session model. Label every result by model tier.
5. **Require a GAPS section** in the probe report — that section, not the pass/fail, is the deliverable.

## Procedure

1. **Build the launcher:** `./gradlew :cli:installDist` (rebuild before every run — it goes stale). Smoke-test it: `cli check <model> --field … --condition … --code …` returns `{ "valid": true }`.
2. **Bundle tasks:** read `eval/tasks/*.json`, trim each to `id/family/model/intent/gold/rules/expected`, and pass as the Workflow `args.tasks` — as **actual JSON**, not a stringified blob (a string breaks `args.tasks`; the runner now throws loudly if `tasks` is empty).
3. **Run the runner** (opt-in / token-heavy — only on the user's explicit request): `Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models: [...], k: N, tasks: [...] } })`. Phases: Produce → Recheck (one warm-JVM `cli batch`) → Judge → pass@k.
4. **Estimate cost before launching** (EVAL-SPEC §8a): `agents ≈ (A+C)×M×k` produce `+ 1` recheck `+ (valid-authoring + C×M×k)` judge. Tell the user the estimate.
5. **Targeted re-run after a fix** (EVAL-SPEC §8b) — the runner fans out over **exactly** the tasks/models you pass, so selective runs need no special flag: **subset `args`.** Pass only the affected tasks in `args.tasks` and only the relevant tiers in `args.models`, at a low `k`. Example — confirm the T4/T6 fixes on opus:
   ```
   Workflow({ scriptPath: "eval/benchmark.workflow.js",
     args: { models: ["opus"], k: 2, tasks: [ <T4 obj>, <T6 obj> ] } })   // ~ (2)×1×2 + 1 + ≤4 ≈ 9 agents
   ```
   If the **material** changed (skill/catalog/CLI), start a **FRESH** run — do NOT resume (a resume replays the old material from the produce cache). `resumeFromRunId` is only for recovering an aborted run with unchanged inputs. Mixing families is fine: an empty authoring or comprehension set just skips that phase.
6. **Record results:** fold pass@k + analysis into `eval/RESULTS.md` (dated **+ time-of-day**, newest first; get the clock from `date`). Label the run targeted vs full.

## Reading results honestly

- `pass = kernel-valid AND judge-equivalent` (authoring) / `judge-correct vs the rubric` (comprehension).
- **A 0/k cell is not automatically a material gap.** Read the judge's rationale first and separate:
  - **Real material gaps** → improve the skill/catalog (e.g. an under-taught operator).
  - **Eval-design artifacts** → fix the harness, not the material: an **over-specified gold** (e.g. a specific regex when many are valid), or a **judge lacking model context** (it didn't know the enum domain, so it marked an equivalent rule wrong). The 2026-06-05 run hit both (T3, T6).
- Flakes happen at low `k`; raise `k` for cells that matter before drawing conclusions.
