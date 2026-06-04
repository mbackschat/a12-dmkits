# a12-rulekit — self-sufficiency benchmark corpus

The curated task set for the N6 self-sufficiency eval. Method, scoring, and protocol live in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md); this directory holds the **corpus** and the **runner entry points**.

## Layout

- `tasks/*.json` — one task per file (schema below). Each authoring task carries a **kernel-verified gold** answer. All fixtures/rules here are our own originals (license-safe, per the project `CLAUDE.md`).

## Task schema

```json
{
  "id": "T1",
  "family": "authoring" | "comprehension",
  "model": "subscription",                       // a fixture name under cli/src/test/resources/models/
  "operators": ["GroupFilled", "Equal", ...],    // catalog families this task exercises (coverage axis)
  "intent": "When a subscription's Tier is PRO, each add-on's MonthlyFee must be provided.",
  "gold": {                                        // authoring: the expected, kernel-verified answer
    "field": "/Subscription/Addons/MonthlyFee",    //   error field (absolute path)
    "condition": "GroupFilled(...) And [/Subscription/Tier] == \"PRO\" And FieldNotFilled(MonthlyFee)",
    "code": "PRO_ADDON_FEE_REQUIRED"
  }
}
```

(Comprehension tasks instead point at an embedded rule + the expected explanation facts — added with the rules-bearing fixture.)

## The two layers

- **Layer 1 — oracle (deterministic, CI):** `EvalGoldOracleTest` (in `:cli`) reads every task here and asserts its gold *validates against the real kernel* (via core `RuleValidator.check`). This guards that the corpus is well-formed and the golds are real — it runs on every build, no LLM involved.
- **Layer 2 — agent runner (soft, opt-in):** `benchmark.workflow.js` (draft) fans out `model × task × k` **clean subagents**, gives each only the shipped material (the CLI launcher + catalog), scores its output via the CLI (`check` accepts + matches gold), and aggregates **pass@k**. Non-deterministic + token-heavy → run on demand, track pass-rate against a threshold, not a per-commit gate. Pin the model (`opus`/`sonnet`/`haiku`) for reproducibility and comparison. **Depends on the `batch` CLI verb** (amortizes JVM startup + kernel warm-up across the many checks).

## Adding / changing a task

1. Add a `tasks/<id>.json` with a fresh `id`, the `family`, a real `model`, the `operators` it covers, the `intent`, and (authoring) a `gold` you've confirmed the kernel accepts (`cli check <model> --field … --condition "…" --code …` → `valid:true`).
2. `EvalGoldOracleTest` picks it up automatically; run `./gradlew :cli:test` — a red oracle means the gold doesn't actually validate (fix the gold, not the test).
3. Keep the corpus spanning the catalog operator families — coverage is the point (N6 measures self-sufficiency *per covered operator*).
