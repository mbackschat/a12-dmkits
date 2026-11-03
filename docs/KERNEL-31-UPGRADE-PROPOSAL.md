# Kernel 31.1.1 upgrade (2026.06-ext1) — change analysis & plan (PROPOSAL, executing)

**Status:** PROPOSAL, **executing since 2026-11-02** on the owner's directive. This is the concrete instance of the [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) runbook for the `30.8.1 → 31.1.1` bump. The source audit was written 2026-07-17 against 31.1.0 (2026.06-ext0), with a disposition of DEFER. It was retargeted 2026-11-02 to **31.1.1 (2026.06-ext1)**, the release the sibling `a12-kernel` checkout now carries, which per the project rule makes it this project's version. **§0's work-item table is the state carrier**: each landing updates its row in the same commit. Once every row is done, the durable contracts graduate to their owning specs and this doc is archived.

Primary sources:
- The upstream kernel migration instructions: `a12-kernel` `eec9134:documentation/dev/index.adoc`, section "2026.06".
- The [kernel `30.8.1…31.1.0` comparison](https://github.com/mgm-tp/a12-kernel/compare/cb66e51fa7ab90b650698f861bf670754e2e1e66...f10b9203822cd2a2d86bacccfa1ace615606b3c8) plus the ext1 delta `f10b920…eec9134`.
- The [metamodel comparison](https://github.com/mgm-tp/a12-kernel-mm/compare/d4ec70fcd80f89a6d84071e085a990a9884e38ef...f7ea1796f0f124c89fc877a6e4e49c42e963293b).

Each kernel release is one squashed commit directly on top of the previous one:

| commit | release | kernel |
|---|---|---|
| `cb66e51` | 2025.06-ext5 | 30.8.1 |
| `f10b920` | 2026.06-ext0 | 31.1.0 |
| `eec9134` | 2026.06-ext1 | 31.1.1 |

Ext1 uses the ext0 metamodels (`a12-kernel-mm` `f7ea179`), as pinned by `a12-kernel-bootstrap`'s `releases/2026.06-ext1.json`. Generated `/delombok/` and `build/` output is excluded from every source read here.

## Bottom line

This is a **major-version and model-format migration, not a pin bump.** It brings:
- A mandatory document-model migration from **28.4.0 to 29.4.0**, including an include/reference restructure.
- Hard compile breaks across `:adapter` main and test source sets: V1 documents, variants, `expand`, the deprecated extension registries.
- A kernel module rename (`kernel-*` → `kernel-internal-*`) and Jackson 2 → 3 inside the kernel.
- One new DSL operator, `SumOfTerms`, and two constraint relaxations that contradict laws we currently keep green.

JDK stays **21**. The work is multi-session. The OSS bootstrap prerequisite is done: 31.1.1 is in `mavenLocal`.

## 0 · Decisions and work items

### Owner decisions (2026-11-02)

| # | question | decision |
|---|---|---|
| D1 | version support | **31.1.1 / DM 29.4.0 is the single floor**, with no dual support. The last 30.8.1 revision is the local annotated tag `kernel-30.8.1-final` (`7b1892c65`). |
| D2 | include-expansion oracle (kernel `expand` removed, A12K-4021) | The kernel-free `ModelReader`'s parity test compares against the remaining `a12internal` `CombinationModelService` expansion, **behind one adapter port**. |
| D3 | include identity in our API/CLI | **`includeConfig.reference` is the only identity.** Alias-based input fails fast with a corrective diagnostic; there is no tool-local alias. |
| D4 | a12-kernel-lean | **Migrate together.** Letter A was sent 2026-11-02. The probe tooling moves first, their probe models are migrated by the kernel on request, and one verdict-delta letter is sent per batch, with the release announced before it is published. |
| D5 | kernel TypeScript runtime | A **separate follow-up** after this upgrade. Here, only correct the docs that still call it blocked. |
| D6 | releases | **Release at the end, gated.** All gates pass and the drop-in certificate is re-run on 31.1.1; the release step gets a cold review; the peer letter goes out first; the owner confirms once more before `--go-live`. |
| D7 | CLAUDE.md | Update only the version mentions (release, kernel, A12 Tools installation label). |

### Work items (the state carrier)

| id | item | status |
|---|---|---|
| W0 | Bootstrap 31.1.1 into `mavenLocal` (sibling `a12-kernel-bootstrap`) | done (sibling, before 2026-11-02) |
| W1 | Retarget this proposal; record D1–D7 | done 2026-11-02 |
| W2 | Build wiring + `:adapter` compile on 31.1.1. Probe tooling (`kernelProbe`, `probeDifferential`) ported first and announced to a12-kernel-lean. V2 document materializer, variants removed, the D2 expansion port, custom-extension API, Jackson 3. | done 2026-11-03, landed with W3a |
| W3a | Format floor: `ModelFormat.VERSION` 29.4.0, authoring emits the 29.x include shape, the D3 reference-only include redesign, every committed fixture migrated. | done 2026-11-03 |
| W3b | Uniqueness-criteria read/preserve/rewrite. Migration of a12-kernel-lean's 645 probe requests (`evidence/kernel-30.8.1/captures/**/request*.json` at their `f54b66b2`). | W3b-1 done; W3b-2a and W3b-2b open, see the W3b batches below; the probe-request migration offer open. |
| W4 | Guardian hardening (two-way inventories) + realignment: MVK renames, enum-law flips, categories, slots, metamodel counts. `:consistency` re-measured. | done 2026-11-03 — see the W4 batches below |
| W5 | Runtime requalification: the `java.time` date rewrite, code-generation restructure, the ext1 custom-field-type conversion change, Jackson 3 Document JSON number stages. Corpus regenerated. | in progress — every batch below is done except W5-perf |
| W6 | `SumOfTerms` typed vertical slice + interpreter, both kernel strategies | done: kernel laws, typed surface, adapter read-back, interpreter and portable checker ([KF670](KERNEL-FINDINGS.md#kf670)) |
| W7 | Version surfaces, docs, CLAUDE.md (D7), native re-capture + gate | in progress — version surfaces (the CI and native workflows included) and docs done 2026-11-03; the native re-capture and gate run with W8 |
| W8 | Gated release (D6) | open |

Each of W3–W6 ends with a verdict-delta letter to a12-kernel-lean (D4). Their row requirements (reply of 2026-11-02): every measured row names the kernel version, our revision, the launcher identity (dmtool version, source revision and clean state, built and runtime kernel) and the strategy, and an MVK code's meaning is given **by its raising gate**, never by name alone. They depend on the 30.8.1 artifacts in `~/.m2` for their own measurement checkout; never remove them.

**W3a facts (measured 2026-11-02).**

| fact | basis |
|---|---|
| The 31.1.1 migrator CLI (`kernel-md-facade-31.1.1-migrator-cli.jar`, run with `-skipUniqueIdCheck`) migrates 133 of our 141 fixtures. Its structural changes: `modelVersion` → 29.4.0; the mount's `modelAlias` becomes `includeConfig.reference` (the referenced model id), with `includeLevel`/`excludeRules`/`excludeComputations` moved inside `includeConfig`; the include entry in `header.modelReferences` loses `alias`; one `variantFilter` dropped. | scratch run over copies of every fixture |
| The 8 it refuses as invalid JSON models are the deliberately broken consumer fixtures under `consistency/consumer-tests/shared/` and need the same rules by hand. | the same run |
| A12 Tools ext1 writes include references as `{modelType, purpose: "include", reference}` (156 entries), and **`typeDefinitions` references keep `alias`** (all 3 such entries). Every `includeConfig` has a matching header include reference. | the 121 ext1 workspace DMs |

**W4 batches.** The root law lane on 31.1.1 failed 111 tests in 59 classes (2026-11-02). Each batch owns one mechanism.

| batch | mechanism | owning classes | state |
|---|---|---|---|
| W4-build | the converter states a blank or shared name, an unfound computed field and an unfound type definition codelessly, and checks on ([KF652](KERNEL-FINDINGS.md#kf652)) | `DeclaredNameShape`, `ElementNameUniqueness`, `ModelWideNameUniqueness`, `ComputedFieldTargetKind`, the `TypeDefType` rows of `FieldTypeBinding`, `RuleSlot` | done ([KF652](KERNEL-FINDINGS.md#kf652)) |
| W4-mvk | `*_IN_FILTER_CONDITION` → `*_IN_SET_EXPRESSION`; `FILTERED_ENTITY` → `PATH_WITH_ASTERISKS`; `WILDCARD_AT_LOWEST_LEVEL` → `…_REPEATABLE_LEVEL`; `NO_GROUPS_ALLOWED` split; `NEG_CONDITION_IN_ITERATION` on marked negated presence operators | `AggregateSlot`, `FilterBodyComparison`, `FilteredOperandGateOrder`, `HavingFilter*`, `ParallelIterationGate`, `SemanticIndexInFilter`, `SumOfProducts*`, `ConditionClassWithholding`, `FirstFilledOperandShape`, `GroupOperandRepetitionScope`, `IterationBinding`, `MvkLedgerTest`, `CatalogKernelClaimEvidenceTest`, `GrammarProductionLedgerTest`, `KeywordReverseParityTest` | done ([KF656](KERNEL-FINDINGS.md#kf656)); `SumOfTerms` cataloguing and its grammar/keyword guards stay with the version-surface step |
| W4-enum | a labelled enumeration draws `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES`; labelled-enum comparisons and concatenation are admitted | `EnumDisplayComparability`, `EnumValueAndLabel`, `EnumComparability`, `EnumComputationSource`, `EnumIntoText`, `KeyedOperandFamily`, `StringEnumComparisonAgreement`, `ArithmeticOperandKind`, `KeyedEnumerationConcatenation` | done ([KF655](KERNEL-FINDINGS.md#kf655)); the value entry without `value` closed with it, and the blank category token it exposed is [KF660](KERNEL-FINDINGS.md#kf660) |
| W4-order | index and sort reference laws: a `NUMBER` flag admitted, a `null` flag unreadable, a table enum unbuilt | `GroupIndexAndSortReference`, `GroupOrderingMultipleDefect`, `GroupOrderingFieldType`, `CheckerOrderingNameSentenceAgreementTest` | done ([KF653](KERNEL-FINDINGS.md#kf653)) |
| W4-reader | the reader ignores unknown members, binds a body-less field type, refuses `UnspecifiedType`; an absent `errorCode` or `errorEntityRelPath` and a header without id leave no model | `FieldTypeBinding`, `EnumCategoryWireShape`, `EnumDeclarationShape`, `TemporalConfigWireShape`, `RuleCodeAndSeverity`, `RuleConditionAndTargetSlot`, `ModelHeaderSlot` | done ([KF654](KERNEL-FINDINGS.md#kf654)); the value entry without `value` closed in W4-enum ([KF655](KERNEL-FINDINGS.md#kf655)) |
| W4-decl | field-declaration admission: an explicit empty range separator, field-type message pairs, a lone surrogate's `WRONG_ENCODING`, a foreign error-text locale, a blank temporal format, the write-gate categories | `DateRangePair`, `FieldTypeMessageText`, `ErrorTextCharacterGate`, `ModelTextRepertoire`, `ErrorTextLocaleScope`, `RuleMessageAdmissionGate`, `TemporalDeclarationWithoutFormat`, `SerializerTierParity` | done ([KF657](KERNEL-FINDINGS.md#kf657) message gates and `Vordruckname`, [KF658](KERNEL-FINDINGS.md#kf658) the expand copy's empty members, [KF659](KERNEL-FINDINGS.md#kf659) undeclared-locale parameters at code generation) |
| W4-empty | the expand copy's empty-as-absent reading ([KF658](KERNEL-FINDINGS.md#kf658)) at every other site that judges an empty member: the checker, the kits' readers and the interpreter | `ExpandCopyEmptyMemberLawsTest`; `CheckerEmptyMemberAgreementTest` (forty members, the category names, the kits' `ModelIndex` and `EditSession`); `EmptyDeclarationMemberDiffTest` and `EmptyTimeZoneDiffTest` (runtime, kernel and interpreter); the 30.8.1 empty-separator locks requalified ([IF306](INTERPRETER-FINDINGS.md#if306), [KF633](KERNEL-FINDINGS.md#kf633)) | done |
| W4-locale | `ja_JP_JP` and `th_TH_TH` leave no model on 31.1.1 ([KF662](KERNEL-FINDINGS.md#kf662)) | `CheckerLocaleSpellingAgreementTest` | done |
| W4-msgparam | the message-parameter decoder's internal errors became classes (`INVALID_GROUP`, `MVK_INDEX_FOR_ERROR_TEXT_INVALID`, [KF663](KERNEL-FINDINGS.md#kf663)) | `MessageParameterDecoderAgreementTest` | done |
| W4-probe | the probe's raise capture listened on the 30.8.1 runtime package; it now matches the release segment by pattern and refuses when no class carries the name ([KERNEL-PROBE-SPEC](KERNEL-PROBE-SPEC.md)) | `KernelProbeCustomConditionTest` | done |
| W4-limit | `NumberFieldValueLimitRule` was deleted in 31.1, so the bound-magnitude family retires | `CheckerNumberBoundMagnitudeAgreementTest` and its rulekit counterpart | done ([KF582](KERNEL-FINDINGS.md#kf582) retired; the bound scale rule in [KF593](KERNEL-FINDINGS.md#kf593)) |

**W5 batches.** The full adapter lane on 31.1.1 failed 109 of 4292 tests in 56 classes (2026-11-03). A mechanism marked *hypothesis* is the batch's starting account, not a finding; the batch's discriminating rows settle it.

| batch | mechanism | owning classes | state |
|---|---|---|---|
| W5-model | model-level, ingress and check-only laws that run in the adapter lane | `CheckerBasicSentenceOrder`, `CheckerClassMultiplicity`, `CheckerModelFrame`, `CheckerFrameValue`, `CheckOnlyFrameRuntime`, `WorkspaceTypeDefinitionCollision`, `WorkspaceBlankText`, `WorkspaceReachedModel`, `GroupRepeatabilityDomain`, `SelfNamedRuleErrorReference`, `NumberRenderChannel`, `NumberExponentEdge`, `EmptyEnumerationToken`, `UnsupportedConditionLanguageStage`, `A12JsonIngressDiff` | done 2026-11-03 |
| W5-cal | the proleptic ISO calendar replaces the hybrid Julian/Gregorian one: 31.1.1's `DateUtil` moved to `kernel-conversion-java` and computes on `java.time.LocalDate` where 30.8.1 used a non-lenient `GregorianCalendar` (source-read) | `ConstructedDateCalendar`, `StoredDateCalendar`, `HybridCalendarClock`, `YearlessShiftAnchor`, `A12JsonIngressKernel` | done 2026-11-03 |
| W5-tz | hypothesis: `ZonedDateTime`'s gap and overlap resolution replaces `Calendar`'s | `DstTimeZone`, `BerlinHistoricalTz`, `CalendarStepDifferenceInDays`, `WallDayArithmetic`, `TemporalExtrema`, `OverlapInstantInComputation`, `ConstructedDateInstant`, `DateTimeConstantZoneSplit`, `DateTimeComponentCompletion`, `ComputedDateTimeJoin` | done 2026-11-03 |
| W5-phase | temporal operand failure phases; re-triaged after W5-cal and W5-tz, since most rows may be their fallout | `ConstructionArgumentPhase`, `ShiftAmountPhase`, `DifferenceOperandFailureOrder`, `ConstructionProvenance`, `TemporalValueFailure`, `FilterTemporalLiteralFailure`, `CalendarTargetCompletion`, `ClockDeclaredDateGate` | done 2026-11-03 |
| W5-sparse | hypothesis: repetition traversal changed with the runtime service's data path (`DocumentIDataWrapper` and `EntityInstanceConverter` were deleted) | `SparseFilteredAggregate`, `SparseFilteredFill`, `SparseMembershipTraversal`, `SparseOverlapPolicy`, `FilterCacheScope`, `FilterMissingSlot`, `DenseUniquenessPrefix`, `ComputeTargetSubgroupScope`, `PartialGroupCountRelevance`, `PartialDeclaredListExtent`, `ClassificationEnablementGrid` | done 2026-11-03 |
| W5-msg | message rendering and field-owned error texts | `MessageParameterRow`, `FieldConstraintFormal`, `CustomTestRange`, `ComputationDiagnosticAssembly` | done 2026-11-03 |
| W5-perf | performance scenario fingerprints drifted with the fixture migration | `ScenarioInventory` | open — the anchors are refreshed on an idle machine before the release |


**W3b batches.** A12 names a criterion only by `IDocumentUniquenessCriterion.getName()` and lists its fields with `getFieldFullNames()`. It has no path, so criteria get their own field-keyed index, read from the expanded `IDocumentModel` the adapter already holds, beside the rule and computation referrers. Mixing names into those path-keyed sets would invent an address A12 does not have.

| batch | mechanism | acceptance (oracle) | state |
|---|---|---|---|
| W3b-1 | admission classes and move-support rewrite | `DocumentUniquenessCriterionLawsTest`, `UniquenessCriterionRewriteTest` ([KF671](KERNEL-FINDINGS.md#kf671)) | done 2026-11-03 |
| W3b-2a | three kernel measurements before the design: `field remove` on a criterion field today; whether expansion carries an included model's criteria and rebases them, and whether an entry criterion may name a mounted field; whether the sub-model of `group extract` is refused for the copied parent criteria | one laws class, `checkConsistency` on each result | open |
| W3b-2b | criteria as referrers. `where-used` lists them under `documentUniquenessCriteria`; `field remove` and `group remove` refuse with `RK_REFERENCED` naming them, `--cascade` included, since no verb edits a criterion; the `model report` dead-field set, `model usage` and the `model diff` read-by flag count them; rename and move report the criteria they rewrote; `group extract` copies no parent criterion and refuses when one names a field it moves | does-fire refusal tests (CLI-SPEC §6 gate), CLI tests, schema examples, `checkConsistency` on every refactor result; one cold review before landing | open |

Excluded, each a gap row when W3b-2b lands: authoring criteria through the kits (`DocumentModelDraft`), a `model diff` criterion kind, a `model info` count, a portable checker family for the criterion classes (silent is the safe direction), JSON Schema carriage, and a pre-edit gate for `field modify` (the kernel already refuses an optional or transient criterion field in A12's sentence).

Outside the batches: 31.1.1 states a duplicate element id codelessly ("The id [..] of element on path .. is not unique: Used by [..]"); the checker reports it as `RK_ELEMENT_ID_NOT_UNIQUE` ([KF661](KERNEL-FINDINGS.md#kf661)).

---

## 1 · Versions & coordinates

### Version streams

| stream | 30.8.1 (2025.06-ext5) | 31.1.1 (2026.06-ext1) | where we pin it |
|---|---|---|---|
| `kernelVersion` | 30.8.1 | **31.1.1** | `build.gradle:78` **and** `adapter/build.gradle:41`. The duplicate pin goes in W2. |
| distribution label | 2025.06-ext5 | **2026.06-ext1** | `build.gradle:83`; native smoke expectation `.github/mirror-workflows/build-native.yml:194` |
| document-model format | 28.4.0 | **29.4.0** | `core/ModelFormat.java`, committed JSON fixtures, inline fixtures, authoring defaults |
| `kernel-core-runtime-api` | 32.3.4 | **33.0.2** | `adapter/build.gradle:120` |
| `kernel-core-customfieldtype-api` (test) | 28.2.0 | **29.0.2** | `adapter/build.gradle:162` |
| a12-base | 29.3.0 | **30.0.2** | transitive |
| runtime artifact | `kernel-core-runtime-30-8` | `kernel-core-runtime-31-1` | `adapter/build.gradle:142` (test) |
| Jackson (kernel-internal) | 2.x `com.fasterxml` | **3.2.1 `tools.jackson`** (3.1.2 at ext0) | not pinned directly |
| CI bootstrap cache | `kernel-30.8.1-base-29.3.0` | a kernel/base 31.1.1/30.0.2 key | `.github/workflows/ci.yml`, `nightly-performance.yml`, the mirror native workflow |

### Module rename / delete / stable map

The kernel renamed most Gradle projects `kernel-*` → `kernel-internal-*`, and since publishing uses the project name, the Maven artifact IDs changed (source-confirmed). Kernel 31's `kernel-md-facade` now **exports, at compile scope,** the model, builder, serializer and runtime-executor modules. The sibling `a12-kernel-demo` builds against that single dependency at 31.1.1 (its `e7ca3af`). So the default for W2 is **the facade as the one coordinate**. A direct `kernel-internal-*` dependency stays only where it states a compile-time contract the facade does not export.

| adapter dependency (30.8.1) | 31.1.1 fate |
|---|---|
| `kernel-md-facade`, `kernel-md-serializer` | stable names |
| `kernel-core-tool-api` → `kernel-internal-core-tool-api`; `kernel-core-parsetree-api` → `kernel-internal-core-parsetree-api`; `kernel-core-facade` → `kernel-internal-core-facade`; `kernel-core-calc` → `kernel-internal-core-calc`; `kernel-md-model` → `kernel-internal-md-model`; `kernel-md-runtime-api` → `kernel-internal-md-runtime-api`; `kernel-md-runtime-service` → `kernel-internal-md-runtime-service` | renamed |
| `kernel-md-a12internal` | **deleted**: `DocumentModel` moved to **`kernel-md-documentmodel`** |
| `kernel-md-document-api` | **deleted**: the V1 document API is retired (A12K-3684); use `md.document.apiV2` |
| `kernel-core-runtime-api`, `kernel-core-customfieldtype-api` | stable names, new versions (above) |
| `IProblemReporter` | its module moved `kernel-core-base-utils` → `kernel-internal-base-utils`, same package |

**Design signal.** The `kernel-internal-*` naming declares those modules unstable. Using the facade simplifies coordinates, but it does **not** make our source coupling public. The parse-tree, formatter, reference-rewrite and calculation-read capabilities we use have only `internal`/`a12internal` APIs. So the achievable target is one stable coordinate plus a confined internal source boundary in `:adapter`.

### The ext0 → ext1 delta (31.1.0 → 31.1.1)

`git diff --name-only f10b920 eec9134` lists 44 files, mostly licences, notices, package versions, docs and the TS codemod. Eleven are `.java`/`.ts` source outside tests, all read (2026-11-02):
- **Value conversion now receives the configured custom-field-type factory.** `ConversionService`, `ValidationData`, `LowKernelApiUtils`, `DataDocumentConversionUtils` and `DataTransferServiceAdapter` thread `ICustomFieldTypeFactory` into `metaField.convert`/`convertFromBasicType`. This is the one runtime delta. Its observable effect is unmeasured (W5). Re-run `adapter.laws.CustomFieldTypeSpiInvocationLawsTest`.
- **Include/mapping resolution:** `UnexpandedModelResolverImpl` now parses the header and accepts the `runtime_mapping_precompfragment_document` model type (constant moved to `MappingModelService`, used by `HandlePrecompAndOtherFragments`). `StructuralMappingModelService` loses an unused field. This is relevant to D2's port.
- `facade.ts` gains one JSDoc comment. Jackson moves to 3.2.1 and base to 30.0.2.

---

## 2 · DSL / operators (catalog + keyword-parity surface)

### One new operator — `SumOfTerms` / `SummeVonTermen`

A new **set operation** (parse tree `ISetOpOperation`, enum `SetOpOperationName`, lexer terminal `SUMME_VON_TERMEN`; EN `SumOfTerms`, DE `SummeVonTermen`, re-verified at `eec9134`).

- **Syntax / arity:** `SumOfTerms(<iterationGroup*>, <term>)`. Argument 1 is a starred repeatable group path; argument 2 is a numeric term evaluated per iteration. It is usable wherever a number-valued operation is, and **returns NUMBER**.
- **Checker constraints** (July source read of `CheckSetOperOpImpl` + `CheckSetExpressionUtil`; the measured classes are [KF670](KERNEL-FINDINGS.md#kf670)):
  - The iteration entity must be a group, repeatable and wildcarded on its lowest level, with no semantic index.
  - The term must be numeric and may not contain application conditions, `RepetitionNotUnique`, a `Mit` filter or a nested `SumOfTerms`.
  - The lowest wildcard level must be iterated.
  - No `$` outer iteration inside the term.
  - The operator is rejected entirely in H-models.
- **No A12 example exists** (searched 2026-11-02): 0 hits in the 121 document models of the A12 Tools 2026.06-ext1 workspaces, in the kernel documentation (including `documentation/_merged/kernel-ba.md`) and in the metamodels. The only A12 prose is the editor's built-in help (EN "For each repetition, the value of an iterating term is calculated and then those values are summed up."); its DE title still reads `SummeVonProdukten(_Pfad_, _Term_)`. The one worked example is the sibling `a12-kernel-demo`'s timesheet computation, `SumOfTerms(Days*, [Hours] * [Rate] + [Km] * 0.30)`, which the license rule lets us adapt. The demo's prose about empty fields and empty rows is a hypothesis until W6 measures it on both kernel strategies.

Support is an **atomic typed vertical slice**: typed authoring, renderer/reference/explanation, adapter read-back for `ISetOpOperation`, catalog EN+DE, interpreter parse/evaluate/relevance, and differentials. A catalog-only entry would advertise a construct the typed API cannot author and the interpreter cannot execute. The empty-iteration result must be observed against both kernel strategies, never inferred from `SumOfProducts`.

### Two constraint *relaxations*

These contradict laws we currently keep green. Source-verified at `eec9134` (0 hits for the removed checks; `cb66e51`: `CheckVergleichsBedingungImpl` 5, `CheckEntityListenUtils` 1, `CheckOperationImpl` 3). Neither is measured yet.

1. **Enum comparisons:** the compatibility checks are removed. `MVK_INVALID_COMPARE_ENUM_TO_ENUM` / `…_TO_STRING` no longer exist.
2. **`+` with display-value enums:** `CheckOperationImpl.checkWithoutDisplayValues` is deleted. `MVK_NO_STRING_OR_NUMBER_FOR_ADD` remains for the genuine mismatch.

### Not a change

- `INumberOfRootGroupsOperation` is not new and is H-model-only.
- `OperationType.NUMBER` gaining `"VkBigDecimal"` is a codegen helper for `SumOfTerms`.
- No A12-model keyword was removed or renamed. No public parse-tree type moved package.

---

## 3 · Error codes & consistency categories (ledger surface)

Re-derived 2026-11-02 by diffing the enum constants at `cb66e51` and `eec9134`.

- **`BasicDocumentModelConsistencyCategory`:** +1 `LOCALIZED_TEXT_UNIQUENESS_CRITERION_NULL_PROBLEM`.
- **`DocumentModelCategory`:**
  - **−2** `DOCUMENT_MODEL_NUMBER_{MAX,MIN}_VALUE_EXCEEDED`. These are model-consistency category checks only; runtime validation of declared bounds remains.
  - **+10** `DOCUMENT_MODEL_UNIQUENESS_CRITERION_*`: `DUPLICATE_NAME`, `EMPTY_NAME`, `FIELD_NOT_FOUND`, `FIELD_NOT_REQUIRED`, `FIELD_REPEATABLE`, `FIELD_TRANSIENT`, `FIELDS_EMPTY`, `INVALID_ERROR_TEXT`, `INVALID_ERROR_TEXT_PARAMETER`, `MISSING_ERROR_TEXT`.
- **MVK codes (`ParserErrorTypesEnum`, 176 → 175 constants):**
  - **Gone:** `MVK_INVALID_COMPARE_ENUM_TO_ENUM`, `MVK_INVALID_COMPARE_ENUM_TO_STRING`, `MVK_INVALID_UNSPECIFIED_TYPE`, `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION`, `MVK_INVALID_ITERATION_IN_FILTER_CONDITION`, `MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED`.
  - **New:** `MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION`, `MVK_INVALID_ITERATION_IN_SET_EXPRESSION`, `MVK_PARALLEL_ITERATION_FOR_PATH_WITH_ASTERISKS_NOT_ALLOWED`, `MVK_WILDCARD_AT_LOWEST_REPEATABLE_LEVEL_REQUIRED`, `MVK_NO_ITERATION_FOR_LOWEST_WILDCARD`.
  - The source comments pair the three filter codes with the three set-expression codes (same sentence, generalized from "Filterbedingung" to "bei '%s'"). That pairing is a reading, to be measured in W4.
- **⚠ Silent-semantics-drift trap:** `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED` **kept its name and changed meaning**. Its old message ("…ein uneingeschränkter Stern…") moved to the new `…REPEATABLE_LEVEL_REQUIRED`, and the old name now says "für die unterste Ebene ein Stern". A ledger keyed only on the name carries a stale meaning.
- **Also gone** (July source read): `MVK_RULE_TEST_NO_DEFAULT_VALUE` with its `TestRuleErrorType`. In errortext, `FIELD_INVALID_PREFIX` → `FIELD_ERRORTEXT_WRONG`, and `NO_VORDRUCKNAME_IN_H` is removed.
- **a12-base 29.3.0 → 30.0.2:** `RankedNotification`, `Severity`, `ConsistencyCategory`, `ConsistencyProblem` and `Problem` come from a12-base `com.mgmtp.a12.model.notification.*` / `…consistency.*`. `Severity` keeps `INFO`/`WARNING`/`ERROR`, so our severity mapping is unaffected.

---

## 4 · Adapter API breaks (`:adapter` — W2)

The dominant theme is the kernel-wide **V1-document + runtime-variant retirement**, plus removal of deprecated extension registries and of expansion. The break surface includes the shared `RuntimeLaws` oracle and the Kotlin differential tests, not only `KernelAdapter`. Today 89 `:adapter` source files import `com.mgmtp.*` (2026-11-02, `git grep -l 'import com\.mgmtp\.' -- '*.java' '*.kt'`).

1. **V1 document API removed:** `KernelAdapter` and `RuntimeLaws` build V1 `IDocument`s. The md custom-condition differentials implement the V1-shaped method. All move to `DocumentV2` and `DocumentPointer`/`DocumentMultiPointer`.
2. **`DocumentServiceFactory.createDocumentFactory()` removed**, as are `createDocumentV1V2Converter()`, `IDocumentV1V2Converter` and `ofDocumentV1`.
3. **Runtime variant APIs removed:** `getMvkServiceForModel` loses the variant parameter, `extractUsedVariants` disappears, and `getVariant()` overrides fail. Serialized `modelInfo.variant` is unaffected: `DocumentModelInfo.variant` still exists at `eec9134`.
4. **`IDocumentModelService.expand` removed** (A12K-4021). It affects `KernelAdapter`, `KernelConsistencyOracle`, `KernelModelOracle`, `RuntimeLaws` and `CorpusModelPreparer`. Per **D2**, our differential uses `CombinationModelService` behind one adapter port.
5. **Deprecated custom-extension services removed** (A12K-4106, A12K-4079): `PredefinedTypeValidityDiffTest`'s static `CustomFieldTypeService` moves to an `ICustomFieldTypeFactory` passed through `DocumentProcessingConfig`.
6. `IDocumentModelMigrator#getOldestSupportedSourceVersion` → `getFirstSupportedSourceVersion` (A12K-3856), if we call it.

The root fix for items 1–2 is **one shared, model-aware V2 document materializer** used by production evaluation and `RuntimeLaws`. It must preserve explicit-empty placement and convert external values through `createDmAwareDocService(modelId)`. Two independent rewrites would open a semantic-drift seam. `EvalDocSeamLawsTest` is the direct canary.

**Non-breaking but notable:**
- `IMVK_Service` lost two super-interfaces, but every call we make resolves through the remaining parents.
- `MoveSupportDM.changePathReferencesForMove` now also rewrites Document Uniqueness Criteria.
- `ASTVisitor` gained a default `visit(ISetOpOperation)`; `ConditionConverter` reads `SumOfTerms` back into the typed record since W6.
- The core custom-condition SPI (`ICustomCondition`, `IData`, `IIdentifier`) is unchanged.
- The computation read surface is unchanged.
- Dynamic configuration adds default `modelCodeHashProvider` and `considerNotInD`; both belong in the customization-surface audit (W5).

---

## 5 · Runtime semantics (RT-ledger + interpreter differential — W5)

- **Genuinely new:** `SetOperation` (`@FunctionalInterface`), the runtime hook for `SumOfTerms`.
- **⚠ Date/time reimplemented from `java.util.Calendar` to `java.time`** (`RuntimeController`, `BedingungsOperatorHelper`, `FormatDefinitionDatum[Bereich]`), including the year/month difference with its leap-year and 28-Feb edge cases. **This is the highest differential-drift risk.** Re-run the interpreter's date laws (`DateMath.kt`).
- **Behaviour-preserving refactor churn:** immutability, German→English renames, switch modernization. The number formal-error classification rewrite needs an equivalence spot-check.
- **`kernel-md-runtime-service`:** `DocumentModelCodeGenerator` was removed and the dynamic Groovy path restructured (`DocumentDynamicRtService` now goes through `getGeneratedCodeAccessFactory(id).getCalculator()`). Flag this for the codegen-strategy parity check.
- **ext1:** custom-field-type factory in value conversion (§1).
- **Jackson 3** inside the kernel may change the Document JSON number stages (parse configuration, read stringification, write emission; [KF1](KERNEL-FINDINGS.md#kf1)). They were measured on Jackson 2 and must be re-measured.

---

## 6 · Metamodels (`a12-kernel-mm` — schema/constraint oracle)

The same 15 logical metamodel directories remain. JSON artifacts grow **59 → 75**, because eight directories gain current/previous `a12types` variants. Canonical rules go **547 → 544**; computations stay at 6.

- **⚠ MM_Group_1: include config restructured.** It serializes as `includeConfig: { reference, includeLevel, excludeRules, excludeComputations }`, and the mount alias indirection is gone. **A12-written evidence (2026-11-02):**

  | A12 Tools workspaces | document models | include objects |
  |---|---|---|
  | 2025.06-ext5 | 107, all 28.4.0 | 100 `modelAlias` |
  | 2026.06-ext1 | 121, all 29.4.0 | 0 `modelAlias`; 101 `includeConfig` with key sets `{reference}` ×100 and `{reference, excludeRules}` ×1 |

  No ext1 file carries `includeLevel`, so its absent-default must be measured. These workspaces are the **shape reference for W3** (research only, never committed).
- **`Unspecified` is removed.** The 29.1→29.2 migrator converts it to `String` with `_take_reference_configuration`.
- **`STRING_TYPE_TEMPLATE_REFERENCE_2` / `StringType.templateReference` removed.** We have no production consumer; W4 verifies this.
- **`AutoCompleteProps.allowAddingNewItems`** is added. Read/preserve is required; typed authoring is a separate product choice.
- `KernelMmReadCoverageTest`'s `>=545` floor and its "of 547" wording will fail. Re-baseline from executed results only.

---

## 7 · Cross-cutting

- **Document-model format 28.4.0 → 29.4.0 is mandatory**, with minimum and latest both at 29.4.0. **Footprint (2026-11-02):**

  | measure | command | count |
  |---|---|---|
  | JSON files carrying the quoted string `"28.4.0"` | `git grep -l '"28\.4\.0"' -- '*.json'` | **141**: `interpreter` 48, `corpus` 23, `cli` 22, `examples` 19, `adapter` 14, `consistency` 8, root `src` 7 |
  | non-JSON files mentioning `28.4.0` | | 226 |
  | files mentioning `30.8.1` | | 238 |

  The July audit counted 65 models.
- **The migration chain** (CL): 28.4→28.5 `packet`; 28.5→28.6 uniqueness criteria; 28.6→29.0 include restructure; 29.0→29.1 strips `variantFilter`/`variantErrorMessages`; 29.1→29.2 `Unspecified` → `String`; 29.2→29.3 CDM query-root references; 29.3→29.4 removes field-type annotations.
- **Don't rely on implicit migration at our serializer boundary.** The public `IDocumentModelSerializer.deserialize` maps JSON directly and does not invoke `IDocumentModelMigrator` (July source read). Direct-deserialization paths must migrate explicitly or require 29.4.0. A golden test proves migrated JSON keeps every include, reference and exclusion.
- **Variant data in fixtures:** two committed files carry `variantFilter` (`cli/src/test/resources/models/order-variant.dm.json`, `adapter/src/test/resources/checker-near-miss/harvested-report-checks.json`). Retire or reframe `VariantFilterPreservationTest`, the interpreter's null-variant filtering and `InterpreterRealModelDiffTest.variantFilterOnlyRuleIsDroppedLikeKernel`. *Done 2026-11-02:* 31.1.1 reads past both members on every carrier ([KF299](KERNEL-FINDINGS.md#kf299), `RetiredVariantMembersLawsTest`). The preservation lock is `UnknownMemberPreservationTest`, the interpreter's drop and the checker's `VARIANT_FILTER` family are gone, and the differential is `aRetiredVariantFilterIsReadPastLikeTheKernel`.
- **Jackson 2 → 3.** We keep our own Jackson 2 in `core`/`cli` and exchange only strings with the kernel. The distinct root packages coexist. The real cost is native-image reflection metadata.
- **Corpus provenance:** `CorpusEngines.KERNEL_VERSION` and every committed case say 30.8.1. Regenerate through `CorpusGenerator`, never by hand.
- **CI cache keys are part of the pin** (§1).

### Surfaces the July audit did not cover

| surface | impact |
|---|---|
| **`:consistency`** (the Kotlin Multiplatform portable checker; its JS package is published) | Its `CheckFamily` verdicts are measured 30.8.1 verdicts. The enum relaxations and the filter→set-expression codes move them. The checker-silence guards (`checkerSilenceGuardTest`) are the detector; re-measure each affected `validate.laws` grid and then its mirror (W4). |
| **Interpreter compatibility claim** (`interpreter/public-release/README.md` "Semantics target… 30.8.1") | Re-earned only by re-running the drop-in certificate on 31.1.1 (W8). |
| **a12-kernel-lean** (its stated primary goal was 30.8.1 conformance; it measures through our `kernelProbe`/`probeDifferential`) | Handled by D4. Probe tooling moves first in W2. |
| **Kernel TypeScript runtime** (`kernel-core-runtime-ts` and the other four kernel packages, 31.1.1, built as npm tarballs by the bootstrap) | Lifts the blocker named in [BUILD-ENVIRONMENT](BUILD-ENVIRONMENT.md) "Offline and bootstrap limits" and [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md) "Why code-generator JS/TS is not yet measured". The kernel-JS evidence strategy is a follow-up (D5); W7 corrects those two docs. |
| **A12 Tools 2026.06-ext1 workspaces** (machine-local path; `-Da12.corpus1.dir=…/workspaces`) | 121 document models at 29.4.0 replace the ext5 corpus of 107 for the opt-in real-corpus sweeps. They are the W3 shape reference (§6). |

---

## 8 · rulekit impact map (current detection → gap → required fix)

Several guardians detect additions but not stale removals, or scan a hardcoded set. Hardening them is part of the migration (W4).

| surface | current detection / gap | required fix |
|---|---|---|
| model format 28.4→29.4 | `ModelVersionPolicyTest`, `VersionVerbTest`, `VersionMentionFreshnessTest`; no migration golden | migrate fixtures + inline/default versions through the kernel migrator; add preservation and direct-serializer loss guards |
| V1 document + removed extension APIs | compilation; the Java `-Xlint:removal` ratchet misses Kotlin | compile every source set; add a removed-API source guard across Java **and** Kotlin; one V2 materializer |
| new `SumOfTerms` construct | `KernelConstructParityTest` discovers every enum of the `types` package, `SetOpOperationName` included | done in W6 |
| enum-comparison / `+` relaxations | `EnumComparabilityLawsTest` and N7 invalid examples assert the old errors | flip to accepted laws after measuring; update the contradicted finding and catalog gotcha |
| filter → set-expression codes | N7 examples plus source strings | rename across catalog, [MVK-LEDGER.md](MVK-LEDGER.md), `FieldListRef`, `CustomConditionRef`, diagnostics, tests |
| removed / renamed / re-meant MVK codes | `MvkLedgerTest` is one-way | exact two-way equality, then realign entries **and meanings** (§3 trap) |
| consistency categories | `SerializerTierParityTest` scans only the Basic enum | add an exact `DocumentModelCategory` ledger/guardian |
| removed variant/accessor slots | `SlotParityTest` is one-way | make it two-way; keep `modelInfo.variant` |
| include/reference restructure (D3) | `KernelMmIntegrationTest` is kernel-free | migrate the authoring/refactor surface; real-kernel deserialize→expand→consistency gate; preservation laws for both exclusion flags and include levels |
| expansion oracle (D2) | parity was against kernel `expand` | port to `CombinationModelService`; prove recursive include/type-def behaviour and failure atomicity |
| document uniqueness criteria | `ReferenceRewriteTest` has no criterion fixture | read/preserve/index/where-used; rename/move/delete rewrite or refuse |
| portable checker | `checkerSilenceGuardTest` | re-measure affected grids against 31.1.1, then the mirror |
| metamodel corpus 59→75 / rules 547→544 | `KernelMmReadCoverageTest` floor | re-baseline from execution |
| runtime: dates, codegen, conversion, Jackson 3 | RT ledger + differentials | focused date laws, `KernelJavaBaselineDiffTest`, `JavaStaticPartialBaselineDiffTest`, custom-field-type laws, Document JSON number locks, then tri-engine fuzz/corpus |
| corpus provenance | `CorpusSchemaGuardTest`, `CorpusReplayTest` | bump anchors, regenerate via `CorpusGenerator`, replay all three engines |
| source-based guardians | default sibling paths | must scan the `eec9134` tree; fail if a requested source root resolves to a different revision |
| running version / floor (D1) | `KernelVersionTest` (INFO-only) | re-stamp `verifiedAgainst` and the version surfaces |
| native reflection/resources | release-time native gate | re-capture first, rebuild, run the no-crash battery |

---

## 9 · Update plan

The work items in §0 carry state; this section carries the method.

**W2 — build wiring + adapter.** Start with probe tooling, so a12-kernel-lean regains a 31.1.1 oracle as early as possible. Collapse the two `kernelVersion` pins into one and use the facade coordinate (§1). Use `:adapter:compileJava`, `:adapter:compileTestJava` and `:adapter:compileTestKotlin` as the deliberate red inventory. Green `EvalDocSeamLawsTest` first, then `KernelAdapterBootstrapTest`, the `RuntimeLaws` consumers, both custom-condition differentials and `PredefinedTypeValidityDiffTest`.

**W3 — model format + includes.** Run every committed 28.4 fixture through the 31.1.1 migrator into a scratch tree, inspect the structural diff, then update the fixtures, inline fixtures, `ModelFormat.VERSION`, authoring defaults, demos and version captures. A migration golden covers an include, both exclusion flags, both include levels, an `Unspecified` field, a variant filter and a field-type annotation. Redesign include authoring around `includeConfig.reference` (D3):
- **Sweep:** `DmKeys`, `ModelReader`, `DocumentModelDraft`/`ModelDocument`, `MountOptions`/`IncludeLevel`, `IncludeEditor`, `StructureRefactor`, safety/where-used, the CLI include/apply/refactor verbs, schemas/help, tests and demos.
- **Alias input** gets a corrective diagnostic.
- **Lock** with refactor refusal laws.

Add uniqueness criteria to read/preserve/reference indexing. Then offer the same migration for a12-kernel-lean's probe models (D4).

**W4 — guardians + contract realignment.** Make construct, MVK, slot, Basic and DocumentModel category inventories exact in both directions **before** updating their expected data. Then follow the failures through §8. Every flipped law is a verdict-delta row for a12-kernel-lean.

**W5 — runtime requalification.** Run the focused date suites (`DateDifferenceDiffTest`, `DateArithmeticDiffTest`, `DateArithmeticLawsTest`, `DateFunctionsOverDateTimeDiffTest`, `OmittedYearDateDiffTest`), the Java-static baselines and the tri-engine fuzz lane. Re-audit every interpreter customization point (locale, clock/date/timezone, custom conditions, custom field types, charset, the new dynamic-config defaults) and every finding with a re-open-on-upgrade trigger (`mandatoryGroup`, multi-select `uniqueValue`, custom-ref error entities, computation rounding, the SumOfProducts acceptance edge). A changed fact lands in [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) first. Regenerate the corpus.

**W6 — `SumOfTerms`.** Land the §2 slice. Read [interpreter/CLAUDE.md](../interpreter/CLAUDE.md) first and keep the clean-room boundary. Probe both kernel strategies before fixing empty/no-row behaviour.

**W7 — surfaces + native.** Version surfaces (README, CLAUDE.md per D7, plugin, `--version`), docs index, the two "TS runtime blocked" docs, the KERNEL-UPGRADE runbook. Re-capture native config, rebuild, run the native gate.

**W8 — gated release (D6).** The full gate, the drop-in certificate on 31.1.1, a cold review of the release step, the a12-kernel-lean letter, release-repository assembly for the 31.1.1 EUPL notices, then the owner's confirmation before `--go-live`.

The prerequisite in the sibling [`a12-kernel-bootstrap`](../../a12-kernel-bootstrap) is done (W0). Nothing here changes it.

### Completion gates

- No active fixture remains at 28.4.0. Any retained legacy input is a named migration test.
- No V1 document, removed variant API, static custom-extension, deleted module or `_30_8` runtime API remains in kernel-facing sources.
- Include round trips preserve reference, level and both exclusion flags. Rename/move/delete cannot leave a dangling include or uniqueness-criterion path.
- The construct, MVK, slot and consistency guardians are exact in both directions and scan `eec9134`.
- Dynamic-Groovy, Java-static, interpreter JVM and interpreter JS agree on the migrated corpus and the `SumOfTerms` laws, except for reviewed, documented divergences.
- Full build, demos, corpus guards, native gate, version surfaces and release-notice assembly are green.

## 10 · Disposition history

On 2026-07-17 the recommendation was **DEFER**. There was no forcing function, nothing was blocked on 30.8.1, and folding the V1→V2 rewrite into [KERNEL-DECOUPLING-PROPOSAL.md](KERNEL-DECOUPLING-PROPOSAL.md)'s Stage 5 would do it once. That disposition was superseded on **2026-11-02** by the owner's directive to migrate to the release the sibling checkout now carries. Decoupling Stage 5 is still unbuilt, so the V1→V2 rewrite lands directly in `:adapter` under W2.

## 11 · Open build-time confirmations

- **Migration boundary:** confirm that explicit `IDocumentModelMigrator` output for our fixtures is stable after deserialize→serialize, and what the direct serializer does with an old flat include (a loss-detection test, not the plan).
- **Expansion port (D2):** prove that `CombinationModelService` reproduces the include/type-def contract, including failure atomicity, on the existing parity fixtures.
- **`includeLevel` default:** no A12-written ext1 file carries it. Measure absent vs explicit.
- **Runtime drift:** whether the `java.time` rewrite, the number formal-error refactor, the codegen restructure, the ext1 conversion change or Jackson 3 produce observable differences.
