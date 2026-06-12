# a12-dmkits — self-sufficiency benchmark corpus

The curated task set for the N6 self-sufficiency eval. Method, scoring, and protocol live in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md); measured **results** are logged in [`RESULTS.md`](RESULTS.md) (dated, newest first); this directory holds the **corpus** and the **runner entry points** (`benchmark.workflow.js`).

## Layout

- `tasks/<kit>/<NN>-<slug>.json` — one task per file, grouped by **kit** subfolder: `rulekit/` (rules + computations + their comprehension/self-correction), `modelkit/` (structure: field/group/typedef/include/config + delete-safety + refactor), `mixed/` (one `apply` session touching both). **The folder is the kit** (the explicit axis — no derived map); the task `id` is the filename stem (e.g. `01-pro-addon-fee-required`). Each authoring task carries a **kernel-verified gold** answer. All fixtures/rules here are our own originals (license-safe, per the project `CLAUDE.md`). Run tiers (smoke/simple/full) + the categorization are in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md) §8d.

## Task schema

```json
// in eval/tasks/rulekit/01-pro-addon-fee-required.json — the folder (rulekit) is the kit; the id is the filename stem
{
  "id": "01-pro-addon-fee-required",             // = the filename stem (no separate T<N>)
  "family": "authoring" | "authoring-computation" | "modify-computation" | "modify-rule" | "comprehension" | "self-correction"   // rulekit/
           // | "structure" | "delete-safety" | "refactor"   // modelkit/      | "session"   // mixed/
  "difficulty": "simple" | "medium" | "complex", // intrinsic tag (EvalSuiteLedgerTest requires it)
  "suites": ["smoke", "simple"],                 // optional run-tier membership (derive a tier by filtering on this)
  "model": "subscription",                       // a fixture name under cli/src/test/resources/models/
  "modelPath": "cli/src/test/resources/models/multifile/app/storefront.dm.json", // multi-file: explicit (repo-relative) path
  "includeDir": "cli/src/test/resources/models/multifile/lib",                    // multi-file: threaded as --include-dir
  "computation": "/Subscription/Billing/EffectiveFeeComp",                        // modify-computation: the existing computation's full name
  "rule": "/Order/DeliveryNotBeforeOrder",                                        // modify-rule: the existing rule's full name
  "operators": ["GroupFilled", "Equal", ...],    // catalog families this task exercises (coverage axis)
  "intent": "When a subscription's Tier is PRO, each add-on's MonthlyFee must be provided.",
  "gold": {                                        // authoring: the expected, kernel-verified answer
    "field": "/Subscription/Addons/MonthlyFee",    //   error field (absolute path)
    "condition": "GroupFilled(...) And [/Subscription/Tier] == \"PRO\" And FieldNotFilled(MonthlyFee)",
    "code": "PRO_ADDON_FEE_REQUIRED"
  }
}
```

