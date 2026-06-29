# a12-dmkits — Model Review & Diff (proposal / rationale)

> **Status — rationale & decision record.** The *why* behind the human **review & inspection** surface for LLM-driven model edits, and the forks settled in the design dialogue (2026-06-28). The **contract** (verb surface, I/O, invariant, taxonomy, rendering) now lives in [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md); **open/deferred/out-of-scope** items in [`MODEL-REVIEW-LEDGER.md`](MODEL-REVIEW-LEDGER.md). This proposal keeps the problem framing and rationale (timeless); it is archived once the spec is fully built.

## 1. The problem

`dmtool` is driven, by design, by an **LLM** — a coding agent (Claude Code, Codex), Claude Cowork, or Claude Chat — editing the **structure and rules** of A12 document models in a workspace, under a user's natural-language instructions. The user then has to **oversee what happened**: many changes, across models that are **too large to open or diff as raw JSON**.

"Too big to diff" is the symptom. There are three real sub-problems, and the third is the crux:

| # | Sub-problem | Why it's hard here |
|---|---|---|
| **A. Baseline** | review needs a *before* | the agent edits **in place**; without an anchor there is nothing to compare against |
| **B. Altitude** | raw JSON is the wrong representation | opaque condition strings; deep type-discriminated nesting; locale maps; relative paths; non-deterministic write order makes even identical models diff noisily |
| **C. Trust** | the reviewer is overseeing an **unreliable narrator** | the human is checking up on an LLM — if the review surface is *the agent's own summary*, the same untrusted party reports on itself |

**C is the governing principle.** The project's thesis — *"valid means the engine accepts it, not that it looks right"* — has an exact review corollary: **"changed means the tool computed the change from two model states, not what the agent claims."** Every part of the design is *tool-computed truth*, never agent narration (SPEC §1).

## 2. Review is comprehension + change-review

Reducing the problem to "diff" is too narrow: you cannot judge a change without understanding the thing. The human has six jobs; diff is only J5:

| # | "I need to…" | Answered by (existing primitive) | State |
|---|---|---|---|
| **J1** | orient — what is this model, how big, valid, related to what | `model info`, `workspace list`/`graph` | ✅ data exists |
| **J2** | understand structure — the group/field tree, kinds, constraints | `model describe`, `field read` | ✅ data exists |
| **J3** | understand logic — every rule/computation **in plain terms**, polarity explicit, with its message | `rule explain` (per element) | ⚠️ per-element only, not assembled |
| **J4** | trace dependencies — field→rules, rule→fields, dead set, includes | `where-used`, `rule deps`, `model usage` | ✅ data exists |
| **J5** | review change — what the agent did, risk-ranked | — | ❌ missing |
| **J6** | judge health — kernel-valid? suspicious? dead weight? | `model check`, `workspace roles` | ✅ data exists |

The reuse-first finding: **the comprehension *data* (J1–J4, J6) is largely already built** — scattered across agent-facing verbs that emit JSON one element at a time. What is missing is two layers: a **human-facing assembly** (rules in plain language, navigable) and the **change/diff overlay (J5)**. Neither is "more data verbs."

## 3. Decisions (the forks settled, and why)

| Fork | Decision | Why |
|---|---|---|
| **Verb surface** | two verbs, one shared engine — `model report` + `model diff` | matches the *verb = operation, target = scope* scheme; each verb stays lean; they share the assembly+gloss+risk engine |
| **Baseline** | pure two-file core + a `--since <ref>` git convenience | keeps a git-free, testable engine (diffs two model states); `--since` is ergonomic sugar — dmtool stays a pure model tool |
| **v1 scope** | single model; `workspace` rollup deferred | bound the first build; the single-model engine is what the rollup reuses |
| **v1 contents** | both `report` and `diff` | comprehension is the backdrop for review; the gloss engine is shared |
| **Gloss authorship** | Hybrid — tool gloss authoritative + facts + verbatim message; agent enrichment marked, never replacing | preserves tool-truth (C) while staying fluent; the independent gloss enables the gloss-vs-message drift signal |
| **First-write churn** | an explicit `model normalize` verb | canonicalizing a foreign model is a deliberate, isolated, reviewable step — never buried in a content edit |
| **Deterministic writer** | a Phase-0 foundation (not optional hygiene) | the trust invariant *no change → no diff*, plus sane `git blame`/merges, rest on it |
| **Visualization** | mermaid as a narrow accent; bespoke HTML out | dmtool's data is tree + text; only workspace-topology and scoped-dependency are graph-shaped; in-chat HTML is standardized only via MCP Apps (an MCP-server commitment) |
| **CI gate, textconv** | deferred satellites | the CI/PR-web contexts were not prioritized; both are trivial to add later from the JSON |

A note on framing the diff (the dialogue's terminology conclusion): **`model diff` is a *structural* semantic diff** (parse + compare structure, format-invariant); **`--deep` is the *behavioral* upgrade**; the umbrella **`model review` ⊋ semantic diff** (it adds comprehension + risk + gloss). The three-level ladder and the completeness gaps are in SPEC §5e and LEDGER §1.

## 4. Spike outcome — GO (2026-06-30)

A first spike built the structural-diff vertical end-to-end and de-risked the foundation. What it proved:

- **The trust foundation is essentially already in place.** Characterizing the round-trip (tasks #1/#2) showed the writer is **already order-preserving, deterministic, and a stable fixed point** (tree surgery, not POJO re-serialization); the *only* non-idempotency is one-time **formatting** churn, and on a normalized model an edit is **fully localized**. So Phase 0 shrank from "build an order-preserving writer" to "ship `model normalize` + guards" (SPEC §2b, LEDGER §1).
- **The diff approach holds.** `ModelDiff` (in `:adapter`) matches fields + rules into added/removed/modified with per-aspect deltas and a risk tier, and — verified on real fixtures — **ignores reformatting** (the structural-diff payoff). It's shipped as the self-describing `dmtool model diff` verb (manifest/`schema model diff`/`--help`/demonstrated, all conformance-gated) and a cli-tour walkthrough.
- **Dogfooding signal: positive.** Building the engine as a *consumer* of the kits reused the typed surface cleanly — `KernelAdapter` (`ruleFullNames`/`canonicalConditionText`/`ruleSeverity`/`ruleErrorCode`), `BoundModel.fields()`/`FieldInfo` — with **no friction**, so no new `DEV-API-GAPS` entry. The kernel-canonical condition text gave format-independent rule comparison for free.

**Decision: GO** — proceed to the full build. What remains is tracked in the LEDGER: the completeness gaps (`MR1` AST-level condition diff, `MR2` reorder-as-change, `MR3` robust move-matching, `MR4` relational grouping, `MR5` compact/risk-grouped `--text`), the `md`+mermaid rendering, `model report` (comprehension), the `workspace` rollup, and `model normalize` + its guards.

## 5. Where the detail lives

- **Contract** (verbs, I/O shapes, the Phase-0 invariant + order policy, the change model + change kinds, the risk taxonomy, polarity detection, the gloss contract, the rendering & mermaid policy, self-describing/no-rot, the reuse map): [`MODEL-REVIEW-SPEC.md`](MODEL-REVIEW-SPEC.md).
- **Open / deferred / out-of-scope** (the completeness gaps `MR1`–`MR4`, the deferred satellites with their re-open triggers, the won't-build decisions): [`MODEL-REVIEW-LEDGER.md`](MODEL-REVIEW-LEDGER.md).
- **Progress**: git + the task list; this proposal is archived once the spec is fully built.
