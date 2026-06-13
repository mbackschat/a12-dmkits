# dmkits — document-model authoring coverage

> **What this is.** dmkits (rulekit + modelkit + `dmtool`) authors the **whole document model** over the A12 kernel — structure *and* rules/computations — with the kernel as the confirming oracle ("valid" = the engine accepted it). This file began as the *feature-gaps backlog*; that backlog is **closed** (every capability below is shipped), so it's kept as the **authoring-coverage reference**: a map of what dmkits can author, and where each capability is implemented, contracted, and tested. Kernel-behavior facts live in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md); verb/gate contracts in [`CLI-SPEC.md`](CLI-SPEC.md); per-feature status + test-backed snippets on the [`API-POC-STUDY.md` §9 board](API-POC-STUDY.md). The `§3.x` section numbers are **stable anchors referenced from the code**.

---

## 1. The surface we author

dmkits authors the **whole document model** — its structure (fields, groups, type definitions, includes, document config) *and* its rules and computations. Strengths: typed/compile-time-safe authoring, the read→explain→diff→re-validate loop, and an agent-discoverable CLI. §2 maps the coverage, §3 indexes the capabilities (with their contracts/tests), §4 notes what's deliberately out of scope.

---

## 2. Coverage inventory

Everything below is ✅ — the inventory *is* the authoring surface dmkits covers; each note points at the implementation and its lock.

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
| **Annotations** (name-value) on an element | ✅ | element-wide (field/group/rule/type-def): the `ElementMeta` builder mixin (author) + `ModelDocument.setAnnotation`/`removeAnnotation` + the CLI `meta <ref> --annotate/--remove-annotation` (`IAnnotated`) |
| **Global** flag; **Transient** flag | ✅ | `FieldAttrs.global()` / `.notPersisted()` (§3.5) |
| **Internal / external description**, **helper text** | ✅ | descriptions are **element-wide** (field/group/rule/type-def + model `comment`) via the `ElementMeta` mixin (author) + `setInternalDescription`/`setExternalDescription`/`remove…` + the CLI **`meta <ref>`** verb (read/set/remove); `helperText` is field-only (§3.5). `IDescribed`/`modelInfo.comment` — KERNEL-FINDINGS |

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
| Imported type-defs / **type-def-only model** (author one) | ✅ | `DocumentModelDraft.typeDefModel(id)` authors a root-less type-def-only model (kernel-valid standalone); a consumer imports it via `Builder.importTypeDefs(ref)` / `ModelDocument.addImport(ref)` / CLI `typedef import --reference` → a `purpose=typeDefinitions` reference whose types merge in by id. `modelkit.TypeDefModelImportTest`, `cli.TypedefImportVerbTest`, KERNEL-FINDINGS |
| Include a model under a group; alias/name; resolution (`--include-dir`) | ✅ | `addInclude`/`mount`; `ReferenceResolvers.directory` |
| Include **repeatability** | ✅ | `mount(name, alias, repeatability)` (author) + `IncludeEditor.mount(parent, name, repeatability)` (edit) + **CLI `include add --repeatability`** — a mount > 1 expands to a repeating sub-form (`modelkit.IncludeRepeatabilityTest`, `cli.IncludeVerbTest`) |
| Include **exclude-rules** flag | ✅ | `MountOptions.excludingRules()`/`.excludingComputations()` → `mount(...)` / `IncludeEditor.mount(...)` / CLI `include add --exclude-rules --exclude-computations`. The kernel drops the included model's rules/computations at **expand** (fields stay); `edit.IncludeExcludeRulesTest`, KERNEL-FINDINGS |
| Include constraints (no cycles, single root, locale superset) | ✅ | the kernel's to enforce on expand — the **locale rule pinned**: an include must declare a *superset* of the base's locales (cover every base locale; extra locales are harmless), `modelkit.IncludeConstraintsTest`. Cycles / single-root are likewise kernel-enforced on expand (KERNEL-FINDINGS) |
| Config: locales, time zone, decimal separator, base year, condition language, model name | ✅ | `ConfigEditor` / `DocConfig` |
| Config: model **labels**, model-level **annotations**, model **comment** | ✅ | author (`DocumentModelDraft.label/annotation/comment`) + edit (`ConfigEditor.label/annotation/comment`) + **CLI `config modify --label/--annotation/--comment`** (the comment is `modelInfo.comment`, surfaced by `config read`); kernel reads + round-trips them (`modelkit.ModelHeaderTest`/`TypeDefAndModelCommentTest`, `cli.ConfigVerbTest`) |
| Config: **supported characters** (custom allowed-character set) | ✅ | `DocumentModelDraft.supportedCharacters(...)` / `ConfigEditor.supportedCharacters(...)` / CLI `config modify --supported-character` → the `modelConfig.supportedCharacters` list (each entry a char or a range like `A-Z`). The kernel **enforces it at runtime** — an out-of-set value fires `ZeichenNichtImZeichensatz` (KERNEL-FINDINGS). `modelkit.SupportedCharactersTest`, `adapter.laws.SupportedCharactersLawsTest` |

