# dmkits — feature gaps & ideas

A working backlog of capabilities **a12-dmkits** (rulekit + modelkit + `dmtool`) doesn't yet expose, or exposes only partially. It's a brainstorm against the document-model surface we author: a coverage self-inventory, then deep-dives on the gaps worth closing, with enough detail to scope each.

> **Grounding discipline.** Every capability below is justified by something concrete: a kernel API or a metamodel slot that *makes it possible*, or a place in our own code that has explicitly **parked / deferred** it. Nothing here is speculative beyond what the engine and the metamodels already support — each token (e.g. `unit`, `minFractionalDigits`, `suggestions`, `MoveSupportDM`, `IConditionFormatter`) was verified present in the kernel source, the metamodels, or our code before being listed.

> **Status:** analysis + proposal, 2026-06-12. Recommendations are sized, not committed. Canonical per-feature implementation status stays on [`API-POC-STUDY.md` §9](API-POC-STUDY.md); the refactoring contract on [`CLI-SPEC.md` §6](CLI-SPEC.md). Field-slot specifics are mined from the kernel metamodels (`../a12-kernel-mm`, the `MM_*` slot oracle) before any are added.

---

## 1. The surface we author

dmkits authors the **whole document model** — its structure (fields, groups, type definitions, includes, document config) *and* its rules and computations — with the A12 kernel as the confirming oracle: "valid" means the engine accepted it. Today's coverage of that surface is broad; the inventory below marks what's complete, partial, or not yet built, all against what the kernel and metamodels actually allow.

