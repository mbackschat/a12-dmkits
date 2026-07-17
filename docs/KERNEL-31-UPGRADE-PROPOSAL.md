# Kernel 31.1.0 upgrade — change analysis & update plan (PROPOSAL)

**Status:** PROPOSAL, 2026-07-17. **Recommended disposition: DEFER** — finish current work on 30.8.1 and fold this bump into the kernel-decoupling program (as its V1→V2 Stage 5) rather than run it now as a standalone migration; rationale + re-open triggers in §10. This is the concrete instance of the [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) runbook for the `30.8.1 → 31.1.0` bump: it inventories what upstream changed and lays out the phased realignment for when the bump happens. Nothing here is executed — the kernel checkouts were **fetched, not pulled**, and no pins were bumped.

Sources: `git diff HEAD..origin/main` in the consult-only checkouts `../../a12-kernel` (`f10b920 "Release 2026.06-ext0 - 31.1.0"`) and `../../a12-kernel-mm` (`f7ea179`), `/delombok/` + `build/` excluded, source only. There are **no GitHub release notes** — the repos ship squashed release commits, so the source diff is the only changelog.

## Bottom line

This is a **major-version migration, not a pin bump.** `30.8.1` (2025.06-ext5) → **`31.1.0` (2026.06-ext0)** is a new annual line; the old line is preserved upstream as branch `release/2025.06` (== our current pin). The upgrade carries **hard compile breaks in `:adapter`**, a **kernel module rename/consolidation** (`kernel-*` → `kernel-internal-*` + deletions), a **Jackson 2 → 3 move** inside the kernel, **one new DSL operator** (`SumOfTerms`), **two constraint relaxations that will fail our currently-green locked laws**, and a **metamodel include-config restructure** touching our include-authoring surface. JDK stays **21**.

Effort shape: **multi-session** — it fits the [`run-work-program`](../.claude/skills) protocol. There is also a **prerequisite in a sibling repo**: [`a12-kernel-bootstrap`](../../a12-kernel-bootstrap) must be updated *first*, or 31.1.0 never reaches `mavenLocal` and nothing here builds.

This is exactly the scenario [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) was written for — quiet tolerance at runtime, **loud detection at build time**. Most of the work below is *following the guardian failures* the bump will produce.

---

## 1 · Versions & coordinates

### Version streams

| stream | 30.8.1 (now) | 31.1.0 (new) | where we pin it |
|---|---|---|---|
| `kernelVersion` (main line) | 30.8.1 | **31.1.0** | `build.gradle:49`, `adapter/build.gradle:35` |
| runtime-api (hardcoded) | 32.3.4 | **33.0.2** | `adapter/build.gradle:223` |
| customfieldtype-api (test) | 28.2.0 | **29.0.2** | `adapter/build.gradle:236` |
| a12-base (transitive) | 29.3.0 | **30.0.0** | not pinned directly |
| runtime artifact id | `…runtime-30-8` | `…runtime-31-1` | transitive only — safe |
| Jackson (kernel-internal) | 2.x `com.fasterxml` | **3.1.2 `tools.jackson`** | not pinned directly |

### Module rename / delete / stable map (the backbone of the build-file change)

The kernel renamed most Gradle projects `kernel-*` → `kernel-internal-*` (physical dirs unchanged) and, since publishing is `maven(MavenPublication) { from(components.java) }` with **no artifactId override**, the **published Maven artifactId follows the project name** — so these coordinates change. Verified against the new `settings.gradle` and the publish convention in `buildSrc`.

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

New modules: **`kernel-md-documentmodel`** (holds the `DocumentModel` class + the new migration API), `kernel-md-workspace-converter`.

**Design signal to escalate (see §10):** the `kernel-internal-*` renaming is a deliberate statement that these are *internal/unstable*. We reach into eight of them across the N8 boundary. The maintainer decision is whether to keep binding to `kernel-internal-*` (accepting the instability we already acknowledge) or re-plumb `:adapter` onto only the public aggregates (`kernel-md-facade`, `kernel-md-serializer`, `kernel-md-documentmodel`).

---

## 2 · DSL / operators (catalog + keyword-parity surface)

### One new operator — `SumOfTerms` / `SummeVonTermen`

A new **set operation** (parse-tree `ISetOpOperation`, enum `SetOpOperationName.SumOfTerms("SummeVonTermen")`; lexer terminal `SUMME_VON_TERMEN`).

