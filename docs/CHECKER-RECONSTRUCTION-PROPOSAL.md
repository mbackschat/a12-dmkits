# Checker reconstruction — proposal

> **What this is.** The design for rebuilding the portable checker (`:consistency`) along A12 Kernel `30.8.1`'s own check structure, replacing its one-family-per-class implementation. It holds the model the rebuild follows and the rebuild's design; **the state lives in [CONFORMANCE-PLAN](CONFORMANCE-PLAN.md)'s work-item table** (items R0–R5), never here. Opened 2026-09-26.
>
> **Status of the model.** §1 and the appendices are **source-read**: the kernel's `ParserService`, the grammar `GrammarOnly_ConditionParser.g4`, the generated parser (ANTLR 4.13.2), `AbstractDefaultErrorStrategy`, the `Check*Impl` classes and the consistency-rule provider, all under `../a12-kernel`. **§2's parse model is confirmed on A12 itself** by plan item R2's trace over 9 063 conditions ([KF493](KERNEL-FINDINGS.md#kf493)), which corrected one sentence of it, on predicate timing. Decisions the population never failed at stay source-read.

## Why

A12 checks a model in one deterministic sequence, and the first failure of a condition ends that condition's check. The checker instead infers that sequence: a shape parser deliberately looser than A12's grammar, an incompleteness heuristic, about a dozen token scanners that each re-derive a piece of the structure, roughly 130 families that each own one class or class group, and a report assembly that orders the families' findings by "raise points" and withholds some behind others. Each inference is local, so each fix meets the next neighbouring case. The widened sweep showed the cost: 764 inputs of one end-of-input mechanism that no family models, because none of them knows which construct the parser is in ([CONFORMANCE-PLAN](CONFORMANCE-PLAN.md) results, 2026-09-26).

Reproducing the sequence removes the inference. The checker's code then has A12's shape: a stage list, a parser with A12's event order, check actions at the points A12 runs them. A finding's class and its order then follow from the structure, not from tables.

## 1. A12's check structure

| level | what runs | order and stopping |
|---|---|---|
| model | `DocumentModelConsistencyRulesProvider`: schema-version pattern, schema version, id uniqueness, number-field value limits, attachment groups, multi-select groups, **code generation**, the serializer's basic check | all eight run, in this order |
| code generation (`CodeGenerationRule` → `generateValidationCodeKernelInternal`) | the element container for the **default variant** (`DocumentModelServiceUtils.convert`); then every computation (`CalculationService.checkAndExtractAllCalculationInfo`); then every rule (`CodeGeneratorService` → `ValidationCodeGenerator.preCheck` → `RuleInfoBuilder.parseCondition`) | all computations, then all rules; how a failing rule affects the next is for R0 to settle |
| one condition | pre-parse, parse, post-parse (§2) | the first failure ends the condition |
| a message's parameters | `RuleParser.hasValidErrorText`: when some message contains `$`, the condition is parsed again first, and any problem there becomes `INVALID_CONDITION` for the message; the parameters are then parsed by a separate grammar with the same error strategy | per message |

A computation is checked through the same entry as a rule: `ComputationAlternativesJoiner` joins its preamble, field gate, common precondition and per-alternative `(precondition) AND [computed] != <operation>` into one condition text, and `parseAndCheckCondition` parses that text with the post-parse checks included. Positions are relative to the joined text. The snippet entries (`checkSnippet`, `bedingungSnippet`, `operationSnippet`) are used only by `isValidComputation`, outside `checkConsistency`.

## 2. One condition

### 2.1 Three stages

| stage | what runs | how it fails |
|---|---|---|
| pre-parse | `checkErrorFieldSetAndValid` | `MVK_MISSING_ERROR_FIELD`, then `MVK_ERROR_FIELD_NOT_VALID` |
| parse | the strict parser `FullChecks_ConditionParser` for every consistency path; lexing on demand, prediction, matching, check actions (§2.2–§2.4) | the first thrown `MVKParserError` ends the parse: no `Check*Impl` collects |
| post-parse (`checkNormalRule`) | 11 checks in a fixed order (Appendix A.3) | each throws, except `IterationChecker` (step 8), which reports what it found and then stops |

### 2.2 The parse stage's event order

ANTLR interleaves three kinds of event, left to right:

