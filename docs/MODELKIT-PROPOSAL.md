# modelkit — proposal addendum & knowledge-source evaluation

> **Status: spike complete; modelkit M1–M6 landed (2026-06-06) — see §H.** This is an **addendum to [`PROJECT-PROPOSAL.md`](PROJECT-PROPOSAL.md)** — it does *not* supersede any of it. It captures the reasoning behind fork #8 (D9) / Phase 3 (§16) / the umbrella (D10) that outgrew the proposal's one-liner, records a **material-source evaluation** (§E), and carries the **spike outcome + the go-forward plan** (§H): modelkit is feasible (foreign-rule read since taken to **100% typed on both corpora**, F-RES shipped), the **M1–M6 builders** have shipped (skeleton, type config + requiredness, the full field-type system incl. typedef reuse, field/group metadata, includes, repeatable-group keys + enum categories — the `dm.modelkit` package), and the go-forward direction is **read → edit → write-back** (§H "Direction"). modelkit was extracted as its own Gradle module (`:modelkit`) on 2026-06-06, then **folded back into the root project as the `dm.modelkit` package on 2026-06-09** (D10 superseded — see the Direction note below): the structure⊥dsl boundary is now `dm.modelkit ⊥ dm.rulekit`, held at the package level by `SiblingBoundaryTest` rather than by module isolation, and modelkit may use `dm.core` + the kernel. Its tests live in the root test set (shared fixtures + the kernel oracle). The core↔rulekit split stays deferred (tightly coupled, most-shared fixtures — low boundary value).
>
> **Direction change (2026-06-09 — D10 superseded by Architecture A):** the kernel-free wall is being **dropped**. modelkit becomes a **sibling over a shared `core`** (may use `core` + the kernel), unified with rulekit on the `BoundModel` rep. The next increments (**M7+**) turn modelkit from build-from-scratch into the structure half of the **unified read → edit → write-back** goal (read · edit-in-place · delete · doc-config), structure constraints riding the kernel `checkConsistency` gate. The TS-twin guarantee rides **D9** (DM-JSON), not the wall. See §H "Direction" + ARCHITECTURE "the umbrella".

modelkit = a typed, kernel-validated kit for document-model **structure** (fields, groups, type-defs, includes, doc config), the peer of rulekit's rule kit. It began as a build-from-scratch authoring builder (M1–M6); its go-forward scope is **read → edit → write-back** of an existing model (M7+, "Direction" §H — the use case). ARCHITECTURE [§5.2 + "the umbrella"](ARCHITECTURE.md) decides *where the code goes* (now: the `dm.modelkit` sibling package over `dm.core`); this doc argues *whether it's worth it* and *what knowledge it's built from*.

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
| 6 | **`a12-kernel-mm` real models — the rules** (547 constraint conditions across the 15 metamodels) | **D + B** — how real-world rules are written *and* (because they constrain models) the structural validity laws in executable form | EUPL-1.2, read-only | ✅ **read round-trip = 100% typed** (both corpora; see WORKSPACE-CORPUS-ANALYSIS); the structural validity laws they encode are mined into KERNEL-FINDINGS | Confirmed both "we understand these rules" and surfaced the structural laws — the kind-B oracle modelkit's constraint work leans on. |
| 7 | **A12 Tools workspace corpus** (Corpus 1; ~150 conditions across application models) | **D** — application-domain rule patterns; **B** — real header/locale/include conventions at scale | **proprietary** — read-only, never commit/migrate; transform via own-domain re-expression | ◐ Operator histogram + the bare-locale finding (268 `en` vs 107 `en_US`) | Real-world structural conventions (how apps actually shape models) inform modelkit's *ergonomics* (defaults, common group shapes), not its laws. Patterns only; nothing committed. |
| 8 | **`BasicConsistencyCheck` + the 16 `BasicDocumentModelConsistencyCategory`** | **B** — the executable structural validator: every category = a way a model can be structurally invalid | kernel public surface | ✅ All 16 categories classified + reflectively CI-locked (instrument 4) | modelkit's **runtime oracle** (the structural analogue of `RuleValidator`). Already the F6 output contract — modelkit asserts against it directly. |
| 9 | **MVK ledger** ([MVK-LEDGER.md](MVK-LEDGER.md)) | **B** — every `MVK_*` rule-diagnostic classified (erased-by-types / pre-checked / kernel-delegated / pending) | ours (MIT), CI-locked | ✅ Complete + CI-gated | The *model* for a modelkit-side ledger of structural diagnostics (which `BasicConsistencyCheck` categories modelkit erases-by-types vs delegates). |
| 10 | **Parsetree-api construct-name enums** (KERNEL-FINDINGS §238) | **A** — the kernel's machine-readable operator inventory (reverse-parity anchor) | kernel public surface | ✅ `KernelConstructParityTest` | Operator-side, not structural; listed for completeness. If a structural-construct enum exists (field-type registry), it's the parsetree-equivalent for **B**. |

