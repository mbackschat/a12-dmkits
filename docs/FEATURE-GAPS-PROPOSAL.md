# dmkits — feature gaps & ideas

A working backlog of capabilities **a12-dmkits** (rulekit + modelkit + `dmtool`) doesn't yet expose, or exposes only partially. It's a brainstorm against the document-model surface we author: a coverage self-inventory, then deep-dives on the gaps worth closing, with enough detail to scope each.

> **Grounding discipline.** Every capability below is justified by something concrete: a kernel API or a metamodel slot that *makes it possible*, or a place in our own code that has explicitly **parked / deferred** it. Nothing here is speculative beyond what the engine and the metamodels already support — each token (e.g. `unit`, `minFractionalDigits`, `suggestions`, `MoveSupportDM`, `IConditionFormatter`) was verified present in the kernel source, the metamodels, or our code before being listed.

> **Status:** analysis + proposal, 2026-06-12; **Tier 1 + Tier 2 implemented 2026-06-13** (§3.1–§3.7, one slot deferred — the number `unit`/`trait`; per-section update banners below, canonical status on the §9 board). Tier 3 (§3.8–§3.9) remains on-demand. Recommendations are sized, not committed. Canonical per-feature implementation status stays on [`API-POC-STUDY.md` §9](API-POC-STUDY.md); the refactoring contract on [`CLI-SPEC.md` §6](CLI-SPEC.md). Field-slot specifics are mined from the kernel metamodels (`../a12-kernel-mm`, the `MM_*` slot oracle) before any are added.

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
| **Custom** field kind | ✅ | `CustomSpec` + `customField(...).type(...)` → `CustomFieldType` (§3.4; structural round-trip — value validation needs a registered validator) |
| String: min/max length, pattern (+message) | ✅ | `StringSpec`/`StringFieldBuilder` |
| String: **line-breaks**, **alphabetical sorting**, **suggestions** | ✅ | `.lineBreaks()`/`.alphabeticalSorting()`/`.suggestions(loc,…)` (`hintList`) — §3.4 |
| Number: min/max value, fixed fraction-digits (incl. 0) | ✅ | `NumberSpec.maxFractionalDigits/minValue/maxValue` |
| Number: fraction-digits **range** (min/max) | ✅ | `.fractionDigits(min,max)` → `minFractionalDigits`/`maxFractionalDigits` (§3.4) |
| Number: **zero-not-allowed**, **leading-zeros** | ✅ | `.zeroNotAllowed()`/`.leadingZerosAllowed()` (§3.4) |
| Number: **unit** (Amount/Percent/Permille) | ✅ | `.unit(NumberTrait.AMOUNT\|PERCENT\|PERMILLE)` → the lowercase `trait` slot (api.ts contract); round-trips via `INumberType.getTrait()`. `AmountWithCent` is a separate *derived* cross-field-check category, not this slot (KERNEL-FINDINGS) |
| Date Fragment / Date Range formats | ✅ | `DateFragmentSpec` / `DateRangeSpec` |
| Enumeration: values, per-locale labels, **categories** | ✅ | `EnumSpec`/`EnumValue`/`EnumCategory` |
| Enumeration: **alphabetical sorting**, **invalid-value error message** | ✅ | `.alphabeticalSorting()`/`.invalidValueMessage(text)` (`errorMessage`) — §3.4 |
| Type Definition field reference | ✅ | `TypeDefRef` + referential-integrity check |

### Field-level properties
| Capability | Status | Note |
|---|---|---|
| Required; "only if parent group filled" | ✅ | `Requiredness` (3 modes) |
| Per-locale **label** | ✅ | `FieldAttrs.label` / `ModelDocument.setLabel` |
| **Annotations** (name-value) on an element | ✅ | `ElementDraft.Annotation` / `setAnnotation` |
| **Global** flag; **Transient** flag | ✅ | `FieldAttrs.global()` / `.notPersisted()` (§3.5) |
| **Internal / external description**, **helper text** | ✅ | `.internalDescription/externalDescription/helperText(loc, text)` (§3.5) |

