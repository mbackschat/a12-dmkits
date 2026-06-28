# a12-dmkits — Model Review & Diff (proposal)

> **Status — proposal (not built).** Intent + contract for a human **review & inspection** surface over LLM-driven model edits. When it ships, its contract graduates into [`CLI-SPEC.md`](CLI-SPEC.md) (the verb surface) and its rationale into [`API-FINDINGS.md`](API-FINDINGS.md); this proposal is then retired per the doc lifecycle. Decided in a design dialogue 2026-06-28; the forks settled are tabulated in §3.

## 1. The problem

`dmtool` is driven, by design, by an **LLM** — a coding agent (Claude Code, Codex), Claude Cowork, or Claude Chat — editing the **structure and rules** of A12 document models in a workspace, under a user's natural-language instructions. The user then has to **oversee what happened**: many changes, across models that are **too large to open or diff as raw JSON** (opaque condition strings, type-discriminated nesting, locale-map arrays, enum value/category parallel arrays).

"Too big to diff" is the symptom. There are three real sub-problems, and the third is the crux:

| # | Sub-problem | Why it's hard here |
|---|---|---|
| **A. Baseline** | Review needs a *before* | the agent edits **in place**; without an anchor there is nothing to compare against |
| **B. Altitude** | Raw JSON is the wrong representation | conditions are opaque strings; deep type-discriminated nesting; locale maps; relative paths; **non-deterministic write order** makes even semantically-identical models diff noisily |
| **C. Trust** | The reviewer is overseeing an **unreliable narrator** | the human is checking up on an LLM — if the review surface is *the agent's own summary*, the same untrusted party reports on itself |

**C is the governing principle.** The project's thesis — *"valid means the engine accepts it, not that it looks right"* — has an exact review corollary: **"changed means the tool computed the change from two model states, not what the agent claims."** Every part of this design is *tool-computed truth*, never agent narration.

## 2. The reviewer's jobs (review is comprehension + change-review)

Reducing the problem to "diff" is too narrow: you cannot judge a change without understanding the thing, and once you understand it the change is obvious. The human has six jobs; diff is only J5:

| # | "I need to…" | Answered by (existing primitive) | State |
|---|---|---|---|
| **J1** | orient — what is this model, how big, valid, related to what | `model info`, `workspace list`/`graph` | ✅ data exists |
| **J2** | understand structure — the group/field tree, kinds, constraints | `model describe`, `field read` | ✅ data exists |
| **J3** | understand logic — every rule/computation **in plain terms**, polarity explicit, with its message | `rule explain` (per element) | ⚠️ per-element only, not assembled |
| **J4** | trace dependencies — field→rules, rule→fields, dead set, includes | `where-used`, `rule deps`, `model usage` | ✅ data exists |
| **J5** | review change — what the agent did, risk-ranked | — | ❌ **missing** |
| **J6** | judge health — kernel-valid? suspicious? dead weight? | `model check`, `workspace roles` | ✅ data exists |

The honest reuse-first finding: **the comprehension *data* (J1–J4, J6) is largely already built** — it is scattered across agent-facing verbs that emit JSON one element at a time. What is missing is two layers: a **human-facing assembly** that renders rules in plain language and is navigable, and the **change/diff overlay (J5)**. Neither is "more data verbs."

## 3. Decisions (the forks settled)

| Fork | Decision | Why |
|---|---|---|
| **Verb surface** | **Two verbs, one shared engine** — `model report` (comprehend) + `model diff` (review) | matches dmtool's *verb = operation, target = scope* scheme; each verb stays lean; they share the assembly+gloss+risk engine |
| **Baseline** | **Pure two-file core + a `--since <ref>` git convenience** | keeps a git-free, testable engine (diffs two model states); `--since` shells to git only as ergonomic sugar — dmtool stays a pure model tool |
| **v1 scope** | **Single model**; `workspace` rollup is a fast-follow | bound the first build; the single-model engine is what the rollup reuses |
| **v1 contents** | **Both `report` and `diff`** | comprehension is the backdrop for review; the gloss engine is shared |
| **Gloss authorship** | **Hybrid** — tool emits a deterministic gloss **and** structured facts **and** the verbatim message; the agent may enrich, but the **tool gloss stays authoritative & citeable** | preserves tool-truth (C) while staying fluent; the independent gloss enables the gloss-vs-message drift signal (§7) |
| **First-write churn** | **An explicit `model normalize` verb** | canonicalizing a foreign-authored model is a deliberate, isolated, reviewable step — never buried inside a content edit |
| **Deterministic writer** | **Phase 0 foundation** (not optional hygiene) | the trust invariant *no change → no diff*, plus sane `git blame`/merges, rest on it (§4) |
| **Visualization** | **mermaid as a narrow accent**; bespoke HTML **out** | dmtool's data is tree + text; only workspace-topology and scoped-dependency are graph-shaped (§8). In-chat HTML is standardized only via MCP Apps (an MCP-server commitment) — deferred |
| **CI gate, textconv** | **deferred satellites** | the CI/PR-web contexts were not prioritized; both are trivial to add later from the JSON |