- **Keywords:** EN `SumOfTerms`, DE `SummeVonTermen`.
- **Syntax / arity:** `SumOfTerms(<iterationGroup*>, <term>)` — arg 1 is a starred repeatable **group** path; arg 2 is an arbitrary numeric **term** evaluated per iteration. Registered as an `einfacheOperation`, so it is usable anywhere a number-valued operation is (rule conditions and computations). **Returns NUMBER.**
- **Semantics:** "for each repetition the value of an iterating term is calculated, then those values are summed." Distinct from `SumOfProducts`, which is hard-wired to the product of exactly two repeatable *fields*; `SumOfTerms` sums *any* term over an explicitly-starred group.
- **Checker constraints** (`CheckSetOperOpImpl` + `CheckSetExpressionUtil`): iteration entity must be a group, repeatable + wildcarded on its lowest level, no semantic index; term must be numeric, may not contain application-conditions / `RepetitionNotUnique` / a `Mit` filter / a nested `SumOfTerms`; the lowest wildcard level **must** be iterated (stricter than the `Mit` filter); no `$` outer iteration inside the term; rejected entirely in H-models.
- **No kernel BA-doc example exists** — the only kernel prose is the parser-web tooltip (which even has a DE-title copy-paste bug: it shows `SummeVonProdukten(...)`). Our catalog example will be our own (license-safe), verified through the tool.

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
- **`DocumentModelCategory`**: **+10** `DOCUMENT_MODEL_UNIQUENESS_CRITERION_*` (fields-empty / not-found / not-required / repeatable / transient / missing-error-text / invalid-error-text / invalid-error-text-parameter / duplicate-name / empty-name), **−2** number min/max (`DOCUMENT_MODEL_NUMBER_{MAX,MIN}_VALUE_EXCEEDED`). Backed by a new `DocumentUniquenessCriteriaRule` and the deletion of `NumberFieldValueLimitRule` — i.e. **the kernel no longer consistency-checks number min/max**, and gained a new "document uniqueness criteria" feature.

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

`RankedNotification`, `Severity`, `ConsistencyCategory`, `ConsistencyProblem`, `Problem` now come from `com.mgmtp.a12.model.notification.*` / `…consistency.*` (a12-base), not the kernel; the kernel deleted its own `RankedNotificationImpl` etc., and `base-model-migration-api` was removed. **Whether the `Severity` enum constant set changed is not observable from the kernel diff** — must be verified against base 30.0.0 (we consume these transitively via `kernel-md-serializer`).

---

## 4 · Adapter API breaks (`:adapter` — the real work)

Dominant themes: a kernel-wide **V1-document + `variant` retirement** and a **module consolidation**. All line numbers are current `adapter/src/main/java/…`.

### Certain Java compile breaks

1. **V1 document API removed** — `KernelAdapter` imports `md.document.api.IDocument` + `…services.IDocumentFactory` (both deleted), used in `prepare()`/`buildInstances()`. The whole "build a V1 `IDocument` via factory, then convert to V2" path is gone → **rewrite to build `DocumentV2` directly** via the `apiV2` builder.
2. **`DocumentServiceFactory.createDocumentFactory()` removed** — 31.1.0 exposes only `createDocumentV2Serializer()` + `createDmAwareDocService(String)`.
3. **`createDocumentV1V2Converter()` / `IDocumentV1V2Converter` / `ofDocumentV1` removed** — no V1→V2 bridge remains.
4. **`DocumentModelService.getMvkServiceForModel(model, variant, reporter)` → 2-arg `(model, reporter)`** — the `String variant` parameter was dropped kernel-wide (also from `convert`, `getCalculationServiceForModel`, `isValidComputation`, `createModelCodeIdentifier`).
5. **`IDocumentModelService.extractUsedVariants(...)` removed** — the whole `variant` concept retired.
6. **`IDocumentModelService.expand(model, resolver)` removed** (3 call sites: `KernelAdapter` ×2, `KernelConsistencyOracle`) — expansion moved to static `CombinationModelService.expand(model, IUnexpandedModelResolver, CombinationModelExpandParams)` returning `Optional<IDocumentModel>`. Different resolver type + params object + Optional return → **a rewrite, not a swap**.

### Non-breaking but notable

