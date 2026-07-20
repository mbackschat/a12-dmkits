# a12-dmkits — Model Review & Diff (spec)

> **Status — the contract for the human review & inspection surface** over LLM-driven model edits: the verb surface, I/O, the change model + risk taxonomy, the gloss, the rendering policy — plus the **rationale** (the problem, the trust principle, the six jobs, the settled forks) in §9. **Open/deferred/out-of-scope** items live in [`MODEL-REVIEW-GAPS.md`](MODEL-REVIEW-GAPS.md). This may later fold into [`CLI-SPEC.md`](CLI-SPEC.md).

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

## 2. Phase 0 — deterministic, order-preserving writer (foundation — BUILT)

The trust invariant *no change → no diff* and a sane git history (`blame`, merges) rest on a deterministic writer. This gates the trustworthiness of everything below. **Status: delivered** — the writer was already order-preserving + deterministic (tree surgery), the `model normalize` verb engages it, and the guards lock it (§2b–§2d).

### 2a. The invariant (testable success criterion)

> **Idempotent round-trip:** load → write with no edits = **byte-identical** output. **Localized edits:** changing one element makes the diff touch **only** that element.

### 2b. Order policy — *preserve*, do not *sort*

Element order in DM-JSON is **semantically meaningful** (declaration order = form display order), so sorting would change the model. The policy:

| Layer | Policy |
|---|---|
| element arrays (fields/groups/rules) | **preserve** existing order on round-trip; deterministic placement for additions; never reshuffle |
| object keys (`type`/`id`/`name`/payload; locale & annotation maps) | **stable canonical order** (keys are not semantic) — the likely current leak |
| new elements | deterministic position, recorded so a later diff reads as a clean `added` |

The fix lives in the kits' write path (`ModelWriter` / `ModelDocument` tree surgery, shared by the CLI). *Note (spike-confirmed, tasks #1/#2):* the write path is Jackson **tree surgery**, so element/key order is **already preserved** — there is **no reordering**. Across all fixtures the round-trip is **deterministic** (a compact-reformatted input converges to the same output) and a **stable fixed point** (`once == twice`); the *only* divergence from the stored file is **formatting** (`"k":` vs `"k" : `, indentation/array style — Jackson's `DefaultPrettyPrinter`). And on an already-normalized model, a one-rule edit is **fully localized** (0 lines changed elsewhere, +14 added). So Phase 0 was **not an order-preserving-writer rewrite** (the tree-surgery architecture already provides it) — it was **(1)** the `model normalize` verb (§2c) and **(2)** the guards (§2d), both now shipped. **Canonical-format decision: keep Jackson's `DefaultPrettyPrinter`** — it is the form every mutating write path already emits, so adopting it costs nothing and `model normalize` brings foreign files to it (a one-time, explicit reformat); introducing a different canonical format would churn every existing tool output for no semantic gain.

### 2c. `model normalize` (built)

`dmtool -m <model> model normalize` rewrites a foreign-authored model into the canonical form **once**, so the one-time reformat is an explicit, reviewable commit rather than churn smuggled into the first content edit. Formatting only — element/key order preserved, no semantic change; in place by default, `-o` redirects, `--dry-run` previews (`changed.reformatted` reports whether it differed). Self-describing like every verb (manifest/`--help`/demonstrated).

### 2d. Guards (built)

`RoundTripIdempotentTest` (a normalized fixture round-trips byte-identical across the corpus, **element order preserved/never sorted**, format-independent, and the EditSession write path stays stable) + `LocalizedEditDiffTest` (one edit → single-element diff) lock the invariant, so a future serialization change (a POJO-serializing write path, a Jackson bump reordering map keys, a misguided element sort) fails the build instead of silently reintroducing churn.

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

## 4. `model report` — comprehend (built)

