# modelkit — proposal addendum & knowledge-source evaluation

> **Status: spike complete; modelkit M1–M6 landed (2026-06-06) — see §H.** This is an **addendum to [`PROJECT-PROPOSAL.md`](PROJECT-PROPOSAL.md)** — it does *not* supersede any of it. It captures the reasoning behind fork #8 (D9) / Phase 3 (§16) / the umbrella (D10) that outgrew the proposal's one-liner, records a **material-source evaluation** (§E), and carries the **spike outcome** (§H): modelkit is feasible, foreign-rule ingest now types **100% of the kernel-mm metamodel rules** (Corpus-1 55%, pending the `F-RES` multi-file loader), and the **M1–M6 builders** have shipped (skeleton, type config + requiredness, the full field-type system incl. typedef reuse, field/group metadata, includes, and repeatable-group keys + enum categories) (the `modelkit` package — see §H increment list). modelkit is now its own Gradle module (`:modelkit`, extracted 2026-06-06) — standalone, Jackson-only, so the structure⊥dsl boundary (D10) is compiler-enforced; its tests stay in the aggregate set for now (shared fixtures + the kernel oracle live there). The core↔rulekit split stays deferred (tightly coupled, most-shared fixtures — low boundary value).

modelkit = a typed, kernel-validated builder for document-model **structure** (fields, groups, type-defs, includes), the peer of rulekit's rule builder. ARCHITECTURE [§5.2 + "the umbrella"](ARCHITECTURE.md) decides *where the code goes*; this doc argues *whether it's worth it* and *what knowledge it would be built from*.

---

## A. Why modelkit — the asymmetry

rulekit edits the **rules inside** a model. modelkit edits the **model itself**. They are **parallel layers over a shared model-core**, not stacked:

```
 model-core   (index · GENERIC dsl-free DM-JSON write · validate · diagnostics)   ← shared base
  ├── rulekit    (catalog · AST/dsl · rule builders)        — over model-core
  └── modelkit   (Field/Group/TypeDef/Include builders)     — over model-core, does NOT touch dsl
```

The asymmetry that makes it interesting: **rulekit presupposes a model exists.** Everything binds to a `BoundModel`. Today those models come from hand-written DM-JSON fixtures or the kernel demo. modelkit is the missing half — it lets the *structure* be authored typed-and-kernel-confirmed too.

The case for it, in one table:

| Argument | Substance |
|---|---|
| **Closes the authoring loop** | We can type-safely author *half* a document model (rules); the structure half is raw JSON text blocks. modelkit makes the whole model a typed, kernel-confirmed artifact — the completeness story for the OSS pitch. |
| **The oracle already exists** | rulekit's edge is "confirmed by the engine, not just plausible." modelkit gets the *same* oracle free — `BasicConsistencyCheck` / the 16 `BasicDocumentModelConsistencyCategory` (the F6 work) already validate *structure*. No new oracle to build. |
| **The hard prep is done** | `write.DmJson` (`insertIntoGroup`/`groupElements`/`findElement`, include-preserving) is the generic, dsl-free write half — extracted 2026-06-05 for exactly this. |
| **Dogfoods the fixtures** | Our own-domain test fixtures could be *authored through modelkit* — typed, validated, a constant live proof the kit works (today they're hand-written DM-JSON). |
| **Persona symmetry** | The BA gets whole-model authoring via the CLI (build a model from scratch), not just "add a rule to an existing model." |

---

## B. The thesis — modelkit *is* formalization

The reframing that ties this addendum together: **modelkit is not "a Field/Group builder" — it is the vehicle for formalizing the structural half of the document-model knowhow.**

Just as rulekit turned *"prose about valid rule conditions"* into a typed AST + a self-verifying operator catalog, modelkit turns *"prose about valid model structure"* into a **typed builder that makes invalid structure unrepresentable**. The bigger prize the project is circling isn't a feature — it's *capturing the document-model knowhow as formal, tool-/LLM-consumable artifacts instead of md prose.* modelkit is one pillar of that; the operator catalog is another; the executable kernel-laws a third.

So "should we formalize the knowhow instead of md files?" is largely answered by:

> **build modelkit** (formalizes structural validity into types) **+ extend the executable-laws pattern** (formalizes behavioral semantics) **+ keep the *why* in prose** (by design).

---

## C. Q1 — Why not `betterbuilders`, beyond the public-surface rule

The owner's rule (ship against the kernel's **public** OSS surface only) is the load-bearing constraint. Worth seeing *why* the betterbuilders calculus differs from rules to structure:

