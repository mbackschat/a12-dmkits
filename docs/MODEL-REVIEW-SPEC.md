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
                            where-used · canonical read · check
```

- **JSON is the trusted contract**; `md`/`text` are projections (the existing *JSON default + `--text`* pattern).
- **The glossed rules/computations catalog is the centerpiece** — every rule in plain language, grouped by the field/group it guards.
- **Token-efficiency is first-class:** scoping (`--group`, `--rules-only`, depth limits) over a 50k-token monolith.

## 4. `model report` — comprehend (built)

`dmtool -m <model> model report` assembles, in one read, **identity** (J1), a lean **structure outline** (J2 — field paths + kinds, group repeatability; full per-kind config stays a `field read` drill-down, keeping the payload token-efficient), the **unreferenced (dead-field) set** (J4 — the audit headline; the *full* field→referrers map stays `model usage`, deliberately **not** duplicated here — the redundancy audit, §9d), and the **glossed rules/computations catalog** (J3 — the centerpiece), with **polarity** carried **once** at the top (it's the same fact for every rule, not repeated per entry). Output rides the result envelope; the `data` payload is published as `model-report.schema.json` (`schema model report`, locked by `DataSchemaConformanceTest`). The engine is the `ModelReport` library primitive (`:adapter`), reusing `ModelContext.describe`/`explain` + `KernelAdapter` reads — assembly, not new foundations. Includes/imports resolve through the kernel-free `dm.core.ModelResolver` (`ModelReport.of(json, ModelResolver)`), the same abstraction the rest of the surface uses, so a consumer need not import `com.mgmtp.*` (a low-level kernel-resolver overload stays for callers that already hold one).

`--tree` is the report's agent-first structure/comprehension projection, not a separate verb and not a change to the default payload. It replaces `data` with `{root,summary,nodes[]}`: one flat, deterministic mixed-document-order list whose records carry `path`, `parent`, `depth`, `kind`, lean type/config/repeatability facts, computed status, and the distinct provenance signals `origin` (only at an included-model mount boundary) and `typeRef` (the field's authored named type before effective-type inlining). `--root <path>` re-bases one subtree at depth 0 and refuses a missing root with `RK_NO_SUCH_ELEMENT` plus a near-path hint; `--depth N` caps descendants. Large enum domains are explicit previews (`valueCount` + `truncated:true`), with `--include enum` selecting the complete domain. `--include rules,computations,descriptions,annotations` (or `all`) adds the token-heavy facets: a rule is anchored exactly once at its error entity, a model-level rule stays at the synthetic model root `/`, and a computation exactly once at its computed field. The tree projection reads the expanded `ModelReader`/`ModelIndex` plus the stored rule/computation DSL directly, so selecting it never bootstraps the kernel or interpreter; an opaque computation therefore still carries its target and verbatim DSL. Optional descriptions and annotations come from one immutable path index built in a single element-tree traversal, not a root search per emitted node/facet, so rich projection work stays linear in model size plus output size. The flat JSON remains jq-friendly; no filter flags duplicate jq.

Renderings (shape × host — §6): **json** (contract, built) · **text** (built — the ordinary report renders the compact glossed rule/computation catalog + dead-field set; tree mode draws `├─/└─` hierarchy from the exact same flat records via the `ReportText` renderer) · **md** (outline + glossed catalog + tables; mermaid only for a workspace-topology block — a later slice).

**Scaling — the dead-field set is `O(R+C+F)`, not `O(F·(R+C))` (Set RP fix).** The unreferenced set needs the field→referrers *inverse*, but the report must build it in **one pass**: `KernelAdapter.referrersByEntity()` parses each rule and computation **once** (`getDirectlyReferencedEntities`) and accumulates an entity→referrers multimap the report reads by key. The original per-field loop (`whereUsed`/`whereUsedInComputations` for every field) re-parsed every rule and computation *once per field* — `O(F·(R+C))` kernel parses — so `model report` did not finish on huge models (thousands of fields × rules). The same inverse serves `model usage` (the full map) and the group-refactor subtree-referrer scan; a name→element index in the adapter (`ruleIndex`/`calcIndex`) removes the companion `O(R²)` per-lookup scan in the rule/computation catalog. The single-pass contract is locked deterministically (kernel-parse count ≤ R+C per report, F-independent), not by wall-clock, by `ModelReportUsagePerfTest`. The gloss renderer also shares one process-wide immutable `OperatorCatalog`; it never reparses the bundled catalog per rule or computation fragment (`OperatorCatalogLoadingTest`). `ModelContext` likewise indexes the model-static repeatable-group paths once: every rule gloss and every computation common/alternative-precondition gloss reuses that immutable set instead of rescanning all groups per condition (`ModelContextRepeatableGroupCacheTest`). Tree-text layout classifies last siblings in one pass over the flat nodes, then builds each node prefix once from an ancestor stack and reuses it for attached logic lines; beyond the unavoidable prefix bytes it emits, traversal is linear (`ReportTextDeepTreeTest`). Enum category projection deduplicates category values once in encounter order with hash-backed membership, and `BoundModel.enumOf` consumes that same indexed result; it is `O(V)` for `V` authored category values rather than a growing-list scan. Default describe reads an enum's domain through the strict values-only `FieldInfo.allowedValues()` surface and never materializes its category projection (`EnumCategoryDedupTest`).

**Profile-shaped CLI catastrophe canary.** The opt-in `:adapter:profileCliPerfTest` synthesizes the committed compact, depth-shaped, and breadth-shaped profiles before any clock, then launches the installed JVM `dmtool` process for `model describe`, the default JSON `model report`, and `model report --tree --text` with rule/computation facets. A separate preflight requires exit 0 and exact profile cardinality in each output, including an exact parse of the tree renderer's summary line. Timed observations are normalized by `F+G` for describe and `F+G+R+C` for both report lanes, and adjacent profiles use the shared interleaved paired-growth estimator under the existing `<9×` catastrophe floor; every child process retains the 180-second liveness fuse. This detects a gross profile-shaped cliff; it is neither a linearity certificate nor an absolute latency gate, and it is not wired into the default suite or CI.

### 4a. The gloss (Hybrid C)

Per rule/computation, side by side: a **deterministic gloss** (templated from the AST + operator catalog — the citeable baseline) and the **verbatim stored message** (the modeller's intent); **polarity** (`fires on violation`) is the report-level constant carried once (§4), not per entry. The agent may add a *marked* fluency line; it never replaces the tool gloss. The independent gloss is what enables the gloss-vs-message drift signal (§5d).

A **computation** entry additionally carries its **preconditions** — the `commonPrecondition` (the gate over the whole computation, when set) and the `alternatives` (each a `precondition?` + `operation`, the DSL text) — with the **same key names and shapes `computation read` emits**, read from the same `Computation.draft()`, so the two surfaces agree on *when* a computed field is filled (a reviewer no longer has to open `computation read` per computation). A common precondition over a single unconditional operation folds onto that alternative as its own precondition on the kernel round-trip (semantically identical — [KERNEL-FINDINGS §11 supplement](KERNEL-FINDINGS.md), locked by `adapter.ComputationCommonPreconditionFoldTest`), so `commonPrecondition` appears distinctly only with ≥2 alternatives; the parity + the emission are locked by `ModelReportComputationPreconditionsCliTest` (report ≡ `computation read`) and `ModelReportComputationPreconditionsTest` (the engine).

An entry that rulekit cannot model is never presented as merely “not explained”: it carries the preserved `canonicalText` plus a non-empty `reason` naming the unsupported conversion shape. The engine represents explained and Opaque outcomes as a sealed choice, so an explained computation cannot lose its preconditions/alternatives/tolerance metadata and an Opaque entry cannot accidentally acquire a gloss. `ModelReportOpaqueReasonTest` and `ModelReportOpaqueReasonCliTest` lock both rule and computation branches.

## 5. `model diff` — review

Parses **both** states into the model and compares **structure** (so JSON formatting noise never reaches the reviewer). Both states must **bind**: a self-contained model needs no resolver, while an includes-bearing one resolves its mounts through a kernel-free `dm.core.ModelResolver`. `ModelDiff.between(base, head, resolver)` remains the convenience when both states share one dependency snapshot; `between(base, head, baseResolver, headResolver)` is the review form when revisions/worktrees differ. The resolver-less `between` refuses an includes-bearing model (it throws rather than diff a half-bound model). The CLI mirrors this distinction: two-file mode accepts repeatable `--base-workspace` and `--head-workspace` roots when the snapshots contain different versions of the same referenced id; otherwise shared `-w/--workspace` applies, falling back to each file's own folder. `--since <ref>` resolves the base model **and its referenced models** at the Git ref while the head resolves against the working tree. Resolver indexes are lazy (a self-contained diff performs no workspace scan), equal current roots share one index, and a Git workspace uses one tree listing plus one persistent batch reader rather than one Git process per model. Matches by id/path; a removed+added pair that is the same element relocated is re-labelled `RENAMED`/`MOVED`, in two layers (conservative throughout — unsure ⇒ remove+add, the safe direction):

- **exact-fingerprint fast path** — an unambiguous pair with **identical content fingerprint**, exactly one↔one, and a **locality** match (same parent XOR same leaf name, i.e. what `field rename`/`field move` do);
- **similarity matcher** (for a rename that *also* edited the element, so the fingerprint differs) — considers only same-family, rename-local (same parent) or move-local (same leaf) candidates, then pairs a leftover removed/added only when they are each other's **mutual clear best**: a score (reference-overlap + fingerprint + name, weighted toward *content* over name) that clears `PAIR_MIN` and beats the runner-up by `PAIR_MARGIN` **on both sides**. Candidate buckets are sparse and bounded; an oversized sibling/leaf bucket is left as remove+add before scoring, so a generated rename storm cannot allocate or score a removed×added matrix. An ambiguous set (competing candidates within the margin) likewise never pairs. The relocated change then carries **both** the path move and the content deltas, so the edit is never hidden (a rename+invert still shows `POLARITY_INVERTED`, classified across the two names).

### 5a. Change kinds

| element | tracked |
|---|---|
| **field** | `retyped` (kind), config (min/max/pattern/scale/unit/format/length), requiredness, enum value add/remove, metadata |
| **rule** | canonical condition before/after, located condition edits, polarity/edit classification and reference delta; error entity, severity, code, messages, descriptions/annotations |
| **computation** | operation/precondition table, computed target, tolerances, suppressions, messages, opaque stored DSL |
| **group** | repeatability, index field, ordered sort fields, mount alias/options, metadata; child structure through the actual child element changes |
| **typedef** | added/removed/exact rename; declared kind/type; complete typed config and enum state; descriptions/annotations |
| **include / import** | direct include alias/reference; type-definition import alias/reference; mount alias/options stay on the authored group |
| **config** | one stable `CONFIG model` element: model identity and compatible version skew; locales; decimal separator; time zone; condition language; short-name mode; supported characters; base year; labels; comment; annotations including canonicalized roles |
| **element position** | a field/group/rule/computation **reordered** within its parent's mixed authored sequence — a display-order change, semantic per §2b |

All listed families are implemented. Field aspects cover declared/effective type, every typed config slot, requiredness and custom required messages, enum membership/order/labels/categories, and metadata/flags. Rule aspects cover canonical condition, error entity, severity/code, localized messages, and metadata. A removed or retyped field consults the shared inverse rule/computation reader map, so `REFERENCED_ELEMENT_REMOVED`/`RETYPE_AFFECTS_READERS` cannot be hidden by the reader changing in the same diff. Group aspects are repeatability, index field, ordered sort fields, mount alias/options, and labels/descriptions/annotations; its children remain independent elements. Computation aspects are target, common precondition, ordered alternative preconditions/operations/tolerances, suppressions, messages, and verbatim opaque DSL. Typedefs carry their complete sealed `FieldTypeSpec` and descriptive metadata; only an unambiguous exact fingerprint is paired as a typedef rename. Direct includes and type-definition imports carry purpose/alias/reference as authored declarations, distinct from expanded provider content; mount rewiring remains a group delta. Configuration uses one stable element id and deterministic strings; locales, supported characters, and roles are sorted JSON arrays so set order does not fabricate a change.

Each `Delta` retains its deterministic `before`/`after` strings as the stable portable and display contract. The additive `beforeValue()`/`afterValue()` headless API exposes a sealed `DeltaValue` (`TextValue`, `BooleanValue`, `IntegerValue`, `DecimalValue`, or `StringListValue`), so services can switch exhaustively without reparsing numbers, booleans, or canonical lists. CLI JSON mirrors that view as tagged `beforeValue`/`afterValue` objects beside the strings; an absent side has neither representation. A value that cannot be interpreted under its known aspect degrades to `TextValue` rather than making a diff unreadable.

#### Completeness locks

| Family / mutation slots | Focused executable lock |
|---|---|
| computation target, common/alternative preconditions, ordered operations, tolerances, suppressions, messages, opaque DSL | `adapter.review.ModelDiffComputationTest` |
| group add/remove, repeatability, index/sort, mount options, metadata, mixed authored position | `adapter.review.ModelDiffGroupTest` |
| field declared/effective type, every sealed type-config slot, requiredness/messages, enum state, metadata/flags, inverse-reader impact | `adapter.review.ModelDiffFieldCompletenessTest`, `ModelDiffReaderRiskTest` |
| rule condition, error entity, severity/code, localized messages, descriptions/annotations | `adapter.review.ModelDiffRuleEnvelopeTest` plus the existing `ModelDiff*Test` condition/refactor family |
| typedef add/remove/config/enum/metadata and exact-only rename | `adapter.review.ModelDiffTypedefTest` |
| direct include and type-definition import add/remove/retarget/alias, resolver-backed separation from expanded content | `adapter.review.ModelDiffIncludeTest` |
| configuration identity/version projection, locales, every scalar setting, supported characters, base year, labels/comment/annotations/roles, canonical set order | `adapter.review.ModelDiffConfigTest` |
| stable rendered deltas plus sealed scalar/list values and their CLI/schema shape | `adapter.review.ModelDiffTypedDeltaTest`, `cli.ModelDiffTypedDeltaCliTest`, `cli.DataSchemaConformanceTest` |
| distinct dependency snapshots, two-file folder fallback, Git-ref dependency resolution, and unused-workspace laziness | `adapter.review.ModelDiffSideResolverTest`, `cli.ModelDiffWorkspaceCliTest`, `cli.ModelDiffSinceTest` |

### 5b. Review-impact taxonomy (`risk`) — *loosening > tightening*

A removed/loosened rule **silently accepts bad data** (invisible); a tightened/added rule **fails loudly** at validation (caught). So the taxonomy is asymmetric:

| Tier | Reason codes |
|---|---|
| **HIGH** | `POLARITY_INVERTED` · `RULE_REMOVED` · `REFERENCED_ELEMENT_REMOVED` · `RETYPE_AFFECTS_READERS` · `KIND_CHANGED` · `SEVERITY_DOWNGRADED` · `CONSTRAINT_LOOSENED` (min/max/repetition cap widened, pattern relaxed, required→optional, enum value added, or supported-character domain widened — including restoring its empty full-BMP default) |
| **MEDIUM** | `RULE_ADDED` · `CONSTRAINT_TIGHTENED` (including a lower repetition cap, enum-value removal, or restricting the empty full-BMP supported-character default) · `COMPUTATION_CHANGED` · `GROUP_CHANGED` (directionally unclear index/sort/mount edit) · `REPEATABILITY_CHANGED` · `TYPEDEF_ADDED/REMOVED` · `INCLUDE_CHANGED` · `CONFIG_CHANGED`/`LOCALE_REMOVED` · `TYPE_CHANGED`/`FIELD_CONFIG_CHANGED` (direction unclear) · `STRUCTURE_ADDED/REMOVED` (unreferenced) · `ELEMENT_MOVED` (a re-homing move — content preserved, but a different parent can change display/scope) |
| **LOW** | `MESSAGE_CHANGED` · `LOCALE_ADDED` · `METADATA_CHANGED` · `CODE_CHANGED` · `POSITION_CHANGED` (a pure reorder — display-order is semantic §2b but not a validation loosening) · `ELEMENT_RENAMED` (a content-preserving rename — refs rewritten, so not a loosening) |

Review-impact codes are documented via `schema model diff` (the `data.findings`-code pattern, like `workspace roles`), not in `diagnostics`.

`POLARITY_INVERTED` is detected structurally from the two rule ASTs (§5c level 1); a non-inverted condition change is further **classified** from the ASTs (`ConditionEdit`, §5e). `REFERENCED_ELEMENT_REMOVED` and `RETYPE_AFFECTS_READERS` use one `KernelAdapter.referrersByEntity()` inverse per model side, never a reader scan per changed field. The `risk` field is the fixed **review-impact tier** and `riskReasons` explains it; it is consequence/triage severity, not probability or diff uncertainty.

### 5c. Polarity-inversion detection (layered by confidence)

The built detector is structural: the head condition must be the logical **complement** of the base. The kernel has no `Not` node (`ConditionPolarity`), so the complement is pushed into the leaf polarity bits (comparison flip `<`↔`>=`, presence `filled`, pattern `matched`, value-list `included`) and across `And`↔`Or` by De Morgan; a clean full flip ⇒ `POLARITY_INVERTED` (HIGH), `basis:operator-flip`. Conservative: a node with no clean complement (quantifiers, custom, date/custom-type validity, tolerance comparisons) yields no inversion, so a flip is never claimed falsely. The comparison key is the canonical **rendering** (style-independent — a `PathStyle` hint is authored-notation provenance, not semantics), so a base read from stored notation and a head read from a `rule modify` edit still match. A bounded behavioral witness search is deferred in [`MODEL-REVIEW-GAPS.md`](MODEL-REVIEW-GAPS.md) §2; generated samples may find a counterexample but cannot prove equivalence.

### 5d. gloss-vs-message drift signal

Because the gloss is an *independent* tool reading of the condition, placing it next to the human-authored message catches an agent's inversion: an inverted condition makes the tool gloss disagree with the unchanged message → flagged. (An agent-written gloss would restate the message and hide it — the reason for Hybrid C.)

### 5e. What kind of diff this is

Three levels exist: **textual/line** (`git diff`), **structural/AST** (the common "semantic diff" — parse + compare structure, format-invariant), and **behavioral** (what it *does*). **`model diff` is a structural semantic diff.** Scope: `model diff` *is* a semantic diff, but `model review` ⊋ semantic diff (it adds comprehension + review impact + gloss — judgment, not diff). A changed rule condition is compared at the **AST** level: a clean complement is `POLARITY_INVERTED` (§5c), and otherwise `ConditionEdit` classifies the edit coarsely — STRUCTURE (clause add/remove) → REFERENCES → OPERATOR → OPERAND (literal) — reusing `ConditionReferences` + a Condition-layer operator/node-count walk (it never re-walks the deep `Calculation` operand tree). A pure **reorder** (§5a `element position`) is computed per parent over one mixed authored field/group/rule/computation sequence as the *minimal* move set — the complement of the longest common subsequence of the two sibling orders — so a group moving relative to a field is visible while an add/remove that merely shifts absolute indices never invents a move (`REORDERED`/`POSITION_CHANGED`, LOW); an element that also changed content folds the position into its existing change rather than being double-listed. The authored element sequence and complete field/group/rule state are collected in one `ModelDocument.authoredState()` traversal per side, avoiding repeated path lookups as models grow; configuration is projected once from the same parsed `ModelDocument`, the reader inverse is built once per side, and loading uses one kernel-free index expansion plus one checked adapter preparation per side—never a separate kernel preflight followed by another kernel deserialize/expand. The lightweight authored `ModelDocument` parse remains separate from the core index by design: sharing its Jackson tree across the published kit boundary would leak a third-party type or require a parallel JSON-tree API. A removed+added pair is re-labelled `RENAMED`/`MOVED` by the two-layer matcher (exact-fingerprint fast path + bounded sparse similarity, §5-intro), which survives a rename *and* edit without a quadratic score matrix. On top of the coarse category, a changed condition also carries a **located edit-script** (`ConditionEditScript`): *which* clause/operand moved ("threshold 1000→2000 on `[Amount]`", "added clause: `FieldFilled(Approved)`"). It flattens `And`/`Or` chains and matches operands by content, so a clause inserted into a chain is one located add (a downstream clause is never mislocated), a pure re-association is no edit, and a genuinely ambiguous restructure degrades to the coarse `ConditionEdit` kind for that region — it never guesses a wrong location. The address is the breadcrumb of enclosing logic operators (`""` at the root, `And`, `And/Or`), stable under re-association.

### 5f. Renderings

**json** (the change-set, `model-diff.schema.json`) · **md** (impact-sorted cards, before→after condition + polarity callout, an optional scoped diff-impact mermaid subgraph) · **text** (impact-grouped, low-impact collapsed, ASCII; no mermaid).

*(Status: `json` and the verb are built; a changed condition's located edit-script rides its `condition` delta as `conditionEdits` (§5e). `--text` is the bespoke **impact-sorted** view (`DiffText` via the `VerbText` registry) — HIGH then MEDIUM, one line per change with each condition edit as an indented `↳ <summary>` sub-line, then a **REFACTORS** section (one `↻` headline per grouped rename/move + its rewritten-referrer count, the folded members collapsed under it, §5g) and a **REORDERED** section (each moved element with its `position` before→after, surfaced separately since a reorder is LOW-impact but semantic §2b), LOW collapsed to a count. The summary counts renames/moves (`… , N renamed/moved`) and reorders (`… , N reordered`). The `md` artifact + mermaid accent are a later slice.)*

### 5g. Relational refactor grouping

A reference-rewriting refactor (a rename/move that re-pointed N referrers) is folded into **one** semantic operation instead of N+1 scattered edits: `data.refactors[]`, each `{kind: RENAME|MOVE, from, to, referrers[]}`, whose referrers are the `MODIFIED` rules or computations whose reference-delta is *exactly* that `from`→`to` repoint (dropped `from`, gained `to`). A coincidental reference change with no matching relocation is **never** fabricated into a refactor, and a relocation with no referrer yields no group (nothing relational to fold). The overlay is **non-destructive** — the raw `RENAMED`/`MOVED` and per-referrer `MODIFIED` changes stay in `changes`, each carrying `partOfRefactor` (the index into `refactors`) — so a consumer can verify the fold from the change-set itself.

## 6. Rendering & the mermaid policy

dmtool is **tree + text**; only workspace-topology and scoped-dependency are graph-shaped. Match representation to the shape, not to the all-graph tools:

| view | shape | representation | mermaid? |
|---|---|---|---|
| structure (groups/fields) | deep tree, large | outline / tables | ❌ |
| rule/computation logic | gloss | prose + tables | ❌ |
| field↔rule dependency | bipartite, consulted locally | tables; mermaid for a scoped slice | ⚠️ scoped |
| cross-model includes/subtypes | graph, small | mermaid / DOT / tree | ✅ |

So mermaid is a **narrow accent**: the **workspace topology** (`workspace graph --format mermaid` — the reuse-first quick win) and the **scoped diff-impact subgraph**. Never the full structure tree, rule logic, or a whole-model dependency hairball.

**Host caveat:** mermaid renders in claude.ai (Artifact), Cursor, VS Code Copilot, GitHub/IDE previews — **not in Claude Code / the terminal** (degrades to raw source). So mermaid is always a layer on text that stands alone; the `text` format uses the ASCII tree. **HTML is out** until dmtool is an MCP server and can use the official MCP Apps standard ([`MODEL-REVIEW-GAPS.md`](MODEL-REVIEW-GAPS.md) §2). **Delivery:** stdout default; `-o/--out <file>` writes the artifact (existing convention).

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
| canonical condition before/after + typed edit classification | **reuse** `KernelAdapter` read-back + `ConditionEdit`/`ConditionEditScript` |
| canonical condition text | **reuse** `rule format` |
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
| **Git diff integration** | use `model diff`, not a `textconv` driver | `textconv` needs per-user executable configuration, produces a lossy human-only diff, and is not executed by GitHub's PR renderer; normalization covers line-diff hygiene while `model diff` provides the semantic review |
| **v1 scope** | single model; `workspace` rollup deferred | bound the first build; the single-model engine is what the rollup reuses |
| **Gloss authorship** | Hybrid (C) — tool gloss authoritative + facts + verbatim message; agent enrichment marked, never replacing | preserves tool-truth while staying fluent; the independent gloss enables the gloss-vs-message drift signal (§5d) |
| **First-write churn** | an explicit `model normalize` verb | canonicalizing a foreign model is a deliberate, isolated, reviewable step — never buried in a content edit |
| **Deterministic writer** | a Phase-0 foundation, not optional hygiene (§2) | the trust invariant *no change → no diff*, plus sane `git blame`/merges, rest on it |
| **Visualization** | mermaid as a narrow accent; bespoke HTML out (§6) | dmtool's data is tree + text; only workspace-topology + scoped-dependency are graph-shaped; in-chat HTML is standardized only via MCP Apps |

### 9d. Redundancy & non-overlap

The verbs were audited against the existing read surface (trigger: an early `model report` carried a `usage` map that literally duplicated `model usage`).

- **`model report` is a lean re-projection, not a dup:** its `identity` ⊂ `model info` and `structure` ⊂ `model describe` are *leaner, different* projections (the comprehension frame — header essentials, an outline of path+kind) that point at `info`/`describe`/`field read` for authoritative detail. The full field→referrers map stays `model usage`; the report carries only the **dead-field set** (the audit value). **Polarity** is one report-level fact, not repeated per entry. Per-rule `canonicalText` is an inline convenience (and the *only* representation for an Opaque rule), not a wholesale dup of `rule format`.
- **No overlap:** `model diff` is the only diff, `model normalize` the only canonicalizer, and report (comprehend one model) vs diff (compare two) are distinct.
- **Forward constraint:** the deferred `workspace report`/`workspace diff` rollup ([`MODEL-REVIEW-GAPS.md`](MODEL-REVIEW-GAPS.md) §2) **must not** duplicate `workspace list` (the model index) or `workspace check` (the validation gate) — it is the per-model report/diff *aggregator*, to be designed non-overlapping.
