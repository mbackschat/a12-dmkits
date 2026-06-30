# a12-dmkits — Model Review & Diff (gaps ledger)

> **What this is** — the open-issues ledger for the model **review & inspection** surface (the `-LEDGER` family, peer of [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md) / [`INTERPRETER-GAPS-LEDGER.md`](INTERPRETER-GAPS-LEDGER.md)). The **contract** is [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md); the **rationale** is [`MODEL-REVIEW-PROPOSAL.md`](MODEL-REVIEW-PROPOSAL.md). **Records only OPEN work** — a shipped gap is **deleted** (git + the SPEC section / its finding are the record, no "shipped" list). `MR<n>` ids are stable so a code comment or SPEC ref resolves. §1 open (tractable, to build), §2 deferred (with a re-open trigger each), §3 out-of-scope (won't build).

## 1. Open — tractable, to build

These are the completeness gaps between the v1 structural diff and a *complete* one (SPEC §5e), plus the foundation verification in flight.

- **MR1 — AST-level condition edit-script.** The polarity-inversion half is **shipped** (`ConditionPolarity` proves a structural flip → `POLARITY_INVERTED` HIGH; SPEC §5c level 1). What remains is the fuller **edit script** over the two condition ASTs — "`<`→`>=`", "an `And` clause referencing X was added", "threshold 1000→2000" — so a `CONDITION_CHANGED` says *what* moved, not just *that* it did. The ASTs already exist (`KernelAdapter.read` → `ReadResult.Converted(Condition)`). *(SPEC §5a/§5e.)*
- **MR2 — reorder-as-change in the renderings.** A pure element reorder is a display-order (hence semantic, SPEC §2b) change; it is a tracked change kind (SPEC §5a `element position`) but is not yet surfaced in the `md`/`text` output. *(SPEC §5f.)*
- **MR3 — robust move/rename + simultaneous-edit matching.** The v1 matcher is conservative content-fingerprint pairing (unsure ⇒ remove+add). A similarity-based matcher (GumTree-style) would survive a rename *and* edit in the same change. *(SPEC §5.)*
- **MR4 — relational refactor grouping.** Present a reference-rewriting refactor (rename/move that rewrote N referrers) as **one** semantic operation, not N independent edits. The pieces exist (refactors report `rewroteReferences`); the diff should fold them. *(SPEC §5a.)*
- **MR5 — compact verb-specific `--text` (diff *and* report).** Both `model diff` and `model report` `--text` currently ride the generic envelope tree (the `summary` headlines them), but the SPEC vision is compact verb-shaped renderings: the diff *risk-sorted, low-risk-collapsed* (§5f), the report an *indented structure outline + glossed catalog* (§4). A bespoke formatter is deferred for both — it wants a clean **per-verb text-render mechanism** (a registry the `--text` boundary dispatches to), since forcing per-verb branches into the deliberately-generic `TextRenderer` is a smell and a broad generic change risks the `--text` golden tests. The diff's needs the risk ranking as its grouping key.

## 2. Deferred — with a re-open trigger

Built only when the trigger fires (the disposition is expected to change; it lives here, not as a durable "why").

- **`workspace report` / `workspace diff` rollup** (Phase 2). *Re-open when* multi-model oversight per agent session proves the dominant need — then scope up the single-model engine to a per-model rollup (models added/removed, includes rewired). **Design constraint (PROPOSAL §6 audit):** it must NOT duplicate `workspace list` (the model index) or `workspace check` (the validation gate) — it is the per-model report/diff *aggregator*, non-overlapping by construction.
- **`--deep` behavioral polarity / equivalence diff.** The SeedGenerator + interpreter fire-set differential (SPEC §5c). *Re-open when* structural polarity detection proves insufficient on real diffs (false negatives on non-operator-flip inversions, or a need to report "re-expressed but behaviorally identical").
- **CI / PR gate.** `workspace diff` JSON → fail the build on an unreviewed high-risk change. *Re-open when* the CI/PR-gate context is prioritized (it was not chosen for v1).
- **git `textconv` driver.** A `.gitattributes` textconv rendering `.dm.json` through a readable projection so raw `git diff` / GitHub PR view is legible without running the tool. *Re-open when* GitHub-PR-web review becomes a target context (superseded for the chosen contexts by `model diff` + `report --format text`).
- **MCP Apps HTML widget.** In-chat interactive HTML via the official MCP Apps standard (sandboxed iframe). *Re-open when* dmtool ships as an MCP server (today it is a CLI invoked over Bash, so the standard doesn't apply).
- **Risk-policy configurability.** A knob to reclassify reason codes per team. *Re-open when* a consumer needs it (consumer-exists discipline); v1 is a fixed taxonomy (SPEC §5b).

## 3. Out-of-scope — won't build

- **A bespoke HTML report app** (outside the MCP Apps standard). It is a maintenance/rot surface, hard to CI-gate, and pins one consumption context — the research (Playwright structured-text-first; Neo4j data-modeling emits mermaid *text*, not an app) and the trust principle argue against it. The text/markdown + narrow-mermaid surface (SPEC §6) covers the chosen contexts; standardized in-chat HTML is the deferred MCP Apps path (§2).
- **Byte-matching the A12 editor's exact formatting.** Brittle and not worth it; the answer is the deliberate `model normalize` (SPEC §2c), not chasing a foreign formatter.
- **A whole-model dependency graph rendered as mermaid.** A 150-rule × 200-field hairball nobody can read; dependency stays tabular and the human scopes in (SPEC §6). Only *scoped* dependency/diff-impact subgraphs use mermaid.