Where dmkits is strong: typed/compile-time-safe authoring, the read→explain→diff→re-validate loop, an agent-discoverable CLI. The gaps cluster in three places — **refactoring** (rename/move that rewrites references), **condition tooling** (canonical format, scoped evaluation), and **field-config completeness** (slots the metamodel allows that modelkit doesn't surface yet).

---

## 2. Coverage self-inventory

Legend: ✅ supported · ◐ partial · ❌ not yet built.

### Fields & field config
| Capability | Status | Note |
|---|---|---|
| Field kinds: String, Number, Date, DateTime, Time, Boolean, Confirm, Enumeration, Date Fragment, Date Range, Type Definition | ✅ | `FieldKind` + `FieldTypeSpec` (11 records) |
| **Custom** field kind | ❌ | deferred in `FieldKind.java`; no `CustomSpec`. Kernel ships `kernel-core-customfieldtype-api` |
| String: min/max length, pattern (+message) | ✅ | `StringSpec`/`StringFieldBuilder` |
| String: **line-breaks**, **alphabetical sorting**, **suggestions** | ❌ | metamodel has these slots; not modeled |
| Number: min/max value, fixed fraction-digits (incl. 0) | ✅ | `NumberSpec.fractionDigits/minValue/maxValue` |
| Number: fraction-digits **range** (min/max) | ❌ | metamodel `minFractionalDigits`/`maxFractionalDigits`; only the fixed form modeled |
| Number: **unit** (Amount/Percent/Permille), **zero-not-allowed**, **leading-zeros** | ❌ | all present as metamodel slots; no builder slot |
| Date Fragment / Date Range formats | ✅ | `DateFragmentSpec` / `DateRangeSpec` |
| Enumeration: values, per-locale labels, **categories** | ✅ | `EnumSpec`/`EnumValue`/`EnumCategory` |
| Enumeration: **alphabetical sorting**, **invalid-value error message** | ❌ | metamodel slots; not modeled |
| Type Definition field reference | ✅ | `TypeDefRef` + referential-integrity check |

### Field-level properties
| Capability | Status | Note |
|---|---|---|
| Required; "only if parent group filled" | ✅ | `Requiredness` (3 modes) |
| Per-locale **label** | ✅ | `FieldAttrs.label` / `ModelDocument.setLabel` |
| **Annotations** (name-value) on an element | ✅ | `ElementDraft.Annotation` / `setAnnotation` |
| **Global** flag; **Transient** flag | ❌ | both are metamodel field attributes; not modeled |
| **Internal / external description**, **helper text** | ❌ | metamodel slots (`internalDescription`/`externalDescription`/`helperText`); not modeled |

### Groups, rules, computations
| Capability | Status | Note |
|---|---|---|
| Groups, nesting, repeatability + max | ✅ | modelkit groups |
| Index field (+ auto uniqueness/mandatory rules) | ◐ | repeatable-group keys supported; confirm the auto-rule pair (`RepetitionNotUnique` + mandatory) is emitted |
| Validation rule: code, error field, **level** (Error/Warning/Info), condition, per-locale messages | ✅ | `Rule` / `Severity` |
| `@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)` | ✅ | DSL directive; `DirectiveLawsTest` |
| Error-message interpolation `$Field.value$` | ✅ | `Dsl.fieldValue` |
| Error-message interpolation `$Field$` (label), `$#Group$` (repetition index) | ❌ | kernel `ErrorTextParameter` / `repetitionIndex` support these; no API |
| Computation: computed field, common precondition, alternatives, comments | ✅ | `Computation` / `Alternative` |
| Canonical **format** of a condition | ❌ | kernel exposes `IConditionFormatter`; no verb wraps it |

### Type-defs, includes, config
| Capability | Status | Note |
|---|---|---|
| Local type definitions (add/rename/extract/inline/remove) | ✅ | `ModelDocument.*TypeDefinition` + CLI verbs |
| Included type-defs (read-only, via include) | ✅ | resolver-served |
| Imported type-defs / **type-def-only model** (author one) | ◐ / ❌ | imported refs resolve (`ImportedTypeDef`/`TypeDefModel` exist); no verb to *create* a type-def-only model |
| Include a model under a group; alias/name; resolution (`--include-dir`) | ✅ | `addInclude`/`mount`; `ReferenceResolvers.directory` |
| Include **repeatability**; **exclude-rules** flag | ❌ | mount repeatability hardcoded to 1; `excludeRules` is a metamodel include slot, not exposed |
| Include constraints (no cycles, single root, locale superset) | ◐ | enforced by kernel on expand; single-root enforced at build |
| Config: locales, time zone, decimal separator, base year, condition language, model name | ✅ | `ConfigEditor` / `DocConfig` |
| Config: model **labels**, model-level **annotations** | ❌ | header arrays exist but unpopulated; no API |
| Config: **supported characters** (custom allowed-character set) | ❌ | kernel validates against `supportedCharacters`/`characterSet`; not modeled |

### Composite element templates
| Capability | Status | Note |
|---|---|---|
| **Attachment** group template | ❌ | a first-class A12 group template (`attachment` in the kernel/metamodel); buildable by hand only |
| **Multi-Select** group template | ❌ | a first-class A12 template (`multiSelect`); buildable by hand only |

### Analysis & runtime
| Capability | Status | Note |
|---|---|---|
| Validate a model through the kernel | ✅ | `doc validate` |
| Evaluate rules against a document instance | ✅ | `doc eval` / `doc compute` (`IDocumentRtService`) |
| Scoped **single-rule** evaluation against an instance | ◐ | `doc eval` is the engine; no rule-scoped convenience — see §3.3 |

---

## 3. Gap deep-dives (the features worth building)

Each: what's missing, the current state (file:line), whether the kernel/metamodel helps, the build shape, a recommendation.

### 3.1 Within-model reference-rewriting rename + move — **the flagship gap** ⭐

**Current state.** Rename verbs exist for field/group/rule but **refuse** when the element is referenced — `SafetyGate` + `SafetyChecks.notReferenced` returns a `Refusal` ("rename the referrers first … references are not rewritten — that is the parked engine", `FieldRenameCommand.java:22-24`). There is **no `move` verb**. Type-def rename is the lone rewriter (it repoints `typeDefinitionId`, a metadata field, not an AST).

**The key finding — the kernel already ships the rewriting engine.** `MoveSupportDM.changePathReferencesForMove(DocumentModel, Map<oldPath,newPath>)` (`kernel-md/kernel-md-model/.../a12internal/services/MoveSupportDM.java`) updates references across **rule conditions, error messages, and computations** (preconditions, operations, common precondition) for both rename and move. The low-level `MoveSupportInternal` (`kernel-tool/kernel-core-service`) exposes:
- `String changeConditionTextForMove(conditionText, ruleFullname, entityMoves, lang)`
- `Map<Locale,String> changeErrorTextsForMove(errorTexts, ruleFullname, entityMoves, lang)`

It parses leniently (handles not-yet-valid input), preserves absolute-vs-relative path style where it can, and deliberately **does not touch `*`-bearing (repetition-list) references**. It rewrites *references*; the caller still moves the element itself.

> ⚠️ This is `a12internal` / `@OnlyForUsage` API — exactly the kernel-internal tier rulekit isolates behind its **adapter module** (the N8 / D15 boundary). A reference-rewriting rename/move belongs in `adapter`, wrapped and guardian-tested, not in public core.

**Build shape.**
1. Adapter wrapper around `MoveSupportDM` taking an `EditSession`/`ModelDocument` + an `entityMoves` map.
2. Flip `field/group rename` from *refuse-if-referenced* to *rewrite-then-validate* (keep the gate's other refusals — collisions, etc.).
3. New `field move` / `group move` verbs (move the node + rewrite refs + re-validate once).
4. Honor the kernel limitation: **refuse** (don't silently skip) when a `*`-reference would be affected — the safety-gate discipline already fits this.
5. Law tests on the does-fire tier: rename rewrites a referrer; move adapts a relative path; the `*`-reference case refuses.

**Recommendation: Tier 1, highest leverage.** Removes the single most-felt limitation (the "parked engine"), and the hard part (the rewriter) is a kernel primitive we wrap rather than build. Cost is dominated by guardrails and the `*`-reference refusal, not the rewrite logic. Update [`CLI-SPEC.md` §6](CLI-SPEC.md) and add the new internal-API coupling to [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md).

### 3.2 Canonical condition format — cheap, kernel-backed

**Current state.** The `DslRenderer` produces a canonical-ish rendering, but there's no verb that takes an *arbitrary* condition string and returns the engine's canonical form — and the renderer's normalization is *ours*, not guaranteed to match the kernel's.

**Kernel support.** Exposed and reusable: `IConditionFormatter` (`kernel-core-tool-api`, `…api.services.IConditionFormatter`) — `formatConditionText(IRule, IProblemReporter)`, `formatConditionSnippet(...)`, plus `formatAndTranslateConditionText(…, Locale, …)` (format **and** switch EN↔DE in one call). Backed by `FormatterVisitor`/`FormatterUtil` in `kernel-core-parser`.

**Build shape.** A `rule format` / `computation format` verb (or a `--format` flag on `read`/`modify` output) round-tripping through `IConditionFormatter`. Bonus: `formatAndTranslate` gives a kernel-authoritative EN↔DE switch, complementing `DslRenderer(DE)`.

**Recommendation: Tier 1, low cost.** Improves diff stability (re-express → format → compare) and gives a single canonical form. Thin wrapper over a public kernel API (it's `tool-api` — confirm core-reachability vs. adapter).

### 3.3 Scoped single-rule evaluation — packaging over an engine we already have

**Current state.** `doc eval --doc <instance>` already runs the whole model's rules against a document via `IDocumentRtService.validateFull` (`KernelAdapter.java:532`). What's missing is the *scoping* ergonomic: "evaluate **this one rule** and just its dependencies, on this data."

**Build shape.** A `rule test --doc <instance>` verb that (a) resolves the rule's referenced fields (we already compute referenced-entities / `whereUsed`), (b) evaluates, and (c) reports whether *that* rule fired — the empirical polarity check the project prizes. Could be pure packaging over `doc eval` (filter the result to the rule). For exact scoped-codegen semantics, the kernel also ships `IOnTheFlyTestingService` (`kernel-core-ontheflytesting`) which generates a scoped validation; wrap it only if we want that fidelity.

**Recommendation: Tier 1–2, mostly packaging.** Makes polarity *empirical* rather than argued — high value for agents — at low cost as a scoped filter over `doc eval`.

### 3.4 Field-model completeness — parity items (Tier 2)

Bounded, mechanical gaps in the modelkit field model. Each is a `FieldTypeSpec` slot + builder method + emit + a kernel round-trip law. The metamodels in `../a12-kernel-mm` are the authoritative slot oracle (mine `MM_*` for the exact JSON keys before adding each — they're confirmed present, e.g. `unit`, `minFractionalDigits`, `suggestions`, `customFieldType`).

| Gap | Detail to honor | Effort |
|---|---|---|
| Number fraction-digits **range** | min/max fractional digits (the fixed form already works; the range is the hole) | S |
| Number **unit** | Amount/Percent/Permille; **Amount restricts to 0 or 2 decimals** (a pre-check) | S |
| Number **zero-not-allowed**, **leading-zeros** | booleans (the kernel emits a dedicated zero-not-allowed error) | S |
| String **suggestions** | localized hint list, validated against the field's own pattern | M |
| String **line-breaks**, **alphabetical sorting** | booleans | S |
| Enum **alphabetical sorting**, **invalid-value error message** | sorting boolean; the error message is per-locale | S |
| **Custom** field kind | custom-type name + min/max length; kernel ships custom types (`kernel-core-customfieldtype-api`) | M |

**Recommendation: Tier 2, batch them.** One metamodel-driven field-config sweep, each item TDD'd against a kernel round-trip. Cheap wins that close the "can dmkits express every field the engine can?" question.

### 3.5 Field-level metadata — flags & descriptions (Tier 2)

- **Global** (partial-validation relevance) and **Transient** (not persisted) — two metamodel field attributes; both affect kernel semantics, so add as first-class attrs, not annotations.
- **Internal / external description, helper text** — per-locale text slots, distinct from `label` in the metamodel. Authoring + round-trip; no rule semantics.

**Recommendation: Tier 2.** Small, but verify the exact JSON keys via `a12-kernel-mm` first (descriptions, helper text, and labels are distinct slots).

### 3.6 Error-message interpolation — `$Field$` and `$#Group$` (Tier 2)

We support `$Field.value$` (`Dsl.fieldValue`). The kernel's error-text parameter mechanism (`ErrorTextParameter`) also supports `$Field$` (localized **label**) and `$#Group$` (repetition **index**, cf. `repetitionIndex`). Both are message-rendering concerns, not condition AST — add `Dsl.fieldLabel(ref)` and `Dsl.repetitionIndex(groupRef)` emitting `$Field$` / `$#Group$`. Confirm the exact tokens against the kernel message renderer.

**Recommendation: Tier 2, small.** Rounds out message authoring; the repetition-index form pairs naturally with uniqueness rules on repeatable groups.

### 3.7 Session-editor parity — `RuleEditor` severity/message edits (Tier 2)

`RuleEditor` currently exposes only condition `modify`; "severity / message / rename edits are not yet exposed — deferred" (`RuleEditor.java:13-14`). For the read→edit→write-back loop to be complete, an existing rule should be re-severity-able and re-message-able in-session without a full replace.

**Recommendation: Tier 2.** Mechanical editor extension; pairs with the rename-rewriting work (§3.1), since rename is the other deferred editor edit.

### 3.8 Composite-element templates — Attachment & Multi-Select (Tier 3)

Attachment and Multi-Select are first-class A12 group **templates** (both present in the kernel/metamodel). modelkit builds them only as hand-assembled plain groups. A `field add --kind ATTACHMENT` / a `multiselect add` generator would emit the canonical template so an agent doesn't reconstruct it from memory.

**Recommendation: Tier 3, sugar.** Nice-to-have generators; the underlying capability already exists. Worth it mainly because the exact field/rule composition is easy to get wrong by hand — **mine that composition from the metamodel/demo**, don't hand-author it.

### 3.9 Model-level metadata & niche composition (Tier 3)

- **Model labels** (per-locale) and **model-level annotations** — the header arrays exist but are never populated; add config-editor methods.
- **Supported characters** (custom allowed-character set) — the kernel validates field values against `supportedCharacters`/`characterSet`; a real validation feature, niche, would need the header slot modeled + a round-trip.
- **Include repeatability** (mount hardcoded to 1) and **exclude-rules** flag (`excludeRules`) — structural include options we don't expose.
- **Type-def-only model** — we *resolve* imported type-defs (`ImportedTypeDef`/`TypeDefModel` exist) but can't *author* a model containing only type-defs; a `--typedef-model` creation mode would close it.

**Recommendation: Tier 3, on demand.** Build when a concrete corpus need appears; none blocks current use.

---

## 4. Adjacent but out of scope

**Model-version migration.** Bumping a model to a newer kernel format is a distinct concern with its own existing tool (`kernel-md-cli` → `MigratorCli`). dmkits authors and edits; it deliberately never bumps a model's version on write. Not an authoring feature.

---

## 5. Recommendation summary

| # | Feature | Tier | Kernel help | Why |
|---|---|---|---|---|
| 3.1 | Reference-rewriting **rename + move** | **1 ⭐** | `MoveSupportDM` (internal) | Removes the biggest limitation; the engine is a kernel primitive we wrap (via adapter) |
| 3.2 | Canonical **format** verb | **1** | `IConditionFormatter` (public) | Cheap; diff stability; one canonical form + EN↔DE |
| 3.3 | Scoped **single-rule test** | **1–2** | `IDocumentRtService` (wrapped) / `IOnTheFlyTestingService` | Makes polarity empirical; mostly packaging over `doc eval` |
| 3.4 | Field-config completeness | **2** | metamodels as oracle | Batchable wins (range decimals, unit, suggestions, sorting, Custom) |
| 3.5 | Field flags + descriptions | **2** | metamodels as oracle | Global/Transient semantics; description/helper slots |
| 3.6 | `$Field$` / `$#Group$` interpolation | **2** | kernel message renderer | Completes message authoring |
| 3.7 | `RuleEditor` severity/message edits | **2** | — (our deferred marker) | Completes the in-session edit loop |
| 3.8 | Attachment / Multi-Select templates | **3** | metamodels as oracle | Template generators over groups |
| 3.9 | Model metadata, include options, type-def-only model | **3** | metamodels as oracle | On-demand niche capability |

**If only three things get built:** §3.1 (rename/move rewriting — the differentiator), §3.2 (format — cheap, high polish), and §3.3 (scoped single-rule test — empirical polarity at low cost). The Tier-2 field/message items are a satisfying batch sweep once completeness becomes the goal.

---

## 6. Verify before building (don't trust this doc blindly)

- **§3.1** — confirm `MoveSupportDM`/`MoveSupportInternal` signatures against the *pinned* kernel (30.8.1) and that they resolve from the adapter classpath; rehearse a `*`-reference input to confirm the refusal path.
- **§3.2** — confirm whether `IConditionFormatter` is core-reachable (`tool-api`) or needs the adapter tier; check the exact normalization set.
- **§3.4–3.6, 3.8–3.9** — mine `../a12-kernel-mm` `MM_*` for the exact JSON keys (unit, suggestions, sorting, Global/Transient, descriptions, message tokens, attachment/multi-select composition, supported-characters, `excludeRules`) **before** adding slots — the metamodel is the slot oracle, not this doc.