**`kit`** is **not a field** — it is the task's subfolder (`rulekit`/`modelkit`/`mixed`), so it can't drift from a map (`EvalSuiteLedgerTest`). The `gold` shape depends on the family:
- **authoring / self-correction / modify-rule** — `{field, condition, code}` (above). For *modify-rule* the `condition` is the intended re-expression, keeping the same rule (named by `rule`) + error field; *self-correction*'s gold is the recovered rule.
- **authoring-computation / modify-computation** — `{computedField, alternatives: [{precondition?, operation}], commonPrecondition?}` (the computation the kernel should accept; for *modify* it's the intended re-expression, keeping the same `computedField`).
- **comprehension** — no `gold`; instead `rules: [...]` (the model's rule names to read back) + `expected` (the rubric of explanation facts).
- **structure / delete-safety / session** (the `modelkit/`+`mixed/` families) — `{op, group/parent, name, kind, expect…}` for a structure edit, `{op, field, cascade, expectRemoved}` for a safe-delete, `{ops:[…], expect:{field, rule}}` for an `apply` session. **Verified by `EvalGoldOracleTest`** (the gold edit re-binds + the post-conditions hold). The **`structure`** family also has a Layer-2 agentic path; **`refactor`** (rename) stays `status:"deferred"` until the Phase-4 rename engine (EVAL-SPEC §8d, **B**).

`modelPath`/`includeDir` are optional **multi-file** keys: present only when the task model `include`s a separate model that lives outside its own directory, so the runner threads `--include-dir` through every verb. `computation` is required for `modify-computation` (the existing computation to re-express); `rule` is required for `modify-rule` (the existing rule to re-express).

## The two layers

- **Layer 1 — oracle (deterministic, CI):** `EvalGoldOracleTest` (in `:cli`) reads every task here and asserts its gold *validates against the real kernel* — authoring/computation golds via core `RuleValidator.check` (multi-file tasks with a `ReferenceResolvers.directory` resolver), modify golds by replacing the named computation/rule and re-binding, and **structure/delete-safety/session golds via `EditSession`** (apply the gold edit → re-bind → assert the expected element exists / the cascade set is gone / the session's field+rule are present). This guards that the corpus is well-formed and the golds are real — it runs on every build, no LLM involved. (The `refactor`/rename gold is `status:"deferred"` until Phase 4.)
- **Layer 2 — agent runner (soft, opt-in):** `benchmark.workflow.js` fans out `model × task × k` **clean subagents**, gives each only the shipped material (the **self-describing CLI** — verbs + `--help`/`manifest`/`cli operators`; no raw catalog hand-off), scores its output, and aggregates **pass@k**. Non-deterministic + token-heavy → run on demand, track pass-rate against a threshold, not a per-commit gate. Pin the model (`opus`/`sonnet`/`haiku`) for reproducibility and comparison.

## How the runner works (and how Claude Code drives it)

Claude Code (the main loop) never runs the agents itself — it **orchestrates** via the [`run-rulekit-eval`](../.claude/skills/run-rulekit-eval/SKILL.md) skill: build the launcher (`:cli:installDist`), **mirror the model fixtures into a sandbox (`bash eval/prep-sandbox.sh`)**, bundle the tasks, then `Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks } })`. The workflow runs in the background and returns a pass@k aggregate; Claude then root-causes any misses and records the run in [`RESULTS.md`](RESULTS.md). It's driven from the **main loop, not a sub-agent** — the isolation the eval needs is provided one layer down by the workflow's own clean subagents (EVAL-SPEC §8c).

**Committed fixtures are never mutated.** The authoring verbs write in place by default, so a produce agent pointed at a committed fixture can corrupt it — it has, repeatedly (EVAL-SPEC §8c). So `eval/prep-sandbox.sh` mirrors `cli/src/test/resources/models` into `/tmp/rk-eval-sandbox` and the runner resolves every model path there; the operator runs that script (it's a mutating setup, which a sandboxed subagent can't be granted permission for). The workflow's first phase only **verifies** the sandbox is present and halts loudly if not.

The script is four phases over `model × task × k`:

0. **Prep** — verify (read-only) that the operator built the `/tmp` model sandbox; halt the run if absent.
1. **Produce** — one **clean subagent** per (model, task, run), model pinned. Neutral prompt + ONLY the self-describing CLI + the (sandboxed) model file (no skill/docs/source — the material boundary *is* the measurement). This **bare-tool** mode is the default and the CI/trend instrument — it measures whether the CLI's self-explaining surface *alone* suffices. The skill-equipped **production** mode is opt-in (`args.skillPath: "skill/SKILL.md"` — the producer reads the verbatim skill — or inlined `args.skill`), a separate boundary requiring the post-run audit (EVAL-SPEC §2a). Returns a candidate rule (authoring) or an explanation (comprehension) via StructuredOutput.
2. **Recheck** — every candidate is re-validated against the real kernel (the agents' self-reported `kernelAccepted` is not trusted): rules via **one** warm-JVM `cli batch` of `check` (N checks in one process — `batch`'s reason to exist), computations via `cli add` (validates before persisting), modifications via `cli modify … --dry-run` (replace-then-bind, read-only, which also reports `changed` — a modify must be ACCEPTED *and* an actual change).
3. **Judge** — a separate agent scores each kernel-valid candidate for **semantic equivalence** to the gold (authoring) / correctness vs the rubric (comprehension) — catching kernel-valid-but-*wrong* rules (e.g. inverted polarity). `pass = kernel-valid AND judge-equivalent`.

Phases pipeline where they can; the whole run is one background `Workflow`. A 0/k cell is **not** automatically a real gap — separate genuine gaps (fix the skill/CLI) from eval-design artifacts (fix the harness), per EVAL-SPEC §3 and the worked cases in `RESULTS.md`.

## Adding / changing a task

1. Add a `tasks/<id>.json` with a fresh `id`, the `family`, a real `model`, the `operators` it covers, the `intent`, and a `gold` you've confirmed the kernel accepts. Verify per family: authoring → `cli check <model> --field … --condition "…" --code …` → `valid:true`; computation → `cli add <model> <spec.json>` → exit 0; modify-computation → `cli modify <model> --computation <name> --spec <spec.json> --dry-run` → exit 0 with `changed:true`; modify-rule → `cli modify <model> --rule <name> --condition "…" --dry-run` → exit 0 with `changed:true`; multi-file → add `--include-dir <lib>` and the model's `modelPath`/`includeDir` keys.
2. `EvalGoldOracleTest` picks it up automatically; run `./gradlew :cli:test` — a red oracle means the gold doesn't actually validate (fix the gold, not the test).
3. Keep the corpus spanning the catalog operator families — coverage is the point (N6 measures self-sufficiency *per covered operator*).