### Groups, rules, computations
| Capability | Status | Note |
|---|---|---|
| Groups, nesting, repeatability + max | ✅ | modelkit groups |
| Index field (+ auto uniqueness/mandatory rules) | ✅ | declaring `indexFieldName` is enough — the kernel **auto-generates** the `RepetitionNotUnique` + mandatory rule pair (confirmed empirically, `adapter.laws.IndexFieldLawsTest`; KERNEL-FINDINGS) |
| Validation rule: code, error field, **level** (Error/Warning/Info), condition, per-locale messages | ✅ | `Rule` / `Severity` |
| `@SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)` | ✅ | DSL directive; `DirectiveLawsTest` |
| Error-message interpolation `$Field.value$` | ✅ | `Dsl.fieldValue` |
| Error-message interpolation `$Field$` (label), `$#Group$` (repetition index) | ✅ | `Dsl.fieldLabel` / `Dsl.repetitionIndex` (§3.6) |
| Computation: computed field, common precondition, alternatives, comments | ✅ | `Computation` / `Alternative` |
| Canonical **format** of a condition | ✅ | `rule format` / `computation format` (+ EN↔DE) over `IConditionFormatter` (§3.2) |
| In-session **severity / message** edit of a rule | ✅ | `RuleEditor.severity(...)` / `.messages(...)` (§3.7) |

### Type-defs, includes, config
| Capability | Status | Note |
|---|---|---|
| Local type definitions (add/rename/extract/inline/remove) | ✅ | `ModelDocument.*TypeDefinition` + CLI verbs |
| Included type-defs (read-only, via include) | ✅ | resolver-served |
| Imported type-defs / **type-def-only model** (author one) | ◐ / ❌ | imported refs resolve (`ImportedTypeDef`/`TypeDefModel` exist); no verb to *create* a type-def-only model |
| Include a model under a group; alias/name; resolution (`--include-dir`) | ✅ | `addInclude`/`mount`; `ReferenceResolvers.directory` |
| Include **repeatability** | ✅ | `mount(name, alias, repeatability)` (author) + `IncludeEditor.mount(parent, name, repeatability)` (edit) + **CLI `include add --repeatability`** — a mount > 1 expands to a repeating sub-form (`modelkit.IncludeRepeatabilityTest`, `cli.IncludeVerbTest`) |
| Include **exclude-rules** flag | ❌ | `excludeRules` is a metamodel include slot, still not exposed (on-demand) |
| Include constraints (no cycles, single root, locale superset) | ✅ | the kernel's to enforce on expand — the **locale rule pinned**: an include must declare a *superset* of the base's locales (cover every base locale; extra locales are harmless), `modelkit.IncludeConstraintsTest`. Cycles / single-root are likewise kernel-enforced on expand (KERNEL-FINDINGS) |
| Config: locales, time zone, decimal separator, base year, condition language, model name | ✅ | `ConfigEditor` / `DocConfig` |
| Config: model **labels**, model-level **annotations** | ✅ | author (`DocumentModelDraft.label/annotation`) + edit (`ConfigEditor.label/annotation`) + **CLI `config modify --label/--annotation`**; kernel reads + round-trips them (`modelkit.ModelHeaderTest`, `cli.ConfigVerbTest`) |
| Config: **supported characters** (custom allowed-character set) | ✅ | `DocumentModelDraft.supportedCharacters(...)` / `ConfigEditor.supportedCharacters(...)` / CLI `config modify --supported-character` → the `modelConfig.supportedCharacters` list (each entry a char or a range like `A-Z`). The kernel **enforces it at runtime** — an out-of-set value fires `ZeichenNichtImZeichensatz` (KERNEL-FINDINGS). `modelkit.SupportedCharactersTest`, `adapter.laws.SupportedCharactersLawsTest` |

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
| Scoped **single-rule** evaluation against an instance | ✅ | `rule test --doc` — fired / passed / suppressed verdict (§3.3) |

---

## 3. Gap deep-dives (the features worth building)

Each: what's missing, the current state (file:line), whether the kernel/metamodel helps, the build shape, a recommendation.

### 3.1 Within-model reference-rewriting rename + move — **the flagship gap** ⭐