- **`IMVK_Service`** dropped super-interfaces `ICodeGeneratorService` + `IChangeRuleSupport` (both deleted), but every call we make resolves through retained parents → **no break**.
- **`MoveSupportDM.changePathReferencesForMove`** — signature unchanged, but now **also rewrites Document Uniqueness Criteria** on a move (more references rewritten; relevant to `ReferenceRewriter` + `ReferenceRewriteTest`).
- **New set-op AST node** — `ASTVisitor` gained default-bodied `visit(ISetOpOperation)`, so `ConditionConverter`'s anonymous visitor still compiles, but it has **no handling for the new node** → `SumOfTerms` won't round-trip until added.
- **`IDocumentRtService`** removed deprecated V1/`Locale` overloads — we already use the `DocumentProcessingConfig` V2 forms, so runtime *calls* survive.
- **Custom-condition SPI** (`ICustomCondition`, `IData`, `IIdentifier`) **unchanged** despite the 32.3.4→33.0.2 bump — the interpreter's SPI impl is safe; only the version literal changes.
- **Computation read surface** (`ICalculationService.extractCalculationInfo`, `ICalculationInfo`, `IWithComparisonOperation`) **fully unchanged**.

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

Changes live in the metamodel DM-JSON (`documentModels/`); `test-mmvalidators/` unchanged. Many mm package version bumps (e.g. `mmField_1` 13.9→14.7, `mmGroup_1` 12.4→13.1, `ctFIELD_TYPE_ATTRIBUTES_1` 2.4→3.1, `mmSmmFieldTypeChecks_1` 2.4→3.2).

- **⚠ MM_Group_1 — include config restructured (highest-impact for us).** The flat fields `modelAlias` / `excludeRules` / `excludeComputations` were replaced by a nested group **`includeConfig`** with fields **`reference`** (was `modelAlias`), **`excludeValidationRules`** (was `excludeRules`), `excludeComputations`. Constraint validators renamed `A12_MODEL_ALIAS_*` → `A12_MODEL_REFERENCE_*`; `A12_MODEL_ALIAS_DIFFERENT_TYPE_DEFINITION_MODE` removed. **This is our include-authoring surface** (`edit/IncludeEditor`, `modelkit/IncludeLevel`, the CLI include verbs, `core/DmKeys`, and fixtures like `cli/.../models/multifile/app/storefront.dm.json`).
- **Removed `STRING_TYPE_TEMPLATE_REFERENCE_2` typedef** (the `Ziffernfolge`/`NumberSequence` predefined-string-pattern capability) — check `modelkit/StringFieldBuilder`/`FieldKind`/`FieldTypeSpec` don't rely on it.
- **CT_SELECTION_V1** — all 8 selection validations removed (content-type is fields-only now); new `AutoCompleteProps` (`allowAddingNewItems`) on selection fields.
- **MM_SMM_FieldTypeChecks_1** — removed `INT_INVALID_ATTR_UNSPECIFIED_TYPES` (the now-removed `Unspecified` type).
- Ubiquitous non-structural: a new `a12types` build variant (packaging, not schema).

---

## 7 · Cross-cutting

- **Jackson 2 → 3.** The kernel moved to `tools.jackson` (3.1.2); `jackson-annotations` stays 2.x. We keep our **own** Jackson 2 (`com.fasterxml.jackson:2.18.2`) in `core`/`cli` for DM-JSON and exchange only **strings** with the kernel (never `JsonNode` across the boundary) — distinct root packages, so they coexist. **Real cost: native-image reflection metadata** (kernel POJOs now under `tools.jackson.*`), not a boundary break.
- **JDK 21 unchanged** — no toolchain change.
- **DM-JSON on-disk compatibility of our fixtures is a build-time verification** (needs 31.1.0 in `mavenLocal`): the new migration framework is for author-defined `modelVersion` steps, not a kernel-format migration, but the include-config restructure (§6) may change how includes serialize.

---

## 8 · rulekit impact map (guardian → what fails → fix)

