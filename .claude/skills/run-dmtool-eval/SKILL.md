---
name: run-dmtool-eval
description: Run the a12-dmkits self-sufficiency eval / Layer-2 benchmark correctly. Use this whenever you (the main assistant) are about to run, resume, or score the N6 eval in this repo — e.g. the user says "run the benchmark", "run the eval", "re-run T4". It loads the protocol guardrails (clean subagent, material boundary, neutral prompts, pinned model, cost model, targeted re-runs) and the run procedure. This is the OPERATOR'S playbook — it is NOT shipped material and must never be given to the eval's subagents (in the wired **bare-tool** mode they get only the self-describing CLI + the model file — no skill; EVAL-SPEC §2a — and the CLI exposes the operator catalog via `cli operators`).
---

# Running the a12-dmkits self-sufficiency eval (N6)

Operator's playbook. **The guidelines live once in [`docs/EVAL-SPEC.md`](../../../docs/EVAL-SPEC.md) — read §5 (protocol) and §8a/§8b/§8c (cost, targeted re-runs, architecture) before running.** This file is the in-context checklist + the non-negotiables; the spec carries the detail and rationale. Results go in [`eval/RESULTS.md`](../../../eval/RESULTS.md).

## Two things not to confuse

- This is the **operator's** playbook (main loop). **Never** give it, `docs/`, or source to the eval's subagents. In the wired **bare-tool** mode (the only runnable one — `benchmark.workflow.js`, which forbids `*.md`) they get ONLY the **self-describing CLI** (verbs + `--help`/`manifest`/`schema`/`cli operators` — the operator catalog is reached *through* the CLI, no raw `operators.json` hand-off) + the model file. The N1 skill ([`skill/SKILL.md`](../../../skill/SKILL.md), the **material under test**) is added **only in production mode** (EVAL-SPEC §2a), now **wired (opt-in)** — pass **`--skill skill/SKILL.md`** to `eval-produce.ts`, which copies it outside the repository and lets the producers read exactly that file. Bare-tool stays the default; don't claim a run measured the skill unless you passed it.
- Run it **in the main loop, not a sub-agent** — orchestration needs shared context (write `RESULTS.md`, track cost, `resumeFromRunId`) and agent nesting is restricted; the isolation the eval needs is provided by the clean producers `eval-produce.ts` launches, never by a Workflow or Agent-tool subagent, which inherits your CLAUDE.md files, memory index and skill listing. Layers + data flow: EVAL-SPEC §8c.

## Guardrails — violating any silently invalidates the run (detail: EVAL-SPEC §1–§5)

1. **Clean producer** is the instrument — never self-evaluate, and never produce through the Agent tool or a Workflow `agent()`. Before a run whose numbers matter, run `scripts/run-typescript.sh clean-agent scripts/typescript/clean-agent.ts --self-test`; it must print `clean`.
2. **Material boundary** — the subagent reads only the CLI (which self-describes its operator catalog via `cli operators`) + the model file under test (bare-tool, the wired default), plus `skill/SKILL.md` *only* if you've explicitly enabled production mode (§2a); never the operator playbook, source, `docs/`, the raw `operators.json`, or tests.
3. **Neutral prompt** — state the requirement, never hint at the technique; keep skill examples structurally distinct from tasks.
4. **Pin the model** (`opus`/`sonnet`/`haiku`) explicitly; label results by tier.
5. **Require a GAPS section** — that, not pass/fail, is the deliverable.

## Run checklist

