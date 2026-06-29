# a12-dmkits — Model Review & Diff (spec)

> **Status — target contract, built out by the spike.** The stable contract for the human **review & inspection** surface over LLM-driven model edits. Sections describe the intended I/O and behaviour; they graduate from *target* to *implemented* as the spike (and follow-on work) ships them. **Rationale** (the *why*, the forks, the dialogue) lives in [`MODEL-REVIEW-PROPOSAL.md`](MODEL-REVIEW-PROPOSAL.md); **open/deferred/out-of-scope** items live in [`MODEL-REVIEW-LEDGER.md`](MODEL-REVIEW-LEDGER.md). When everything here is built, the proposal is archived and this becomes the durable reference (and may fold into [`CLI-SPEC.md`](CLI-SPEC.md)).

## 1. Surface — two verbs, one engine

Review = **comprehension** (understand the whole model) + **change-review** (what the agent changed). One shared engine, two verbs, following the CLI's *verb = operation, target = scope* scheme:

| verb | scope | answers |
|---|---|---|
| `model report` | one model | every element rendered for comprehension (structure outline + glossed rules/computations catalog + usage) |
| `model diff` | one model vs a baseline | the structural change-set, risk-ranked |
| `model normalize` | one model | canonicalize the file once (Phase 0) so later edits diff cleanly |

```
single:    dmtool -m order.dm.json model report
diff:      dmtool -m order.dm.json model diff --since HEAD        # git convenience
diff:      dmtool model diff old.dm.json new.dm.json              # pure two-file core
```

The diff **engine is a library primitive** (the kit tier — reused by `model report` and, later, the `workspace` rollup; usable by a JVM library consumer). The verbs are thin consumers that wrap its result in the standard envelope. **Trust principle (governing):** every fact is computed by the tool from the model state(s); the surface never relies on the agent's narration.

## 2. Phase 0 — deterministic, order-preserving writer (foundation)

The trust invariant *no change → no diff* and a sane git history (`blame`, merges) rest on a deterministic writer. This gates the trustworthiness of everything below.

### 2a. The invariant (testable success criterion)

> **Idempotent round-trip:** load → write with no edits = **byte-identical** output. **Localized edits:** changing one element makes the diff touch **only** that element.

### 2b. Order policy — *preserve*, do not *sort*

Element order in DM-JSON is **semantically meaningful** (declaration order = form display order), so sorting would change the model. The policy:

| Layer | Policy |
|---|---|
| element arrays (fields/groups/rules) | **preserve** existing order on round-trip; deterministic placement for additions; never reshuffle |
| object keys (`type`/`id`/`name`/payload; locale & annotation maps) | **stable canonical order** (keys are not semantic) — the likely current leak |
| new elements | deterministic position, recorded so a later diff reads as a clean `added` |