**The headline read of the table:** every source is *rules-mined* well (the rulekit work) but **structure-mined barely** — rows 2/3/6 (the metamodel slot inventory, the betterbuilders API shape, the structural laws the metamodel rules encode) are the under-exploited gold, and they are exactly what modelkit needs. The spike targets them.

---

## G. Open decisions (deferred, recorded so they aren't lost)

| Decision | Current lean | Trigger to revisit |
|---|---|---|
| **Module vs package** — *resolved 2026-06-09* | Extracted as `:modelkit` (2026-06-06), then **folded back into the root project as the `dm.modelkit` package** (D10 superseded — siblings over a shared core; the kernel-free wall is dropped). `dm.modelkit ⊥ dm.rulekit` is held by `SiblingBoundaryTest` (a package import sweep). The `:core`/`:rulekit` split stays deferred. | *Resolved* — see "Direction (2026-06-09)" + ARCHITECTURE "the umbrella". |
| **Umbrella name — decided 2026-06-06** | Repo **`a12-dmkits`**; Maven group **`io.github.mbackschat.a12.dm`**; **package root `a12.dm`** with sub-packages `dm.core`/`dm.rulekit`/`dm.modelkit`/`dm.adapter`/`dm.cli` (1:1 to future subprojects); kits keep their names. Full rationale + diagram: [`ARCHITECTURE.md`](ARCHITECTURE.md) "the umbrella". The package-root rename (cheapest pre-publish) is the gateway; the repo rename + Gradle module split can lag. | modelkit is now a real peer (M1–M4) — the rename is the next pre-publish gate. |
| **Schema-oracle provenance** | MM_ metamodels are the schema oracle but **EUPL** — re-express the *mechanism* (slots, the include scheme) in our own domain; never commit their content. | Each structural increment (same discipline as the rule corpus). |
| **Source-artifact provenance on the index** (mirror of D14) | TBD — needed so include-aware editing later writes to the *owning* artifact. | When modelkit gains include-editing (beyond the MVP). |
| **Formalization target per kind** | B → derive-from-oracle (option 4) + the typed builder; C → executable laws (option 1); the skill's C-facts → modest machine-readable spine (option 2) only where a consumer exists; E → prose. | If a concrete consumer for a richer knowledge-spine appears. |

---

## H. Spike outcome, and the go-forward plan (2026-06-05; plan extended 2026-06-09)

> The spike (2026-06-05) ran and is done; **the current go-forward plan is the "Direction (2026-06-09)" subsection below** — read → edit → write-back, M7+. The spike's *forward-useful* findings are kept here; its raw read-coverage measurements live in [`WORKSPACE-CORPUS-ANALYSIS.md`](WORKSPACE-CORPUS-ANALYSIS.md), the behavioral laws in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md).

