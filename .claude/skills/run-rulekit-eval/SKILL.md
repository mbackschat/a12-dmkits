---
name: run-rulekit-eval
description: Run the a12-dmkits self-sufficiency eval / Layer-2 benchmark correctly. Use this whenever you (the main assistant) are about to run, resume, or score the N6 eval in this repo — e.g. the user says "run the benchmark", "run the eval", "re-run T4". It loads the protocol guardrails (clean subagent, material boundary, neutral prompts, pinned model, cost model, targeted re-runs) and the run procedure. This is the OPERATOR'S playbook — it is NOT shipped material and must never be given to the eval's subagents (in the wired **bare-tool** mode they get only the self-describing CLI + the model file — no skill; EVAL-SPEC §2a — and the CLI exposes the operator catalog via `cli operators`).
---

# Running the a12-dmkits self-sufficiency eval (N6)

Operator's playbook. **The guidelines live once in [`docs/EVAL-SPEC.md`](../../../docs/EVAL-SPEC.md) — read §5 (protocol) and §8a/§8b/§8c (cost, targeted re-runs, architecture) before running.** This file is the in-context checklist + the non-negotiables; the spec carries the detail and rationale. Results go in [`eval/RESULTS.md`](../../../eval/RESULTS.md).

## Two things not to confuse

- This is the **operator's** playbook (main loop). **Never** give it, `docs/`, or source to the eval's subagents. In the wired **bare-tool** mode (the only runnable one — `benchmark.workflow.js`, which forbids `*.md`) they get ONLY the **self-describing CLI** (verbs + `--help`/`manifest`/`schema`/`cli operators` — the operator catalog is reached *through* the CLI, no raw `operators.json` hand-off) + the model file. The N1 skill ([`skill/SKILL.md`](../../../skill/SKILL.md), the **material under test**) is added **only in production mode** (EVAL-SPEC §2a), now **wired (opt-in)** — pass **`args.skillPath: "skill/SKILL.md"`** (preferred — the producer reads the verbatim file itself) or `args.skill` (inlined contents). Bare-tool stays the default; don't claim a run measured the skill unless you passed one of them — and always run the **boundary audit** (checklist step 7) after a production run, since `skillPath` lets the agent read a file.
- Run it **in the main loop, not a sub-agent** — orchestration needs shared context (write `RESULTS.md`, track cost, `resumeFromRunId`) and agent nesting is restricted; the isolation the eval needs is provided one layer down by the Workflow's clean subagents. Layers + data flow: EVAL-SPEC §8c.

## Guardrails — violating any silently invalidates the run (detail: EVAL-SPEC §1–§5)

1. **Clean subagent** is the instrument — never self-evaluate.
2. **Material boundary** — the subagent reads only the CLI (which self-describes its operator catalog via `cli operators`) + the model file under test (bare-tool, the wired default), plus `skill/SKILL.md` *only* if you've explicitly enabled production mode (§2a); never the operator playbook, source, `docs/`, the raw `operators.json`, or tests.
3. **Neutral prompt** — state the requirement, never hint at the technique; keep skill examples structurally distinct from tasks.
4. **Pin the model** (`opus`/`sonnet`/`haiku`) explicitly; label results by tier.
5. **Require a GAPS section** — that, not pass/fail, is the deliverable.

## Run checklist

