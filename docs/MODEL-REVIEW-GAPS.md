# a12-dmkits — Model Review & Diff (gaps)

> **What this is** — the open-issues tracker for the model **review & inspection** surface (the `-GAPS` family, peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md) / [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md)). The **contract + rationale** are in [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md) (rationale in its §9). **Records only OPEN work** — a shipped gap is **deleted** (git + the SPEC section / its finding are the record, no "shipped" list). `MR<n>` ids are stable so a code comment or SPEC ref resolves. §1 open (tractable, to build), §2 deferred (with a re-open trigger each), §3 out-of-scope (won't build).

## 1. Open — tractable, to build

- **MR9 — complete `model diff` across the rest of the §5a change-kind taxonomy.** Computations and groups are now projected completely, and reorder detection uses the authored mixed element sequence; the remaining silent false “no change” cases are **typedefs/includes/config**, field **unit/format/metadata/requiredness/enum values**, and rule **error entity/messages/metadata**. [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md) §5a marks the built subset. **Build order remaining:** complete fields/rules, typedefs/includes, then config; each added aspect carries the loosening>tightening risk direction (§5b). **Lock:** one focused `model diff` test family per added projection.

The diff-completeness set that shipped (reorder-as-change, exact + similarity move/rename matching, relational refactor grouping, the located condition edit-script) is the *matching* machinery, not element-family coverage — its contract lives in [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md) §5. New gaps land here as found.

## 2. Deferred — with a re-open trigger

Built only when the trigger fires (the disposition is expected to change; it lives here, not as a durable "why").

- **`workspace report` / `workspace diff` rollup** (Phase 2). *Re-open when* multi-model oversight per agent session proves the dominant need — then scope up the single-model engine to a per-model rollup (models added/removed, includes rewired). **Design constraint (SPEC §9d audit):** it must NOT duplicate `workspace list` (the model index) or `workspace check` (the validation gate) — it is the per-model report/diff *aggregator*, non-overlapping by construction.
- **`--deep` bounded behavioral witness search.** Generate deterministic examples for changed rules, evaluate both states with the interpreter, and report a concrete input when their fire sets differ. Finite sampling can establish a difference but cannot prove equivalence: exhausting the budget yields only `NO_DIFFERENCE_OBSERVED`, never “behaviorally identical,” and never lowers the structural risk classification. *Re-open when* a real diff exposes a structural false negative that a generated witness would make reviewable. If built, scope it to changed rules with explicit seed/resource budgets and differential/performance guards.
- **CI / PR gate.** `workspace diff` JSON → fail the build on an unreviewed high-risk change. *Re-open when* the CI/PR-gate context is prioritized (it was not chosen for v1).
- **MCP Apps HTML widget.** In-chat interactive HTML via the official MCP Apps standard (sandboxed iframe). *Re-open when* dmtool ships as an MCP server (today it is a CLI invoked over Bash, so the standard doesn't apply).
- **Risk-policy configurability.** A knob to reclassify reason codes per team. *Re-open when* a consumer needs it (consumer-exists discipline); v1 is a fixed taxonomy (SPEC §5b).

## 3. Out-of-scope — won't build

- **A bespoke HTML report app** (outside the MCP Apps standard). It is a maintenance/rot surface, hard to CI-gate, and pins one consumption context — the research (Playwright structured-text-first; Neo4j data-modeling emits mermaid *text*, not an app) and the trust principle argue against it. The text/markdown + narrow-mermaid surface (SPEC §6) covers the chosen contexts; standardized in-chat HTML is the deferred MCP Apps path (§2).
- **Byte-matching the A12 editor's exact formatting.** Brittle and not worth it; the answer is the deliberate `model normalize` (SPEC §2c), not chasing a foreign formatter.
- **A whole-model dependency graph rendered as mermaid.** A 150-rule × 200-field hairball nobody can read; dependency stays tabular and the human scopes in (SPEC §6). Only *scoped* dependency/diff-impact subgraphs use mermaid.
