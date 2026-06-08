# a12-dmkits — self-sufficiency benchmark corpus

The curated task set for the N6 self-sufficiency eval. Method, scoring, and protocol live in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md); measured **results** are logged in [`RESULTS.md`](RESULTS.md) (dated, newest first); this directory holds the **corpus** and the **runner entry points** (`benchmark.workflow.js`).

## Layout

- `tasks/*.json` — one task per file (schema below). Each authoring task carries a **kernel-verified gold** answer. All fixtures/rules here are our own originals (license-safe, per the project `CLAUDE.md`).

## Task schema

```json
{
  "id": "T1",
  "family": "authoring" | "authoring-computation" | "modify-computation" | "modify-rule" | "comprehension",
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

The `gold` shape depends on the family:
- **authoring / modify-rule** — `{field, condition, code}` (above). For *modify-rule* the `condition` is the intended re-expression, keeping the same rule (named by `rule`) + error field.
- **authoring-computation / modify-computation** — `{computedField, alternatives: [{precondition?, operation}], commonPrecondition?}` (the computation the kernel should accept; for *modify* it's the intended re-expression, keeping the same `computedField`).
- **comprehension** — no `gold`; instead `rules: [...]` (the model's rule names to read back) + `expected` (the rubric of explanation facts).

`modelPath`/`includeDir` are optional **multi-file** keys: present only when the task model `include`s a separate model that lives outside its own directory, so the runner threads `--include-dir` through every verb. `computation` is required for `modify-computation` (the existing computation to re-express); `rule` is required for `modify-rule` (the existing rule to re-express).

## The two layers

- **Layer 1 — oracle (deterministic, CI):** `EvalGoldOracleTest` (in `:cli`) reads every task here and asserts its gold *validates against the real kernel* — authoring/computation golds via core `RuleValidator.check` (multi-file tasks with a `ReferenceResolvers.directory` resolver), modify-computation golds by replacing the named computation and re-binding, modify-rule golds by replacing the named rule's condition and re-binding. This guards that the corpus is well-formed and the golds are real — it runs on every build, no LLM involved.
- **Layer 2 — agent runner (soft, opt-in):** `benchmark.workflow.js` fans out `model × task × k` **clean subagents**, gives each only the shipped material (the **self-describing CLI** — verbs + `--help`/`manifest`/`cli operators`; no raw catalog hand-off), scores its output, and aggregates **pass@k**. Non-deterministic + token-heavy → run on demand, track pass-rate against a threshold, not a per-commit gate. Pin the model (`opus`/`sonnet`/`haiku`) for reproducibility and comparison.

## How the runner works (and how Claude Code drives it)

Claude Code (the main loop) never runs the agents itself — it **orchestrates** via the [`run-rulekit-eval`](../.claude/skills/run-rulekit-eval/SKILL.md) skill: bundle the tasks, then `Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks } })`. The workflow runs in the background and returns a pass@k aggregate; Claude then root-causes any misses and records the run in [`RESULTS.md`](RESULTS.md). It's driven from the **main loop, not a sub-agent** — the isolation the eval needs is provided one layer down by the workflow's own clean subagents (EVAL-SPEC §8c).

The script is three phases over `model × task × k`:

1. **Produce** — one **clean subagent** per (model, task, run), model pinned. Neutral prompt + ONLY the self-describing CLI + the model file (no skill/docs/source — the material boundary *is* the measurement). This is the **bare-tool** mode and it is the only one wired here — it measures whether the CLI's self-explaining surface *alone* suffices; the skill-equipped **production** mode is a separate boundary (EVAL-SPEC §2a). Returns a candidate rule (authoring) or an explanation (comprehension) via StructuredOutput.
2. **Recheck** — every candidate is re-validated against the real kernel (the agents' self-reported `kernelAccepted` is not trusted): rules via **one** warm-JVM `cli batch` of `check` (N checks in one process — `batch`'s reason to exist), computations via `cli add` (validates before persisting), modifications via `cli modify … --dry-run` (replace-then-bind, read-only, which also reports `changed` — a modify must be ACCEPTED *and* an actual change).
3. **Judge** — a separate agent scores each kernel-valid candidate for **semantic equivalence** to the gold (authoring) / correctness vs the rubric (comprehension) — catching kernel-valid-but-*wrong* rules (e.g. inverted polarity). `pass = kernel-valid AND judge-equivalent`.

Phases pipeline where they can; the whole run is one background `Workflow`. A 0/k cell is **not** automatically a real gap — separate genuine gaps (fix the skill/CLI) from eval-design artifacts (fix the harness), per EVAL-SPEC §3 and the worked cases in `RESULTS.md`.

## Adding / changing a task

1. Add a `tasks/<id>.json` with a fresh `id`, the `family`, a real `model`, the `operators` it covers, the `intent`, and a `gold` you've confirmed the kernel accepts. Verify per family: authoring → `cli check <model> --field … --condition "…" --code …` → `valid:true`; computation → `cli add <model> <spec.json>` → exit 0; modify-computation → `cli modify <model> --computation <name> --spec <spec.json> --dry-run` → exit 0 with `changed:true`; modify-rule → `cli modify <model> --rule <name> --condition "…" --dry-run` → exit 0 with `changed:true`; multi-file → add `--include-dir <lib>` and the model's `modelPath`/`includeDir` keys.
2. `EvalGoldOracleTest` picks it up automatically; run `./gradlew :cli:test` — a red oracle means the gold doesn't actually validate (fix the gold, not the test).
3. Keep the corpus spanning the catalog operator families — coverage is the point (N6 measures self-sufficiency *per covered operator*).