## 4. Phase 0 — deterministic, order-preserving writer (the foundation)

The whole design rests on *git as the baseline* and *trust = no change → no diff*. A non-deterministic writer breaks both at the root, so this is a **prerequisite that gates the trustworthiness of everything above it** — not for `model diff` (which is structural and immune to ordering noise), but for the **git substrate every consumer shares**:

- **The trust invariant fails** if a no-op or unrelated edit reshuffles elements — the human sees a diff with no semantic change and cannot tell a real move from serialization churn.
- **It poisons the whole git workflow:** the committed artifact is the raw JSON, so non-deterministic order means noisy commits, **`git blame` lies**, and two agents editing different parts produce **overlapping reorder churn → merge conflicts**.
- **The raw `git diff` is the universal fallback** — the human/teammate/agent *will* look at it; it must be *confirmable against* `model diff`, not a second unreliable narrator.

### 4a. The invariant (the testable success criterion)

> **Idempotent round-trip:** load → write with no edits = **byte-identical** output. **Localized edits:** changing one element makes the diff touch **only that element**.

### 4b. Order policy — *preserve*, do not *sort*

The nuance that must be right: **element order in DM-JSON is semantically meaningful** — field/group declaration order is the form's **display order**. Sorting elements would silently reorder the rendered form. So:

| Layer | Policy |
|---|---|
| **Element arrays** (fields/groups/rules in a group) | **preserve existing order** on round-trip; **deterministic placement** for additions (append within the group unless positioned); never reshuffle |
| **Object keys** (`type`/`id`/`name`/payload; locale maps; annotations) | **stable canonical order** is fine and good (keys are not semantic) — the most likely current leak (Jackson `HashMap` / `writerWithDefaultPrettyPrinter`) |
| **Newly created elements** | deterministic position, recorded so a later diff reads as a clean `added`, not a reshuffle |

The fix lives in one place — the core `ModelWriter` (shared by the CLI) — so it is contained.

### 4c. `model normalize`

A deliberate verb (`dmtool -m m.dm.json model normalize`, `-o`/`--dry-run` per convention) that canonicalizes a foreign-authored model **once**, so the one-time reformat is an explicit, reviewable commit rather than churn smuggled into the first real edit.

### 4d. First implementation step — *characterize current behavior*

Before designing the fix, **verify the current round-trip**: is load→save already idempotent? Where does non-determinism leak — locale maps, annotation maps, element placement on `add`? This scopes the fix to the real gap rather than an assumed one.

### 4e. Guards

A `RoundTripIdempotentTest` (load→write equals input across the fixture corpus) and a `LocalizedEditDiffTest` (one edit → single-element diff). These lock the invariant so a future serialization change can't silently reintroduce churn.

## 5. The shared engine

One **tool-computed** data model feeds both verbs and all renderings. Inspection and review share it.

```
                 ┌─────────────────────────────────────────────┐
   model(s) ───▶ │  ENGINE: assemble facts → gloss+polarity →   │ ───▶ JSON  (the truth contract)
   baseline ───▶ │  diff + reference-delta + risk classify       │ ───▶ Markdown (outline+catalog+tables, mermaid accent)
                 └─────────────────────────────────────────────┘ ───▶ text   (outline+tables+ASCII tree)
                    reuses: info · describe · usage · explain ·
                            where-used · RenderedDiff · check
```

