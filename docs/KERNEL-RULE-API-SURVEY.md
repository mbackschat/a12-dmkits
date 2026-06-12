# Kernel & editor API survey — bidirectional rule modification

> **Status:** comprehensive survey, 2026-06-03, against kernel **`30.8.1`** sources (sibling clones `a12-kernel`, `a12-antlr4-code-editor`). Prompted by the owner declaring **bidirectional rule modification** (text ⇄ Java builder) a project **vision/goal** — recorded in [`PROJECT-PROPOSAL.md`](./PROJECT-PROPOSAL.md) §2/§12/§16. This doc inventories *everything* in the kernel and the editor stack with potential value for that goal, so nothing is rediscovered later.
>
> **Verification legend:** ✅ = verified directly in source during this survey. ◐ = agent-surveyed (three deep parallel sweeps), signature plausible but **re-verify before first use**. Everything here is *reference, not dependency* until a design decision (proposal §17) adopts it.
>
> **Policy frame:** consumption is governed by the tiered **N8** policy (proposal D15): core = public-API-only; tests = internal allowed; an opt-in **Java-only kernel-adapter module** may use the `a12internal` *read* surface under guardrails (boundary conversion, read-only, pin + CI canary, fail-fast coverage).

## 1. The goal and the architecture it implies

```
READ  (text → tree):   DSL text ──IRuleParser.parseConditionNoExpand──▶ ICondition (model-resolved kernel AST)
                                ──converter visitor (extends ASTVisitor)──▶ rulekit AST
WORK  (one tree):      rulekit AST — immutable, typed, catalog-ids + model refs (D14)
                       transform combinators (#1) · explain (F9) · re-express (#2)
WRITE (tree → text):   rulekit renderer ──▶ canonical DSL ──validate──▶ DM-JSON (public write path)
GUARD (round-trip):    kernelFormat(kernelParse(render(ast))) == render(ast)  ·  en↔de translation oracle
```

The kernel supplies the *read* half (parse, format, translate, refactor, analyze) almost entirely; rulekit supplies the **one tree you actually modify** — the kernel AST is effectively immutable (✅ only 14 setters across all `…Impl` classes; trees are constructor-assembled in `ParseTreeCreator`) and its surface is idiosyncratic (`getUsb()`, `isHmodel()`, `parseBedingung*`). Working *on* `ICondition` is the only architecture the sources rule out.

## 2. Tier map

| Tier | Packages | Stability | rulekit may use it in |
|---|---|---|---|
| **Public API** | `kernel-md-model-api`, `kernel-md-facade` (`DocumentModelServiceFactory`, `IDocumentModel`, `IDocumentModelService`, `IDocumentModelSerializer`, `IDocumentModelSearchService`) | contract | core (shipped, polyglot) |
| **`a12internal` API modules** | `kernel-core-tool-api` (interface-only: `IMVK_Service` + sub-interfaces, `IEC`/`IEntity` ado tier), `kernel-core-parsetree-api` (AST interfaces, `@OnlyForUsage`), `kernel-core-facade.a12internal` (`ServiceFactory`), `kernel-md-model.a12internal` (`DocumentModelService`) | none guaranteed; *designed for consumption* (interfaces, `@OnlyForUsage`) | kernel-adapter module, tests |
| **`internal` impl** | `kernel-core-parser.internal` (`ParserService`, `ConditionFormatter`, check layer, grammar, `LexerTerminals_*`), `kernel-md-a12internal` (`Rule`, `Computation` data classes), various `internal.service` | none; implementation detail | tests; adapter only where no API-module equivalent exists |

Key structural fact (✅): **`IMVK_Service` is the composition of focused sub-interfaces** — `IRuleParser` (extends `IRuleConsistencyCheck`), `IConditionFormatter`, `IRuleMetaInformation`, `IRuleCollectionMetaInformation`, `IChangeRuleSupport` (deprecated), all in the interface-only `kernel-core-tool-api`. That module is the *least* internal of the internal tiers — pure contracts, no impl.

## 3. Service bootstrap — the practical entry point

