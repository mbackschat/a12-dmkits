# Model Review & Diff — gaps

> **What this is.** The `-GAPS` tracker for the model **review & inspection** surface (peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md) / [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md)). The **contract + rationale** live in [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md) (rationale in its §9). `MR<n>` ids are stable, so a code comment or SPEC ref resolves.
>
> **Lifecycle.** A gap lives here only while open: §1 open (tractable, to build), §2 deferred (with a re-open trigger each), §3 out-of-scope (won't build). When a gap ships its row is **deleted** in the same commit and its rationale graduates to the SPEC section or its finding — git is the *what/when*. There is no "shipped" list. New gaps land in §1 only when they are open and actionable.
>
> **Current state.** §1 is drained. The implemented change model and completeness contract live in [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md) §5.

## 1. Open

_None open._

## 2. Deferred — with re-open triggers

Built only when the trigger fires (the disposition is expected to change; it lives here, not as a durable "why").

- **`workspace report` / `workspace diff` rollup** (Phase 2). *Re-open when* multi-model oversight per agent session proves the dominant need — then scope up the single-model engine to a per-model rollup (models added/removed, includes rewired). **Design constraint (SPEC §9d audit):** it must NOT duplicate `workspace list` (the model index) or `workspace check` (the validation gate) — it is the per-model report/diff *aggregator*, non-overlapping by construction.
- **`--deep` bounded behavioral witness search.** Generate deterministic examples for changed rules, evaluate both states with the interpreter, and report a concrete input when their fire sets differ. Finite sampling can establish a difference but cannot prove equivalence: exhausting the budget yields only `NO_DIFFERENCE_OBSERVED`, never “behaviorally identical,” and never lowers the structural risk classification. *Re-open when* a real diff exposes a structural false negative that a generated witness would make reviewable. If built, scope it to changed rules with explicit seed/resource budgets and differential/performance guards.
- **CI / PR gate.** `workspace diff` JSON → fail the build on an unreviewed high-risk change. *Re-open when* the CI/PR-gate context is prioritized (it was not chosen for v1).
- **MCP Apps HTML widget.** In-chat interactive HTML via the official MCP Apps standard (sandboxed iframe). *The re-open trigger fired 2026-08-06* — dmtool ships an MCP server ([`MCP-SPEC`](MCP-SPEC.md)) — and the row moved with it: MCP Apps is an independently versioned extension of that surface, so it is tracked as **MG2** in [`MCP-GAPS`](MCP-GAPS.md) with its own trigger (the report surface being wanted in-chat rather than as a file). Nothing about the review artifacts changes here.
- **Document uniqueness criteria as their own element kind.** `model diff` flags a removed field a base criterion names (`REFERENCED_ELEMENT_REMOVED`), but an added, removed or edited criterion is no change of its own, and `model info` counts none. Removing a mount whose included field a base criterion named is flagged nowhere, since the mounted field has no authored field row and the group row reads rule referrers only. *Re-open when* a review needs the criterion edits themselves ([KF671](KERNEL-FINDINGS.md#kf671)).
- **Risk-policy configurability.** A knob to reclassify reason codes per team. *Re-open when* a consumer needs it (consumer-exists discipline); v1 is a fixed taxonomy (SPEC §5b).

## 3. Out of scope

- **A bespoke HTML report app** (outside the MCP Apps standard). It is a maintenance/rot surface, hard to CI-gate, and pins one consumption context — the research (Playwright structured-text-first; Neo4j data-modeling emits mermaid *text*, not an app) and the trust principle argue against it. The text/markdown + narrow-mermaid surface (SPEC §6) covers the chosen contexts; standardized in-chat HTML is the deferred MCP Apps path (§2).
- **Byte-matching the A12 editor's exact formatting.** Brittle and not worth it; the answer is the deliberate `model normalize` (SPEC §2c), not chasing a foreign formatter.
- **A whole-model dependency graph rendered as mermaid.** A 150-rule × 200-field hairball nobody can read; dependency stays tabular and the human scopes in (SPEC §6). Only *scoped* dependency/diff-impact subgraphs use mermaid.