- **JSON is the trusted contract** the agent reads and re-narrates; `md`/`text` are projections (the existing *JSON default + `--text`* pattern).
- **The glossed rules/computations catalog is the centerpiece** — the single hardest part of reading a huge model. Every rule rendered in plain language (J3), grouped by the field/group it guards. Hybrid gloss (§6c).
- **Token-efficiency is a first-class concern** (Apollo's minified-SDL lesson, Playwright's snapshot levels, Neo4j's truncation): for a huge model the engine must support **scoping** (`--group`, `--rules-only`, depth limits) rather than emitting a 50k-token monolith.

## 6. `model report` — comprehend (J1–J4, J6)

```
dmtool -m order.dm.json model report                 # full inspection
dmtool -m order.dm.json model report --group /Order  # scoped
dmtool -m order.dm.json model report --format md      # the human artifact (default json)
```

### 6a. What it assembles

Identity + health (J1, J6) · the **structure outline** (J2, from `describe`) with per-field config · the **glossed catalog** (J3) of rules + computations · the **dependency/usage** view (J4, the unreferenced/dead set surfaced).

### 6b. Renderings (shape × host — see §8)

- **json** — the contract; `data` payload published as `model-report.schema.json` (served by `schema model report`, locked by `DataSchemaConformanceTest`).
- **md** (the Cowork/Chat artifact) — backbone = **indented structure outline** + **glossed catalog** + per-field **tables**; mermaid only for a workspace-topology block.
- **text** (Claude Code / terminal) — the same outline + tables + the existing **ASCII `tree`**; mermaid omitted (it does not render in a terminal).

### 6c. The gloss (Hybrid C)

Per rule/computation the engine emits, side by side: the **deterministic gloss** (templated from the AST + operator catalog — the citeable baseline), **polarity** (`fires on violation`), and the **verbatim stored message** (the modeller's own intent). The agent **may** add a marked fluency line; it never replaces the tool gloss. The independent gloss is what makes §7's drift signal possible.

## 7. `model diff` — review (J5)

```
dmtool -m order.dm.json model diff --since HEAD      # vs last commit (git convenience)
dmtool model diff old.dm.json new.dm.json            # two explicit files (pure core)
```

Parses **both** states into the model and compares **structure** (so JSON formatting noise never reaches the reviewer). Matches elements by id; a removed+added pair with a matching content fingerprint is re-labelled `renamed`/`moved` (git-style similarity; conservative — unsure ⇒ remove+add, which surfaces as a high-risk deletion, the safe direction).

### 7a. Per-element change kinds

| element | tracked |
|---|---|
| **field** | `retyped` (kind), config (min/max/pattern/scale/unit/format/length), requiredness, enum value add/remove, metadata |
| **rule** | condition (→ RenderedDiff + polarity-before/after + gloss-before/after + reference-delta), severity, code, messages |
| **computation** | operation/precondition table, computed target, messages |
| **group** | repeatability, index field, sort fields, child structure |
| **typedef / include / config** | added/removed/renamed; mount point; imports; locale/decimal/roles/charset |

### 7b. Risk taxonomy — *loosening > tightening*

The governing insight: for someone overseeing an LLM, **loosening is more dangerous than tightening** — a removed/loosened rule *silently accepts bad data* (invisible), while a tightened/added rule *fails loudly* at validation (caught). This mirrors the project's "a skipped condition rule reads as a clean pass — the dangerous case."

| Tier | Reason codes |
|---|---|
| **HIGH** | `POLARITY_INVERTED` · `RULE_REMOVED` · `REFERENCED_ELEMENT_REMOVED` · `SEVERITY_DOWNGRADED` · `CONSTRAINT_LOOSENED` (min/max widened, pattern relaxed, required→optional, enum value added) · `KIND_CHANGED`+`RETYPE_AFFECTS_READERS` · `OPAQUE_OR_CUSTOM_ADDED` |
| **MEDIUM** | `RULE_ADDED` · `CONSTRAINT_TIGHTENED` · `CONDITION_CHANGED_SAME_POLARITY` · `COMPUTATION_CHANGED` · `REPEATABILITY_CHANGED` · `STRUCTURE_ADDED/REMOVED` (unreferenced) |
| **LOW** | `MESSAGE_CHANGED` · `LOCALE_ADDED` · `METADATA_CHANGED` · `CODE_RENAMED` |

Risk codes are documented via `schema model diff` (the `data.findings`-code pattern, like `workspace roles`), not in `diagnostics` (the `RK_*` envelope catalog).

### 7c. Polarity-inversion detection (layered by confidence)

1. **Structural (v1, default):** `Not(...)` wrap, comparison flips (`<`↔`>=`), De Morgan `And`↔`Or` shape changes → `confidence:high`, `basis:operator-flip`.
2. **Behavioral (`--deep`, later):** reuse the **SeedGenerator + interpreter** — generate N sample instances, evaluate the rule under base and head, report the **fire-set delta** ("now fires on 12 of 50 docs it used to pass"). Inversion = approximately complemented fire-set.

### 7d. The gloss-vs-message drift signal

Because the gloss is an *independent* tool reading of the condition, placing it next to the human-authored message catches an agent's polarity inversion: an inverted condition makes the tool gloss ("fires when delivery ≥ order") disagree with the unchanged message ("must not be earlier") → flagged. An agent-written gloss would restate the message and hide it (the reason for Hybrid C).

### 7e. Renderings

- **json** — the change set (`model-diff.schema.json`).
- **md** — risk-sorted cards (high first, low collapsed); each rule change shows before→after canonical DSL + the polarity callout; an optional **scoped diff-impact mermaid subgraph** (changed rules → fields, styled).
- **text** — risk-grouped listing, low-risk collapsed, ASCII; no mermaid.

## 8. Rendering & the mermaid policy

dmtool is **tree + text**, unlike the all-graph tools (Neo4j, Apollo, Graphiti) that emit mermaid because their data *is* a graph. Match representation to **our** shapes:

| dmtool view | true shape | representation | mermaid? |
|---|---|---|---|
| structure (groups/fields) | deep TREE, large | indented outline / tables | ❌ (a 200-node tree diagram is a hairball) |
| rule/computation logic | expression tree → gloss | prose + tables | ❌ (a *language* problem) |
| field↔rule dependency | bipartite graph, consulted **locally** | tables; mermaid for a **scoped** slice | ⚠️ scoped only |
| cross-model includes/subtypes | GRAPH, **small** | mermaid / DOT / tree | ✅ the one real fit |

So mermaid is a **narrow, deliberate accent**, used only where the data is *actually a graph and small*: the **workspace topology** (`workspace graph` already emits `json/tree/dot` — adding **`--format mermaid`** is the reuse-first quick win) and the **scoped diff-impact subgraph**. Never the full structure tree, the rule logic, or a whole-model dependency hairball.

**Host caveat (load-bearing):** mermaid renders in claude.ai (as an Artifact), Cursor, VS Code Copilot Chat, and GitHub/IDE previews — **but not in Claude Code / the terminal**, where it degrades to raw source. So mermaid is always a *layer on text that stands alone*; the `text` format swaps it for the ASCII tree. (Plus mermaid has a hard size cap and clutters past a few dozen nodes — another reason it is topology-only.)

**HTML is out of scope** until dmtool is an MCP server and can use the official **MCP Apps** standard (sandboxed-iframe widgets, Anthropic+OpenAI, 2026-01-26). Hand-rolling a bespoke HTML app outside that standard is the app-rot this design avoids.

**Delivery:** stdout by default (the agent pipes/saves); `-o/--out <file>` writes the artifact (the existing mutating-verb convention), so an `md` report becomes a file the Cowork/Chat surface can open.

## 9. Self-describing & no-rot (project discipline)

- Both verbs ride the **result envelope**; `schema model report` / `schema model diff` publish the `data` payloads (`DataSchemaConformanceTest` locks them against real output).
- Each verb carries a CI-executed **`manifest` example** (`VerbDemonstrationTest`).
- Risk-reason codes live in `schema model diff` (the `data.findings` pattern).
- `model normalize` is a mutating verb (`CONTRACT_EXEMPT` if it emits an artifact shape; otherwise the standard envelope).
- The `examples/` showboat demos gain a `cli-review` walkthrough (CI drift-gated).

## 10. Phasing

```
Phase 0 (foundation)  deterministic order-preserving writer + idempotent-round-trip guards + `model normalize`
Phase 1 (v1)          shared engine → `model report` + `model diff`, single model, json|md|text,
                      mermaid accents (`workspace graph --format mermaid` + scoped diff-impact), structural polarity
Phase 2 (fast-follow) `workspace report` / `workspace diff` rollup (multi-model oversight)
Satellites (triggered) `--deep` behavioral polarity · CI/PR gate · git textconv · MCP-Apps HTML
```

## 11. Reuse map (what exists vs. what's new)

| Need | Status |
|---|---|
| J1/J2/J4/J6 data (info/describe/usage/where-used/check) | **reuse verbatim** |
| J3 glossed catalog | **thin compose** of `rule explain` across all elements |
| before/after condition + lossiness | **reuse** `RenderedDiff` |
| canonical condition text | **reuse** `rule format` |
| behavioral fire-set delta (`--deep`) | **reuse** `SeedGenerator` + `DmInterpreter` |
| deterministic writer + `model normalize` | **new** (core `ModelWriter`) |
| assembly + `md`/`text` rendering | **new** (the report/diff renderers) |
| diff engine + risk taxonomy | **new** |
| `workspace graph --format mermaid` | **new, tiny** (extends an existing verb) |

## 12. Open items

1. **Phase 0 verification** — characterize the current round-trip (§4d) before the writer fix; suspected leaks: locale/annotation maps, element placement on `add`.
2. **`--deep` behavioral polarity** — phase 2; structural detection first.
3. **Workspace rollup into v1?** — pulled forward only if multi-model oversight per session proves dominant.
4. **Risk-policy configurability** — fixed taxonomy v1; no knob until a consumer needs it (consumer-exists discipline).
