# a12-dmkits — self-sufficiency benchmark corpus

The curated task corpus for the N6 self-sufficiency eval, plus the **runner entry points** (`benchmark.workflow.js`).

**Doc responsibilities (kept non-overlapping on purpose):**

| Doc | Owns |
|---|---|
| [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md) | the **method** — clean-subagent instrument, material boundary, scoring shapes, cost model, run tiers, protocol (the *stable contract*) |
| **this file (`README.md`)** | the corpus **operating manual** — the task **schema** (fields), the per-family **gold shapes**, the two layers, and how to **run / add** a task |
| [`TASKS.md`](TASKS.md) | the per-task **inventory/catalog** — every task's intent + what it exercises (the coverage map) |
| [`RESULTS.md`](RESULTS.md) | the dated **measured results** — pass@k, lifts (newest first) |

So: *shapes* live here, the *instances* live in `TASKS.md`, and the *authoritative current list* is always the corpus files (`tasks/<kit>/*.json`) themselves.

A second, lighter instrument lives here too: **`gap-finder.workflow.js`** — the on-demand **self-explainability audit** (Option C, [`../docs/CLI-CONFORMANCE-SPEC.md`](../docs/CLI-CONFORMANCE-SPEC.md) "the gap-finder"). k cold agents critique the CLI's self-description for *qualitative* gaps the deterministic conformance matrix can't see; `gap-allowlist.json` is its reasoned tripwire state. It's ~3 agents (no corpus / kernel recheck / judge), distinct from the `benchmark` runner.

## Layout

- `tasks/<kit>/<NN>-<slug>.json` — one task per file, grouped by **kit** subfolder: `rulekit/` (rules, computations, their comprehension/self-correction, runtime document-instance discovery, and read-only computation preflight), `modelkit/` (structure: field/group/typedef/include/config + delete-safety + refactor + metadata: descriptions/annotations/comment + template: multi-select/attachment group templates), `mixed/` (one `apply` session touching both). **The folder is the kit** (the explicit axis — no derived map); the task `id` is the filename stem (e.g. `01-pro-addon-fee-required`). Each task carries a deterministic oracle appropriate to its family. All fixtures/rules here are our own originals (license-safe, per the project `CLAUDE.md`). Run tiers (smoke/simple/full) + the categorization are in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md) §8d.

## Task schema

```json
// in eval/tasks/rulekit/01-pro-addon-fee-required.json — the folder (rulekit) is the kit; the id is the filename stem
{
  "id": "01-pro-addon-fee-required",             // = the filename stem (no separate T<N>)
  "family": "authoring" | "authoring-computation" | "modify-computation" | "modify-rule" | "comprehension" | "computation-comprehension" | "self-correction" | "runtime" | "computation-preflight"   // rulekit/
           // | "structure" | "delete-safety" | "refactor" | "metadata" | "template" | "workspace-roles"   // modelkit/      | "session"   // mixed/
  "difficulty": "simple" | "medium" | "complex", // intrinsic tag (EvalSuiteLedgerTest requires it)
  "suites": ["smoke", "simple"],                 // optional run-tier membership (derive a tier by filtering on this)
  "model": "subscription",                       // a fixture name under cli/src/test/resources/models/
  "modelPath": "cli/src/test/resources/models/multifile/app/storefront.dm.json", // multi-file: explicit (repo-relative) path
  "includeDir": "cli/src/test/resources/models/multifile/lib",                    // multi-file: threaded as -w/--workspace
  "computation": "/Subscription/Billing/EffectiveFeeComp",                        // modify-computation: the existing computation's full name
  "rule": "/Order/DeliveryNotBeforeOrder",                                        // modify-rule: the existing rule's full name
  "mode": "model-compute" | "model-eval" | "rule-eval",                         // runtime: which live document-instance behavior the task requests
  "instance": {"Order": {"Subtotal": 125}},                                      // runtime: canonical A12 Document JSON supplied inline to the agent
  "proposal": {"computedField": "…", "alternatives": [{"operation": "…"}]},  // computation-preflight: proposed computation supplied inline
  "workspaceDir": "cli/src/test/resources/models/roles-ws",                       // workspace-roles: the workspace dir (holds auth/roles.yaml + models)
  "expectedCodes": ["UNDEFINED_ROLE", "UNDEFINED_AUTHORITY"],                      // workspace-roles: the finding codes the lint must produce (oracle)
  "operators": ["GroupFilled", "Equal", ...],    // catalog families this task exercises (coverage axis)
  "intent": "When a subscription's Tier is PRO, each add-on's MonthlyFee must be provided.",
  "gold": {                                        // authoring: the expected, kernel-verified answer
    "field": "/Subscription/Addons/MonthlyFee",    //   error field (absolute path)
    "condition": "GroupFilled(...) And [/Subscription/Tier] == \"PRO\" And FieldNotFilled(MonthlyFee)",
    "code": "PRO_ADDON_FEE_REQUIRED"
  }
}
```