> **Update — shipped 2026-06-12.** Both halves landed, safety-gated: `field`/`group`/`rule rename` now *rewrite-then-validate*, and `field`/`group move` *relocate-then-rewrite-then-validate*. The rewriter is `adapter.ReferenceRewriter` wrapping `MoveSupportDM`. **Correction to the analysis below:** starred-group references *are* followed on a (group) rename/move — `Sum(Items*/Amount)` → `Sum(Lines*/Amount)` — so the planned `*`-reference **refusal** (build-shape step 4) proved unnecessary; the kernel re-validate is the backstop for any narrow case the rewriter misses (KERNEL-FINDINGS §10). The eval task `03-rename-field` is active (Layer-1 oracle). Contract: [`CLI-SPEC.md` §6](CLI-SPEC.md). The original analysis stays below as the rationale of record.

**Current state (pre-implementation).** Rename verbs exist for field/group/rule but **refuse** when the element is referenced — `SafetyGate` + `SafetyChecks.notReferenced` returns a `Refusal` ("rename the referrers first … references are not rewritten — that is the parked engine", `FieldRenameCommand.java:22-24`). There is **no `move` verb**. Type-def rename is the lone rewriter (it repoints `typeDefinitionId`, a metadata field, not an AST).

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

> **Update — shipped 2026-06-13.** `rule format` / `computation format` verbs land it (read envelope, canonical text under `data.canonical`, `--lang EN|DE` for the kernel-authoritative keyword switch). **Two corrections to the analysis below.** (1) `IConditionFormatter` is **not** a public `tool-api` — it's the `a12internal` / `@OnlyForUsage` tier (`…core.tool.a12internal.api.services.IConditionFormatter`), so it belongs in the **adapter** module; in fact the capability was *already* wired, since `IMVK_Service extends IConditionFormatter` and `KernelAdapter.canonicalConditionText` already called `formatConditionText` — only the **translate** peer and the verbs were new. (2) The kernel recognizes **exactly** `Locale.US` and `Locale.GERMANY` as condition languages and **silently falls back to German** for any other locale (incl. bare `Locale.ENGLISH`/`Locale.GERMAN`) — so the adapter takes rulekit's `ConditionLanguage`, not a raw `Locale` ([KERNEL-FINDINGS](KERNEL-FINDINGS.md), `adapter.KernelAdapterFormatTest`). The original analysis stays below.

**Current state.** The `DslRenderer` produces a canonical-ish rendering, but there's no verb that takes an *arbitrary* condition string and returns the engine's canonical form — and the renderer's normalization is *ours*, not guaranteed to match the kernel's.

**Kernel support.** Exposed and reusable: `IConditionFormatter` (`kernel-core-tool-api`, `…api.services.IConditionFormatter`) — `formatConditionText(IRule, IProblemReporter)`, `formatConditionSnippet(...)`, plus `formatAndTranslateConditionText(…, Locale, …)` (format **and** switch EN↔DE in one call). Backed by `FormatterVisitor`/`FormatterUtil` in `kernel-core-parser`.

**Build shape.** A `rule format` / `computation format` verb (or a `--format` flag on `read`/`modify` output) round-tripping through `IConditionFormatter`. Bonus: `formatAndTranslate` gives a kernel-authoritative EN↔DE switch, complementing `DslRenderer(DE)`.