| surface | guardian that will fire | fix |
|---|---|---|
| new operator `SumOfTerms` | `KernelConstructParityTest`, `KeywordParityTest` | catalog `SumOfTerms` (EN+DE, arity, constraints, own example); add EN/DE keyword rows |
| enum-comparison relaxation | `EnumComparabilityLawsTest` (currently asserts the code *fires*) | flip the laws to "now accepted"; rewrite the [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) enum-comparability finding + catalog gotcha (`operators.json:339`) |
| `+`-with-display-value-enum relaxation | N7 / any invalid-example asserting `MVK_NO_STRING_OR_NUMBER_FOR_ADD` here | re-verify; update example |
| filter→set-expression code renames | `SchemaExamplesGuardTest`/N7 (catalog `invalidExample.error` executed), `MvkLedgerTest` | rename in `operators.json` (~lines 1998/2004/2037), [MVK-LEDGER.md](MVK-LEDGER.md), source strings (`FieldListRef.java`, `CustomConditionRef.java`), tests |
| removed/renamed/split MVK codes | `MvkLedgerTest` | update [MVK-LEDGER.md](MVK-LEDGER.md) rows (removals lines 73/74/115; renames 47/91/166; the 204 split) |
| consistency categories +11/−2 | `SerializerTierParityTest` | classify the new `…UNIQUENESS_CRITERION_*`; retire the two number min/max |
| runtime `_31_1` repackage + `SetOperation` | `RtSemanticsLedgerTest` | re-classify into `_31_1`; add `SetOperation`; note relocated utils |
| date `java.time` reimpl | interpreter differential / `RuntimeLaws` | re-run date-arithmetic laws; fix any drift in `DateMath.kt` |
| `SumOfTerms` evaluation | interpreter differential | clean-room implement in `Operators.kt`/`ExprEval.kt`/`Aggregates.kt` + lock via differential |
| include-config restructure | `KernelMmIntegrationTest` (opt-in) + adapter include tests + round-trips | update `DmKeys`/`IncludeEditor`/modelkit + regenerate fixtures |
| `MoveSupportDM` now rewrites uniqueness criteria | `ReferenceRewriteTest` | extend `ReferenceRewriter` expectations |
| running version vs floor | `KernelVersionTest` (F11, INFO-only) | re-stamp `verifiedAgainst` in `operators.json` |
| native reflection/resources | native gate (release-time) | regenerate all three metadata sources (`tools.jackson`, new kernel types) |

---

## 9 · Update plan (phased)

### Phase 0 — Prerequisite: `a12-kernel-bootstrap` (sibling repo)

31.1.0 is not in `mavenLocal`; nothing here builds until it is. Update the bootstrap kit:
- `bootstrap.sh`: `KERNEL_VERSION 30.8.1 → 31.1.0`, `BASE_VERSION 29.3.0 → 30.0.0`, and the hardcoded **`KERNEL_MODULES`** list (uses old `kernel-*` project names → must become `kernel-internal-*` + the new `kernel-md-documentmodel`, drop deleted ones).
- Re-check `patches/` apply against the restructured tree.
- Publish and confirm `mavenLocal` has `com.mgmtp.a12.kernel:kernel-md-facade:31.1.0` and the `kernel-internal-*` coordinates.

**This is where the two open coordinate questions get answered empirically** (exact published artifactIds; whether the internal modules publish at all).

### Phase 1 — Build wiring

Bump `kernelVersion` (`build.gradle`, `adapter/build.gradle`), the hardcoded `33.0.2`/`29.0.2`, and rename the eight `kernel-*` → `kernel-internal-*` coordinates; repoint `kernel-md-a12internal` → `kernel-md-documentmodel`; drop `kernel-md-document-api`; add `kernel-internal-base-utils` if `IProblemReporter` needs it. Confirm resolution before touching Java.

### Phase 2 — Adapter compile (the core rewrite)

Rewrite `KernelAdapter.prepare()`/`buildInstances()` to build `DocumentV2` directly (V1 `IDocument`/factory/converter gone); reroute model expansion through `CombinationModelService.expand`; drop `variant` everywhere (`getMvkServiceForModel` 2-arg, remove `extractUsedVariants`); source `DocumentModel` from the new module. Green `KernelAdapterBootstrapTest` (N8 canary).

### Phase 3 — Guardian realignment (follow the failures)

Work the §8 table: catalog (`SumOfTerms` + `verifiedAgainst` re-stamp), MVK/consistency ledgers, the filter→set-expression renames across catalog + source + tests, the two relaxation-law flips (+ the contradicted [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) finding), the RT-semantics ledger repackage, `ReferenceRewriter`. Each with red/green TDD; docs travel in the same commit.

### Phase 4 — Interpreter parity

Clean-room `SumOfTerms` (iterate group, evaluate term per row, sum; empty→0 semantics like `SumOfProducts`) + lock via the differential. Re-run the date-arithmetic differential against 31.1.0 (`java.time` reimpl) and fix drift. Confirm the restructured codegen path doesn't break the generator-strategy differential. (Read [interpreter/CLAUDE.md](../interpreter/CLAUDE.md) before any interpreter work — the two #1 rules.)