- ✅ **`DocumentModelService.getMvkServiceForModel(DocumentModel, variant[, IProblemReporter])` → `IMVK_Service`** (`kernel-md-model` `…a12internal.services.DocumentModelService.java:393-416`). Internally: `convertToExternal` → `DocumentModelServiceUtils.convert(extDocModel, variant, pr)` → `ElementContainer` → `createServiceConfiguration` → `new MVKService(cfg)`. This bridges directly from the md-tier model rulekit already holds (deserialize + expand, as `RuleValidator` does today) — **no hand-assembly of `IServiceConfiguration` needed**.
- ◐ `IServiceConfiguration` carries `getIEC()`, model/project settings, and **`strictness(): ParserStrictness` (STRICT | LENIENT)** — lenient tolerates incomplete models. Directly relevant to proposal Q10 (incomplete-model authoring) if that's ever revisited.
- ◐ `DocumentModelServiceUtils.A12ModelSpecificSettings` supplies `getLanguageUsedInConditionText()`, `getBaseYear()`, `isFieldRefByShortNameAllowed()` — the same model-config facts rulekit's `ModelIndex` reads publicly.
- ◐ `kernel-core-facade.a12internal.ServiceFactory` constructs each sub-service individually (`getRuleParser`, `getConditionFormatter`, `getRuleMetaInformation`, `getRuleCollectionMetaInformation`, `getCalculationService`, `getDynamicEnumerationService`, `getFieldErrorTextParser`) from an `IServiceConfiguration` — useful if rulekit ever wants one capability without the whole `MVKService`.
- ◐ `ElementContainer` (extends core `IEC`) adds **`getAllRules(): List<IRule>`** and `getAllCalculations()` — enumerate every rule/computation of a model at the parse tier (corpus analysis, bulk re-render, model-wide refactoring).

## 4. Text → AST (`IRuleParser`, a12internal API)

- ✅ `parseAndCheckCondition(IRule, String conditionText, IProblemReporter, ExpandMode)` (on `ParserService`; facade variants `parseConditionExpand`/`parseConditionNoExpand` ✅ on `MVKService`) — **raw text + rule context → `ICondition`**. For foreign rules the `IRule` is already in the bound model, so foreign text → AST is one call.
- ✅ Snippet entry points: `parseBedingungSnippetNoExpand(String, IRule, pm)` → `ICondition`, `parseOperationSnippetNoExpand(String, IRule, pm)` → `IOperation` — parse **fragments** (computation preconditions/operations, partial conditions) without a full rule.
- ◐ `expandCondition(ICondition)` — post-parse expansion step (ExpandMode semantics: expansion of group references into field lists; verify before relying on a mode).
- ✅ Errors arrive via `IProblemReporter` callbacks (MVK-coded), not exceptions.

## 5. The kernel AST (`kernel-core-parsetree-api`, all `@OnlyForUsage`)

- ✅ ~40 node interfaces under `…parsetree.a12internal.api.{condition,operation}`: conditions (`ICompositeCondition`, `IComparisonCondition`, `IEntityListCondition`, `IValueListCondition`, `IEntityListAndValueListCondition`, `IRootGroupCondition`, `IApplicationCondition`, `IDateValidityCondition`, …) and operations (`ICompositeOperation`, `IFieldIdOperation`, `IConstantOperation`, `IEntityListOperation`, `IGroupOperation`, `IOpListOperation`, the date family `IDate{Construction,Extraction,Add,Diff}Operation` + range/time variants, …).
- ✅ **`IEntityId` is model-resolved and semantically rich**: `getFullName()` (resolved path), `getIndices()` with documented constants (`WILDCARD=0`, `ITERATION=-1`, `SEMANTIC_INDEX=-4`, `PARALLEL_ITERATION=-6`), `isField()`, `getCategoryName()` (enum categories), ◐ `getFilterCondition()` (attached `Having` filter), wildcard start/end bounds. The iteration machinery is *explicit in the tree* — exactly what F9 explain and the converter need.
- ◐ **Name enums are a machine-readable operator inventory**: `EntityListConditionName` (~18 members), `EntityListOperationName`, `FieldIdOperationName`, `GroupOperationName`, `ValueListConditionName`, `ValidityConditionName`, `RootGroupConditionName`, `CalcOp`, `ComparisonOperation` (incl. `NE1/NE2/NE5/NE10` tolerance variants, `WM`/`NM` pattern ops), `LogOp`, `DateAdd/DateDiff/DateExtraction/PointInTime/OpList…Name` — each with `getRuntimeName()`. **A second coverage anchor for the catalog parity test** (N3), complementing `LexerTerminals_*`: enum members ↔ catalog entries.
- ✅ **`PathNotationConfig`** (`api/PathNotationConfig.java`): types `RELATIVE | ABSOLUTE | SHORTNAME`, preference lists, presets `ABSPATH`/`SHORTNAME`/`RELATIVE` — and **`DEFAULT_MAYBELOSSY = RELATIVE`**: the kernel's own default path preference is *relative*, independently confirming §18.9's policy choice. The parsed tree records the author's notation per reference — meaning a re-render could *preserve* the original path style, softening the #3 "canonicalization blast radius".
- ◐ Utils: `FindAllAst`/`FindAnyAst` (predicate queries over trees), `FeldIdUtilities` (interpret `IEntityId` indices: wildcard/iteration kind per level, iteration counts), `IndexArt` (index-kind enum).