**Recommendation: Tier 1, low cost.** Improves diff stability (re-express → format → compare) and gives a single canonical form. Thin wrapper over a public kernel API (it's `tool-api` — confirm core-reachability vs. adapter).

### 3.3 Scoped single-rule evaluation — packaging over an engine we already have

> **Update — shipped 2026-06-13.** `rule test <rulePath> --doc <instance>` lands it, as packaging over `KernelAdapter.evaluate` (`testRule` → a three-way `RuleTestOutcome.Verdict`). **Refinement to the analysis below:** `doc eval --rule` *already* did the fired/not-fired scoping, so the new value is the **suppression** distinction — a rule the kernel never evaluated because a `referencedFields` field is formally invalid is reported `SUPPRESSED` (with the offending field named), not a misleading `passed`. The `IOnTheFlyTestingService`/codegen route was **not** needed (the doc's earlier mention was speculative): filtering `evaluate()`'s messages by the rule's path + checking its referenced fields for formal-check errors is enough. `adapter.laws.RuleTestLawsTest` / `cli.RuleTestVerbTest`. The original analysis stays below.

**Current state.** `doc eval --doc <instance>` already runs the whole model's rules against a document via `IDocumentRtService.validateFull` (`KernelAdapter.java:532`). What's missing is the *scoping* ergonomic: "evaluate **this one rule** and just its dependencies, on this data."

**Build shape.** A `rule test --doc <instance>` verb that (a) resolves the rule's referenced fields (we already compute referenced-entities / `whereUsed`), (b) evaluates, and (c) reports whether *that* rule fired — the empirical polarity check the project prizes. Could be pure packaging over `doc eval` (filter the result to the rule). For exact scoped-codegen semantics, the kernel also ships `IOnTheFlyTestingService` (`kernel-core-ontheflytesting`) which generates a scoped validation; wrap it only if we want that fidelity.

**Recommendation: Tier 1–2, mostly packaging.** Makes polarity *empirical* rather than argued — high value for agents — at low cost as a scoped filter over `doc eval`.

### 3.4 Field-model completeness — parity items (Tier 2)

> **Update — shipped 2026-06-13 (one slot deferred).** Landed: Number `fractionDigits(min,max)` range + `zeroNotAllowed()` + `leadingZerosAllowed()`; String `suggestions(...)` (kernel `hintList`) + `lineBreaks()` + `alphabeticalSorting()`; Enum `alphabeticalSorting()` + `invalidValueMessage(...)` (the localized `errorMessage`); and the **Custom** field kind (`customField`, structural round-trip). **Two corrections to the table below:** (1) the exact keys are `trait`/`hintList`/`minFractionalDigits`/`zeroNotAllowed`/`leadingZerosAllowed` and the Custom discriminator is `CustomFieldType` + `name` (not `customFieldType`) — serializer-confirmed. (2) The kernel DM-JSON deserializer **ignores unknown keys** (`@JsonIgnoreProperties`), so each slot is verified by **kernel read-back** (`ModelIndex.fieldType`), not a clean load. **Update 2026-06-13 — the `unit`/`trait` slot landed too** (`.unit(NumberTrait)`): a later spike found it's a clean lowercase `trait` slot (`amount`/`percent`/`permille`, per api.ts), not the "inconsistent" thing an earlier note feared — `AmountWithCent` is a separate *derived* cross-field-check category (KERNEL-FINDINGS). `modelkit.FieldConfigExtrasTest`. **CLI: all of this is reachable via the rich `field add <spec.json>`** (per-kind config + metadata; `schema field add`, `cli.FieldAddSpecTest`). The original table stays below.

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

> **Update — shipped 2026-06-13.** `FieldAttrs` gained `.global()` / `.notPersisted()` (the kernel `transient` flag) and `.internalDescription/externalDescription/helperText(locale, text)`. Confirmed keys (serializer-verified): `global`/`transient`/`helperText` in the `Field` body, `internalDescription`/`externalDescription` element-level (alongside `annotations`), all localized as `[{locale, text}]`. Verified by kernel read-back via `ModelIndex.field(path)` (`modelkit.FieldLevelMetadataTest`). **CLI: settable on the rich `field add <spec.json>`** (`global`/`transient`/`internalDescription`/`externalDescription`/`helperText`). The original analysis stays below.

- **Global** (partial-validation relevance) and **Transient** (not persisted) — two metamodel field attributes; both affect kernel semantics, so add as first-class attrs, not annotations.
- **Internal / external description, helper text** — per-locale text slots, distinct from `label` in the metamodel. Authoring + round-trip; no rule semantics.

**Recommendation: Tier 2.** Small, but verify the exact JSON keys via `a12-kernel-mm` first (descriptions, helper text, and labels are distinct slots).

### 3.6 Error-message interpolation — `$Field$` and `$#Group$` (Tier 2)

> **Update — shipped 2026-06-13.** `Dsl.fieldLabel(FieldRef)` → `$<path>$` (the localized-label form — a bare path, no suffix) and `Dsl.repetitionIndex(GroupRef)` → `$#<groupPath>$` (the repeatable group's 1-based row number). Confirmed: the kernel accepts both with **full paths** (the same path form `fieldValue` uses), end-to-end via `RuleValidator` (`MessageInterpolationTest`). **CLI discoverability:** the three tokens are documented on the `messages.text` field of the `rule add`/`computation add` input schema (`schema rule add`), so an agent writing a raw CLI message learns them. The original analysis stays below.

We support `$Field.value$` (`Dsl.fieldValue`). The kernel's error-text parameter mechanism (`ErrorTextParameter`) also supports `$Field$` (localized **label**) and `$#Group$` (repetition **index**, cf. `repetitionIndex`). Both are message-rendering concerns, not condition AST — add `Dsl.fieldLabel(ref)` and `Dsl.repetitionIndex(groupRef)` emitting `$Field$` / `$#Group$`. Confirm the exact tokens against the kernel message renderer.

**Recommendation: Tier 2, small.** Rounds out message authoring; the repetition-index form pairs naturally with uniqueness rules on repeatable groups.

### 3.7 Session-editor parity — `RuleEditor` severity/message edits (Tier 2)

> **Update — shipped 2026-06-13.** `RuleEditor.severity(Severity)` and `.messages(LocalizedText…)` edit a rule in place (condition untouched), delegating to `EditSession.modifyRuleSeverity`/`modifyRuleMessages` → `ModelWriter.replaceSeverity`/`replaceMessages` (rewrites the rule node's `severity` / `errorMessage` slots). Rename stays the `EditSession.renameRule` refactor (not an editor method). **CLI: `rule modify --severity ERROR|WARNING|INFO --message <locale>=<text>`** (condition optional; `cli.RuleVerbTest`). `RuleComputationEditorTest`. The original note stays below.

`RuleEditor` currently exposes only condition `modify`; "severity / message / rename edits are not yet exposed — deferred" (`RuleEditor.java:13-14`). For the read→edit→write-back loop to be complete, an existing rule should be re-severity-able and re-message-able in-session without a full replace.

**Recommendation: Tier 2.** Mechanical editor extension; pairs with the rename-rewriting work (§3.1), since rename is the other deferred editor edit.

### 3.8 Composite-element templates — Attachment & Multi-Select (Tier 3)

Attachment and Multi-Select are first-class A12 group **templates** (both present in the kernel/metamodel). modelkit builds them only as hand-assembled plain groups. A `field add --kind ATTACHMENT` / a `multiselect add` generator would emit the canonical template so an agent doesn't reconstruct it from memory.

**Recommendation: Tier 3, sugar.** Nice-to-have generators; the underlying capability already exists. Worth it mainly because the exact field/rule composition is easy to get wrong by hand — **mine that composition from the metamodel/demo**, don't hand-author it.

### 3.9 Model-level metadata & niche composition (Tier 3)

> **Update — partially shipped 2026-06-13.** Model labels/annotations (the completeness batch) and **supported characters** have landed; **include exclude-rules** and the **type-def-only model + import** are in the same on-demand batch. Corrections to the analysis below: the supported-character slot is `modelConfig.supportedCharacters` (not a `characterSet`) and is **runtime-enforced**, not just authored metadata (out-of-set value → `ZeichenNichtImZeichensatz`, KERNEL-FINDINGS); a type-def-only model is just a normal `modelType: "document"` model with `typeDefinitions` and no root groups — there is **no** separate model discriminator — and a consumer pulls them in with a `modelReferences` entry of `purpose: "typeDefinitions"` (the kernel constant `PURPOSE_4_ONLY_TYPE_DEFS`, **not** `"import"`).

- **Model labels** (per-locale) and **model-level annotations** — ✅ shipped (completeness batch): `DocumentModelDraft.label/annotation` + `ConfigEditor.label/annotation` + CLI `config modify --label/--annotation`.
- **Supported characters** (custom allowed-character set) — ✅ shipped: `modelConfig.supportedCharacters` (each entry a char or a range like `A-Z`), runtime-enforced; `modelkit.SupportedCharactersTest`, `adapter.laws.SupportedCharactersLawsTest`.
- **Include repeatability** (✅ shipped, completeness batch) and **exclude-rules** flag (`excludeRules`) — structural include options.
- **Type-def-only model** — author a model containing only type-defs + import them into a consumer (`purpose: "typeDefinitions"`).

**Recommendation: Tier 3, on demand.** Build when a concrete corpus need appears; none blocks current use.

---

## 4. Adjacent but out of scope

**Model-version migration.** Bumping a model to a newer kernel format is a distinct concern with its own existing tool (`kernel-md-cli` → `MigratorCli`). dmkits authors and edits; it deliberately never bumps a model's version on write. Not an authoring feature.

---

## 5. Recommendation summary

| # | Feature | Tier | Status | Kernel help | Why |
|---|---|---|---|---|---|
| 3.1 | Reference-rewriting **rename + move** | **1 ⭐** | ✅ 2026-06-12 | `MoveSupportDM` (internal) | Removes the biggest limitation; the engine is a kernel primitive we wrap (via adapter) |
| 3.2 | Canonical **format** verb | **1** | ✅ 2026-06-13 | `IConditionFormatter` (**a12internal**, via `IMVK_Service`) | Cheap; diff stability; one canonical form + EN↔DE |
| 3.3 | Scoped **single-rule test** | **1–2** | ✅ 2026-06-13 | `IDocumentRtService` (wrapped) — packaging over `evaluate()`, no codegen | Makes polarity empirical; adds the suppressed-vs-passed distinction |
| 3.4 | Field-config completeness | **2** | ✅ 2026-06-13 | metamodels as oracle + kernel read-back | Batchable wins (range decimals, suggestions, sorting, Custom, **unit/trait**) |
| 3.5 | Field flags + descriptions | **2** | ✅ 2026-06-13 | metamodels as oracle + kernel read-back | Global/Transient semantics; description/helper slots |
| 3.6 | `$Field$` / `$#Group$` interpolation | **2** | ✅ 2026-06-13 | kernel message renderer | Completes message authoring |
| 3.7 | `RuleEditor` severity/message edits | **2** | ✅ 2026-06-13 | — (our deferred marker) | Completes the in-session edit loop |
| 3.8 | Attachment / Multi-Select templates | **3** | 🔮 not built | metamodels as oracle | Template generators over groups |
| 3.9 | Model metadata, include options, type-def-only model | **3** | 🔮 not built | metamodels as oracle | On-demand niche capability |

**Status (2026-06-13): all of Tier 1 and Tier 2 are shipped** — §3.1 (rename/move, the differentiator), §3.2 (format + EN↔DE), §3.3 (scoped single-rule test), and the full Tier-2 sweep §3.4–§3.7 (the number `unit`/`trait` slot, briefly deferred, landed after a spike — KERNEL-FINDINGS). A follow-on **completeness batch** also closed several §2-inventory items (index-field auto-rules, model labels/annotations, include repeatability, the include locale-superset rule). Tier 3 (§3.8 templates, §3.9 niche composition) remains on-demand. **CLI exposure (2026-06-13):** the authoring/edit capabilities are now on the `dmtool` CLI too — rich `field add <spec.json>` (§3.4 config + §3.5 metadata, `schema field add`), `rule modify --severity/--message` (§3.7), `config modify --label/--annotation` (F), `include add --repeatability` (D), and the `$Field$`/`$#Group$` message tokens documented on the add schema (§3.6) — so the whole batch is reachable by a cold agent driving the bare CLI, not just the typed library.

---

## 6. Verify before building (don't trust this doc blindly)

These pre-build cautions were resolved during implementation (outcomes recorded inline above + in KERNEL-FINDINGS); they remain as the verification record:

- **§3.1** — ✅ confirmed: `MoveSupportDM` resolves from the adapter classpath against the pinned kernel; `*`-refs *follow* a rename (so the planned refusal proved unnecessary).
- **§3.2** — ✅ resolved: `IConditionFormatter` is **a12internal**, not public `tool-api`; reached via `IMVK_Service` (already wired). The EN↔DE locale set is exactly `Locale.US`/`Locale.GERMANY` (else silent German — KERNEL-FINDINGS).
- **§3.4–3.6** — ✅ keys serializer-confirmed (`trait`/`hintList`/`minFractionalDigits`/`zeroNotAllowed`/`leadingZerosAllowed`, `CustomFieldType`+`name`, `global`/`transient`/`helperText`, `internalDescription`/`externalDescription`); the kernel deserializer **ignores unknown keys**, so each slot was verified by **kernel read-back**, not a clean load (KERNEL-FINDINGS). The `unit`/`trait` slot briefly looked inconsistent but a spike resolved it (it's a clean lowercase `trait`; `AmountWithCent` is a separate derived category) — shipped.
- **§3.8–3.9** — not built; mine `../a12-kernel-mm` `MM_*` for the exact keys (attachment/multi-select composition, supported-characters, `excludeRules`) **before** adding, when a concrete need appears.