### Phase 5 — Metamodel-driven structure

Update include-authoring to `includeConfig`/`reference`/`excludeValidationRules` (`DmKeys`, `IncludeEditor`, modelkit, CLI include verbs); regenerate affected fixtures; re-run `KernelMmIntegrationTest`. Verify the `STRING_TYPE_TEMPLATE_REFERENCE_2` removal doesn't strand a `StringFieldBuilder` capability.

### Phase 6 — Native image + release plumbing

Regenerate all three native metadata sources (`tools.jackson`, new kernel types, i18n bundles per [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) row 31); re-run the native gate + no-crash battery. Re-run `../scripts/assemble-release-repo.sh` for the 31.1.0 EUPL notices at next release.

### Phase 7 — Docs & floor

[KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) (enum comparability now contradicted; date semantics), [KERNEL-INTEGRATION-NOTES.md](KERNEL-INTEGRATION-NOTES.md) (module renames, Jackson 3, base 30.0.0), [API-FINDINGS.md](API-FINDINGS.md) if design-worthy; re-stamp `verifiedAgainst`; update [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) if the coupling map gained rows. Consider a CI version matrix (floor + 31.1.0) now that a second version exists (the F11 carry-forward).

---

## 10 · Recommendation & decision points

**Recommended disposition (2026-07-17): DEFER — do not upgrade now.** There is no forcing function (30.8.1 is maintained upstream as `release/2025.06`), and 31.1.0 adds nothing the project needs — one operator (`SumOfTerms`) no consumer is asking for, plus two constraint *relaxations* (§2) that would only make our catalog/laws loosen. **No open gap is blocked on 30.8.1** (checked across every `-GAPS` ledger). Against that near-zero benefit, upgrading now is largely **throwaway work**: the biggest cost is the adapter V1→V2 rewrite (§4), which [KERNEL-DECOUPLING-PROPOSAL.md](KERNEL-DECOUPLING-PROPOSAL.md) already plans as its **Stage 5** — do decoupling first and that rewrite happens once, behind the SPI ports, turning 31.1.0 into a confined `:adapter` change instead of a cross-cutting migration touching 65 kernel-importing files. Deferring also avoids the gratuitous date-arithmetic differential risk (§5, Theme A) taken with no driver.

**Suggested sequence:** (1) finish the in-flight capture program (Set Z2 handback) on 30.8.1; (2) run the decoupling program to the master gate; (3) then execute this bump as decoupling Stage 5 (V1→V2) + a version bump behind the boundary — this proposal is the ready worklist for that moment, and the build-time guardians (§8) make the realignment mechanical.

**Re-open triggers (upgrade sooner if):** a consumer needs `SumOfTerms` or another 31.x-only capability; decoupling lands (do it immediately as Stage 5 — best timing); a **32.x** release ships (don't let the gap stretch to two annual lines); or a security/correctness fix lands in the 31.x line that 30.8.1 doesn't get (none today).

The three maintainer calls this disposition resolves:

1. **Internal-module strategy.** Keep binding `:adapter` to `kernel-internal-*` (accept the instability the rename declares), or re-plumb onto only the public aggregates (`kernel-md-facade`/`kernel-md-serializer`/`kernel-md-documentmodel`)? — *Recommended:* re-plumb behind the SPI, i.e. let the decoupling program own this; don't decide it standalone. Only relevant once Phase 2 runs.
2. **Timing / scope.** Full migration now, or track 31.1.0 while staying on 30.8.1? — *Recommended:* **defer** (above); when it runs, it is a [`run-work-program`](../.claude/skills).
3. **`SumOfTerms` priority.** Ship in this bump (catalog + interpreter) or catalog-only first? — *Recommended:* ship with the eventual bump; no reason to front-run it while deferred.

## 11 · Open questions needing build-time confirmation (can't verify without 31.1.0 in `mavenLocal`)

- Exact published artifactIds for the `kernel-internal-*` modules (and whether they publish externally at all) — resolved by Phase 0.
- Whether our committed DM-JSON **fixtures still load** under 31.1.0 (esp. includes after the `includeConfig` restructure) — does the kernel accept old-shape, auto-migrate, or reject?
- Whether the a12-base 30.0.0 **`Severity` enum constant set** changed (consumed transitively).
- Whether the `java.time` date reimpl produces any **observable differential drift** on our date laws.