1. `./gradlew :cli:installDist`, then **`bash eval/prep-sandbox.sh`** (mirrors the model fixtures into `/tmp/rk-eval-sandbox` so produce agents never write a committed fixture — a mutating setup that a sandboxed subagent can't be granted permission for, so the main loop runs the script; the workflow's Prep phase only verifies it). Then smoke-test: `cli -m <model> rule check --field … --condition … --code …` → envelope `valid:true`.
2. Bundle tasks: read `eval/tasks/*/*.json` — the **kit subfolders** `rulekit/`/`modelkit/`/`mixed/` (the folder is the kit; the task `id` is the filename stem). Trim each to `id/family/model/intent/gold/rules/expected` **plus** the routing keys the runner needs — `modelPath`/`includeDir` (multi-file tasks, e.g. `15-storefront-name-multifile`) and `computation` (modify-computation tasks, e.g. `16-modify-effective-fee-sum-addons`). Pass as `args.tasks` — **actual JSON, not a string**. (Today the runner only handles the `rulekit/` families; `modelkit/`+`mixed/` tasks are authored-but-inert until B — §8d.)
3. Estimate cost (EVAL-SPEC §8a) and tell the user — it's opt-in / token-heavy, only on explicit request.
4. Launch: `Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks } })` → Produce → Recheck (`cli batch` for rules · `cli computation add --dry-run` for computations · `cli modify --dry-run` for both modify families — rule modifications are batched so the DSL needs no shell-quoting) → Judge → pass@k. **Five families:** authoring · authoring-computation · modify-computation · modify-rule · comprehension. The **rule judges verify by execution** (run `eval-doc` for gold vs candidate on discriminating docs); computation judges still reason (with the type-aware empty-operand fact).
   - **Production-mode** run (measure the skill lift): pass **`args.skillPath: "skill/SKILL.md"`** (PREFERRED — the producer agents read the file itself, so the verbatim skill needs no transcription and can't drift) or `args.skill` = the inlined contents. Label the result **production**, never mixed into the bare-tool trend. **Then run the boundary audit** (step 7).
5. **Selective re-run after a fix** (EVAL-SPEC §8b): subset `args.tasks` / `args.models` at low `k`. Material changed ⇒ **fresh** run, not resume.
6. Record pass@k + analysis in `eval/RESULTS.md` (date **+ time-of-day** via `date`, newest first; label targeted vs full).
7. **Production-run boundary audit (mandatory — the material boundary IS the measurement).** `skillPath` lets a producer agent read a file, so confirm it read **only** the skill. With `D` = the run's transcript dir (`…/subagents/workflows/wf_…`):
   ```bash
   grep -rhoE '"file_path" *: *"[^"]+"' "$D"/agent-*.jsonl | sort -u                       # Read-tool reads
   grep -rhoE '"command" *: *"[^"]+"' "$D"/agent-*.jsonl | grep -iE 'cat |head |sed |less '  # bash reads
   ```
   **Clean** = the only files read are `skill/SKILL.md`, the model `.dm.json` under test, and the agents' own `/tmp/*.json` docs. Any `docs/` · `src/` · `*Test` · other `*.md` · `eval/tasks` read is a **boundary breach → the run is invalid** (re-run after tightening the prompt). (For bare-tool runs this should find no skill read at all.)

## Run tiers (the cost/coverage ladder — detail: EVAL-SPEC §8d)

Don't default to the full pass@k — it's a milestone tool. Pick the tier (tasks are tagged `difficulty` + a derived `kit`; `EvalSuiteLedgerTest` locks the categorization):

- **smoke** (~2 min, soft) — the **deterministic** core (every gold through the CLI + the demos verify — largely `EvalGoldOracleTest`) **plus ≥1 rulekit agent run**: bundle the `smoke` suite and run sonnet · k=1.
  `jq -s -c '[.[]|select(.suites and (.suites|index("smoke")))]' eval/tasks/*/*.json`
- **simple** (<7 min) — the `simple` suite (~6 tasks spanning all difficulties), **sonnet** · k=1 (opus only as a single spot-check).
  `jq -s -c '[.[]|select(.suites and (.suites|index("simple")))]' eval/tasks/*/*.json`
- **full** — all tasks, sonnet + **few** opus, k=3 (the ~40-min baseline).

Default the agentic tiers to **sonnet** (it matches opus here — RESULTS.md); opus is a spot-check, not the default. The **modelkit agentic** run (smoke's second agent run + `full` structure coverage) is **deferred to Phase 2+ / `apply` Phase 3** — until then modelkit rides only the deterministic smoke core.

## Reading results

`pass = kernel-valid AND judge-equivalent` (authoring) / `judge-correct vs the rubric` (comprehension). **A 0/k cell is not automatically a material gap** — read the judge's rationale and separate *real gaps* (fix the skill/catalog) from *eval-design artifacts* (fix the harness: an over-specified gold, or a judge lacking model context). See EVAL-SPEC §3 and the `RESULTS.md` log for worked cases. Flakes happen at low `k`.