**`kit`** is **not a field** — it is the task's subfolder (`rulekit`/`modelkit`/`mixed`), so it can't drift from a map (`EvalSuiteLedgerTest`). The `gold` **shape** depends on the family (below — these are the *templates*; for the actual tasks that use each shape, see the catalog in [`TASKS.md`](TASKS.md)):
- **authoring / self-correction / modify-rule** — `{field, condition, code}` (above). For *modify-rule* the `condition` is the intended re-expression, keeping the same rule (named by `rule`) + error field; *self-correction*'s gold is the recovered rule.
- **authoring-computation / modify-computation** — `{computedField, alternatives: [{precondition?, operation}], commonPrecondition?}` (the computation the kernel should accept; for *modify* it's the intended re-expression, keeping the same `computedField`).
- **comprehension** — no `gold`; instead `rules: [...]` (the model's rule names to read back) + `expected` (the rubric of explanation facts).
- **computation-comprehension** — the comprehension twin for computations: no `gold`; instead `computations: [...]` (the model's computation names to read back) + `expected` (the rubric — the **computed value**, the **WHEN** precondition guard, each **band**'s precondition→operation, and no-inversion). **Verified by `EvalGoldOracleTest`** (reads each named computation to `Converted` + asserts `computation explain` surfaces the `commonPrecondition` and a non-empty `computationAlternatives` — the deterministic backbone), and has a **Layer-2 agentic path** that is **LLM-judged vs the rubric** (like `comprehension`).
- **structure / delete-safety / session** (the `modelkit/`+`mixed/` families) — `{op, group/parent, name, kind, expect…}` for a structure edit, `{op, field, cascade, expectRemoved}` for a safe-delete, `{ops:[…], expect:{field, rule}}` for an `apply` session. **Verified by `EvalGoldOracleTest`** (the gold edit re-binds + the post-conditions hold), and **all three have a Layer-2 agentic path** (deterministically scored — no LLM judge; EVAL-SPEC §8d **B**).
- **metadata** (the `modelkit/` describe/annotate family) — `{op: "describe" | "annotate" | "comment", ref?, kind?, locale?, name?, value?, text?}`: `describe` → `{ref, kind:"internal"|"external", locale, text}`; `annotate` → `{ref, name, value}`; `comment` → `{text}` (model-level). **Verified by `EvalGoldOracleTest`** (apply the gold metadata edit via `EditSession`, re-bind, and assert the value reads back through the kernel), and has a **Layer-2 agentic path** (deterministically scored — apply the agent's edit via `-o`, then the gold value reads back through `meta`/`config read`; EVAL-SPEC §8d **B**).
- **template** (the `modelkit/` `usageType` group-template family, §3.8) — `{op: "group multiselect" | "group attachment", parent, name, expectGroup, field?, values?, maxRows?}`: `group multiselect` → `{parent, name, field, values, maxRows, expectGroup}`; `group attachment` → `{parent, name, expectGroup}`. **Verified by `EvalGoldOracleTest`** (expand the template via `EditSession`, re-bind — the kernel's `MultiSelect`/`AttachmentGroupRule` accept the composition — and assert the group exists), and has a **Layer-2 agentic path** (deterministically scored — `applied && expectGroup exists && the right usageType marker`; §8d **B**).
- **refactor** (the `modelkit/` rename/move family) — `{op, field, to, expect:{renamed, to, rewroteReferences}}`. **Verified by `EvalGoldOracleTest`** (active since 2026-06-12 — the reference-rewriting rename/move engine shipped): the gold rename is applied via `adapter.ReferenceRewriter` + `ModelDocument`, must re-bind valid→valid, and the new path must exist while the old is gone. Now also has a **Layer-2 agentic path** (deterministically scored — `field rename --to` via `-o`, then `applied && new path resolves && old path gone`; §8d **B**).
- **workspace-roles** (the `modelkit/` cross-file access-control family, R4) — no single `model`; instead `workspaceDir` (the workspace tree holding `auth/roles.yaml` + `auth/users.yaml` + models) + `expectedCodes` (the `WorkspaceRoles` finding codes the lint must produce — the deterministic oracle) + `expected` (the free-text rubric for the agent judge). **Verified by `EvalGoldOracleTest`** (lints the fixture via `WorkspaceRoles` and asserts the produced codes equal `expectedCodes`). Its **Layer-2 agentic path** is LLM-judged: the agent must *discover* `workspace roles` from the self-describing CLI and report the undefined references without inventing false ones.
- **runtime** — `mode` (`model-compute` / `model-eval` / `rule-eval`) + inline canonical Document `instance` + exact `expected` machine facts and first human-readable summary line. **Verified by `EvalGoldOracleTest`**, which runs the real CLI in machine and `--text` modes. `EvalSuiteLedgerTest` derives the denominator from every live manifest verb carrying `--instance`, so a new runtime verb is uncovered until it gains a task. Layer 2 uses one clean agent to discover and run the verb and scores its structured observations directly; correctness remains owned by the deterministic oracle.
- **computation-preflight** — inline computation `proposal` + neutral `intent` + exact `expected` preview facts. **Verified by `EvalGoldOracleTest`**, which runs the real `computation add` preview and proves the model remains byte-identical. The task and actual producer prompt are guarded against naming the read-only option, so Layer 2 measures discovery rather than prompt recall. Its structured observation is scored directly without a second agent.

`modelPath`/`includeDir` are optional **multi-file** keys: present only when the task model `include`s a separate model that lives outside its own directory, so the runner threads `-w/--workspace` through every verb. `computation` is required for `modify-computation` (the existing computation to re-express); `rule` is required for `modify-rule` (the existing rule to re-express).

## The two layers

- **Layer 1 — oracle (deterministic, CI):** `EvalGoldOracleTest` (in `:cli`) reads every task here and executes the family-appropriate backbone: kernel validation/read-back for rules and computations, valid→valid structural edits, exact runtime machine + text output for every live document-instance verb, and an accepted byte-preserving computation preflight. This guards that the corpus and expected facts are real; no LLM is involved.
- **Layer 2 — agent runner (soft, opt-in):** `benchmark.workflow.js` fans out `model × task × k` **clean subagents**, gives each only the shipped material (the **self-describing CLI** — verbs + `--help`/`manifest`/`cli operators`; no raw catalog hand-off), scores its output, and aggregates **pass@k**. Non-deterministic + token-heavy → run on demand, track pass-rate against a threshold, not a per-commit gate. Pin the model (`opus`/`sonnet`/`haiku`) for reproducibility and comparison.

## How the runner works (and how Claude Code drives it)

Claude Code (the main loop) never runs the agents itself — it **orchestrates** via the [`run-dmtool-eval`](../.claude/skills/run-dmtool-eval/SKILL.md) skill: build the launcher (`:cli:installDist`), **mirror the model fixtures into a sandbox (`bash eval/prep-sandbox.sh`)**, bundle the tasks, then `Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks } })`. The workflow runs in the background and returns a pass@k aggregate; Claude then root-causes any misses and records the run in [`RESULTS.md`](RESULTS.md). It's driven from the **main loop, not a sub-agent** — the isolation the eval needs is provided one layer down by the workflow's own clean subagents (EVAL-SPEC §8c).

**Committed fixtures are never mutated.** The authoring verbs write in place by default, so a produce agent pointed at a committed fixture can corrupt it — it has, repeatedly (EVAL-SPEC §8c). So `eval/prep-sandbox.sh` mirrors `cli/src/test/resources/models` into `/tmp/rk-eval-sandbox` and the runner resolves every model path there; the operator runs that script (it's a mutating setup, which a sandboxed subagent can't be granted permission for). The workflow's first phase only **verifies** the sandbox is present and halts loudly if not.

The script is four phases over `model × task × k`:

0. **Prep** — verify (read-only) that the operator built the `/tmp` model sandbox; halt the run if absent.
1. **Produce** — one **clean subagent** per (model, task, run), model pinned. Neutral prompt + ONLY the self-describing CLI + the (sandboxed) model file, plus an inline document instance or computation proposal when that is the task input (no docs/source/task files — the material boundary *is* the measurement). This **bare-tool** mode is the default and the CI/trend instrument. Returns a candidate artifact, explanation, or structured observation.
2. **Recheck** — every candidate is re-validated against the real kernel (the agents' self-reported `kernelAccepted` is not trusted): rules via **one** warm-JVM `cli batch` of `check` (N checks in one process — `batch`'s reason to exist), computations via `cli add` (validates before persisting), modifications via `cli modify … --dry-run` (replace-then-bind, read-only, which also reports `changed` — a modify must be ACCEPTED *and* an actual change).
3. **Score** — authoring/comprehension families use a separate judge and structural families use an executor agent. Runtime and preflight discovery are scored directly in the workflow against deterministic expected facts, so they deliberately spawn no second agent.

Phases pipeline where they can; the whole run is one background `Workflow`. A 0/k cell is **not** automatically a real gap — separate genuine gaps (fix the skill/CLI) from eval-design artifacts (fix the harness), per EVAL-SPEC §3 and the worked cases in `RESULTS.md`.

## Adding / changing a task

1. Add a `tasks/<id>.json` with a fresh `id`, the `family`, a real `model`, the `operators` it covers, the `intent`, and a `gold` you've confirmed the kernel accepts. Verify per family: authoring → `cli check <model> --field … --condition "…" --code …` → `valid:true`; computation → `cli add <model> <spec.json>` → exit 0; modify-computation → `cli modify <model> --computation <name> --spec <spec.json> --dry-run` → exit 0 with `changed:true`; modify-rule → `cli modify <model> --rule <name> --condition "…" --dry-run` → exit 0 with `changed:true`; multi-file → add `-w <lib>` and the model's `modelPath`/`includeDir` keys.
2. `EvalGoldOracleTest` picks it up automatically; run `./build.sh :cli:test` — a red oracle means the gold or expected observation does not hold (fix the task, not the test).
3. **Add the matching row to [`TASKS.md`](TASKS.md) in the same commit** — the catalog is hand-maintained (no drift guard), so a new/renamed/removed task must update it (and this `README` if the *schema* changed). If you added a new **family**, also wire it in `benchmark.workflow.js` + classify it in `EvalGoldOracleTest`.
4. Keep the corpus spanning the catalog operator families — coverage is the point (N6 measures self-sufficiency *per covered operator*).