For **rules**, D9 rejected betterbuilders because *"the condition stays an opaque string — adds nothing to the AST work"* ([KERNEL-RULE-API-SURVEY](KERNEL-RULE-API-SURVEY.md) C). That argument is **weaker for structure** — betterbuilders genuinely *does* encode the slot knowledge for fields/groups. So the real disqualifiers are the boundary, not "it adds nothing":

| Concern | Why it disqualifies betterbuilders for modelkit |
|---|---|
| **Internal tier** | Violates the §3 public-only core boundary (ARCHITECTURE). modelkit-on-betterbuilders would be **adapter-locked = Java-only** — it could not live in `model-core`. |
| **Kills the TypeScript twin** | §4's data-only out-surface (the DM-JSON spine) is exactly what keeps the twin possible. betterbuilders is Java in-memory objects — no polyglot path, ever. |
| **Mutability is "convenience, not contract" (D9)** | betterbuilders builds the *internal* model; D9 states internal mutation isn't a contract. Building modelkit's primary surface on a non-contract fights the F11 version-tolerance posture. |
| **DM-JSON is already the only write path** | `IDocumentModel` exposes *no setters* (survey line 120) — DM-JSON *is* the public write surface, and `write.DmJson` already implements it. |

**But** — like the metamodels — betterbuilders stays valuable as a **read-only reference/oracle**: study its API to *learn* the valid slots, then re-express through DM-JSON. "Reference, never dependency" (D9). That is the bridge to the knowledge-source evaluation below.

---

## D. Q2 — Formalize the knowhow: the gradient and the options

The knowhow is **not homogeneous**, and the project already has a **formalization gradient**. The question is never "prose vs formal" — it's "for each *kind* of knowledge, what representation, and where does formalizing pay for itself?"

| Knowledge kind | Today | Formalized? |
|---|---|---|
| **A. Operator semantics** (DSL vocabulary) | `operators.json` / `OperatorCatalog`, self-verified by N7, served via `cli operators` | ✅ **Fully** — and it's the *proof formalization pays*: CLI + skill + N7 all consume it |
| **B. Structural validity** (what is a valid DM) | MVK ledger (CI-checked) · slot-parity tests · 16 `BasicConsistencyCheck` categories (reflectively locked) · MM_ metamodels (oracle) | ◐ Partly — and *the kernel already formalized it* (metamodel = the formal schema; validator = the executable check) |
| **C. Behavioral semantics** (empty-as-0, polarity, path walk-up, date arg order) | prose in KERNEL-FINDINGS/SEMANTICS; *some* locked as executable `…LawsTest` | ◐ Partly — the **irreducibly-ours** part; *not* in the metamodel, it's engine behavior |
| **D. Rule *patterns*** (how real rules are written) | prose in [WORKSPACE-CORPUS-ANALYSIS](WORKSPACE-CORPUS-ANALYSIS.md) | ✗ No (deliberately — it drives the backlog, not a runtime artifact) |
| **E. Rationale / judgment** (the *why*) | prose | ✗ **By design** — CLAUDE.md: "keep prose for the rationale, which doesn't tabulate" |

The options to formalize — **not exclusive; the right answer layers them by knowledge kind:**

