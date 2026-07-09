# a12-dmkits — Model Review & Diff (gaps)

> **What this is** — the open-issues tracker for the model **review & inspection** surface (the `-GAPS` family, peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md) / [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md)). The **contract + rationale** are in [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md) (rationale in its §9). **Records only OPEN work** — a shipped gap is **deleted** (git + the SPEC section / its finding are the record, no "shipped" list). `MR<n>` ids are stable so a code comment or SPEC ref resolves. §1 open (tractable, to build), §2 deferred (with a re-open trigger each), §3 out-of-scope (won't build).

## 1. Open — tractable, to build

The completeness gaps between the structural diff and a *complete* one (SPEC §5e). **None currently open** — the diff-completeness set (reorder-as-change, exact + similarity move/rename matching, relational refactor grouping, and the located condition edit-script) has shipped; the contract lives in [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md) §5. New gaps land here as found.

## 2. Deferred — with a re-open trigger

Built only when the trigger fires (the disposition is expected to change; it lives here, not as a durable "why").

- **`workspace report` / `workspace diff` rollup** (Phase 2). *Re-open when* multi-model oversight per agent session proves the dominant need — then scope up the single-model engine to a per-model rollup (models added/removed, includes rewired). **Design constraint (SPEC §9d audit):** it must NOT duplicate `workspace list` (the model index) or `workspace check` (the validation gate) — it is the per-model report/diff *aggregator*, non-overlapping by construction.
- **`--deep` behavioral polarity / equivalence diff.** The SeedGenerator + interpreter fire-set differential (SPEC §5c). *Re-open when* structural polarity detection proves insufficient on real diffs (false negatives on non-operator-flip inversions, or a need to report "re-expressed but behaviorally identical").
- **CI / PR gate.** `workspace diff` JSON → fail the build on an unreviewed high-risk change. *Re-open when* the CI/PR-gate context is prioritized (it was not chosen for v1).
- **git `textconv` driver.** A `.gitattributes` textconv rendering `.dm.json` through a readable projection so raw `git diff` / GitHub PR view is legible without running the tool. *Re-open when* GitHub-PR-web review becomes a target context (superseded for the chosen contexts by `model diff` + `report --format text`).
- **MCP Apps HTML widget.** In-chat interactive HTML via the official MCP Apps standard (sandboxed iframe). *Re-open when* dmtool ships as an MCP server (today it is a CLI invoked over Bash, so the standard doesn't apply).
- **Risk-policy configurability.** A knob to reclassify reason codes per team. *Re-open when* a consumer needs it (consumer-exists discipline); v1 is a fixed taxonomy (SPEC §5b).

## 3. Out-of-scope — won't build

- **A bespoke HTML report app** (outside the MCP Apps standard). It is a maintenance/rot surface, hard to CI-gate, and pins one consumption context — the research (Playwright structured-text-first; Neo4j data-modeling emits mermaid *text*, not an app) and the trust principle argue against it. The text/markdown + narrow-mermaid surface (SPEC §6) covers the chosen contexts; standardized in-chat HTML is the deferred MCP Apps path (§2).
- **Byte-matching the A12 editor's exact formatting.** Brittle and not worth it; the answer is the deliberate `model normalize` (SPEC §2c), not chasing a foreign formatter.
- **A whole-model dependency graph rendered as mermaid.** A 150-rule × 200-field hairball nobody can read; dependency stays tabular and the human scopes in (SPEC §6). Only *scoped* dependency/diff-impact subgraphs use mermaid.