The fix lives in the core write path (`DmJson` / `ModelWriter` / `ModelDocument`, shared by the CLI). *Note (spike-confirmed, tasks #1/#2):* the write path is Jackson **tree surgery**, so element/key order is **already preserved** — there is **no reordering**. Across all fixtures the round-trip is **deterministic** (a compact-reformatted input converges to the same output) and a **stable fixed point** (`once == twice`); the *only* divergence from the stored file is **formatting** (`"k":` vs `"k" : `, indentation/array style — Jackson's `DefaultPrettyPrinter`). And on an already-normalized model, a one-rule edit is **fully localized** (0 lines changed elsewhere, +14 added). So Phase 0 reduces to **(1)** `model normalize` (make the one-time reformat an explicit, reviewable commit) and **(2)** a stability/locality guard — **not** an order-preserving-writer rewrite, which the tree-surgery architecture already provides. (Open consideration: pick the canonical format closer to the stored fixtures to shrink the one-time churn — LEDGER §1.)

### 2c. `model normalize`

A deliberate verb that canonicalizes a foreign-authored model **once**, so the one-time reformat is an explicit, reviewable commit rather than churn smuggled into the first content edit. `-o`/`--dry-run` per convention.

### 2d. Guards

`RoundTripIdempotentTest` (load→write equals input across the fixture corpus) + `LocalizedEditDiffTest` (one edit → single-element diff) lock the invariant so a future serialization change can't reintroduce churn.

## 3. The shared engine

```
                 ┌─────────────────────────────────────────────┐
   model(s) ───▶ │  ENGINE: assemble facts → gloss+polarity →   │ ──▶ JSON  (the truth contract)
   baseline ───▶ │  diff + reference-delta + risk classify       │ ──▶ Markdown (outline+catalog+tables, mermaid accent)
                 └─────────────────────────────────────────────┘ ──▶ text   (outline+tables+ASCII tree)
                    reuses: info · describe · usage · explain ·
                            where-used · RenderedDiff · check
```

- **JSON is the trusted contract**; `md`/`text` are projections (the existing *JSON default + `--text`* pattern).
- **The glossed rules/computations catalog is the centerpiece** — every rule in plain language, grouped by the field/group it guards.
- **Token-efficiency is first-class:** scoping (`--group`, `--rules-only`, depth limits) over a 50k-token monolith.

## 4. `model report` — comprehend

Assembles identity + health (J1, J6), the **structure outline** (J2) with per-field config, the **glossed catalog** (J3), and the **dependency/usage** view (J4, the unreferenced set surfaced). Output rides the result envelope; the `data` payload is published as `model-report.schema.json` (`schema model report`, `DataSchemaConformanceTest`).

Renderings (shape × host — §6): **json** (contract) · **md** (outline + glossed catalog + tables; mermaid only for a workspace-topology block) · **text** (outline + tables + ASCII `tree`; no mermaid).

### 4a. The gloss (Hybrid C)

Per rule/computation, side by side: a **deterministic gloss** (templated from the AST + operator catalog — the citeable baseline), **polarity** (`fires on violation`), and the **verbatim stored message** (the modeller's intent). The agent may add a *marked* fluency line; it never replaces the tool gloss. The independent gloss is what enables the gloss-vs-message drift signal (§5d).

## 5. `model diff` — review

Parses **both** states into the model and compares **structure** (so JSON formatting noise never reaches the reviewer). Matches by id/path; a removed+added pair with matching content fingerprint is re-labelled `renamed`/`moved` (conservative — unsure ⇒ remove+add, the safe direction).

### 5a. Change kinds

| element | tracked |
|---|---|
| **field** | `retyped` (kind), config (min/max/pattern/scale/unit/format/length), requiredness, enum value add/remove, metadata |
| **rule** | condition (→ RenderedDiff + polarity-before/after + gloss-before/after + reference-delta), severity, code, messages |
| **computation** | operation/precondition table, computed target, messages |
| **group** | repeatability, index field, sort fields, child structure |
| **typedef / include / config** | added/removed/renamed; mount point; imports; locale/decimal/roles/charset |
| **element position** | a field/group/rule **reordered** within its parent — a display-order change, semantic per §2b |

### 5b. Risk taxonomy — *loosening > tightening*

A removed/loosened rule **silently accepts bad data** (invisible); a tightened/added rule **fails loudly** at validation (caught). So the taxonomy is asymmetric:

| Tier | Reason codes |
|---|---|
| **HIGH** | `POLARITY_INVERTED` · `RULE_REMOVED` · `REFERENCED_ELEMENT_REMOVED` · `SEVERITY_DOWNGRADED` · `CONSTRAINT_LOOSENED` (min/max widened, pattern relaxed, required→optional, enum value added) · `KIND_CHANGED`+`RETYPE_AFFECTS_READERS` · `OPAQUE_OR_CUSTOM_ADDED` |
| **MEDIUM** | `RULE_ADDED` · `CONSTRAINT_TIGHTENED` · `CONDITION_CHANGED_SAME_POLARITY` · `COMPUTATION_CHANGED` · `REPEATABILITY_CHANGED` · `STRUCTURE_ADDED/REMOVED` (unreferenced) |
| **LOW** | `MESSAGE_CHANGED` · `LOCALE_ADDED` · `METADATA_CHANGED` · `CODE_RENAMED` |

Risk codes are documented via `schema model diff` (the `data.findings`-code pattern, like `workspace roles`), not in `diagnostics`.

*(Status: a subset is built — `RULE_REMOVED`/`RULE_ADDED`/`KIND_CHANGED`/`SEVERITY_DOWNGRADED`/`CONSTRAINT_LOOSENED`/`CONSTRAINT_TIGHTENED`/`CONDITION_CHANGED`/`STRUCTURE_ADDED`/`STRUCTURE_REMOVED` and a few LOW codes, with the loosening>tightening direction for numeric bounds/lengths/pattern. The `risk` tier + `riskReasons` ride each change in `data`. `POLARITY_INVERTED` (it needs the AST diff, LEDGER MR1), `REFERENCED_ELEMENT_REMOVED`, and `RETYPE_AFFECTS_READERS` are not yet computed — a removed/re-typed field is graded structurally without its reader set.)*

### 5c. Polarity-inversion detection (layered by confidence)

1. **Structural (default):** `Not(...)` wrap, comparison flips (`<`↔`>=`), De Morgan shape changes → `confidence:high`, `basis:operator-flip`.
2. **Behavioral (`--deep`, LEDGER):** SeedGenerator + interpreter → fire-set delta; inversion ≈ complemented fire-set.

### 5d. gloss-vs-message drift signal

Because the gloss is an *independent* tool reading of the condition, placing it next to the human-authored message catches an agent's inversion: an inverted condition makes the tool gloss disagree with the unchanged message → flagged. (An agent-written gloss would restate the message and hide it — the reason for Hybrid C.)

### 5e. What kind of diff this is

Three levels: **textual/line** (`git diff`), **structural/AST** (the common "semantic diff" — parse + compare structure, format-invariant), **behavioral** (what it *does*). **`model diff` is a structural semantic diff;** `--deep` (§5c) is the behavioral upgrade (reports "re-expressed but behaviorally identical"). Scope: `model diff` *is* a semantic diff, but `model review` ⊋ semantic diff (it adds comprehension + risk + gloss — judgment, not diff). Completeness gaps (AST-level condition diff, reorder-as-change, robust move-matching, relational grouping) are tracked in LEDGER §1.

### 5f. Renderings

**json** (the change-set, `model-diff.schema.json`) · **md** (risk-sorted cards, before→after condition + polarity callout, an optional scoped diff-impact mermaid subgraph) · **text** (risk-grouped, low-risk collapsed, ASCII; no mermaid).

*(Status: `json` and the verb are built; `--text` currently rides the **generic** envelope renderer — the `summary` headlines the counts and the change-set renders as an indented tree — while the bespoke compact, risk-sorted, collapsed format is deferred to LEDGER MR5, paired with the risk ranking. The `md` artifact + mermaid accent are a later slice.)*

## 6. Rendering & the mermaid policy

dmtool is **tree + text**; only workspace-topology and scoped-dependency are graph-shaped. Match representation to the shape, not to the all-graph tools:

| view | shape | representation | mermaid? |
|---|---|---|---|
| structure (groups/fields) | deep tree, large | outline / tables | ❌ |
| rule/computation logic | gloss | prose + tables | ❌ |
| field↔rule dependency | bipartite, consulted locally | tables; mermaid for a scoped slice | ⚠️ scoped |
| cross-model includes/subtypes | graph, small | mermaid / DOT / tree | ✅ |

So mermaid is a **narrow accent**: the **workspace topology** (`workspace graph --format mermaid` — the reuse-first quick win) and the **scoped diff-impact subgraph**. Never the full structure tree, rule logic, or a whole-model dependency hairball.

**Host caveat:** mermaid renders in claude.ai (Artifact), Cursor, VS Code Copilot, GitHub/IDE previews — **not in Claude Code / the terminal** (degrades to raw source). So mermaid is always a layer on text that stands alone; the `text` format uses the ASCII tree. **HTML is out** until dmtool is an MCP server and can use the official MCP Apps standard (LEDGER §2). **Delivery:** stdout default; `-o/--out <file>` writes the artifact (existing convention).

## 7. Self-describing & no-rot

- Both verbs ride the **result envelope**; `schema model report` / `schema model diff` publish the `data` payloads (`DataSchemaConformanceTest` locks them against real output).
- Each verb carries a CI-executed **`manifest` example** (`VerbDemonstrationTest`); the verb list is locked by `ManifestVerbTest.EXPECTED_VERBS`.
- Risk-reason codes live in `schema model diff` (the `data.findings` pattern).
- A `cli-review` showboat demo (CI drift-gated) demonstrates the verbs end-to-end.

## 8. Reuse map (what exists vs. what's new)

| Need | Status |
|---|---|
| J1/J2/J4/J6 data (info/describe/usage/where-used/check) | **reuse verbatim** |
| J3 glossed catalog | **thin compose** of `rule explain` across all elements |
| before/after condition + lossiness | **reuse** `RenderedDiff` |
| canonical condition text | **reuse** `rule format` |
| behavioral fire-set delta (`--deep`) | **reuse** `SeedGenerator` + `DmInterpreter` |
| deterministic writer + `model normalize` | **new** (core `ModelWriter`/`DmJson`) |
| diff engine + risk taxonomy + assembly/`md`/`text` rendering | **new** (the library primitive + the verb renderers) |
| `workspace graph --format mermaid` | **new, tiny** (extends an existing verb) |