1. `./build.sh :cli:installDist`, then **`bash eval/prep-sandbox.sh`** (mirrors the model fixtures into `/tmp/rk-eval-sandbox` so produce agents never write a committed fixture — a mutating setup that a sandboxed subagent can't be granted permission for, so the main loop runs the script; the workflow's Prep phase only verifies it). Then smoke-test: `bin/dmtool -m <model> rule check --field … --condition … --code …` → envelope `valid:true`.
2. Bundle tasks: read `eval/tasks/*/*.json` — the **kit subfolders** `rulekit/`/`modelkit/`/`mixed/` (the folder is the kit; the task `id` is the filename stem). **Simplest-safe: pass the WHOLE task objects** (the runner reads only the keys it needs) so no routing key can ever be dropped — trimming has silently scored a task 0/k. If you do trim, keep `id/family/model/intent/gold/rules/expected` **plus EVERY** routing key the runner needs: `modelPath`/`includeDir` for multi-file tasks; `computation` for modify-computation; `rule` for modify-rule and focused runtime; `mode` + `instance` for runtime; and `proposal` for computation-preflight. Pass as `args.tasks` — **actual JSON, not a string**. The workflow fails fast when a family-specific routing key is absent.
3. Estimate cost (EVAL-SPEC §8a) and tell the user — it is opt-in/token-heavy. Let `T₂` be judged or executor-scored tasks and `T₁` the directly scored runtime/preflight tasks: the estimate is `(2·T₂ + T₁) × models × k + recheck + prep`; a single runtime/preflight task at one model and `k=1` costs two agents (prep + producer), not three.
4. Produce, then score. Write the bundled tasks to a file and run `scripts/run-typescript.sh eval-produce scripts/typescript/eval-produce.ts --tasks tasks.json --models sonnet [--efforts '{"sonnet":"xhigh"}'] --k 1 --out DIR` (a run past ~10 minutes goes to the background); it must end `all N cell(s) clean`. Then `Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks, efforts, produced } })` with `produced` = `DIR/produced.json`. The live families are **eight judged** (authoring, self-correction, authoring-computation, modify-computation, modify-rule, comprehension, computation-comprehension, workspace-roles), **six executor-scored** (structure, session, delete-safety, metadata, template, refactor), and **two directly scored discovery families** (runtime, computation-preflight). Recheck uses one warm `cli batch` for rules and read-only computation/rule previews; rule judges verify equivalence by execution, computation judges use the type-aware semantic rubric, structural scorers apply/read the edit, and discovery output is compared directly with deterministic expected facts without a second score agent.
   - **Production-mode** run (measure the skill lift): add `--skill skill/SKILL.md` to `eval-produce.ts`. Label the result **production**, never mixed into the bare-tool trend.
5. **Selective re-run after a fix** (EVAL-SPEC §8b): subset `args.tasks` / `args.models` at low `k`. Material changed ⇒ **fresh** run, not resume.
6. Record pass@k + analysis in `eval/RESULTS.md` (date **+ time-of-day** via `date`, newest first; label targeted vs full).
7. **Boundary audit (every run — the material boundary IS the measurement).** `eval-produce.ts` does it: it refuses a cell whose init event shows inherited context or whose transcript names the repository, `docs/`, a `.md` file or a skill other than the production-mode copy, and writes the reasons to `DIR/report.json`. A refused run is invalid; read its transcripts under `DIR/transcripts/` before re-running.

## Run tiers (the cost/coverage ladder — detail: EVAL-SPEC §8d)

Don't default to the full pass@k — it's a milestone tool. Pick the tier (tasks are tagged `difficulty` + a derived `kit`; `EvalSuiteLedgerTest` locks the categorization):

- **smoke** (~2 min, soft) — the **deterministic** core (every gold through the CLI + the demos verify — largely `EvalGoldOracleTest`) **plus ≥1 rulekit agent run**: bundle the `smoke` suite and run sonnet · k=1.
  `jq -s -c '[.[]|select(.suites and (.suites|index("smoke")))]' eval/tasks/*/*.json`
- **simple** (<7 min) — the `simple` suite (~6 tasks spanning all difficulties), **sonnet** · k=1 (opus only as a single spot-check).
  `jq -s -c '[.[]|select(.suites and (.suites|index("simple")))]' eval/tasks/*/*.json`
- **full** — all tasks, sonnet + **few** opus, k=3 (the ~40-min baseline).

Default the agentic tiers to **sonnet** (it matches opus here — RESULTS.md); opus is a spot-check, not the default. The **modelkit/mixed agentic** runs are live for all six families — **`structure`**, **`delete-safety`**, **`metadata`**, **`template`**, **`refactor`** (modelkit) and **`session`** (mixed) (smoke's modelkit run = `modelkit/01`); all deterministically scored.

## Reading results

`pass = kernel-valid AND judge-equivalent` for authored rules/computations; `judge-correct vs the rubric` for comprehension/workspace roles; executor facts match for structural tasks; and the structured observation matches the deterministic task facts for runtime/preflight. **A 0/k cell is not automatically a material gap** — read the rationale and separate real material gaps from harness artifacts. See EVAL-SPEC §3 and `RESULTS.md`; low-`k` agent cells can flake.