| Option | Pro | Con / when it's a trap | Best fit |
|---|---|---|---|
| **1. Executable laws** (extend `validate/laws/…LawsTest`) | can't go stale — the build catches drift; real-kernel-verified | tests *assert* facts, they don't *serve* them (you can't query "what happens to an empty number" from a test). Correctness-gating, not knowledge-serving | kind **C** |
| **2. Machine-readable spine** (extend the operators.json model) | consumable by tool *and* LLM; one source can generate prose + skill + CLI help | needs a schema; **classic over-engineering trap** if nothing consumes it | the **C**-facts the skill teaches |
| **3. Encode validity AS kernel rules** (self-hosting / meta) | maximally elegant — dogfoods rulekit on itself; the MM_ rules *already do exactly this* | huge; MM_ is EUPL (re-express); most of **C** (empty-as-0) isn't expressible as a rule — it's engine internals | aspirational |
| **4. Derive-from-oracle** (generate from metamodel/validator/enums) | zero drift *by construction* | EUPL provenance on MM_ structure (re-express the mechanism, not the expression — standing rule); covers **B** only, never **C** | kind **B** |
| **5. Literate prose, single-source + generate** | cheap, readable | prose-rot is the disease we're treating | kind **E** only |

**The discipline that decides each row — name it loudly:** *formalize a fact only when a consumer exists* (the tool, the LLM, or a CI gate). The operators catalog works because three things consume it (CLI, N7, skill). A "DM knowledge ontology" that nothing queries would rot **worse** than prose — cost with no payoff. Formalization that only feeds other formalization is the trap.

---

## E. Material-source evaluation — *all* the knowledge on document models

The owner's priority: **use as many sources as possible** to extract document-model knowledge. This is the inventory, so it isn't forgotten and the spike (and later analysis) can pick up each thread. Columns: what DM-knowledge it carries, the access/license constraint, how far it's been mined *today*, and its **untapped potential for modelkit / formalization.**

| # | Source | DM-knowledge it carries | License / access | Mined today | Untapped potential |
|---|---|---|---|---|---|
| 1 | **Operator catalog** (`operators.json` + `OperatorCatalog`, self-verifying via N7; `cli operators`) | **A** — DSL vocabulary: keywords (EN/DE), operands, kinds, examples, gotchas | ours (MIT) | ✅ **Fully formalized** — the model to imitate | The *template* for a structural-construct catalog (Field-kind / Group / TypeDef catalog). Reuse the self-verify + serve-via-CLI pattern. |
| 2 | **`a12-kernel-mm` metamodels** (`MM_Field_1`, `MM_Group_1`, `MM_Typedef_1`, `MM_Rule_1`, `MM_Computation_1`, `MM_Common_v2`, `CT_MODEL_HEADER_1`, …; 15 canonical, **547 rules**, **473 fields**) | **B (structure!)** — the *formal schema of document-model structure itself*: which slots a Field/Group/TypeDef has, their types, their constraints. **This is the kernel's own formalization of B.** | **EUPL-1.2** — read-only, own-words only; sibling clone `../a12-kernel-mm`, opt-in via `KernelMmIntegrationTest` (skips if absent) | ◐ **Loaded + validated only** — all 15 load/expand/`checkConsistency` under pinned 30.8.1; `CT_MODEL_HEADER` slots documented (KERNEL-FINDINGS §schema-oracle). **Slot inventory for Field/Group/TypeDef NOT yet extracted.** | **The richest, least-exploited source for modelkit.** The MM_Field/Group/Typedef models *are* modelkit's input schema (option-4 derive-from-oracle). Their **rules** encode the structural validity laws (constraint oracle). This is the spike's Q2. |
| 3 | **`betterbuilders`** (kernel-internal `RuleBuilder` et al. — structure builders) | **B** — the kernel's own structure-builder API shape: what it lets you set on a field/group | **internal tier** — reference, never dependency (D9); read-only | ✗ Not mined | Cross-check the metamodel slot inventory against an *API* view of the same slots (two angles on B). Confirms we haven't missed a slot. Never linked/depended-on. |
| 4 | **`KERNEL-SEMANTICS.md`** (a12-kernel-demo; 14 sections) | **C** — behavioral semantics: truth values, empty values, formal errors, *required*, decimals, dates, strings/patterns, enums, iteration, paths, computations, validation model, messages, CustomCondition | consult-only, **do not write** (owner policy) | ◐ Consulted per-increment; the canonical behavioral reference | The authoritative inventory of **C** (the executable-laws target, option 1). §4 "required" + §12 "validation model" are directly modelkit-relevant (field requiredness, formal vs rule validation). |
| 5 | **`KERNEL-FINDINGS.md`** (ours; section-numbered to mirror #4; every entry test-locked) | **B + C** — experiment-verified facts the demo lacks: schema-oracle (B), includes/`modelAlias` mechanism (B), polarity, message-locale law, paths, AOT | ours (MIT) | ✅ The *already-formalized* end of **C** — each entry has a `…LawsTest` lock | The proven option-1 pattern. New structural findings from the spike land here, section-numbered, test-locked. The §schema-oracle + §includes entries are modelkit's starting structural facts. |
| 6 | **`a12-kernel-mm` real models — the rules** (547 constraint conditions across the 15 metamodels) | **D + B** — how real-world rules are written *and* (because they constrain models) the structural validity laws in executable form | EUPL-1.2, read-only | ◐ Operator *histogram* extracted (FieldFilled 293, RNU 90, CustomCondition 53, Pattern 55, GroupFilled 41 …); the **ingest round-trip coverage is NOT measured** | The spike's Q1: run rulekit `ingest`/`explain` over these 547 → coverage % vs `Opaque` (F10 tail). Tests both "can we understand them?" *and* surfaces the structural laws they encode. |
| 7 | **A12 Tools workspace corpus** (Corpus 1; ~150 conditions across application models) | **D** — application-domain rule patterns; **B** — real header/locale/include conventions at scale | **proprietary** — read-only, never commit/migrate; transform via own-domain re-expression | ◐ Operator histogram + the bare-locale finding (268 `en` vs 107 `en_US`) | Real-world structural conventions (how apps actually shape models) inform modelkit's *ergonomics* (defaults, common group shapes), not its laws. Patterns only; nothing committed. |
| 8 | **`BasicConsistencyCheck` + the 16 `BasicDocumentModelConsistencyCategory`** | **B** — the executable structural validator: every category = a way a model can be structurally invalid | kernel public surface | ✅ All 16 categories classified + reflectively CI-locked (instrument 4) | modelkit's **runtime oracle** (the structural analogue of `RuleValidator`). Already the F6 output contract — modelkit asserts against it directly. |
| 9 | **MVK ledger** ([MVK-LEDGER.md](MVK-LEDGER.md)) | **B** — every `MVK_*` rule-diagnostic classified (erased-by-types / pre-checked / kernel-delegated / pending) | ours (MIT), CI-locked | ✅ Complete + CI-gated | The *model* for a modelkit-side ledger of structural diagnostics (which `BasicConsistencyCheck` categories modelkit erases-by-types vs delegates). |
| 10 | **Parsetree-api construct-name enums** (KERNEL-FINDINGS §238) | **A** — the kernel's machine-readable operator inventory (reverse-parity anchor) | kernel public surface | ✅ `KernelConstructParityTest` | Operator-side, not structural; listed for completeness. If a structural-construct enum exists (field-type registry), it's the parsetree-equivalent for **B**. |

**The headline read of the table:** every source is *rules-mined* well (the rulekit work) but **structure-mined barely** — rows 2/3/6 (the metamodel slot inventory, the betterbuilders API shape, the structural laws the metamodel rules encode) are the under-exploited gold, and they are exactly what modelkit needs. The spike targets them.

---

## F. The spike — focused, measurable, *not* a full-scale study

> **Executed 2026-06-05 — results in §H.** This section records the spike's *design* (the questions and method); §H carries the outcome and the forward plan.

modelkit is worthwhile **as the formalization vehicle**, not as "the next feature." Given that depth, the right next step was a **time-boxed feasibility spike, not a waterfall study** — because the load-bearing premises are *empirically checkable*, and the measurements reshape both the modelkit scope and the formalization strategy.

Three measurable questions:

| Q | Question | Method | What the number decides |
|---|---|---|---|
| **Q1** | Can we extract DM knowledge from kernel-mm **now**? ("we understand these rules, right?") | Run rulekit `ingest`/`explain` over the 547 kernel-mm metamodel rules; report **coverage %** (clean ingest vs `Opaque`/F10 tail) | Whether "extract ALL knowledge" is real today or aspirational; sizes the F10 gap |
| **Q2** | What does the metamodel actually constrain for **structure**? | Read MM_Field/Group/Typedef (+ CT_MODEL_HEADER) as the schema oracle; enumerate the slots each construct has, own-words | The modelkit-MVP input schema **and** the formalization target for kind B |
| **Q3** | What behavioral knowledge (**C**) is *not* in the metamodel/validator? | Inventory KERNEL-SEMANTICS + KERNEL-FINDINGS for facts no oracle covers (empty-as-0 class) | The set that must become executable laws (option 1), because nothing else covers it |

**Output** = a short feasibility memo (the three numbers/inventories) + a modelkit increment breakdown grounded in *measured* coverage, not speculation. Roughly a day, not a study. It either confirms the thesis (high ingest coverage + clean metamodel slots → modelkit is a short hop) or surfaces blockers cheaply.

The spike honors the standing rules: kernel-mm is **read-only, own-words, EUPL** (no committed content); betterbuilders is **reference, never dependency**; KERNEL-SEMANTICS is **consult-only**. New verified facts land in KERNEL-FINDINGS (test-locked); patterns inform the backlog; nothing of either corpus is committed.

---

## G. Open decisions (deferred, recorded so they aren't lost)

| Decision | Current lean | Trigger to revisit |
|---|---|---|
| **Module vs package** | Start as a **`structure` package** in core (crosses no kernel boundary → hasn't "earned" a module, §1). | Extract `:model-core` + `:modelkit` only when size/coupling justify it. |
| **Umbrella name — decided 2026-06-06** | Repo **`a12-dmkits`**; Maven group **`io.github.mbackschat.a12.dm`**; **package root `a12.dm`** with sub-packages `dm.core`/`dm.rulekit`/`dm.modelkit`/`dm.adapter`/`dm.cli` (1:1 to future subprojects); kits keep their names. Full rationale + diagram: [`ARCHITECTURE.md`](ARCHITECTURE.md) "the umbrella". The package-root rename (cheapest pre-publish) is the gateway; the repo rename + Gradle module split can lag. | modelkit is now a real peer (M1–M4) — the rename is the next pre-publish gate. |
| **Schema-oracle provenance** | MM_ metamodels are the schema oracle but **EUPL** — re-express the *mechanism* (slots, the include scheme) in our own domain; never commit their content. | Each structural increment (same discipline as the rule corpus). |
| **Source-artifact provenance on the index** (mirror of D14) | TBD — needed so include-aware editing later writes to the *owning* artifact. | When modelkit gains include-editing (beyond the MVP). |
| **Formalization target per kind** | B → derive-from-oracle (option 4) + the typed builder; C → executable laws (option 1); the skill's C-facts → modest machine-readable spine (option 2) only where a consumer exists; E → prose. | If a concrete consumer for a richer knowledge-spine appears. |

---

## H. Spike outcome — current state & what's next (2026-06-05)

The §F spike ran (Q1–Q5, widened per owner direction with **Q4 betterbuilders**, **Q5 write-gate categories**, and a **Corpus-1** counterpart for Q1). Its headline result: foreign-rule ingest/explain now works on real models — the index resolves by name-path (the form conditions reference), so the kernel-mm metamodels *and* the A12 Tools apps ingest at the rates below. The earlier id-vs-name resolution gap that had blocked this, and its correction, are recorded as settled fact in [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) (the `getId()`≠path note) and [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) §10; this section keeps the **measured state and the forward plan**.

### Where ingest stands now

| Corpus | Loadable models | Rules | Typed-converted | Understood (incl. correctly-Opaque) |
|---|---|---|---|---|
| kernel-mm metamodels | 15 / 15 | 553 | **553 (100%)** | **100%** (custom conditions convert to a typed, editable `CustomConditionRef`) |
| A12 Tools apps (Corpus-1) | 43 / 107 | 200 | **111 (55%)** | — (64/107 not yet loadable — need the include resolver `F-RES`) |

*Converter increments (2026-06-06), each round-trip + kernel-validity locked, taking kernel-mm 73.6%→**100%** (every rule typed): (1) enum **field-to-field** comparison `[A] == [B]` / `!=` (+10; `EnumFieldComparisonIngestTest`); (2) **field-list × value-list quantifiers** — constant value lists (+12; `EntityListValueListIngestTest`) and entity-valued value lists, i.e. a field list checked against another field list (+22; `EntityValuedListIngestTest`); (3) **count aggregates** `NumberOfFilledFields` over a repetition list and `NumberOfFilledGroups` over a single repeatable group `Group*` (+12; `FilledCountIngestTest`) — the latter corrected a too-strict rulekit invariant (KERNEL-FINDINGS §2 supplement); (4) the **`Length(field)`** operand wrapper (+7; `LengthIngestTest`); (5) **`Sum` over an explicit multi-field scope** (+1; `MultiFieldSumIngestTest`); (6) enum **category-navigation** comparisons `[field → Category] == "…"` (+5; `CategoryNavIngestTest`); (7) **group-scoped field-fill** — `AllFieldsFilled`/`AtLeastOneFieldFilled`/`NoFieldFilled` over a group scope, via the new `GroupListRef` (group-repetition-list) keeping nested stars faithfully (+9; `GroupScopedFieldFillIngestTest`); (8) **root-group presence** `GroupFilled`/`GroupNotFilled` on a root group — the kernel's `IRootGroupCondition`, mapped to the existing group-presence node (+6; `RootGroupConditionIngestTest`); (9) **arithmetic operands** in comparisons `[a] < [b] + 2` — the new `Arithmetic` calculation (kernel `ICompositeOperation`) + `NumberExpr.plus/minus/…` (+5; `ArithmeticIngestTest`); (10) the last comparison shapes — **category-vs-category** (`CategoryRef`-to-`CategoryRef`) and **constant-on-the-left** normalization `"X" != [f]` → `[f] != "X"` (+4; `ComparisonTailIngestTest`); (11) **custom conditions** `CustomCondition Y` → typed, editable `CustomConditionRef` (+53; `CustomConditionIngestTest`) — takes ingest to 100%.*

Both corpora resolve at majority-converted — general across the metamodels and the ordinary apps alike. Reproducible via the opt-in `adapter.KernelMmIngestCoverageTest` / `Corpus1IngestCoverageTest` (skip when the read-only corpus is absent; a coverage-floor gate keeps regressions loud). **Premise answered:** the kernel-mm rules *are* understandable — typed for the majority, and legible-as-text for the rest (the formatter emits readable conditions). Their `*_MISSING`/`*_INVALID`/`A12_*` rules are the structural constraint oracle in executable form — modelkit's kind-B target.

### The F10 tail — fully converted (100%)

**Every kernel-mm rule now ingests as typed AST: 553/553 (100%), 0 opaque.** The last step converted custom conditions: an `IApplicationCondition` is **name-only** (`getName()`, no operands), so the typed `CustomConditionRef(name)` captures it exactly — `[X] filled And CustomCondition Y` ingests fully typed and is editable end-to-end (rename the custom condition, swap the `X` side, save). Locked by `adapter.CustomConditionIngestTest`.

The `Ingest.Opaque` mechanism remains for genuinely-unconverted constructs (e.g. date shifts `AddDays`, the deferred aggregate names) — still preserved verbatim, never lost (N8); exercised by `IngestRoundTripTest.preservesAnUnconvertedConstructAsOpaque`. Custom conditions are no longer the example, because they're typed now.

> Note: converting a custom condition makes it *editable*, not *semantically understood* — rulekit still can't reason about what the host code decides (it never evaluates rules). For an edit kit that's the right line: represent it precisely so it round-trips and can be rewritten.

*Closed via the planned sequence (each round-trip + kernel-validity locked): A `Sum` multi-field ✅ → B category-nav ✅ → C group-scoped field-fill (`GroupListRef`) ✅ → D root-group ✅ → E arithmetic (`Arithmetic`) ✅ → F the last comparison shapes — category-vs-category and constant-on-the-left normalization ✅. (The 3 earlier `'False' DateType/format` opaques were resolved incidentally by the category-nav routing in B.)*

Corpus-1's tail skews toward date `Valid`/`Invalid` — the same F10 feature list, weighted differently. **Done so far:** enum field-to-field; the field-list × value-list quantifiers (constant **and** entity-valued value lists); both count aggregates (`NumberOfFilledFields` over a repetition list, `NumberOfFilledGroups` over a single repeatable group); and the `Length(field)` operand wrapper. Each remaining row is its own increment (catalog + AST + handles + N7 + converter wiring).

### Per-question results

| Q | Source (§E row) | Result |
|---|---|---|
| **Q1** | operators / kernel-mm rules / Corpus-1 | Foreign-rule ingest resolves by name-path: **kernel-mm 100% (553/553), Corpus-1 55%** typed-converted — every kernel-mm rule is now typed (custom conditions included, as editable `CustomConditionRef`). |
| **Q2** | MM_ metamodels (schema oracle) | Slot inventory captured for Field/Group/TypeDef/Header; **MVP minimum = `DM(id, locale) → Group(name) → Field(name, type+inline config)`**; typedefs/includes/annotations deferrable. |
| **Q3** | KERNEL-SEMANTICS + FINDINGS | **78 behavioral laws**; ~33 locked, ~29 prose-only; **17 HIGH-trap** (silently wrong if unknown), of which only ~4 are locked. The prose-only HIGH-trap set (empty-as-0, rounding, date arithmetic, three-valued Boolean/Confirm, GroupFilled-on-empty-row, computation-as-validation) is the convert-to-executable-law priority — and the minimum the N1 skill must teach (the self-describing CLI can't expose them; they're invisible to parse-time validation). |
| **Q4** | betterbuilders (kernel's own) | **Confirms** the construct set + high-level slots; **adds** DM-config slots (decimalSeparator, timeZone, `conditionLanguage` default `Locale.US`, `fieldRefByShortNameAllowed`). It's a thin facade over the kernel's autogenerated builders with a `modify(...)` escape hatch — the per-type attrs live in those, so the **metamodel stays the slot authority**. Borrowable: path-addressing + auto-vivify-parent, default-everything. Its MVP matches Q2's exactly. |
| **Q5** | `BasicConsistencyCheck` (write gate) | The **16 `BasicDocumentModelConsistencyCategory`** entries are modelkit's runtime oracle (already classified, `SerializerTierParityTest`-locked). rulekit satisfies the rule/computation subset by construction; **modelkit must satisfy the structural subset** (`ID_EMPTY`, `NAME_EMPTY`, `ENUM_TYPE_DUPLICATE_*`, `INDEX/SORT_FIELD_NAME_EMPTY`, `TYPEDEF_*`, `LOCALIZED_TEXT_*`) — the make-illegal-unrepresentable target list. |

### Verdict & grounded increment breakdown

**modelkit is feasible and *not* blocked by the ingest finding** — it authors structure and emits DM-JSON (reusing `write.DmJson`), validated by `BasicConsistencyCheck`; it never ingests. The slot inventory (Q2, cross-checked by Q4) is clear, the MVP is small, both oracles exist (Q5 write-gate; the kernel for deep checks).

Increments (each: typed builder slice + DM-JSON emit + kernel-validated, own-domain fixtures):

1. **M1 — skeleton — ✅ landed (2026-06-05):** `DocumentModelDraft.named(id).locale(…).rootGroup(name, g -> g.field(name, kind).group(…))` → `toJson()`, in the new **`structure`** package (Jackson-only, no kernel/`dsl` coupling — keeps the `:modelkit` extraction mechanical, D10). Easy kinds = **String/Number/Date/Boolean/Confirm** — `Unspecified` is *excluded* (it's an authoring placeholder the kernel rejects in a complete model: `MVK_UNSPECIFIED_TYPE_NO_CODEGEN`, correcting the Q4 list). Proven through **both** kernel gates — `checkConsistency` (via `ModelLoader`) and `BasicConsistencyCheck` (the serialize/write gate) — by `structure.DocumentModelDraftTest` (own "boutique" domain). Illegal states unrepresentable: no-locale / no-root-group / `repeatable(<2)` fail at build. Closes the first slice of the "stop hand-writing fixtures" loop.
2. **M2 — type config + requiredness — ✅ landed (2026-06-05):** type-safe per-kind field builders (`stringField`/`numberField`/`enumField`) — String(min/max/pattern), Number(scale/min/max), Enumeration(values + per-locale labels) — plus `Requiredness` (optional / required / required-if-parent). Cross-kind nonsense is off the surface; min>max, empty enum, incomplete enum labels, and pattern-without-message all fail fast *before* the kernel. The increment surfaced three structure-validity laws (`MVK_UNSPECIFIED_TYPE_NO_CODEGEN`, `MVK_ERROR_MESSAGE_AND_PATTERN_INVALID`, `MVK_COUNT_OF_VALUES_INVALID`) now recorded in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md). Locked by `structure.FieldConfigTest`. The 80% of real fields.
3. **M3 — the rest of the field-type system — ✅ landed (2026-06-06):** reusable **type definitions** (`typeDef(id, …)` declared on the model + `typedefField(name, id)` references, with fail-fast referential integrity; the typedef field resolves to its underlying kind after expansion) and the remaining **date family** (`dateField` configurable format, `dateRangeField` format+separator, `dateFragmentField`). New finding: `TypeDefType` nests `typeDefinitionId` under a `TypeDefType` block (KERNEL-FINDINGS). Locked by `structure.TypeDefAndDateTypesTest`. **Deferred to M4:** `CustomFieldType` (absent from both corpora — external registered-type dependency, "on real demand") and **metadata** (labels/descriptions/annotations on fields/groups) — orthogonal presentation data.
4. **M4 — metadata — ✅ landed (2026-06-06):** per-locale field **labels** and name/value **annotations** (on fields *and* groups), via a shared `FieldAttrs<SELF>` base so `required`/`label`/`annotation` apply uniformly across all six field builders. A label must cover every declared locale (uniform with enum labels). Locked by `structure.MetadataTest`; shapes in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md). *(Field descriptions + group labels are rare in both corpora — not built.)*
5. **M5 — includes — ✅ landed (2026-06-06):** `Builder.include(alias, reference)` (header `modelReferences`) + `GroupBuilder.mount(name, alias)` (a `modelAlias` mount group, no inline elements), with fail-fast referential integrity (a mount's alias must match a declared include). Validated by expanding against an in-memory resolver — dogfooded by building *both* the base and the included model with modelkit; the included fields resolve under the mount name-path (§10). Locked by `modelkit.IncludeTest`. *(Still deferred: `CustomFieldType` — absent from both corpora, external registered-type dep, on real demand; and the directory-`F-RES` resolver for loading multi-file models off disk, which is a CLI/loader concern, not a modelkit-authoring one.)*
6. **M6 — repeatable-group keys + enum categories — ✅ landed (2026-06-06):** `GroupBuilder.indexField(name)` (a repeatable group's row-key child field — fail-fast: requires `repeatable`, the named field must be a direct child) and `EnumFieldBuilder.category(name, classifications…)` (a named classification mapping positionally onto the enum's values — every value must be classified, length-checked at build). The last two structure slots our own rich fixtures use; closes the gap to dogfooding them. Locked by `modelkit.IndexFieldTest` + `modelkit.EnumCategoryTest`.

**Dogfood — ✅ (2026-06-06):** the "stop hand-writing fixtures" loop is now closed end-to-end. Test models are authored with modelkit in `ModelDrafts` (the peer of `Fixtures`, which loads committed JSON): the **logistics** fixture dropped its hand-written `.dm.json` entirely — it lives only as modelkit Java now, proven by `IterationLawsTest` (which validates rules against it through the real kernel) and `modelkit.DogfoodTest`. The **order** fixture keeps its committed JSON (30+ consumers) but `DogfoodTest` proves the modelkit-authored version is *structurally equivalent* — same skeleton, kinds, number scales, enum domain + categories, and repetition — so it could be switched over too. M1–M6 together can reproduce every own-domain structure we hand-wrote.

**Open cross-cutting follow-ups (independent of modelkit):**
- **Ingest tail:** ✅ **fully closed for kernel-mm (2026-06-06) — 100% typed (553/553)**, custom conditions included (typed, editable `CustomConditionRef`). *(Corpus-1 still sits at 55% — its remaining tail is the same feature set plus the `F-RES` multi-file loader gap; re-measure when those land.)*
- **F-RES:** a directory `IDocumentModelReferenceResolver` so multi-file real models load (64/107 Corpus-1 blocked; kernel-mm unaffected).
- **F-LAW:** convert the prose-only HIGH-trap behavioral laws (Q3) to executable laws — needs a document-evaluating oracle harness (rulekit's CLI deliberately never executes validation).
- **F-TEST:** dedicated unit test for the `ingest()` Opaque-on-unresolved-reference hardening (currently exercised only by the opt-in coverage harness — no focused unit test yet).
- **Operator tail (authoring + ingest):** the low-demand operators still deferred as catalog stubs — `RangeAsString` (the one everyday Corpus-1 gap, count 4), `Date(…)` construction / `Now` / `DateRange` / `DateRangesOverlap`, `SumOfProducts`, `FirstFilledValue`, `Valid`/`Invalid` (custom-field-type validity), date **shifts** ingest (`AddDays`/…, authorable but not yet ingested), and `$` correlation (needs field-vs-field in `Having`, D13). All absent from kernel-mm (hence 100% there); some appear in Corpus-1. Frequency-ordered in [`WORKSPACE-CORPUS-ANALYSIS.md`](WORKSPACE-CORPUS-ANALYSIS.md) §backlog — build on real demand.

---

*Cross-refs: ARCHITECTURE [§5.2 + the umbrella](ARCHITECTURE.md); PROJECT-PROPOSAL D9/D10/N8/N10, fork #8, §16; [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §schema-oracle / §includes; [WORKSPACE-CORPUS-ANALYSIS](WORKSPACE-CORPUS-ANALYSIS.md); [MVK-LEDGER](MVK-LEDGER.md). Spike harnesses: `adapter.KernelMmIngestCoverageTest`, `adapter.Corpus1IngestCoverageTest`.*
