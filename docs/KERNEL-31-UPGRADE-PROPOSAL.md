# Kernel 31.1.0 upgrade — change analysis & update plan (PROPOSAL)

**Status:** PROPOSAL, 2026-07-17. **Recommended disposition: DEFER** — finish current work on 30.8.1 and fold this bump into the kernel-decoupling program (as its V1→V2 Stage 5) rather than run it now as a standalone migration; rationale + re-open triggers in §10. This is the concrete instance of the [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) runbook for the `30.8.1 → 31.1.0` bump: it inventories what upstream changed and lays out the phased realignment for when the bump happens. Nothing here is executed — the kernel checkouts were **fetched, not pulled**, and no pins were bumped.

Primary sources: the upstream [kernel migration instructions](https://github.com/mgm-tp/a12-kernel/blob/f10b9203822cd2a2d86bacccfa1ace615606b3c8/documentation/dev/index.adoc#L522-L606), the exact [kernel `30.8.1…31.1.0` comparison](https://github.com/mgm-tp/a12-kernel/compare/cb66e51fa7ab90b650698f861bf670754e2e1e66...f10b9203822cd2a2d86bacccfa1ace615606b3c8), and the exact [metamodel comparison](https://github.com/mgm-tp/a12-kernel-mm/compare/d4ec70fcd80f89a6d84071e085a990a9884e38ef...f7ea1796f0f124c89fc877a6e4e49c42e963293b). Both fetched releases are one squashed commit directly on top of the pinned release: kernel `cb66e51…f10b920` is 1,335 files, +19,833/−42,057; metamodel `d4ec70f…f7ea179` is 89 files, +26,966/−3,094. Generated `/delombok/` and `build/` output was excluded from the source analysis.

Neither repository publishes Git tags or GitHub Release objects. That does **not** mean there is no authored changelog: `a12-kernel` now carries detailed in-repository migration instructions, which are the primary change list; the full source and metamodel diffs corroborate them and reveal the internal changes the guide intentionally does not cover. Research used fetch-only sibling refs plus detached temporary clones at the two new commits; the sibling worktrees were not pulled, switched, merged, rebased, or otherwise changed.

## Bottom line

This is a **major-version and model-format migration, not a pin bump.** `30.8.1` (2025.06-ext5) → **`31.1.0` (2026.06-ext0)** is a new annual line; the old line is preserved upstream as branch `release/2025.06` (our current pin). The upgrade raises the minimum/latest document-model format from **28.4.0 to 29.4.0**, so all **65 committed JSON models** plus inline fixtures and authoring defaults must migrate. It also carries hard compile breaks across `:adapter` main/test source sets, a kernel module rename/consolidation (`kernel-*` → `kernel-internal-*` + deletions), a Jackson 2 → 3 move inside the kernel, one new DSL operator (`SumOfTerms`), two constraint relaxations that contradict currently-green laws, and an include-config/alias restructure spanning `core`, `modelkit`, `edit`, CLI, fixtures, and demos. JDK stays **21**.

Effort shape: **multi-session** — this doc's work-item table is the state carrier across those sessions ([`../CLAUDE.md`](../CLAUDE.md) "How work runs"). There is also a prerequisite in a sibling repo: [`a12-kernel-bootstrap`](../../a12-kernel-bootstrap) must be updated first, or 31.1.0 never reaches `mavenLocal` and nothing here builds.

This is exactly the scenario [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) was written for — quiet tolerance at runtime, **loud detection at build time**. This audit also found several guardians that are one-way or scan a hardcoded source set; Phase 4 strengthens those before treating their failures as a complete worklist.

---

## 1 · Versions & coordinates

### Version streams

| stream | 30.8.1 (now) | 31.1.0 (new) | where we pin it |
|---|---|---|---|
| `kernelVersion` (main line) | 30.8.1 | **31.1.0** | `build.gradle:49`, `adapter/build.gradle:35` |
| distribution label | 2025.06-ext5 | **2026.06-ext0** | `build.gradle:54`, native Windows smoke expectations |
| document-model format | 28.4.0 | **29.4.0** | `core/ModelFormat.java`, 65 committed JSON models, inline fixtures |
| runtime-api (hardcoded) | 32.3.4 | **33.0.2** | `adapter/build.gradle:223` |
| customfieldtype-api (test) | 28.2.0 | **29.0.2** | `adapter/build.gradle:236` |
| a12-base (kernel pin) | 29.3.0 | **30.0.0** | transitive; the public base source release is 30.0.1 |
| runtime artifact id | `…runtime-30-8` | `…runtime-31-1` | transitive only — safe |
| Jackson (kernel-internal) | 2.x `com.fasterxml` | **3.1.2 `tools.jackson`** | not pinned directly |
| CI bootstrap cache | `kernel-30.8.1-base-29.3.0` | **kernel/base 31/30 key** | `.github/workflows/ci.yml`, `.github/workflows/nightly-performance.yml`, mirror native workflow |

### Module rename / delete / stable map (the backbone of the build-file change)

The kernel renamed most Gradle projects `kernel-*` → `kernel-internal-*` (physical dirs unchanged) and, since publishing uses the project name with no artifact-id override, the published Maven artifact IDs change. This is source-confirmed; exact coordinate names and publication are no longer open questions.

| adapter dependency (30.8.1) | 31.1.0 fate |
|---|---|
| `kernel-md-facade` | **stable name** (version bump only) |
| `kernel-md-serializer` | **stable name** (version bump only) |
| `kernel-core-tool-api` | → **`kernel-internal-core-tool-api`** |
| `kernel-core-parsetree-api` | → **`kernel-internal-core-parsetree-api`** |
| `kernel-core-facade` | → **`kernel-internal-core-facade`** |
| `kernel-core-calc` | → **`kernel-internal-core-calc`** |
| `kernel-md-model` | → **`kernel-internal-md-model`** |
| `kernel-md-runtime-api` | → **`kernel-internal-md-runtime-api`** |
| `kernel-md-runtime-service` | → **`kernel-internal-md-runtime-service`** |
| `kernel-md-a12internal` | **DELETED** — the `DocumentModel` class moved to new **`kernel-md-documentmodel`** |
| `kernel-md-document-api` | **DELETED** — the entire V1 document API is retired (use `md.document.apiV2`) |
| `kernel-core-runtime-api` | stable name, **version 32.3.4 → 33.0.2**; now an `includeBuild` in the kernel |
| `kernel-core-customfieldtype-api` | stable name, **version 28.2.0 → 29.0.2** |
| (`IProblemReporter`) | its module moved `kernel-core-base-utils` → **`kernel-internal-base-utils`**, same Java package |

New modules: **`kernel-md-documentmodel`** (holds the model classes and migration API) and `kernel-md-workspace-converter`. Seven of our direct old coordinates rename to `kernel-internal-*`; the other changes are stable names, removals, or replacement modules.

**Design signal to escalate (see §10):** the `kernel-internal-*` renaming is a deliberate statement that these are internal and unstable. Upstream recommends `kernel-md-facade` (and its BOM) as the single Java dependency, and the facade exposes the required internal artifacts transitively. That can simplify Gradle coordinates, but it does **not** make our source coupling public: the parse-tree, formatter, reference-rewrite, and calculation-read capabilities we use still have only `internal`/`a12internal` APIs. The achievable decision is therefore “one stable aggregate coordinate plus a confined internal source boundary,” not a public-only rewrite unless capabilities are dropped or upstream adds replacements.

---

## 2 · DSL / operators (catalog + keyword-parity surface)

### One new operator — `SumOfTerms` / `SummeVonTermen`

A new **set operation** (parse-tree `ISetOpOperation`, enum `SetOpOperationName.SumOfTerms("SummeVonTermen")`; lexer terminal `SUMME_VON_TERMEN`).

- **Keywords:** EN `SumOfTerms`, DE `SummeVonTermen`.
- **Syntax / arity:** `SumOfTerms(<iterationGroup*>, <term>)` — arg 1 is a starred repeatable **group** path; arg 2 is an arbitrary numeric **term** evaluated per iteration. Registered as an `einfacheOperation`, so it is usable anywhere a number-valued operation is (rule conditions and computations). **Returns NUMBER.**
- **Semantics:** "for each repetition the value of an iterating term is calculated, then those values are summed." Distinct from `SumOfProducts`, which is hard-wired to the product of exactly two repeatable *fields*; `SumOfTerms` sums *any* term over an explicitly-starred group.
- **Checker constraints** (`CheckSetOperOpImpl` + `CheckSetExpressionUtil`): iteration entity must be a group, repeatable + wildcarded on its lowest level, no semantic index; term must be numeric, may not contain application-conditions / `RepetitionNotUnique` / a `Mit` filter / a nested `SumOfTerms`; the lowest wildcard level **must** be iterated (stricter than the `Mit` filter); no `$` outer iteration inside the term; rejected entirely in H-models.
- **No kernel BA-doc example exists** — the only kernel prose is the parser-web tooltip (which even has a DE-title copy-paste bug: it shows `SummeVonProdukten(...)`). Our catalog example will be our own (license-safe), verified through the tool.

For this project, support is an **atomic typed vertical slice**: a typed `GroupRef`/numeric-expression authoring API, renderer/reference/explanation support, adapter read-back for `ISetOpOperation`, catalog EN+DE metadata and examples, interpreter parse/evaluation/relevance support, and dynamic-Java/interpreter differentials. A catalog-only entry would falsely advertise a construct the typed API cannot author and the interpreter cannot execute. The empty-iteration result must be observed against both kernel strategies; it must not be inferred from `SumOfProducts`.

### Two constraint *relaxations* (previously-invalid conditions now validate)

These are the subtle, high-value findings — they contradict laws we currently lock green:

1. **Enum comparisons — compatibility checks fully removed.** Comparing two enums with mismatched display/value maps, or an enum-with-display-values against a string / a texts-less enum, **was** rejected (`MVK_INVALID_COMPARE_ENUM_TO_ENUM` / `MVK_INVALID_COMPARE_ENUM_TO_STRING`) and **is now allowed**. (`CheckVergleichsBedingungImpl` lost `checkEnumVsEnum`/enum-vs-string; `CheckEntityListenUtils` lost the display-value machinery.)
2. **`+` concatenation with display-value enums — restriction removed.** `CheckOperationImpl.checkWithoutDisplayValues` deleted; `MVK_NO_STRING_OR_NUMBER_FOR_ADD` still exists for the genuine mismatch case.

### Not a change (corrections to first-glance reads)

- **`INumberOfRootGroupsOperation` is not new** and is **H-model-only** — it already existed at 30.8.1 (`AnzahlAngegebenerVordrucke`), outside the A12-model DSL we target. The only 31.1.0 delta is a grammar comment + a `toString()` fix.
- **`OperationType.NUMBER` gaining `"VkBigDecimal"`** is a codegen helper for the `SumOfTerms` typed lambda — no DSL/type-system behavior change.
- **No A12-model keyword was removed or renamed.** No public parse-tree API type moved package. The `_30_8`→`_31_1` churn is only in `kernel-rt` resource paths.

---

## 3 · Error codes & consistency categories (ledger surface)

### Consistency categories — two enums changed

- **`BasicDocumentModelConsistencyCategory`** (`SerializerTierParityTest`): **+1** `LOCALIZED_TEXT_UNIQUENESS_CRITERION_NULL_PROBLEM` (16 → 17).
- **`DocumentModelCategory`**: **+10** `DOCUMENT_MODEL_UNIQUENESS_CRITERION_*` (fields-empty / not-found / not-required / repeatable / transient / missing-error-text / invalid-error-text / invalid-error-text-parameter / duplicate-name / empty-name), **−2** number min/max (`DOCUMENT_MODEL_NUMBER_{MAX,MIN}_VALUE_EXCEEDED`). Backed by a new `DocumentUniquenessCriteriaRule` and the deletion of `NumberFieldValueLimitRule`. This removes those two **model-consistency category checks**; it does not remove runtime validation of declared numeric bounds.

### MVK codes (`ParserErrorTypesEnum`) — the `MvkLedgerTest` + enricher surface

- **Removed (3):** `MVK_INVALID_COMPARE_ENUM_TO_STRING`, `MVK_INVALID_COMPARE_ENUM_TO_ENUM`, `MVK_INVALID_UNSPECIFIED_TYPE`.
- **Renamed (3)** — the filter→set-expression family (ties to the grammar `Mit`-filter refactor):
  - `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION` → `MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION`
  - `MVK_INVALID_ITERATION_IN_FILTER_CONDITION` → `MVK_INVALID_ITERATION_IN_SET_EXPRESSION`
  - `MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED` → `MVK_PARALLEL_ITERATION_FOR_PATH_WITH_ASTERISKS_NOT_ALLOWED`
- **Added (2):** `MVK_WILDCARD_AT_LOWEST_REPEATABLE_LEVEL_REQUIRED`, `MVK_NO_ITERATION_FOR_LOWEST_WILDCARD`.
- **⚠ Silent-semantics-drift trap:** `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED` **kept its name but changed meaning** — its old "unrestricted asterisk at lowest repetition" meaning migrated to the new `…REPEATABLE_LEVEL_REQUIRED`; the old name now carries a weaker "lowest level must specify an asterisk" (used by `SumOfTerms`). A ledger keyed only on the name will carry a stale meaning.
- **Also:** `MVK_RULE_TEST_NO_DEFAULT_VALUE` deleted with its `TestRuleErrorType` enum; errortext `FIELD_INVALID_PREFIX` → `FIELD_ERRORTEXT_WRONG`, removed `NO_VORDRUCKNAME_IN_H`.

### a12-base 29.3.0 → 30.0.0 — notification machinery moved out of the kernel

`RankedNotification`, `Severity`, `ConsistencyCategory`, `ConsistencyProblem`, and `Problem` now come from `com.mgmtp.a12.model.notification.*` / `…consistency.*` (a12-base), not kernel-owned implementations; the old model-migration base module was removed. The fetched a12-base source confirms `Severity` is unchanged (`INFO`, `WARNING`, `ERROR`), so our severity mapping needs no semantic change.

---

## 4 · Adapter API breaks (`:adapter` — the real work)

Dominant themes: a kernel-wide **V1-document + runtime-variant retirement**, removal of deprecated extension registries, expansion API removal, and module consolidation. The compile-break surface is broader than production `KernelAdapter`: it includes the shared `RuntimeLaws` oracle and the Kotlin differential tests.

### Certain Java compile breaks

1. **V1 document API removed** — `KernelAdapter` and `RuntimeLaws` build V1 `IDocument` instances; `CustomConditionDiffTest` and `PartialValidationCustomConditionDiffTest` implement the old V1-shaped md custom-condition method. All must move to `DocumentV2`, `DocumentPointer`/`DocumentMultiPointer`, and the V2 condition signature.
2. **`DocumentServiceFactory.createDocumentFactory()` removed** — 31.1.0 exposes only `createDocumentV2Serializer()` + `createDmAwareDocService(String)`.
3. **`createDocumentV1V2Converter()` / `IDocumentV1V2Converter` / `ofDocumentV1` removed** — no V1→V2 bridge remains.
4. **Runtime variant APIs removed** — `DocumentModelService.getMvkServiceForModel(model, variant, reporter)` becomes the two-argument form; `IDocumentModelService.extractUsedVariants` disappears; `getVariant()` overrides fail in `KernelAdapter` and `RuntimeLaws`. This does **not** remove serialized `content.modelInfo.variant`, which still exists in 31.1.0; it removes `variantFilter`, `variantErrorMessages`, and runtime/codegen variant selection.
5. **`IDocumentModelService.expand(model, resolver)` removed** — affected code includes `KernelAdapter`, `KernelConsistencyOracle`, `KernelModelOracle`, `RuntimeLaws`, and `CorpusModelPreparer`. The official migration instruction says to use **A12 Workspace Conversion**; the new workspace converter is `a12internal` and operates on a workspace, while an `a12internal` static `CombinationModelService.expand` utility remains with a new resolver/params/`Optional` contract. Choosing between workspace conversion, our already-parity-locked kernel-free expansion, or another internal dependency is a design decision, not a mechanical method swap.
6. **Deprecated custom-extension services removed** — `PredefinedTypeValidityDiffTest` still uses the static `CustomFieldTypeService`; port it to an `ICustomFieldTypeFactory` passed through `DocumentProcessingConfig`, as newer differential tests already do. The md `ICustomCondition` V1/default overload is gone; its required check method is V2-shaped.

The root fix for items 1–3 is one shared, model-aware **V2 document materializer** used by production evaluation and `RuntimeLaws`. It must create groups/fields via immutable V2 APIs, preserve explicit-empty placement semantics, and convert external values through `createDmAwareDocService(modelId)` so numbers, dates, enums, and custom types have the same internal representation in every route. Two independent rewrites would create a semantic-drift seam. `EvalDocSeamLawsTest`—nested rows, evaluation, computation, empty placement, invalid paths—is the direct canary; `KernelAdapterBootstrapTest` alone does not exercise the document seam.

### Non-breaking but notable

- **`IMVK_Service`** dropped super-interfaces `ICodeGeneratorService` + `IChangeRuleSupport` (both deleted), but every call we make resolves through retained parents → **no break**.
- **`MoveSupportDM.changePathReferencesForMove`** — signature unchanged, but now **also rewrites Document Uniqueness Criteria** on a move (more references rewritten; relevant to `ReferenceRewriter` + `ReferenceRewriteTest`).
- **New set-op AST node** — `ASTVisitor` gained default-bodied `visit(ISetOpOperation)`, so `ConditionConverter`'s anonymous visitor still compiles, but it has **no handling for the new node** → `SumOfTerms` won't round-trip until added.
- **`IDocumentRtService`** removed deprecated V1/`Locale` overloads — we already use the `DocumentProcessingConfig` V2 forms, so runtime *calls* survive.
- **Core custom-condition SPI** (`kernel-core-runtime-api`'s `ICustomCondition`, `IData`, `IIdentifier`) is unchanged despite the 32.3.4→33.0.2 bump, so `KernelCustomConditions` remains source-compatible. The separate **md document-layer** `ICustomCondition` ABI changed to V2 as described above.
- **Computation read surface** (`ICalculationService.extractCalculationInfo`, `ICalculationInfo`, `IWithComparisonOperation`) **fully unchanged**.
- **Dynamic configuration** adds default `modelCodeHashProvider` and `considerNotInD`; neither is a compile break, but both belong in the mandatory customization-surface audit.

---

## 5 · Runtime semantics (RT-ledger + interpreter differential)

`kernel-core-runtime`: ~203 renamed files, **155 pure `_30_8`→`_31_1` churn**, 48 with real body changes. The adapter/interpreter don't import the internal runtime packages, so the rename is not a compile break (one stale doc comment in `RtSemanticsLedgerTest.java:26`).

- **Genuinely new: 1 class** — `SetOperation` (`@FunctionalInterface`), the runtime hook for `SumOfTerms`.
- **Removed:** `ComparisonOperator` enum, `ResultNoTestId`; six date/number utils **relocated** to `kernel-conversion-java` (source of much import churn).
- **⚠ Theme A — date/time reimplemented `java.util.Calendar` → `java.time`** (`RuntimeController`, `BedingungsOperatorHelper`, `FormatDefinitionDatum[Bereich]`). The custom **year/month diff with leap-year / 28-Feb edge cases** was rewritten on `java.time`. **Highest differential-drift risk** — re-run the interpreter's date-arithmetic laws (`../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/eval/DateMath.kt`) against the 31.1.0 oracle.
- **Theme B — behavior-preserving refactor churn:** immutability, German→English renames (`hatVorzeichen`→`signAllowed`, `getJahresinterpretation`→`getInterpretationOfYear`), switch-expression modernization; the number formal-error classification rewrite warrants an equivalence spot-check.
- **`ValidationCommand`** dropped the deprecated `Map<String,ICustomCondition>` validation overloads → callers must use `ICustomConditionFactory`.
- **`kernel-md-runtime-service`:** `CustomConditionWrapper` split into `…MdToVk` + new `…VkToMd`; **`DocumentModelCodeGenerator` removed** — the dynamic Groovy-codegen path was restructured (`DocumentDynamicRtService` now drives compilation via `modelProvider.getGeneratedCodeAccessFactory(id).getCalculator()`). **Flag for interpreter codegen-strategy parity** and any adapter assumption about the codegen entry point.

---

## 6 · Metamodels (`a12-kernel-mm` — schema/constraint oracle)

The same 15 logical metamodel directories remain, but JSON artifacts grow **59 → 75** because eight directories gain current/previous `a12types` variants. Canonical rules are **547 → 544** and computations remain 6. Many model package versions rise (for example Field 13.9→14.7, Group 12.4→13.1, Combination 2.9→2.13, Mapping 4.9→4.15, and SMM field-type checks 2.4→3.2).

- **⚠ MM_Group_1 — include config restructured (highest impact for us).** The metamodel's editor-facing structure calls its nested flag `excludeValidationRules`, but that is **not the serialized DM-JSON key**. Kernel `Group.IncludeConfig` and the migrator serialize `includeConfig: { reference, includeLevel, excludeRules, excludeComputations }`. The old mount `modelAlias` becomes the referenced model ID directly; the matching header `modelReferences[].alias` is deleted or ignored. Constraint validators rename `A12_MODEL_ALIAS_*` → `A12_MODEL_REFERENCE_*`, and `A12_MODEL_ALIAS_DIFFERENT_TYPE_DEFINITION_MODE` disappears.
- **Alias semantics, not just JSON nesting, change.** Our include API and CLI currently treat the persisted alias as a logical handle. The migration must deliberately define `include list/read/rename/set-reference` behavior when kernel identity is now the direct `includeConfig.reference`. A tool-local alias may remain for ergonomics only if its persistence and lookup rules are explicit; it can no longer masquerade as the kernel's stored identity.
- **`Unspecified` is removed** across the field/common/typedef/check metamodels. The 29.1→29.2 migrator converts it to `String` with `_take_reference_configuration`; `INT_INVALID_ATTR_UNSPECIFIED_TYPES` and the related MVK code disappear.
- **`STRING_TYPE_TEMPLATE_REFERENCE_2` and `StringType.templateReference` are removed.** A project source sweep found no production dependency on that capability, so this is a verification item rather than an expected implementation break.
- **CT_SELECTION correction:** the canonical `a12` variant still has the same ten rule names; its validations were **not** removed. The new `none` variant has zero. The actual schema addition is `AutoCompleteProps.allowAddingNewItems`, propagated across selection models, plus new build-variant metadata.
- **Exact metamodel rule removals:** `MM_Common_v2` drops `MVK_UNSPECIFIED_TYPE_NO_CODEGEN`; `MM_SMM_FieldTypeChecks_1` drops `INT_INVALID_ATTR_UNSPECIFIED_TYPES`; `MM_Group_1` has a net −1 as alias validation becomes reference validation. `KernelMmReadCoverageTest`'s `>=545` floor and “of 547” wording will therefore fail and must be re-baselined from executed results.
- **No `SumOfTerms` occurrence exists in the 31.1 metamodel corpus.** The real-corpus sweep cannot serve as coverage for the new operator; dedicated own-domain models and laws are mandatory.

---

## 7 · Cross-cutting

- **Jackson 2 → 3.** The kernel moved to `tools.jackson` (3.1.2); `jackson-annotations` stays 2.x. We keep our **own** Jackson 2 (`com.fasterxml.jackson:2.18.2`) in `core`/`cli` for DM-JSON and exchange only **strings** with the kernel (never `JsonNode` across the boundary) — distinct root packages, so they coexist. **Real cost: native-image reflection metadata** (kernel POJOs now under `tools.jackson.*`), not a boundary break.
- **JDK 21 unchanged** — no toolchain change.
- **Document-model format 28.4.0 → 29.4.0 is mandatory.** Upstream declares both the minimum compatible and latest model version as 29.4.0. Our 65 committed JSON models are all 28.4.0: 14 under `adapter`, 21 under `cli`, 7 under `corpus`, 16 under `examples`, and 7 under root `src`. `core/ModelFormat.VERSION`, inline Java/Kotlin fixtures, version-command captures, and `DocumentModelDraft`'s authored default also carry 28.4.0.
- **The migration chain is substantive:** 28.4→28.5 adds optional `modelInfo.packet`; 28.5→28.6 adds document uniqueness criteria; 28.6→29.0 restructures includes and removes alias indirection; 29.0→29.1 strips `variantFilter` and `variantErrorMessages`; 29.1→29.2 converts `Unspecified` to `String` with `_take_reference_configuration`; 29.2→29.3 adds CDM query-root references; 29.3→29.4 removes field-type annotations.
- **Do not rely on implicit migration at our serializer boundary.** The upstream guide says existing models are migrated automatically, but the public `IDocumentModelSerializer.deserialize` implementation directly maps JSON and does not invoke `IDocumentModelMigrator`; migration is exposed separately through the facade API/CLI. Our direct-deserialization paths must explicitly migrate inputs or require 29.4.0 before deserialization. A 31.1-backed golden must prove migrated JSON preserves every include/reference/exclusion and that old flat include fields cannot be silently dropped.
- **Runtime variant removal is narrower than `modelInfo.variant`.** All 65 models contain `modelInfo.variant`, and the field remains in the 31.1 model API. Only one active fixture contains `variantFilter`; migrate it and retire/reframe `VariantFilterPreservationTest`, the interpreter's null-variant filtering code/tests, and `InterpreterRealModelDiffTest.variantFilterOnlyRuleIsDroppedLikeKernel`.
- **Corpus provenance changes.** The runtime-service coordinate/implementation identity changes, and `CorpusEngines.KERNEL_VERSION` and every committed case still say 30.8.1. Corpus outputs must be regenerated by the corpus capture path (`CorpusGenerator`)—not hand-edited—and reviewed across Groovy dynamic, Java static, and interpreter engines.
- **CI cache keys are part of the pin.** Both CI workflows embed `a12-kernel-30.8.1-base-29.3.0`; leaving them stale can restore a cache with no 31.1 artifacts and make bootstrap behavior misleading.

---

## 8 · rulekit impact map (current detection → gap → required fix)

Several current guardians are intentionally one-way. The upgrade would expose that they detect additions but not stale removals, or scan a hardcoded set that omits a newly-added enum class. Guardian hardening is part of the migration, not optional cleanup.

| surface | current detection / gap | required fix |
|---|---|---|
| model format 28.4→29.4 | `ModelVersionPolicyTest`, `VersionVerbTest`, `VersionMentionFreshnessTest`; no migration golden | migrate all 65 JSON models + inline/default versions through the kernel migrator; add before/after preservation and direct-serializer rejection/loss guards |
| V1 document + removed extension APIs | Java/Kotlin compilation, but the Java `-Xlint:removal` ratchet missed stale Kotlin `CustomFieldTypeService` use | compile main/test Kotlin+Java; add a forbidden-removed-API source guard across every kernel-facing source set; port one shared V2 materializer |
| new `SumOfTerms` construct | `KernelConstructParityTest` hardcodes known enum classes, so new `SetOpOperationName` is invisible; reverse keyword parity may be the only failure | make construct-class discovery/exact inventory exhaustive, then land the complete typed/catalog/read-back/interpreter/differential slice |
| enum-comparison relaxation | `EnumComparabilityLawsTest` currently asserts the old errors fire | flip to accepted laws; update the contradicted [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) finding and catalog gotcha |
| `+` with display-value enum relaxation | N7 invalid examples may assert the old rejection | re-probe and update only the affected invalid example/finding; retain the genuine type-mismatch case |
| filter→set-expression code renames | N7 examples plus source strings | rename catalog, [MVK-LEDGER.md](MVK-LEDGER.md), `FieldListRef`, `CustomConditionRef`, diagnostics, and tests |
| removed/renamed/split MVK codes | `MvkLedgerTest` detects live codes missing from the ledger but not stale ledger codes | make live-kernel ∪ metamodel-code equality exact in both directions, then realign entries and meanings |
| consistency category changes | `SerializerTierParityTest` scans only `BasicDocumentModelConsistencyCategory` and does not cover `DocumentModelCategory` | retain exact Basic coverage and add an exact DocumentModel-category ledger/guardian, including uniqueness criteria and the two retired structural categories |
| removed variant/accessor slots | `SlotParityTest` detects unclassified live accessors but not orphaned classifications | make slot equality bidirectional; remove runtime variant slots while retaining `modelInfo.variant` |
| include/reference restructure | core/modelkit/edit/CLI suites exist; `KernelMmIntegrationTest` is kernel-free and cannot prove 31.1 deserialization/expansion | migrate the full authoring/refactor surface; add a real-kernel deserialize→expand→consistency gate and preservation laws for both exclusion flags and include levels |
| metamodel corpus 59→75 / rules 547→544 | `KernelMmReadCoverageTest` has a `>=545` floor and stale “547” wording | run all 75 files, keep the 15-model canonical chooser explicit, then re-baseline counts and [WORKSPACE-CORPUS-ANALYSIS.md](WORKSPACE-CORPUS-ANALYSIS.md) |
| document uniqueness criteria | `ReferenceRewriteTest` has no criterion fixture | add read/preserve/index/where-used coverage and gate rename/move/delete so criteria cannot retain dangling paths |
| runtime `_31_1`, dates, codegen | RT ledger plus differential suites | reclassify runtime resources; run focused date laws, `KernelJavaBaselineDiffTest`, `JavaStaticPartialBaselineDiffTest`, then the tri-engine fuzz/corpus lanes |
| corpus provenance | `CorpusSchemaGuardTest`, `CorpusReplayTest` | bump identity anchors, regenerate the committed corpus through its authoring tool (`CorpusGenerator`), inspect semantic diffs, replay all three engines |
| source-based guardians | default sibling paths point at the still-30.8.1 worktree | run against an explicit 31.1 tree (`-Da12.kernel.src.dir` / `-Da12.kernel.mm.dir` or equivalent); fail if a requested source root resolves to the wrong revision |
| running version/floor | `KernelVersionTest` is INFO-only by design | choose single 31.1 floor vs explicit dual adapters/fixture sets; re-stamp catalog and distribution/version surfaces |
| native reflection/resources | release-time native gate | recapture first, rebuild, then validate Jackson 3/new kernel types/i18n and run the native no-crash battery |

---

## 9 · Update plan (phased)

### Phase 0 — Prerequisite: `a12-kernel-bootstrap` (sibling repo)

31.1.0 is not in `mavenLocal`; nothing here builds until the bootstrap kit can publish the fetched release and its public-source dependency closure.

- Fetch/review `a12-base` 30.x and `a12-devtools` first. Kernel pins base **30.0.0** while the public base release commit identifies itself as 30.0.1; publishing that source with `-Pversion=30.0.0` must be deliberate and verified. Base now needs build-utilities 0.3.1 and its build still uses the JDK 25 toolchain while emitting Java 21 bytecode.
- In `bootstrap.sh`, move kernel 30.8.1→31.1.0 and base 29.3.0→30.0.0. Remove deleted base projects (`base-parent`, `model-migration-api`) from `BASE_MODULES`.
- Replace the old kernel module list with the exact 31.1 settings inventory: renamed `kernel-internal-*`, runtime `31-1`, `kernel-md-documentmodel`, workspace converter if needed, and no V1 document/service or other deleted projects.
- Rebase or retire `patches/a12-kernel-mm.patch`. Its version patch and build-logic dependencies hardcode old pins and deleted `kernel-md-typed-accessor-gen`; 31 merges the generator/migrator CLIs into `kernel-md-facade`. Update mm runtime API `33.0.2`, runtime `31-1`, base `30.0.0`, and the `a12types` publication path.
- Publish and verify the full facade dependency closure from an empty isolated Gradle/Maven cache, not only the facade POM. The artifact IDs are source-known; this step proves the OSS bootstrap graph actually resolves.

### Phase 1 — Build wiring

Decide the support policy before changing fixtures. **Recommended:** make 31.1/DM 29.4 the single supported floor when this proposal executes. A floor+latest matrix over one source tree is not a free carry-forward: kernel 30 expects model major 28, kernel 31 expects major 29, and the Java APIs/artifact IDs are source-incompatible. Dual support would require separate adapter/source or compatibility layers and version-specific model fixtures; build that only for an explicit consumer requirement.

Then update the root and adapter kernel pins (prefer one source of truth over the current duplicate), distribution label, runtime/custom-field API pins, `ModelFormat.VERSION`, CI/bootstrap cache keys, native Windows smoke expectations, catalog/corpus identity anchors, and version snapshots. Prefer the stable `kernel-md-facade` aggregate coordinate where it supplies the dependency graph; keep any direct internal dependency only when it documents a compile-time source contract the aggregate cannot make public. Drop the V1 document module and repoint `DocumentModel` to `kernel-md-documentmodel`.

Use `:adapter:compileJava`, `:adapter:compileTestJava`, and `:adapter:compileTestKotlin` as the intentional red inventory before implementation. This catches main, oracle, and Kotlin breaks instead of treating `KernelAdapter` as the whole surface.

### Phase 2 — Adapter compile (the core rewrite)

Build one adapter-internal, model-aware V2 materializer and use it from `KernelAdapter` and `RuntimeLaws`. Port both md custom-condition differentials to V2 pointers/documents; replace the static custom-field-type service with per-call factories; remove all `getVariant` overrides and `extractUsedVariants`; update `getMvkServiceForModel`; retain `modelInfo.variant`.

Resolve expansion explicitly. Preferred after the decoupling program: feed the adapter the already-parity-locked kernel-free expansion behind its SPI and keep the upstream workspace/internal converter out of consumer code. If that cannot preserve the kernel combination/include contract, isolate the workspace converter or `CombinationModelService` behind one adapter port and document why the internal dependency remains.

Green `EvalDocSeamLawsTest` first, then `KernelAdapterBootstrapTest`, the affected `RuntimeLaws` consumers, both custom-condition differentials, and `PredefinedTypeValidityDiffTest`. Add a guard covering removed APIs in Java **and Kotlin** kernel-facing source sets.

### Phase 3 — Document-model 29.4 migration + include redesign

Run every committed 28.4 model through the 31.1 migrator API/CLI into a reviewable temporary tree, inspect the structural diff, then update the 65 owned fixtures, all inline fixtures, `ModelFormat.VERSION`, authoring defaults, demos, and version captures. Do not mechanically change only the version string. Add a migration golden containing an include, both exclusion flags, both include levels, an `Unspecified` field, a variant filter, and a field-type annotation; prove the expected 29.4 transformations and round-trip stability.

Redesign include authoring around `includeConfig.reference` with serialized `excludeRules`/`excludeComputations`/`includeLevel`. Sweep `DmKeys`, `ModelReader`, `DocumentModelDraft`/`ModelDocument`, `MountOptions`/`IncludeLevel`, `IncludeEditor`, `StructureRefactor`, safety/where-used code, CLI include/apply/refactor verbs, schemas/help, tests, and user demos. Define whether a CLI logical alias survives separately from kernel persistence. Lock rename vs set-reference, both exclusion flags, SINGLE_RG/MODEL_ROOT, pure load/save preservation, expansion parity, and refactor refusal.

Add document uniqueness criteria to structural read/preserve and reference indexing. Rename/move/delete must either rewrite every criterion path safely or refuse before mutation. Migrate the one `variantFilter` fixture and remove kernel-31 parity claims for a feature the new format strips; any retained legacy input migration belongs at an explicit legacy boundary.

### Phase 4 — Guardian hardening + contract realignment

Make construct, MVK, slot, Basic consistency, and DocumentModel consistency inventories exact in both directions before updating their expected data; add a real-kernel metamodel compatibility gate; require source guardians to identify the revision they scan. Then follow the resulting failures through the §8 map: filter→set-expression code renames, enum/add relaxation-law flips, category/slot removals, RT `_31_1` classification, and `verifiedAgainst`.

Run the 75-file metamodel read sweep and retain an explicit 15-canonical-model result. Re-baseline rule/file counts only from execution. Verify `StringType.templateReference` removal has no production consumer and add `AutoCompleteProps.allowAddingNewItems` to the structure capability decision: read/preserve is required; typed authoring is a separate product choice.

### Phase 5 — `SumOfTerms` + interpreter parity

Land `SumOfTerms` as the atomic typed slice described in §2: typed authoring/navigation, renderer/reference/explanation/usage support, adapter read-back, catalog EN+DE with own-domain valid/invalid examples, interpreter parser/evaluator/relevance, and model-shrinker awareness. Probe both kernel strategies before specifying empty/no-row behavior. Cover multiple rows, arbitrary arithmetic terms, nested repetition scope, forbidden nested/filter/outer iteration, partial validation, and computation use.

Read [interpreter/CLAUDE.md](../interpreter/CLAUDE.md) before this phase and preserve the clean-room boundary: learn behavior from source and the oracle, then write an original Kotlin implementation.

### Phase 6 — Semantic requalification + corpus

Re-run the focused date suites (`DateDifferenceDiffTest`, `DateArithmeticDiffTest`, `DateArithmeticLawsTest`, `DateFunctionsOverDateTimeDiffTest`, `OmittedYearDateDiffTest`) against 31.1.0. Requalify the restructured Java/static codegen path with `KernelJavaBaselineDiffTest` and `JavaStaticPartialBaselineDiffTest`, then the tri-engine fuzz lane.

Re-audit every interpreter customization point on the new kernel—locale, clock/date/timezone, custom conditions, custom field validators, charset, and the new dynamic-config defaults—and every finding with an explicit “re-open on kernel upgrade” trigger: `mandatoryGroup`, multi-select `uniqueValue`, custom-ref error entities, computation rounding, and the SumOfProducts acceptance edge. Record any changed fact in [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) first and lock it in the appropriate law tier.

Bump `CorpusEngines.KERNEL_VERSION`, regenerate the corpus through its authoring tool, review semantic diffs rather than editing JSON by hand, then run schema guards and JVM/JS replays for Groovy dynamic, Java static, and interpreter strategies.

### Phase 7 — Full gate, native image, docs, and release plumbing

At the strategic checkpoint, run the project wrapper's full JVM/KMP suite, fuzz/corpus gates, and [`scripts/verify-demos.sh`](../scripts/verify-demos.sh). Update [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md), [KERNEL-INTEGRATION-NOTES.md](KERNEL-INTEGRATION-NOTES.md), [TESTING-SPEC.md](TESTING-SPEC.md), [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md), API/CLI specs and guide snippets, README/CLAUDE/plugin/version surfaces, examples, and the docs index in the same feature commits that change their contracts.

Before any native build, re-capture native configuration, rebuild, and run the native gate/no-crash battery for Jackson 3, new kernel types, and resources. Re-run the release-repository assembly for 31.1.0 EUPL notices. Once all gates pass, graduate durable contracts to their owning specs and archive/delete this proposal per the documentation lifecycle.

### Completion gates

- No active authored model or fixture remains at 28.4.0; any deliberately retained legacy input is named as a migration test and never reaches the 31.1 serializer un-migrated.
- No V1 document, removed runtime-variant, static custom-extension, deleted module, or `_30_8` runtime API remains in main/test kernel-facing sources.
- Include/reference round trips preserve reference, level, and both exclusion flags; rename/move/delete cannot leave a dangling include or uniqueness-criterion path.
- Construct/MVK/slot/consistency guardians are bidirectionally exact and demonstrably scan the 31.1 source revisions.
- Dynamic kernel, Java-static kernel, interpreter JVM, and interpreter JS agree on the migrated corpus and new `SumOfTerms` laws, except for explicitly reviewed and documented divergences.
- The full build, demos, corpus guards, native gate, version surfaces, and release-notice assembly are green.

---

## 10 · Recommendation & decision points

**Recommended disposition (2026-07-17): DEFER — do not upgrade now.** There is no forcing function (30.8.1 is maintained upstream as `release/2025.06`), and 31.1.0 adds nothing the project needs — one operator (`SumOfTerms`) no consumer is asking for, plus two constraint *relaxations* (§2) that would only make our catalog/laws loosen. **No open gap is blocked on 30.8.1** (checked across every `-GAPS` ledger). Against that near-zero benefit, upgrading now is largely **throwaway work**: the biggest cost is the adapter V1→V2 rewrite (§4), which [KERNEL-DECOUPLING-PROPOSAL.md](KERNEL-DECOUPLING-PROPOSAL.md) already plans as its **Stage 5** — do decoupling first and that rewrite happens once, behind the SPI ports, turning 31.1.0 into a confined `:adapter` change instead of a cross-cutting migration touching 65 kernel-importing files. Deferring also avoids the gratuitous date-arithmetic differential risk (§5, Theme A) taken with no driver.

**Scope qualification from this audit:** decoupling confines the Java kernel-API churn, including the V1→V2 rewrite, but it cannot make the **whole** bump adapter-only. The mandatory DM 28.4→29.4 migration, persisted include/reference redesign, catalog/guardian realignment, `SumOfTerms` interpreter work, corpus recapture, and native metadata remain cross-cutting. That larger true cost strengthens the defer recommendation; it also means the eventual Stage 5 must execute Phases 3–7 here rather than treating the version bump as only an adapter change.

**Suggested sequence:** (1) run the decoupling program to the master gate; (2) then execute this bump as decoupling Stage 5 (V1→V2) + a version bump behind the boundary — this proposal is the ready worklist for that moment, and the build-time guardians (§8) make the realignment mechanical.

**Re-open triggers (upgrade sooner if):** a consumer needs `SumOfTerms` or another 31.x-only capability; decoupling lands (do it immediately as Stage 5 — best timing); a **32.x** release ships (don't let the gap stretch to two annual lines); or a security/correctness fix lands in the 31.x line that 30.8.1 doesn't get (none today).

The three maintainer calls this disposition resolves:

1. **Internal-module strategy.** Keep binding `:adapter` to `kernel-internal-*` (accept the instability the rename declares), or re-plumb onto only the public aggregates (`kernel-md-facade`/`kernel-md-serializer`/`kernel-md-documentmodel`)? — *Recommended:* re-plumb behind the SPI, i.e. let the decoupling program own this; don't decide it standalone. Only relevant once Phase 2 runs.
2. **Timing / scope.** Full migration now, or track 31.1.0 while staying on 30.8.1? — *Recommended:* **defer** (above); when it runs, it spans several sessions with this doc's work-item table as the state carrier.
3. **`SumOfTerms` priority.** Ship in this bump (catalog + interpreter) or catalog-only first? — *Recommended:* ship with the eventual bump; no reason to front-run it while deferred.

## 11 · Open decisions and build-time confirmations

- **Migration boundary:** confirm with the built 31.1 facade that explicit `IDocumentModelMigrator` output for our models is byte/structure-stable after deserialize→serialize, and prove what the direct serializer does with an old flat include. Source shows deserialization itself does not invoke the migrator, so “try the old fixture and see” is a loss-detection test, not the migration plan.
- **Expansion route:** choose the decoupled kernel-free expansion, A12 Workspace Conversion, or the remaining `a12internal` `CombinationModelService`; prove recursive include/type-def behavior and failure atomicity with the same parity fixtures.
- **Version support:** ratify the recommended single 31.1/DM 29.4 floor, or fund separate source/fixture compatibility for 30.8 + 31.1. Do not promise a matrix over one incompatible model/API tree.
- **Include CLI identity:** decide whether the user-facing alias remains as tool-local metadata/locator, is replaced by the direct reference, or is removed. Kernel persistence no longer supplies an alias.
- **Bootstrap provenance:** confirm that publishing the public a12-base 30.0.1 source under the kernel-required 30.0.0 version is reproducible and that the rebuilt mm `a12types`/facade-generator closure resolves from an empty cache.
- **`SumOfTerms` behavior:** measure empty/no-row, partial-validation, and nested-iteration semantics against both dynamic and Java-static kernel strategies before fixing the catalog facets or interpreter law.
- **Runtime drift:** determine whether the `java.time` rewrite, number formal-error refactor, or codegen restructuring produces observable differences in the focused laws and regenerated corpus.