## 6. Traversal (`ASTVisitor`)

✅ Abstract class, ~86 visit methods, **default-`true` bodies**; entry via `IParseTreeEntity.traverse(visitor)` (push-style; composite nodes traverse children, e.g. `IComparisonCondition` walks both operands). The converter pattern: override covered constructs, trap the rest in `visitGeneral` → fail fast on "unsupported construct". The method list doubles as the #3 coverage checklist.

## 7. AST → text (`IConditionFormatter`, a12internal API)

- ✅ `formatConditionText(ICondition, IRule)` / `formatConditionText(IRule, pr)` — complete unparser (normalizes whitespace, removes redundant brackets).
- ✅ `formatConditionPart(IParseTreeEntity, String ruleFullName)` — render any subtree.
- ✅ **`formatAndTranslateConditionText(IRule[, ICondition], Locale)`** — re-emit in the other keyword language (en↔de). Translation working at all proves full-grammar unparse coverage. **Two oracle uses for rulekit (test tier, N8-legal today):** differential renderer check (`kernelFormat(kernelParse(rulekitRender(ast)))` vs rulekit's canonical text) and DE-dialect validation against the kernel's own translation.
- ✅ `formatConditionSnippet` / `formatOperationSnippet` — fragment-level formatting (computation slots).
- ✅ `stripComments(IRule)` — comments are concrete syntax handled outside the AST (re-render loses them; the residual #3 lossiness).

## 8. Refactoring & modification support

- ✅ **`changeConditionText(IRule, oldFullName, newFullName, pr): String`** (on `MVKService`; interface `IChangeRuleSupport` is ✅ `@Deprecated(since="30.6.0")` — ◐ reportedly moved to a `KernelExtToolFacade`; the methods are still present and implemented in `30.8.1`). **Kernel-native entity-rename over rule text**, preserving the author's path-notation style (relative in-group, absolute otherwise). Plus `changeErrorText` (error-message refs) and `changeConditionTextForDecoration(IRule, Map<old,new>, newRuleFullName, pr)` (bulk rename). Textual replacement only — re-validate afterwards.
- ◐ **`MoveSupportDMInternal`** (`kernel-md-model` internal): `changeComputationAlternativesForMove(…, Map<String,String> entityMoves, Locale)` — updates computation preconditions/operations when entity paths change; `updateRelPathInFieldRef` mutates relative path slots. Used by decoration-model joining.
- ◐ **`RulesAndComputationsAdjusterVisitor`** (`kernel-md-join` internal): the kernel's own worked example of the full modify loop — *parse old condition → map entity refs → re-format → `rule.setErrorCondition(newText)`*. The pattern rulekit's adapter replicates with its own AST in the middle.
- ◐ `DocumentModelService.createRelativePath(absSource, absTarget)` / `resolveRelativePath(absSource, relTarget)` — path-form computation at md tier; a cross-check oracle for rulekit's §18.9 renderer path policy.
- ◐ `EntityPathsRelativizer` (`kernel-core-service` internal) — relative/absolute path management during refactoring.

## 9. Analysis services (the F9/explain arsenal)

- ✅ `getDirectlyReferencedEntities(IRule | ICondition[, pr]): Set<IEntity>` (`IRuleMetaInformation`; md-tier wrapper ✅ on `DocumentModelService`, also for `Computation`) — every field/group a condition references. **F9's "referenced fields" bullet, from the kernel itself** — the cross-check for rulekit's own catalog+model projection.
- ◐ `getEntityInRule(IEC, ruleGroup, referencedEntity): IEntity` — resolve a reference (absolute/relative/short) exactly as the parser does; an oracle for rulekit's path-resolution pre-checks.
- ◐ `referencesIteration(ICondition, IndexArt)` / `referencesIteration(IRule, ICondition): boolean[]` — does/where does a condition iterate (per level). Feeds F9's "whether & why the rule iterates".
- ◐ `IRuleCollectionMetaInformation`: `getMandatoryInformationFromRules(…): IMandatoryInformation` (which fields the rules make mandatory), `getChangesAsSpecifiedByRules(…): IModelChanges` (max-repetition restrictions rules imply), `getApplicationConditionNames` (CustomCondition refs), `getNoValidationLength`, `getDynamicEnumerationStringValues` — whole-rule-set semantic analysis.
- ◐ `ReferencedEntitiesInRule` (`kernel-md-model` internal reduction package): md-tier referenced-entity computation incl. root groups; powers `createReducedDocumentModel(…)` — extract the model subset a rule set needs (interesting for focused LLM context: hand an agent the *reduced* model relevant to one rule).
- ◐ `AnalyseService` (parser internal): construct detection (`RepetitionNotUnique` presence etc.).

## 10. Validation — a faster per-rule path

- ✅ `IRuleConsistencyCheck.hasValidConditionText(IRule, pr): boolean` (+ `hasValidErrorText`, `hasValidCustomErrorEntities` ◐) — **per-rule syntax + semantic validation without whole-model `checkConsistency`**. Today rulekit's F4 loop injects DM-JSON, re-deserializes, expands, and checks the *whole model* per candidate; via the adapter, the inner loop of authoring/CI could validate **one rule in-memory** and keep the public `checkConsistency` as the final, shipped-path gate. (The N7 harness is the first beneficiary — one model conversion, then per-example `hasValidConditionText`.)
- ◐ `DocumentModelService.isValidComputation(computationFullName, DocumentModel, variant, pr)` — the computation-side equivalent (parses parts, checks calculation rule, via `ICalculationService.checkCalculationRule`).
- ✅ The MVK error catalog is machine-readable: ◐ `ParserErrorTypesEnum` (parser internal) enumerates ~100+ `MVK_*` codes — a coverage anchor for rulekit's failure→fix table (map every enum member or explicitly exclude).

## 11. Computations (the second rule kind)

- ◐ `ICalculationService` (`kernel-core-tool-api`): `checkCalculationRule(ICalculationRule, …)` — validate a computation; **`checkForCycle(…): Optional<List<Pair<String,IRefType>>>`** — computation dependency-cycle detection *with the cycle path* (a gotcha rulekit's pre-checks can't see model-locally; F2 validation should surface it).
- ◐ `extractCalculationInfo(ICalculationRule, pr): ICalculationInfo` — the parsed decomposition of a computation (common precondition + alternatives), i.e. the read direction for computations.
- ◐ `ComputationPartsConversionService` (md internal): `ICalculationInfo` → `ComputationPreconditionsAndOperations` text parts via the formatter — the kernel's own computation re-render loop.
- ◐ `IDynamicEnumerationService.checkDynamicEnumerationRule(…)` — recognizes the dynamic-enum rule pattern.
- ✅ Snippet parse/format for computation slots exists at the `IMVK_Service` level (§4, §7) — so computations round-trip slot-by-slot, matching rulekit's slot model (§5 of the proposal).

## 12. Testing rules against sample data (exploratory)

◐ `kernel-core-ontheflytesting`: `IOnTheFlyTestingService.testRule(Collection<IRule>, IOnTheFlyTestingParameter)` — evaluate rules against sample data without a full app, by generating + invoking validator code on the fly (`OnTheFlyTestingValidatorGenerator`, `GeneratedCodeInvoker`, `ResultsCollector`). **Caveats:** entry methods carry `@Deprecated` markers (replacement unclear), and it *does* generate + execute code (heavier than `checkConsistency`; contrast N9/§11's "no compile" stance — acceptable for an opt-in "test this rule on this data" feature, not for the validate step). Potential future rulekit feature: *behavioral* examples in the catalog ("given this document, the rule fires") verified in CI. Needs a dedicated spike before any commitment.

## 13. Machine-readable metadata for the operator catalog

| Source | Tier | What it yields | Catalog use |
|---|---|---|---|
| ✅ `LexerTerminals_{en,de}.java` (parser internal) | internal | keyword string → shared token constant, both languages | the planned N3 parity test (per token constant) |
| ◐ parsetree-api name enums (§5) | a12internal | construct inventory with runtime names | second parity anchor: enum member ↔ catalog entry |
| ◐ `ParserErrorTypesEnum` | internal | ~100+ `MVK_*` codes | failure→fix table coverage check |
| ✅ **web content catalogs** `kernel-core-parser-web/src/main/js/internal/content/{operators,predicates,functions,logicalOperators,constants}.ts` | OSS (EUPL-1.2 OR commercial) | ~120 entries, each `{ "de-DE": {name, description}, "en-US": {name, description} }` — **bilingual names *and* prose semantics** (e.g. `DiffersWithToleranceRange10`: "…true if the two values… differ… greater than 10… only allowed for numbers and amounts") | **cross-check oracle for the catalog's `keywords` and `meaning` fields — both languages.** ⚠️ **License boundary:** EUPL content — *reference/compare only, never copy descriptions verbatim* into rulekit's permissively-licensed catalog (same policy as model fixtures, project CLAUDE.md). Write our own `meaning` texts; diff against these in an opt-in test (like the planned `a12-kernel-mm` integration tests) to catch semantic drift. |
| ◐ `MultiLanguageHandler.ts` | OSS web | aggregates the five catalogs into a validated DE↔EN map (throws on missing pair) | precedent for rulekit's own EN/DE completeness check |

## 14. Model write paths — evaluation

| Path | Tier | Verdict |
|---|---|---|
| **A. DM-JSON surgery** (inject/replace rule element in JSON, re-deserialize, expand, check) | public | ✅ **The shipped path** (current `RuleValidator`; polyglot — the TS twin does the same). Stays the only write mechanism in core. |
| **B. In-memory mutation**: `ExternalDocumentModelSerializerService.getInternDocumentModel(IDocumentModel)` → internal `DocumentModel`; ✅ internal `Rule` is Lombok `@Data` with `private @NonNull String errorCondition` → **`setErrorCondition(String)` exists**; same for `Computation`(+`ComputationAlternative`) ◐ | internal | **Adapter/test tier only.** Useful for fast in-memory loops (validate N candidate rules without N serialize/deserialize cycles) and as the mechanism behind a future "apply this modify to the loaded model" tooling op. Never in core, never in a public signature. |
| C. `betterbuilders` (`RuleBuilder` et al.) | internal | Rejected unchanged (D9): model-structure builders, condition stays an opaque string — adds nothing to AST work. |

Public-tier confirmation ◐: `IRule`/`IComputation`/`IDocumentModel` expose **no setters** — there is *no public in-memory write path*; DM-JSON is it (consistent with D9/N8 as designed).

## 15. TS/editor-side findings (for the TS twin & beyond)

- ✅ **No AST anywhere in the TS stack** (verified hard: the only tree contact is an `EntitySpecEndListener` collecting entity rule contexts for path extraction, then discarded). The editor edits strings; TS round-trip has no kernel shortcut — unchanged conclusion.
- ◐ **`ATNSearchService`** (`kernel-core-parser-web`): grammar-agnostic ANTLR-ATN lookahead — computes the set of legal next tokens at a cursor position; `SuggestionService.getSuggestions(...)` wraps it with kind classification (`TokenKind`: PREDICATE/FUNCTION/OPERATOR/…) and signals "a FIELD/GROUP goes here" while leaving field enumeration to the caller (clean model-awareness seam). **Future rulekit idea:** a "what can legally come next" affordance for LLM authoring — though rulekit's typed AST largely obviates it (the type system *is* the affordance).
- ◐ **`EntityExtraction.ts`** — TS-side resolution of absolute/relative/short field paths against a rule's group: a reference implementation for the TS twin's path logic.
- ◐ **`antlrcodeeditor-build-tool`** + vendored ANTLR fork `@com.mgmtp.a12.kernel.antlr4/antlr4` (4.13.2-3; adds TS typings/runtime methods): turns `.g4` into TS lexer/parser; `kernel-core-parser-web`'s Gradle build copies the kernel grammar in and generates at build time (generated sources not committed). **If the TS twin ever needs to parse** (it shouldn't — render-only), the machinery exists OSS-side.
- ◐ Editor integration contract (if rulekit output ever feeds an editor): string in/out (`value`/`onValueSubmit`), async `fetchExternalErrors(input): Promise<ValidationError[]>` (rulekit's validator slots in directly), `fetchHelpTextSupport → monaco.IMarkdownString[]` (rulekit's catalog could power operator hover help).

## 16. Negative findings (checked, not useful)

- `kernel-rewrite` — OpenRewrite kernel-upgrade recipes, not rule rewriting.
- `kernel-md-typed-accessor-gen` — document-instance accessors (a codegen precedent only; L2b itself was dropped — proposal D7).
- `DocumentModelJoiningService` — deprecated (30.6.0) decoration joining; its visitor patterns (§8) are the useful residue.
- Migration framework (`IDocumentModelMigrator`) — schema version migrations, not rule text.
- General codegen modules (`kernel-core-codegen*` beyond condition) — code emission, not authoring; `CodeGenCreator` is only interesting as proof the AST is complete enough to compile from.
- The editor repo itself ships **no rules grammar and no rule knowledge** — it is a grammar-agnostic Monaco framework; all rules intelligence lives in `kernel-core-parser-web`.

## 17. Consequences for rulekit (decided / proposed)

1. **Bidirectional modification is now a recorded vision/goal** (proposal §2, 2026-06-03); the text→AST read path moves from "Phase 5, need-gated" to **Phase 2 scope** alongside explain/modify — see §16 of the proposal. The *working representation* remains rulekit's AST; the *source of truth* question (Q1) is amended: foreign text becomes a supported **input**, builder/rulekit-AST remains the canonical working form, rendered text + DM-JSON the output.
2. **Adapter shape:** one Java-only module wrapping `getMvkServiceForModel` + the converter visitor + thin façades over format/translate/rename/referenced-entities. Kernel types never escape (N8 guardrails). The deprecation of `IChangeRuleSupport` (since 30.6.0) is exactly the instability N8 prices in — pin + canary; prefer re-render-from-AST over `changeConditionText` where rulekit's AST is available, and reserve the kernel rename for foreign-text bulk refactoring.
3. **Cheap wins to adopt early (test tier, no policy change needed):** the differential render oracle and DE-translation oracle (§7); `hasValidConditionText` to speed up the N7 harness (§10); the name-enum parity anchor (§13).
4. **Catalog authoring:** use the web content catalogs as a *drift-detection oracle* for EN/DE keywords and semantics — opt-in test, EUPL boundary respected (no verbatim copying).
5. **Path policy:** `PathNotationConfig` (incl. `DEFAULT_MAYBELOSSY = RELATIVE`) confirms §18.9; the parsed notation style being available per reference means a future re-render can *preserve* author path style — note for the #3 blast-radius discussion.
6. **Exploratory follow-ups (not committed):** on-the-fly rule testing (§12 — spike needed); reduced-model extraction for focused LLM context (§9); ATN "what-comes-next" affordance (§15).