A time-boxed feasibility spike confirmed modelkit is a short hop — high foreign-rule read coverage (since taken to **100% typed** on both corpora) plus clean, enumerable metamodel slots — and **M1–M6 shipped** (2026-06-05/06): skeleton; type config + requiredness; the full field-type system incl. typedefs + the date family; field/group metadata; includes; repeatable-group keys + enum categories — each kernel-validated through both gates and locked by its own `…Test`, and **dogfooded** (`ModelDrafts` authors the test fixtures, replacing hand-written `.dm.json`). The read/adapter work the spike also drove (read → 100%, F-RES, F-LAW, the operator tail, `$` correlation) is **done and tracked elsewhere** — [`WORKSPACE-CORPUS-ANALYSIS.md`](WORKSPACE-CORPUS-ANALYSIS.md), [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md), [`API-POC-STUDY.md` §9](API-POC-STUDY.md) — not here.

### The structural knowledge the plan builds on (from the spike)

The forward-useful spike results — the inputs M7–M10+ consume:

| Source | What it gives the structure work |
|---|---|
| **MM_ metamodels** (schema oracle) | the slot inventory for Field/Group/TypeDef/Header; MVP minimum = `DM(id, locale) → Group(name) → Field(name, type + inline config)` |
| **betterbuilders** (the kernel's own) | confirms the construct set; **names the DM-config slots modelkit must expose** — `decimalSeparator`, `timeZone`, `conditionLanguage`, `fieldRefByShortNameAllowed` (the metamodel stays the slot authority) |
| **`BasicConsistencyCheck`** (the write gate) | the **16 `BasicDocumentModelConsistencyCategory`** entries are the structural constraint oracle (`SerializerTierParityTest`-locked); the structural subset — `ID_EMPTY`, `NAME_EMPTY`, `ENUM_TYPE_DUPLICATE_*`, `INDEX/SORT_FIELD_NAME_EMPTY`, `TYPEDEF_*`, `LOCALIZED_TEXT_*` — is the make-illegal-unrepresentable target |

### Direction (2026-06-09) — M7+: the go-forward plan (read → edit → write-back)

**This is the go-forward plan; the use case is the north star:** *read a DM → add fields/groups → add/modify rules → maybe delete → write back*, on one shared in-memory rep, reached **via iterations** and growing to **full support for ALL DM structures** (includes, typedefs, fields, groups, doc config, rules/computations) **and their constraints** (e.g. what's allowed at the top level). Today this is half-built — rulekit already does read→edit-rules→write-back (include-preserving); the gaps are **structure editing, a structure-read path, delete, full coverage,** and **unifying both kits on one rep.**

**Foundation — done (2026-06-09).** D10's kernel-free module wall is **dropped** (Architecture A — siblings over a shared `core`; ARCHITECTURE "the umbrella", PROJECT-PROPOSAL D10 revised). modelkit is now the `dm.modelkit` sibling **package** in the root project (folded in; `dm.modelkit ⊥ dm.rulekit` held by `SiblingBoundaryTest`), free to use `dm.core` + the kernel — the structural precondition for sharing rulekit's rep. TS portability rides **D9** (DM-JSON), not the wall.

**Design choice — edit the source tree, re-bind to the kernel.** Structure edits mutate the authored DM-JSON tree **surgically** (via `DmJson`, include-preserving) and **re-bind** through `ModelLoader`/`checkConsistency` to validate — the *same pattern rulekit already proves for rules*, extended to structure. modelkit's typed builders (M1–M6) are reused to **construct the node to insert**; the shared rep is the kernel-bound model (`BoundModel` / `ModelIndex` + the retained source), not a separate parallel draft tree (which would risk dropping unknown elements on re-emit).

**Constraints — two tiers, no whack-a-mole.** (1) Make illegal states unrepresentable in the typed edit ops where cheap (as M1–M6 already do — `repeatable(<2)`, undeclared-typedef-ref, …). (2) Lean on the kernel `checkConsistency`/`BasicConsistencyCheck` gate for the **full** set — free on re-bind/write; map each `MVK_*`/category to a clear diagnostic. "What's allowed where" is **mined** from the existing oracles (the MM_ metamodels, the 16 `BasicDocumentModelConsistencyCategory` entries, the MVK ledger — see the table above), per the anti-whack-a-mole rule.

**The increments — earned iteratively, each runnable + red/green-locked:**

| Increment | Plan phase | What |
|---|---|---|
| ✅ done | Phase 0 | record the decision — ARCHITECTURE / PROPOSAL / this doc carry Architecture A |
| ✅ done | Phase 1 | siblings over core — modelkit folded into root; `SiblingBoundaryTest` guard; no behavior change |
| ✅ **M7** | Phase 2 | **read structure + round-trip identity** — landed 2026-06-10: `ModelDocument.read(json)` reads the authored source tree (kernel-free), enumerates header/config/includes/typedef-ids + the group/field tree (name-path, field-type, repeatability), and round-trips it losslessly via the generic Jackson tree — content-equal + idempotent + still kernel-valid (`ModelDocumentTest`; fidelity finding in KERNEL-INTEGRATION-NOTES) |
| ◑ **M8** | Phase 3 | **edit fields + groups** — landed 2026-06-10 (library): `ModelDocument.addField`/`addGroup`/`addRepeatableGroup`/`removeField`/`removeGroup`/`setRepeatable` (surgical tree edits, reusing the builder's node emitter so shapes match) + `validate()` (the kernel gate, funnelling both rejection tiers into `ModelConsistencyException`); `DmJson` moved → `dm.core` (`ModelEditTest`). **CLI structure-edit verbs fold into M9** (built once with the unified session) |
| ✅ **M9** | Phase 4 | **unify rules + structure in one edit session** — landed 2026-06-10: `dm.edit.EditSession` (the orchestration layer above the two ⊥ kits) threads one DM-JSON through both — structure edits (modelkit) and rule/computation edits (rulekit) interleave (add a field, then a rule referencing it), validated once (`EditSessionTest`) |
| ◑ **M10+** | Phase 5 | **full coverage** — first wave landed 2026-06-10: **doc config** (`setDecimalSeparator`/`setTimeZone`/`setConditionLanguage` — the comma-vs-point concern), **type definitions** (`addTypeDefinition` + `addTypedefField`), **includes** (`addInclude` + `mount`, validated against the referenced model via a resolver) — `ModelStructureCoverageTest`. Then (2026-06-10): **richer field-type config** (`addField(FieldTypeSpec)` — string bounds/pattern, number scale, enum + categories, date format/precision; `setFieldType` re-type), **element metadata** (`setLabel` per-locale, `setAnnotation`), and the **CLI structure-edit verbs** (`add-field`/`add-group`/`remove` with the safe-delete `whereUsed` gate, demoed in `cli-structure-edit.md`). **Remaining tail:** placement/top-level constraint surfacing; CLI exposure of the metadata/rich-type edits |

**Deferred until first needed** (minimum code, nothing speculative): moving `DmJson` → `dm.core` lands with **M8**; extracting a `dm.core.BoundModel` substrate lands when **M9**'s unified session needs the shared type. Both are flagged pending in ARCHITECTURE (its core-box already shows the target). **M7's opening step is a source-mining pass over the kernel's Jackson/model-impl tier** (DM-JSON defaults, optional slots, `@JsonTypeInfo` discriminators in `kernel-md-model`/`kernel-md-a12internal`) — deliberately deferred until then per the consumer-exists discipline (§B/§D): the read→edit→write-back path is that knowledge's first consumer.

---

*Cross-refs: ARCHITECTURE [§5.2 + the umbrella](ARCHITECTURE.md); PROJECT-PROPOSAL D9/D10/N8/N10, fork #8, §16; [KERNEL-FINDINGS](KERNEL-FINDINGS.md) §schema-oracle / §includes; [WORKSPACE-CORPUS-ANALYSIS](WORKSPACE-CORPUS-ANALYSIS.md); [MVK-LEDGER](MVK-LEDGER.md). Spike harnesses: `adapter.KernelMmReadCoverageTest`, `adapter.Corpus1ReadCoverageTest`.*