### Composite element templates
| Capability | Status | Note |
|---|---|---|
| **Attachment** group template | ✅ | `attachmentGroup(...)` / `ModelDocument.addAttachmentGroup(...)` / CLI `group attachment` → the kernel's canonical `usageType:"attachment"` composition (8 fields + 4 mandated rules + `content` config); the generator owns the skeleton, the caller tunes size/MIME/category/repeatability. `en_US`/`de_DE` only. `modelkit.AttachmentTemplateTest`, `cli.GroupAttachmentVerbTest` |
| **Multi-Select** group template | ✅ | `multiSelectGroup(...)` / `ModelDocument.addMultiSelectGroup(...)` / CLI `group multiselect` → a `usageType: "multi-select"` repeatable group with one enum/string field; kernel-validated (`MultiSelectGroupRule`). `modelkit.MultiSelectTemplateTest`, `cli.GroupMultiSelectVerbTest` |

### Analysis & runtime
| Capability | Status | Note |
|---|---|---|
| Validate a model through the kernel | ✅ | `doc validate` |
| Evaluate rules against a document instance | ✅ | `doc eval` / `doc compute` (`IDocumentRtService`) |
| Scoped **single-rule** evaluation against an instance | ✅ | `rule test --doc` — fired / passed / suppressed verdict (§3.3) |

---

## 3. Capability index (stable anchors)

The `§3.x` anchors referenced across the code, each pointing at where the capability is contracted and tested. These were originally per-gap deep-dives; the design rationale and kernel facts now live in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) and [`CLI-SPEC.md`](CLI-SPEC.md).

| § | Capability | Where it lives |
|---|---|---|
| §3.1 | Within-model reference-rewriting **rename + move** | `adapter.ReferenceRewriter` (wraps the kernel's `MoveSupportDM`); contract [`CLI-SPEC.md` §6](CLI-SPEC.md); `adapter.ReferenceRewriteTest` |
| §3.2 | Canonical condition **format** (+ EN↔DE) | `rule format` / `computation format` over `IConditionFormatter`; KERNEL-FINDINGS; `adapter.KernelAdapterFormatTest` |
| §3.3 | Scoped **single-rule test** (fired / passed / suppressed) | `rule test --doc`; `adapter.laws.RuleTestLawsTest`, `cli.RuleTestVerbTest` |
| §3.4 | Field-config completeness (decimal range, suggestions, sorting, unit/trait, Custom) | `modelkit.FieldConfigExtrasTest`; KERNEL-FINDINGS |
| §3.5 | Field metadata (global/transient flags, internal/external descriptions, helper text) | `modelkit.FieldLevelMetadataTest` |
| §3.6 | Error-message interpolation `$Field$` / `$#Group$` | `Dsl.fieldLabel` / `Dsl.repetitionIndex`; `MessageInterpolationTest` |
| §3.7 | In-session **severity / message** rule edit | `RuleEditor.severity/messages` → `ModelWriter.replaceSeverity/replaceMessages`; `RuleComputationEditorTest` |
| §3.8 | **Attachment / Multi-Select** `usageType` group templates | `attachmentGroup` / `multiSelectGroup` + CLI `group attachment`/`group multiselect`; KERNEL-FINDINGS; `modelkit.AttachmentTemplateTest` / `modelkit.MultiSelectTemplateTest` |
| §3.9 | Model labels/annotations, supported-characters, include exclude-rules, type-def-only model + import | the §2 "Type-defs, includes, config" rows + KERNEL-FINDINGS |

---

## 4. Adjacent but out of scope

**Model-version migration.** Bumping a model to a newer kernel format is a distinct concern with its own existing tool (`kernel-md-cli` → `MigratorCli`). dmkits authors and edits; it deliberately never bumps a model's version on write. Not an authoring feature.