`dmtool -m <model> model report` assembles, in one read, **identity** (J1), a lean **structure outline** (J2 — field paths + kinds, group repeatability; full per-kind config stays a `field read` drill-down, keeping the payload token-efficient), the **unreferenced (dead-field) set** (J4 — the audit headline; the *full* field→referrers map stays `model usage`, deliberately **not** duplicated here — the redundancy audit, §9d), and the **glossed rules/computations catalog** (J3 — the centerpiece), with **polarity** carried **once** at the top (it's the same fact for every rule, not repeated per entry). Output rides the result envelope; the `data` payload is published as `model-report.schema.json` (`schema model report`, locked by `DataSchemaConformanceTest`). The engine is the `ModelReport` library primitive (`:adapter`), reusing `ModelContext.describe`/`explain` + `KernelAdapter` reads — assembly, not new foundations. Includes/imports resolve through the kernel-free `dm.core.ModelResolver` (`ModelReport.of(json, ModelResolver)`), the same abstraction the rest of the surface uses, so a consumer need not import `com.mgmtp.*` (a low-level kernel-resolver overload stays for callers that already hold one).

`--tree` is the report's agent-first structure/comprehension projection, not a separate verb and not a change to the default payload. It replaces `data` with `{root,summary,nodes[]}`: one flat, deterministic mixed-document-order list whose records carry `path`, `parent`, `depth`, `kind`, lean type/config/repeatability facts, computed status, and the distinct provenance signals `origin` (only at an included-model mount boundary) and `typeRef` (the field's authored named type before effective-type inlining). `--root <path>` re-bases one subtree at depth 0 and refuses a missing root with `RK_NO_SUCH_ELEMENT` plus a near-path hint; `--depth N` caps descendants. Large enum domains are explicit previews (`valueCount` + `truncated:true`), with `--include enum` selecting the complete domain. `--include rules,computations,descriptions,annotations` (or `all`) adds the token-heavy facets: a rule is anchored exactly once at its error entity (model-level at the selected root), and a computation exactly once at its computed field. The flat JSON remains jq-friendly; no filter flags duplicate jq.

Renderings (shape × host — §6): **json** (contract, built) · **text** (built — the ordinary report renders the compact glossed rule/computation catalog + dead-field set; tree mode draws `├─/└─` hierarchy from the exact same flat records via the `ReportText` renderer) · **md** (outline + glossed catalog + tables; mermaid only for a workspace-topology block — a later slice).

**Scaling — the dead-field set is `O(R+C+F)`, not `O(F·(R+C))` (Set RP fix).** The unreferenced set needs the field→referrers *inverse*, but the report must build it in **one pass**: `KernelAdapter.referrersByEntity()` parses each rule and computation **once** (`getDirectlyReferencedEntities`) and accumulates an entity→referrers multimap the report reads by key. The original per-field loop (`whereUsed`/`whereUsedInComputations` for every field) re-parsed every rule and computation *once per field* — `O(F·(R+C))` kernel parses — so `model report` did not finish on huge models (thousands of fields × rules). The same inverse serves `model usage` (the full map) and the group-refactor subtree-referrer scan; a name→element index in the adapter (`ruleIndex`/`calcIndex`) removes the companion `O(R²)` per-lookup scan in the rule/computation catalog. The single-pass contract is locked deterministically (kernel-parse count ≤ R+C per report, F-independent), not by wall-clock, by `ModelReportUsagePerfTest`. This is the report's only perf coverage.

### 4a. The gloss (Hybrid C)