- **a token is lexed** when the parser or a prediction first asks for it, so a lexical error surfaces exactly when a look-ahead or a match reaches it ([KF311](KERNEL-FINDINGS.md#kf311));
- **a decision predicts**, reading as many tokens as it needs, **without running any action**;
- **a rule completes**, and its check action runs: post-order, children before parents, left to right (57 action sites, Appendix A.1). A parent's check reads the result types its children's checks recorded, so type inference happens inside the check actions.

So a check whose rule completed before the parser reached a defect wins over that defect, and a defect that a prediction reaches before a rule completes wins over that rule's check.

### 2.3 A syntax failure's class is (exception kind) × (end of text or not)

From `AbstractDefaultErrorStrategy.reportError`:

| exception | thrown by | offending token is EOF | a real token |
|---|---|---|---|
| `InputMismatchException` | a failed single-token or set match (`recoverInline`) | `MVK_EXPECTED_TOKEN_NOT_FOUND` | `MVK_UNEXPECTED_TOKEN` |
| `NoViableAltException` | a decision no alternative survives, or an LL(1) switch default | `MVK_INCOMPLETE_INPUT` | `MVK_UNEXPECTED_TOKEN` |
| anything else (an in-body failed predicate) | — | `MVK_COMMON_PARSER_ERROR` | same |

`sync` does nothing and `recoverInline` never inserts or deletes a token, so an optional block or a loop that does not see its token simply exits, and the failure surfaces at the next match or decision. The lexer's own error is `MVK_LEXER_STANDARD_ERROR`.

### 2.4 Which construct fails, and at which token

The generated parser has 84 decisions: 52 are LL(1) (32 optional `if`, 8 loop `while`, 12 `switch`) and 32 are adaptive predictions. Beside them sit 209 single-token matches and 24 set matches.

| construct | fails as | offending token |
|---|---|---|
| single-token or set match | `InputMismatch` | the token under the cursor |
| LL(1) switch | `NoViableAlt` | the token under the cursor |
| LL(1) optional or loop | never; it exits | — |
| adaptive decision whose alternatives share **no** prefix (16: ANTLR chose adaptive prediction only because a model predicate is reachable) | `NoViableAlt` | the decision's first token |
| adaptive decision whose alternatives **share** a prefix (16) | `NoViableAlt` | the first token no alternative survives, possibly many tokens ahead |

Two runtime rules shape the last row. **An alternative that has already completed the decision's rule wins** when nothing survives the next token, so the failure moves to whatever follows that rule, as a match or a later decision. And **prediction runs as if every predicate held**: a model predicate is evaluated only where the prediction resolves. So the entity spec's H-model path form stays alive through any path it could spell, and the decision fails wherever neither form continues, measured up to nine tokens past its start. *(Corrected by the trace, 2026-09-26: this read "predicates are applied where prediction starts".)* The overlap decisions an A12 model can reach, with their windows, are in Appendix B.

## 3. What the model already explains

| finding | as a consequence of |
|---|---|
| [KF300](KERNEL-FINDINGS.md#kf300), the four phase codes | §2.3 plus the lexer's own error |
| [KF311](KERNEL-FINDINGS.md#kf311), the earlier failure wins | §2.2, lexing on demand |
| [KF491](KERNEL-FINDINGS.md#kf491), walked against unwalked paths | Appendix B, the entity-spec window: while it predicts, no segment check has run; `$` then a name, a leading climb or a star directly after a name ends the window, and only then do the segment checks run. Confirmed by the trace: `FieldFilled(Items*/NoSuch.)` fails at `)` before `NoSuch` is checked, where KF491's earlier "resolved segment by segment as it is read" predicted `MVK_INVALID_ENTITY` |
| [KF492](KERNEL-FINDINGS.md#kf492), typed argument slots | §2.4: a place count or a day keyword is a match, an operation slot a decision |
| DG83, a message class beside a refused condition | §1, the message's parameters: a refused condition becomes `INVALID_CONDITION` for the message |
| DG117 and DG118, operation types | §2.2: every operation's type is computed bottom-up by the check actions, so a comparison and an extremum see the type of an arithmetic side |
| DG114, a field an `ONLY` variant filter deletes | §1: the element container is built for the default variant before any check |
| the widened sweep's end-of-input clusters | §2.3 × §2.4: `FieldFilled(` and `[` end in a decision, `FieldFilled` and `RoundUpValue(X,` in a match |

## 4. The trace (plan item R2)

A12 keeps nothing of the recognition exception: `MVKParserError` carries a message and a span, no cause, and the parse tree is discarded after a failure. So the trace captures at the moment of failure. `ParserService.checkSnippet(rule, text, grammarRule, reporter, parserInit)` is public; its `parserInit` hook runs after the kernel installs its error strategy and before parsing, so a test can install a subclass of `MVKDefaultErrorStrategy` that records the exception and then calls `super.reportError`, which leaves the production behaviour unchanged. With `Lenient_ConditionParser::bedingung_start` it is the same parser class, lexer, strictness and entry rule as `checkConsistency`, without the post-parse checks. Record the exception kind, the ATN decision (`atn.states[e.offendingState]` as a `DecisionState`), the start and offending tokens, and `getRuleInvocationStack(e.ctx)`; add a lexer error listener for lexical errors. A semantic failure is an `MVKParserError` with no recorded recognition exception.

**Result (2026-09-26):** built as `adapter.A12ParseTraceTest` in the sweep's lane; no signature fell outside the model, and the trace agreed with `checkConsistency` on every input ([KF493](KERNEL-FINDINGS.md#kf493)).

The instrument lives in `:adapter`'s tests (the parser and ANTLR are already on that compile classpath through `kernel-md-model`), in package `io.github.mbackschat.a12.dm.adapter` to reach `KernelModelPreparation.expand`, and gets an `MVKService` the way `KernelAdapter.buildService` does. It runs the whole near-miss population and groups the parse-stage refusals by signature. **Acceptance:** every signature is predicted by §2.3–§2.4 and Appendix B; a signature the model does not predict is a model defect, fixed in this document before any parser code is written. The batch-S seed rows (Appendix C) are part of its input.

## 5. The rebuild (plan items R3–R5)

- **One parser for the A12 (non-H) language, producing one tree.** Its structure follows A12's constructs, with our own decomposition and names; the language is matched, the grammar file's expression is never copied (clean-room, the EUPL boundary).
- **Decisions by kind** (§2.4): LL(1) choices by one token; the overlap decisions with the windows of Appendix B; the completed-rule fallback; predicates only at a decision's start.
- **Check actions at rule completion**, one per A12 action site that an A12 model reaches, each raising its codes in A12's order (Appendix A.2), types propagating bottom-up.
- **Post-parse in A12's fixed order** (Appendix A.3), then the stages above the condition in §1's order.
- **The report is the stage stream.** Raise points, the positioned-code tables and the withholding rules retire; a class A12 would not reach is never computed.
- **Deletion travels with replacement.** The landing that makes a family, a scanner or a heuristic redundant deletes it and its tests in the same commit.
- **What R3 deletes, from the test inventory of 2026-09-26.** Six test files exercise the syntax layer's internals and go with it: `consistency.syntax.ConditionCallTest`, `ConditionComparisonTest` and `ConditionReferenceTest`; `consistency.ConditionShapeTest` and `ConditionCompletenessTest`; and `adapter.laws.CheckerParserCorpusSweepTest`. The last carries one property the rebuild keeps: the parser accepts every stored condition and operation of the local reference corpora in its model's language. Tests that go through the public report API, 192 files, stay unless the families they test are deleted. Method-level divergence locks in 42 files become ledger rows once T1 has moved their inputs into the population. No test was redundant with the sweep, because none of their inputs is in its population yet.
- **Non-goals:** the H-model arm (the checker reads A12 models only), the lenient parser, and code generation itself.
- **Acceptance:** the sweep ledger's parse-stage clusters are empty, with no over-rejection, on JVM and JS; Appendix C's rows are predicted; the old syntax layer and every family the rebuilt stage covers are gone.

## Appendix A — check actions (source-read)

### A.1 Where they sit

Actions run at the end of their rule's only alternative unless noted. `bedingung_start`, `teilBedingung`, `geklammerteBedingung`, `einfacheBedingung`, **`applikationsBedingung`** (so it is never checked), `entityListe`, `entity`, `vglOp`, `logOp` and the list sub-rules carry no action. Predicates: `isHModel` at six places, `isA12Model` on the entity-spec path alternative, `isGroupFromHModel` in the H entity loop.

| rule | action | note |
|---|---|---|
| `preamble` | `checkPreamble` | completes first, so warning suppression is registered before any other check |
| `bedingung` | `checkBedingung` | **mid-rule**, inside the inner connective loop: runs for the 3rd, 4th, … part, never with one or two parts |
| the list conditions (`feldListenBedingung`, `kontextListenBedingung`, `feldListeUndWerteListenBedingung`, `fieldAndValueListCondition`, `feldUndWerteListenBedingung`) | one action each | rule end |
| `dateValidityCondition`, `predefinedTypeValidityCondition` | one each | rule end |
| `vergleichsBedingung` | `checkVergleichsBedingung` | rule end; `vglOp` has none |
| `wiederholungBedingung` | `checkWiederholungBedingung` | rule end |
| `operation` | `checkOperation` ×5 | end of each alternative; a binary node's runs after its right operand, bottom-up: for `a+b*c`, a, b, c, `*`, `+` |
| `einfacheOperation` | `checkEinfacheOperation` | type propagation only |
| the operation rules (`feldWertOp`, `feldListenOp`, `feldListenOperOp`, `feldOp`, `feldOperOp`, `rundenOp`, `rundenOperOp`, `feldListenAnzahlOp`, `anzahlWertOp`, `feldOpMitParams`) | one each | rule end |
| constants (`konstante`, `konstanteReferenzjahr`) | one each | `checkKonstante` only records the type; a date-shaped string gets a date type |
| the date rules (`datumDiffOp`, `datumAddOp`, `datumExtractOp`, `datumKonstrukt`, `timeKonstrukt`, `dateTimeKonstrukt`, `datumBereichKonstrukt`, `datumBereichOp`, `datumAngabe`, `feldOpMitDatumSpecial`) | one each | rule end |
| `entitySpec` | `checkEntitySpec` ×3 | end of each alternative; the path alternative also runs `initFilterCondition` right after `Mit` (raising `MVK_NO_GROUPS_ALLOWED` before the filter is parsed and switching the error entity to the filtered field) and `resetFilterCondition` after it |
| `regelKontextEntity` | `checkRegelKontextEntity` | rule end |
| `relativeUp`, `relUp` | one each | `relUp`'s runs first |
| `idNameRef` | `checkIdNameRef` | **once per path segment** |
| `semanticIndex` | `checkSemanticIndex` | after the nested entity spec's check, before the enclosing one's |
| `formularBedingung` | `checkFormularBedingung`; its name sub-rule's `checkFormularBedingungName` runs **before** the arguments are parsed | |
| `groupOp` | `checkGroupOp` | |
| H-model rules | seven actions | reachable only behind `isHModel`, except `anzahlAngegebenOpHModel`, which is not gated and so draws an error in an A12 model |

### A.2 What each action raises, in order

"Common" gates: field expected → `NO_GROUPS_ALLOWED`; group expected → `NO_FIELDS_ALLOWED`; no star → `NO_WILDCARDS_ALLOWED`; no category → `CATEGORY_NOT_ALLOWED`; value validation → `INVALID_RAW_TYPE`; duplicate operand → `DUPLICATE_PARAM1`/`2`; root group → `ROOT_GROUP_REFERENCED(_AS_GROUP)`. All codes are `MVK_`-prefixed.

| action | codes (A12 arm) |
|---|---|
| `checkPreamble` | `INVALID_SUPPRESSED_WARNING` unless the name is `MVK_INVALID_COMPARE_DEC_PLACES` |
| `checkBedingung` | `BRACKET_MISSING` (the first connective against the current one) |
| `checkFeldListenBedingung` | always first: duplicates → root group → category. Then per operator: the one-field fill checks `PARAMSIZE_INVALID1`, `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`; the all/together/not-all family `PARAMSIZE_INVALIDN`, `INVALID_USB_WILDCARD`; exactly-one/more-than-one `PARAMSIZE_INVALIDN`; date-range overlap `NO_GROUPS_ALLOWED`, `PARAMSIZE_INVALIDFN`, `NO_DATE_RANGE`, `INVALID_DATE_RANGE_FORMAT`, `DATE_WITH_AND_WITHOUT_YEAR`; values-not-unique `PARAMSIZE_INVALIDN`, `ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED`, `VARYING_TYPES_NOT_ALLOWED`, `INVALID_RAW_TYPE` |
| `checkKontextListenBedingung` | duplicates → category. At-least/none-of: `ROOT_GROUP_WITH_OTHER_PARAMETERS`; group (not) filled: `PARAMSIZE_INVALID1`, `NO_WILDCARDS_ALLOWED`, `NO_FIELDS_ALLOWED`; the all/together family: `ROOT_GROUP_REFERENCED`, `PARAMSIZE_INVALID2`, `NO_WILDCARDS_G_ALLOWED`, `INVALID_USB_WILDCARD` |
| `checkFeldListeUndWerteListenBedingung` | duplicates, root group, `VARYING_TYPES_NOT_ALLOWED`; then `INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON` (the date-range operator takes one field without star) or `ONLY_STRING_ENUM_NUMBER_ALLOWED`; `INVALID_RAW_TYPE`; then the value list — strings: `NO_CONST` / `INVALID_TYPES_FOR_COMPARISON` / `INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY`; entities: duplicates, root group, varying types, raw type, and for the date-range operator `NO_DATE_RANGE`, `INVALID_DATE_RANGE_FORMAT`, `DATE_WITH_AND_WITHOUT_YEAR` |
| `checkFieldAndValueListCondition` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `TABLE_ENUM_EXPECTED`, `DEPENDENT_COLUMN_SIZE_MISMATCH`; per parameter `NO_GROUPS_ALLOWED` / `NO_WILDCARDS_ALLOWED` / `ONLY_ENUMS_ALLOWED`; per value, with a table: `STRING_VALUE_NOT_IN_COLUMN` / `ENUM_VALUES_NOT_IN_COLUMN` |
| `checkFeldUndWerteListenBedingung` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `ONLY_STRING_ENUM_NUMBER_ALLOWED`, `INVALID_RAW_TYPE`; a field list: duplicates, `VARYING_TYPES_NOT_ALLOWED`, `INVALID_RAW_TYPE`; constants (skipped for a table enumeration without a table): `INVALID_TYPES_FOR_COMPARISON`, then `INVALID_STRING_CONSTANT_FOR_ENUMERATION(_CATEGORY)` |
| `checkDateValidityCondition` | `ONLY_CONSTANTS_USED` |
| `checkPredefinedTypeValidityCondition` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `INVALID_RAW_TYPE`, `NO_STRING_OR_ENUM_OR_EXT_ENUM`, `NO_EMPTY` |
| `checkVergleichsBedingung` | 1 `INVALID_COMPARE_TO_CONST` (two constants, unless a date-time against exactly one `Today`/`Now`); 2 tolerance: `INVALID_TYPE_FOR_TOLERANCE_OP`, stop; 3 pattern: `INVALID_PARAMETER_FOR_PATTERN_COMPARISON` (the left side must be `[field]`), `INVALID_TYPE_FOR_PATTERN_COMPARISON` (left, then right), `MISSING_CONST_IN_PATTERN_COMP`, `INVALID_PATTERN`, stop; 4 ordering: `INVALID_TYPE_FOR_COMPARISON`; 5 `INVALID_COMPARE_TO_YES`, `INVALID_COMPARE_TO_YESNO`; 6 `INVALID_COMPARE_TO_DATE`; 7 `INVALID_COMPARE_TO_DATE_RANGE`; 8 string/enumeration, both directions: `INVALID_COMPARE_TO_ENUM_OR_STRING`, `INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON`, `INVALID_COMPARE_ENUM_TO_STRING`, `INVALID_COMPARE_ENUM_TO_ENUM`; 9 number: `INCONSISTENT_TYPES_COMPARED`, and for `==`/`!=` `INVALID_COMPARE_DEC_PLACES` unless suppressed; 10 base year against a boolean constant or a base year: `INCONSISTENT_TYPES_COMPARED` |
| `checkWiederholungBedingung` | duplicates, `CATEGORY_NOT_ALLOWED`, `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `SEMANTIC_INDEX_NOT_ALLOWED`, `INVALID_RAW_TYPE`; with a `Von` group `NO_FIELDS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `SEMANTIC_INDEX_NOT_ALLOWED`, `GROUP_NOT_REPEATABLE`, `NOT_IN_GROUP`; without one `NOT_IN_SAME_PATH`, `NOT_IN_RULEGROUP`, `REPEATABLE_GROUP_MISSING` |
| `checkOperation` | a primary: none (moves the type up). A binary node: `NO_NUMBER_FOR_CALC` (`^ * / -`), `INVALID_NUMBER_FOR_EXP`, `NO_STRING_OR_NUMBER_FOR_ADD` (`+` takes number+number or string+string, never an enumeration with display values); then two subtree walks: `TOO_MANY_DIV_FOR_CALC` (one `/` per chain, reset at `+ - ^` and brackets) and `TOO_MANY_POW_FOR_CALC` (an unbracketed `(a^b)^c`) |
| `checkFeldWertOp` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `INVALID_RAW_TYPE`, `INVALID_DATE_TYPE` (a partly known date) |
| `checkFeldListenOp` | duplicates, root group; `SumOfProducts`: `NO_GROUPS_ALLOWED`, `PARAMSIZE_INVALID_2`, `DIFFERENT_Z_MULT`, `DIFFERENT_GROUPS`, `REPEATABLE_LEVEL_REQUIRED` / `WILDCARD_AT_LOWEST_LEVEL_REQUIRED` / `WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED`, `INVALID_PARAMS_FOR_OP`; the others `PARAMSIZE_INVALIDN`; then `FirstFilledValue`: `NO_BOOLY_ALLOWED`, `VARYING_TYPES_NOT_ALLOWED`; `MaxValue`/`MinValue`: `DATE_AND_NONDATE` / `PARTIAL_DATE_NOT_ALLOWED` / `DATEFORMATS_NOT_COMPATIBLE`, or `NOT_SORTABLE`; `Sum`(`OfProducts`): `NO_NUMBER` |
| `checkFeldListenOperOp` (`Max`, `Min`) | `TOO_MANY_CONST_FOR_CALC_OP`; then, when the first operand is a date, `DATE_AND_NONDATE` / `PARTIAL_DATE_NOT_ALLOWED` / `DATEFORMATS_NOT_COMPATIBLE`, else `NOT_SORTABLE` |
| `checkFeldOp` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`; `AbsValue`: `CATEGORY_NOT_ALLOWED`, `NO_NUMBER`; `Length`: `CATEGORY_NOT_ALLOWED`, `NO_STRING`; `FieldValueAsNumber`: `INVALID_TABLE_ENUM`, `NO_CONVERT_IN_NUMBER`, `INVALID_RAW_TYPE`; `FieldValueAsString`: `CATEGORY_NOT_ALLOWED`, `NO_NUMBER` |
| `checkFeldOperOp` | `NO_CONST`, `NO_NUMBER` |
| `checkRundenOp` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `CATEGORY_NOT_ALLOWED`, `NO_NUMBER`, `INVALID_DIGIT_FOR_ROUNDING` |
| `checkRundenOperOp` | `NO_CONST`, `NO_NUMBER`, `INVALID_DIGIT_FOR_ROUNDING` |
| `checkFeldListenAnzahlOp` | `CATEGORY_NOT_ALLOWED` **before** the duplicate check, duplicates; `NumberOfFilledGroups`: `ROOT_GROUP_WITH_OTHER_PARAMETERS`, `SEMANTIC_INDEX_NOT_ALLOWED`, `NO_FIELDS_ALLOWED`, `PARAMSIZE_INVALIDGN`, `NO_Z_ITERATION`; `NumberOfFilledFields`: `ROOT_GROUP_REFERENCED`, `PARAMSIZE_INVALIDN`; `NumberOfDifferentValues`: `ROOT_GROUP_REFERENCED`, `PARAMSIZE_INVALIDN`, then one of `STRING_ENUM_AND_NON_STRING_ENUM` + `INVALID_RAW_TYPE` / `NUMBER_AND_NON_NUMBER` / the date codes / `ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED` |
| `checkAnzahlWertOp` | duplicates, `PARAMSIZE_INVALIDN`, `ROOT_GROUP_REFERENCED`; by constant — false: `NO_TYPEYESNO`, `DIFFERENT_NO_VALUE`; true: `NO_TYPE_YES_OR_YESNO`, `DIFFERENT_YES_VALUE`; a string: `NO_STRING_OR_ENUM`, `INVALID_STRING_CONSTANT_FOR_ENUMERATION(_CATEGORY)`, `INVALID_RAW_TYPE`, `NO_DATE_CONSTANT`; a number: `NO_NUMBER`; otherwise `INVALID_CONST` |
| `checkFeldOpMitParams` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `CATEGORY_NOT_ALLOWED`, `INVALID_INTERVAL`, `INVALID_TYPE`, `INVALID_RAW_TYPE` |
| `checkKonstanteReferenzjahr` | `NO_BASE_YEAR` |
| `checkDatumDiffOp` | `INVALID_DATE_TYPE` (both operands) → `WRONG_DATE_FORMAT_FOR_OP` (both) → `INVALID_TIME` → `WRONG_DATE_FORMAT_FOR_OP` (years/months with a time component) → `DATE_WITH_AND_WITHOUT_YEAR` |
| `checkDatumAddOp` | `INVALID_DATE_TYPE` → `WRONG_DATE_FORMAT_FOR_OP` → `INVALID_TIME` → `WRONG_DATE_FORMAT_FOR_OP` → `NON_NUMERIC` (the amount) |
| `checkDatumExtractOp` | from a date-time: `WRONG_DATE_FORMAT_FOR_OP`; the others: `INVALID_DATE_TYPE`, `NO_CONST` (a string literal), `WRONG_DATE_FORMAT_FOR_OP` |
| `checkDatumKonstrukt`, `checkTimeKonstrukt` | day → month → year (hours → minutes → seconds): a constant out of range `WRONG_DATE_FORMAT_FOR_OP` (day 1–31, month 1–12, year 1800–2199, century 18–21, short year 0–99; hours 0–23, minutes and seconds 0–59); a field `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `NO_DIGIT_PATTERN`, `CATEGORY_NOT_ALLOWED`, `WRONG_DATE_FORMAT_FOR_OP`; an extraction `INVALID_DATE_OR_TIME_EXTRACT_OP` / `DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED`; then `MISSING_YEAR`, `EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS` |
| `checkDateTimeKonstrukt` | `WRONG_DATE_FORMAT_FOR_OP` (date part), `INVALID_DATE_TYPE`, `WRONG_DATE_FORMAT_FOR_OP` (time part) |
| `checkDatumBereichKonstrukt` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `WRONG_DATE_FORMAT_FOR_OP` |
| `checkDatumBereichOp` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `CATEGORY_NOT_ALLOWED`, `NO_DATE_RANGE` |
| `checkDatumAngabe` | a field: `CATEGORY_NOT_ALLOWED`, `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `NO_DATE`; a string: `INVALID_DATE_STRING`; an extraction: `INVALID_DATE_FOR_OP` |
| `checkFeldOpMitDatumSpecial` | `NO_GROUPS_ALLOWED`, `NO_WILDCARDS_ALLOWED`, `CATEGORY_NOT_ALLOWED`, `INVALID_DATE_TYPE_FOR_OP` |
| `checkEntitySpec` | 1 `$` outside a filter: `INVALID_OUTER_ITERATION`; 2 per level, skipped for an unmarked reference inside a filter: `ERROR_FIELD_NOT_IN_RULEGROUP`; `ERROR_FIELD_NO_INDEX` / `ERROR_FIELD_INVALID_INDEX` / `ERROR_FIELD_INVALID_INDEX_TYPE`, `MULTIPLE_INDEX_FIELDS(2)`; then `SEMANTIC_INDEX_INVALID_ITER` or `NO_WILDCARD`; `SEMANTIC_INDEX_BELOW_WILDCARD`; 3 `GROUP_IS_EMPTY` / `ROOT_GROUP_IS_EMPTY`; 4 with a `Mit` filter: `NO_GROUPS_ALLOWED`, `WILDCARDS_REQUIRED`, `SEMANTIC_INDEX_NOT_ALLOWED`, `INVALID_ITERATION_IN_FILTER_CONDITION`, `NO_WILDCARD`, `SEMANTIC_INDEX_NOT_ALLOWED`, `NO_ITERATION_FOR_WILDCARD`, `DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION` (a nested `Mit`, an application condition, repetition-not-unique); 5 a category: `NO_GROUPS_ALLOWED`, `NO_ENUMERATION`, `CATEGORY_UNKOWN` |
| `checkRegelKontextEntity` | `INVALID_WILDCARD_FOR_RULEGROUP`, `RULE_GROUP_IS_EMPTY_OR_INVALID`, then `GROUP_IS_EMPTY` / `ROOT_GROUP_IS_EMPTY` |
| `checkRelativeUp` | `TOO_MANY_PARENT_GROUPS`; `INVALID_ENTITY` (a turning-point name that does not match); `INVALID_WILDCARD_REL` |
| `checkRelUp` | `TOO_MANY_PARENT_GROUPS` |
| `checkIdNameRef` (per segment) | `TOO_MANY_PARENT_GROUPS` → `FIELDNAME_NOT_UNIQUE` (a short-name lookup) → `INVALID_ROOT_GROUP` (a root-level candidate) or `INVALID_ENTITY` → `POINT_IN_A12_MODEL` (`.*`, after the existence check) → `INVALID_WILDCARD` |
| `checkSemanticIndex` | `SEMANTIC_INDEX_NO_FIELD`, `SEMANTIC_INDEX_WITH_WILDCARD`, `SEMANTIC_INDEX_WITH_SEMANTIC_INDEX`, `MULTIPLE_INDEX_FIELDS(2)`, `NO_INDEX_FIELD`, `INDEX_FIELD_INVALID`, `INDEX_TYPE_INCONSISTENT` / `INDEX_VALUE_INVALID`, `SEMANTIC_INDEX_CONTAINED_IN_INDEX`, `INVALID_RAW_TYPE`, `SEMANTIC_INDEX_WITH_CATEGORY`, `SEMANTIC_INDEX_AND_WILDCARD` |
| `checkFormularBedingungName` | `INVALID_ROOT_GROUP_CONSTR` (before the arguments) |
| `checkGroupOp` | `NO_FIELDS_ALLOWED`, `NO_WILDCARDS_ALLOWED` |
| `checkAnzahlAngegebenOpHModel` (ungated) | `INVALID_ROOT_GROUP_CONSTR` |

### A.3 Post-parse, in order (`checkNormalRule`)

| step | check | code |
|---|---|---|
| 1 | parallel iteration | `PARALLEL_ITERATION_NOT_ALLOWED` |
| 2 | a parallel-iterated filtered entity | `PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED` |
| 3 | parallel iteration inside a filter | `PARALLEL_ITERATION_NOT_ALLOWED` |
| 4 | a semantic index inside a filter | `SEMANTIC_INDEX_NOT_ALLOWED` |
| 5–6 | repeated repetition-not-unique | `INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE` |
| 7 | raw-type `Length` | `INVALID_LENGTH_OF_RAW_TYPE` |
| 8 | `IterationChecker`, on the parser's entity map: error field not referenced (`ERROR_FIELD_NOT_REFERENCED`) → iteration classes (`ERROR_FIELDGROUP_ITERATION4/5_INVALID`, `ERROR_FIELD_AND_ITERATION_INVALID`) → `ITERATION_RESTRICTION_IN_A12_MODEL`; reports, then stops | several |
| 9 | USB iteration | `UITERATION_INVALID` |
| 10 | parallel iteration validity | `PARALLEL_ITERATION_INVALID` |
| 11 | a negated condition in an iteration | `NEG_CONDITION_IN_ITERATION` |

Before step 1, `setParallelIterationIndices` turns some iterations into parallel iterations without raising anything.

## Appendix B — decisions an A12 model reaches (source-read)

| decision | alternatives that share a prefix | window |
|---|---|---|
| entity spec (dec 56) | the H path form and the A12 path form share any `'/'? name ('/' name)*` prefix and are told apart only by their predicates, which prediction evaluates only where it resolves; the rule-context form and the path form share `$` | the whole path, until a token only one form takes: `$` then a name, a leading `..`, a `*` directly after a name, `For`, `Mit`, …; measured 0–9 tokens |
| filter after a key (dec 55) | `Mit` may follow an entity spec ending in `Fuer` *key* | ambiguous by design |
| simple condition (dec 6) | the two validity conditions share `Valid (` | three tokens |
| relative climb (dec 63, 66) | `../..` against `../x` | three tokens |
| name continuation (dec 83) | `.` then a name, against `.*` and the H row suffix | two tokens |
| operation primary (dec 20) and loop (dec 24) | a leading comment; operator tokens that also follow an inner operation | two tokens; precedence |
| comment placement (dec 3, 17, 19) | `;;` may lead or trail several constructs | ambiguous by design |

Matches and switches that can fail in practice: the set matches `tagSpec` (first/last day), `inOp`, `toOp` and `nameOhnePunkt` (after the application-condition keyword, after `->`, and after `/` inside a path); the switches `commonKonstante` (a count's constant), `vglOp` (after a comparison's left side) and `wertListenSpezifier2` (after a value list's comma). Every other set match and switch is pre-checked by an earlier prediction. The 16 predicate-only decisions are 0, 4, 7, 9, 16, 23, 25, 31, 37, 38, 39, 40, 41, 57, 59 and 69.

## Appendix C — seed rows (from the batch-S review, 2026-09-26)

Each row was predicted from the model before R2's trace ran. **Measured 2026-09-26** (`A12ParseTraceTest`): 18 of 21 held. The three misses were misapplications of the model. `$Code/` is a mismatch, because `$` then a name ends the window. The unstarred `CurrentRepetition` row is `MVK_NO_WILDCARD`, because the completed spec's check runs first. `Abs(-1)` meets a constant check. The comment row settled that a comment is refused inside an argument list. The test's row list is the measured record; the table below keeps the reasoning.

| input (host: the sweep fixtures) | predicted A12 class | why |
|---|---|---|
| `FieldFilled(Total) And FieldFilled` | `MVK_EXPECTED_TOKEN_NOT_FOUND` | the alternative is predicted from its first token, then `(` fails to match at EOF |
| `FieldFilled(Total) And Valid` | `MVK_INCOMPLETE_INPUT` | the validity decision runs out of input |
| `… And RoundUpValue(Total,` · `RangeAsNumber(Code, 1,` · `ValueAsDate(Estimated,` | `MVK_EXPECTED_TOKEN_NOT_FOUND` | a digit or day-keyword match at EOF; controls `Max([Total],` and `AddDays(Placed,` are `MVK_INCOMPLETE_INPUT` (a decision) |
| `FieldFilled(Code) And FieldFilled(Items*/NoSuch.)` | `MVK_UNEXPECTED_TOKEN` at `)` | the name loop exits through the completed-rule fallback, then `.` `*` fails; no segment check ran |
| `FieldFilled(Code) And FieldFilled(Items*/Lots/Qty/)` on `/Order{Code, Items*{Count, Lots*{Qty}}}` | `MVK_UNEXPECTED_TOKEN` | the entity spec never completes, so its star checks never run; control: the closed path is `MVK_NO_WILDCARD` |
| `FieldFilled(Code) And FieldFilled($Code/)` | `MVK_UNEXPECTED_TOKEN` | same |
| `CurrentRepetition(/Warehouse, /Aisles/Bins)` | `MVK_UNEXPECTED_TOKEN` | the group operation takes one entity spec |
| `FieldFilled(Reference) And AddDays([Premium], 1) > 0` | `MVK_UNEXPECTED_TOKEN` (measured, `MessageParameterConditionDependencyLawsTest`) | a date slot never starts with `[` |
| `RoundUpValue(Total In NoSuch) > 0` | `MVK_UNEXPECTED_TOKEN` at `In` | a single entity-spec slot takes no list keyword |
| `Abs(RuleGroup) > 0` | `MVK_UNEXPECTED_TOKEN` | an operation slot takes no rule-context entity |
| `[Total] > ٢` | `MVK_LEXER_STANDARD_ERROR` | the lexer's digits are ASCII |
| `RoundUpValue(Total, ;;c` + newline + `2)` | unknown | settles whether a comment may stand there; the grammar admits `Comment` only at condition and operation edges and gives it no hidden channel, which contradicts a KERNEL-FINDINGS note that comments ride the hidden channel |
| `ValueNotConsistent Qty` · `[Total] > Abs Qty` | `MVK_UNEXPECTED_TOKEN` at `Qty` | the keyword matches, the following token fails |
| admitted controls: `Abs({[Total]})`, `Abs(-1)`, `Max([Total], RoundUp([Qty], 0))`, `AddDays(Placed, Abs(-1))`, `Abs(BaseYear)` | admitted | an operation slot takes a bracketed operation, a call, a constant and a signed number |