Per rule/computation, side by side: a **deterministic gloss** (templated from the AST + operator catalog — the citeable baseline) and the **verbatim stored message** (the modeller's intent); **polarity** (`fires on violation`) is the report-level constant carried once (§4), not per entry. The agent may add a *marked* fluency line; it never replaces the tool gloss. The independent gloss is what enables the gloss-vs-message drift signal (§5d).

A **computation** entry additionally carries its **preconditions** — the `commonPrecondition` (the gate over the whole computation, when set) and the `alternatives` (each a `precondition?` + `operation`, the DSL text) — with the **same key names and shapes `computation read` emits**, read from the same `Computation.draft()`, so the two surfaces agree on *when* a computed field is filled (a reviewer no longer has to open `computation read` per computation). A common precondition over a single unconditional operation folds onto that alternative as its own precondition on the kernel round-trip (semantically identical — [KERNEL-FINDINGS §11 supplement](KERNEL-FINDINGS.md), locked by `adapter.ComputationCommonPreconditionFoldTest`), so `commonPrecondition` appears distinctly only with ≥2 alternatives; the parity + the emission are locked by `ModelReportComputationPreconditionsCliTest` (report ≡ `computation read`) and `ModelReportComputationPreconditionsTest` (the engine).

## 5. `model diff` — review

Parses **both** states into the model and compares **structure** (so JSON formatting noise never reaches the reviewer). Both states must **bind**: a self-contained model needs no resolver, while an includes-bearing one resolves its mounts through a kernel-free `dm.core.ModelResolver` — the `ModelDiff.between(base, head, resolver)` overload (mirroring `ModelReport`; a low-level kernel-resolver overload also exists). The resolver-less `between` refuses an includes-bearing model (it throws rather than diff a half-bound model). The `model diff` CLI verb binds **self-contained** models only today — it threads no workspace resolver (a two-file diff has no single workspace root), so a multi-model diff is a programmatic-only capability. Matches by id/path; a removed+added pair that is the same element relocated is re-labelled `RENAMED`/`MOVED`, in two layers (conservative throughout — unsure ⇒ remove+add, the safe direction):

- **exact-fingerprint fast path** — an unambiguous pair with **identical content fingerprint**, exactly one↔one, and a **locality** match (same parent XOR same leaf name, i.e. what `field rename`/`field move` do);
- **similarity matcher** (for a rename that *also* edited the element, so the fingerprint differs) — pairs a leftover removed/added only when they are each other's **mutual clear best**: a score (reference-overlap + fingerprint + name, weighted toward *content* over name) that clears `PAIR_MIN` and beats the runner-up by `PAIR_MARGIN` **on both sides**; an ambiguous set (competing candidates within the margin) never pairs. The relocated change then carries **both** the path move and the content deltas, so the edit is never hidden (a rename+invert still shows `POLARITY_INVERTED`, classified across the two names).

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
| **MEDIUM** | `RULE_ADDED` · `CONSTRAINT_TIGHTENED` · `CONDITION_CHANGED_SAME_POLARITY` · `COMPUTATION_CHANGED` · `REPEATABILITY_CHANGED` · `STRUCTURE_ADDED/REMOVED` (unreferenced) · `ELEMENT_MOVED` (a re-homing move — content preserved, but a different parent can change display/scope) |
| **LOW** | `MESSAGE_CHANGED` · `LOCALE_ADDED` · `METADATA_CHANGED` · `CODE_CHANGED` · `POSITION_CHANGED` (a pure reorder — display-order is semantic §2b but not a validation loosening) · `ELEMENT_RENAMED` (a content-preserving rename — refs rewritten, so not a loosening) |

Risk codes are documented via `schema model diff` (the `data.findings`-code pattern, like `workspace roles`), not in `diagnostics`.

*(Status: a subset is built — `RULE_REMOVED`/`RULE_ADDED`/`POLARITY_INVERTED`/`KIND_CHANGED`/`SEVERITY_DOWNGRADED`/`CONSTRAINT_LOOSENED`/`CONSTRAINT_TIGHTENED`/`STRUCTURE_ADDED`/`STRUCTURE_REMOVED`/`POSITION_CHANGED`/`ELEMENT_RENAMED`/`ELEMENT_MOVED` and a few LOW codes, with the loosening>tightening direction for numeric bounds/lengths/pattern. `POLARITY_INVERTED` is detected structurally from the two rule ASTs (§5c level 1); a non-inverted condition change is further **classified** from the ASTs (`ConditionEdit`, §5e) into `CONDITION_RESTRUCTURED`/`REFERENCE_CHANGED`/`OPERATOR_CHANGED`/`THRESHOLD_CHANGED` (all MEDIUM), with `CONDITION_CHANGED` the fallback for an unparseable condition. The `risk` tier + `riskReasons` ride each change in `data`. `REFERENCED_ELEMENT_REMOVED` and `RETYPE_AFFECTS_READERS` are not yet computed — a removed/re-typed field is graded structurally without its reader set.)*

### 5c. Polarity-inversion detection (layered by confidence)

1. **Structural (default — BUILT):** the head condition is the logical **complement** of the base. The kernel has no `Not` node (`ConditionPolarity`), so the complement is pushed into the leaf polarity bits (comparison flip `<`↔`>=`, presence `filled`, pattern `matched`, value-list `included`) and across `And`↔`Or` by De Morgan; a clean full flip ⇒ `POLARITY_INVERTED` (HIGH), `basis:operator-flip`. Conservative: a node with no clean complement (quantifiers, custom, date/custom-type validity, tolerance comparisons) yields no inversion, so a flip is never claimed falsely. The comparison key is the canonical **rendering** (style-independent — a `PathStyle` hint is authored-notation provenance, not semantics), so a base read from stored notation and a head read from a `rule modify` edit still match.
2. **Behavioral (`--deep`, LEDGER):** SeedGenerator + interpreter → fire-set delta; inversion ≈ complemented fire-set. This is also the only path that can prove *sameness* (`CONDITION_CHANGED_SAME_POLARITY`); the structural detector proves a **flip**, never sameness, so a non-flip change stays the honest `CONDITION_CHANGED`.

### 5d. gloss-vs-message drift signal

Because the gloss is an *independent* tool reading of the condition, placing it next to the human-authored message catches an agent's inversion: an inverted condition makes the tool gloss disagree with the unchanged message → flagged. (An agent-written gloss would restate the message and hide it — the reason for Hybrid C.)

### 5e. What kind of diff this is

Three levels: **textual/line** (`git diff`), **structural/AST** (the common "semantic diff" — parse + compare structure, format-invariant), **behavioral** (what it *does*). **`model diff` is a structural semantic diff;** `--deep` (§5c) is the behavioral upgrade (reports "re-expressed but behaviorally identical"). Scope: `model diff` *is* a semantic diff, but `model review` ⊋ semantic diff (it adds comprehension + risk + gloss — judgment, not diff). A changed rule condition is compared at the **AST** level: a clean complement is `POLARITY_INVERTED` (§5c), and otherwise `ConditionEdit` classifies the edit coarsely — STRUCTURE (clause add/remove) → REFERENCES → OPERATOR → OPERAND (literal) — reusing `ConditionReferences` + a Condition-layer operator/node-count walk (it never re-walks the deep `Calculation` operand tree). A pure **reorder** (§5a `element position`) is computed per parent as the *minimal* move set — the complement of the longest common subsequence of the two sibling orders — so an add/remove that merely shifts absolute indices never invents a move (`REORDERED`/`POSITION_CHANGED`, LOW); an element that also changed content folds the position into its existing change rather than being double-listed. A removed+added pair is re-labelled `RENAMED`/`MOVED` by the two-layer matcher (exact-fingerprint fast path + similarity, §5-intro), which survives a rename *and* edit. On top of the coarse category, a changed condition also carries a **located edit-script** (`ConditionEditScript`): *which* clause/operand moved ("threshold 1000→2000 on `[Amount]`", "added clause: `FieldFilled(Approved)`"). It flattens `And`/`Or` chains and matches operands by content, so a clause inserted into a chain is one located add (a downstream clause is never mislocated), a pure re-association is no edit, and a genuinely ambiguous restructure degrades to the coarse `ConditionEdit` kind for that region — it never guesses a wrong location. The address is the breadcrumb of enclosing logic operators (`""` at the root, `And`, `And/Or`), stable under re-association.

### 5f. Renderings

**json** (the change-set, `model-diff.schema.json`) · **md** (risk-sorted cards, before→after condition + polarity callout, an optional scoped diff-impact mermaid subgraph) · **text** (risk-grouped, low-risk collapsed, ASCII; no mermaid).

*(Status: `json` and the verb are built; a changed condition's located edit-script rides its `condition` delta as `conditionEdits` (§5e). `--text` is the bespoke **risk-sorted** view (`DiffText` via the `VerbText` registry) — HIGH then MEDIUM, one line per change with each condition edit as an indented `↳ <summary>` sub-line, then a **REFACTORS** section (one `↻` headline per grouped rename/move + its rewritten-referrer count, the folded members collapsed under it, §5g) and a **REORDERED** section (each moved element with its `position` before→after, surfaced separately since a reorder is LOW-risk but semantic §2b), LOW collapsed to a count. The summary counts renames/moves (`… , N renamed/moved`) and reorders (`… , N reordered`). The `md` artifact + mermaid accent are a later slice.)*

### 5g. Relational refactor grouping

A reference-rewriting refactor (a rename/move that re-pointed N referrers) is folded into **one** semantic operation instead of N+1 scattered edits: `data.refactors[]`, each `{kind: RENAME|MOVE, from, to, referrers[]}`, whose referrers are the `MODIFIED` rules whose reference-delta is *exactly* that `from`→`to` repoint (dropped `from`, gained `to`). A coincidental reference change with no matching relocation is **never** fabricated into a refactor, and a relocation with no referrer yields no group (nothing relational to fold). The overlay is **non-destructive** — the raw `RENAMED`/`MOVED` and per-referrer `MODIFIED` changes stay in `changes`, each carrying `partOfRefactor` (the index into `refactors`) — so a consumer can verify the fold from the change-set itself (the trust principle applied to the tool's own output). *(Built. The referrer set is rules today; computations join when the computation-diff slice lands.)*

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
| deterministic writer + `model normalize` | **new** (`ModelWriter`'s tree surgery) |
| diff engine + risk taxonomy + assembly/`md`/`text` rendering | **new** (the library primitive + the verb renderers) |
| `workspace graph --format mermaid` | **new, tiny** (extends an existing verb) |

## 9. Background & rationale

The *why* behind this surface (the forks were settled in the design dialogue, 2026-06-28). The J-codes used above (J1–J6) are defined in §9b.

### 9a. The problem — and the trust principle

`dmtool` is driven by an **LLM** editing the structure + rules of large A12 models **in place**, under a user's natural-language instructions; the user must then oversee many changes across models **too big to diff as raw JSON**. "Too big to diff" is the symptom — there are three sub-problems, the third governing:

| | Sub-problem | Why it's hard here |
|---|---|---|
| **A. Baseline** | review needs a *before* | the agent edits in place — no anchor to compare against (→ the two-file core + `--since`, §1) |
| **B. Altitude** | raw JSON is the wrong representation | opaque condition strings, deep type-discriminated nesting, locale maps, relative paths, formatting churn (→ the structural diff + `model normalize` + the gloss) |
| **C. Trust** | the reviewer oversees an **unreliable narrator** | the human is checking up on an LLM — if the review surface is *the agent's own summary*, the untrusted party reports on itself |

**C governs.** The project thesis — *"valid means the engine accepts it, not that it looks right"* — has a review corollary: **"changed means the tool computed the change from two model states, not what the agent claims."** Every fact in this surface is tool-computed, never agent narration.

### 9b. Comprehension + change-review — the six jobs

Reducing review to "diff" is too narrow: you can't judge a change without understanding the thing. The reviewer's six jobs, and where each is answered:

| | "I need to…" | Answered by |
|---|---|---|
| **J1** | orient — what is this model, how big, valid, related to what | `model info`, `workspace list`/`graph`; the report's **identity** |
| **J2** | understand structure — the group/field tree, kinds, constraints | `model describe`, `field read`; the report's **structure outline** |
| **J3** | understand logic — every rule/computation **in plain terms**, polarity, message | `rule explain` (per element), **assembled** by the report's **glossed catalog** (the centerpiece) |
| **J4** | trace dependencies — field↔rule, dead set, includes | `where-used`, `model usage`; the report's **dead-field set** |
| **J5** | review change — what the agent did, risk-ranked | **`model diff`** — the new capability |
| **J6** | judge health — kernel-valid? suspicious? dead weight? | `model check`, `workspace roles` |

The reuse-first finding: the comprehension *data* (J1–J4, J6) was **already built**, scattered across agent-facing verbs that emit JSON one element at a time. What was missing is two layers — a **human-facing assembly** (`model report`) and the **change overlay** (`model diff`, J5) — neither of which is "more data verbs."

### 9c. Decisions (the settled forks)

| Fork | Decision | Why |
|---|---|---|
| **Verb surface** | two verbs, one shared engine — `model report` + `model diff` | matches *verb = operation, target = scope*; each verb stays lean; they share the assembly+gloss+risk engine |
| **Baseline** | pure two-file core + a `--since <ref>` git convenience | a git-free, testable engine (diffs two model states); `--since` is ergonomic sugar — dmtool stays a pure model tool |
| **v1 scope** | single model; `workspace` rollup deferred | bound the first build; the single-model engine is what the rollup reuses |
| **Gloss authorship** | Hybrid (C) — tool gloss authoritative + facts + verbatim message; agent enrichment marked, never replacing | preserves tool-truth while staying fluent; the independent gloss enables the gloss-vs-message drift signal (§5d) |
| **First-write churn** | an explicit `model normalize` verb | canonicalizing a foreign model is a deliberate, isolated, reviewable step — never buried in a content edit |
| **Deterministic writer** | a Phase-0 foundation, not optional hygiene (§2) | the trust invariant *no change → no diff*, plus sane `git blame`/merges, rest on it |
| **Visualization** | mermaid as a narrow accent; bespoke HTML out (§6) | dmtool's data is tree + text; only workspace-topology + scoped-dependency are graph-shaped; in-chat HTML is standardized only via MCP Apps |

### 9d. Redundancy & non-overlap

The verbs were audited against the existing read surface (trigger: an early `model report` carried a `usage` map that literally duplicated `model usage`).

- **`model report` is a lean re-projection, not a dup:** its `identity` ⊂ `model info` and `structure` ⊂ `model describe` are *leaner, different* projections (the comprehension frame — header essentials, an outline of path+kind) that point at `info`/`describe`/`field read` for authoritative detail. The full field→referrers map stays `model usage`; the report carries only the **dead-field set** (the audit value). **Polarity** is one report-level fact, not repeated per entry. Per-rule `canonicalText` is an inline convenience (and the *only* representation for an Opaque rule), not a wholesale dup of `rule format`.
- **No overlap:** `model diff` is the only diff, `model normalize` the only canonicalizer, and report (comprehend one model) vs diff (compare two) are distinct.
- **Forward constraint:** the deferred `workspace report`/`workspace diff` rollup (LEDGER §2) **must not** duplicate `workspace list` (the model index) or `workspace check` (the validation gate) — it is the per-model report/diff *aggregator*, to be designed non-overlapping.
