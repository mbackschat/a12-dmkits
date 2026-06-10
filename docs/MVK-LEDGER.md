# The MVK ledger — every kernel rule-diagnostic, classified

Instrument 2 of the constraint-mining discipline (`CLAUDE.md`): every `MVK_*` code the kernel can report, classified (300 codes across four sources, kernel `30.8.1`). **`unclassified` is the proactive experiment queue.** `MvkLedgerTest` keeps each section in exact two-way parity with its source (the three enums reflectively — internal tier, test-only per N8; the metamodel section against the sibling `a12-kernel-mm` clone, skipped when absent).

**The fourth source is the deep one** (found 2026-06-04 while repairing this ledger’s coverage hole): `checkConsistency` validates the model *as a document against the MM_Common metamodel* — its generated validators (`mmvalidators-…-codegen` on the classpath) carry 105 structural laws authored as ordinary A12 rules, each with an `MVK_*` errorCode. Structural laws **are** metamodel rules.

Buckets: **erased** — unrepresentable in the typed API · **pre-checked** — caught at `build()`/handle creation with the fix named · **kernel-delegated** — deliberately left to F4 (with reason) · **n/a-structural** — constrains model parts outside rulekit's authoring surface (header, groups, fields, types, type-level texts); read–edit–write-back preserves those parts, so the law is unreachable from rulekit-authored changes, while `checkConsistency` still enforces it on every F4/F6 run · **pending** — its operator family is not built yet (corpus backlog) · **unclassified** — not yet analyzed (queue).

## How the queue resolves — per-source resolution paths (2026-06-10)

The four sources are not equally hard to drain. The decisive split: **does the code have a rule body, or only Java check code behind it?**

| Source (section below) | Queue (2026-06-10) | Resolvable how |
|---|---|---|
| `MM_Common` metamodel rules | **0 — resolved 2026-06-10** (see the section) | **Rule-explanation dogfood — directly resolvable.** Every code in this section is carried by one or more real A12 rules in `../a12-kernel-mm/documentModels/MM_Common_v2/codegen-mm_common-v2-19.8.json` (134 MVK-coded rules covering exactly the 105 codes; verified 2026-06-10 — no other metamodel in the clone carries MVK rules). The rule's condition *is* the law's formal definition and its error message is the kernel team's own gloss — run `cli explain --rule` on it and read the classification off the explanation. Kernel probes only for rows the rule text leaves ambiguous. |
| `ParserErrorTypesEnum` | **0 — resolved 2026-06-10** (see the section) | **Source-reading + targeted probes — no rule body exists.** These fire from Java check code, so the oracles are the kernel source (`check/.../Check*Impl` javadoc states each rule; the ANTLR grammar for syntax-tier codes), cross-checked against the BA doc's per-`MVK_*` rationale (`kernel-ba.md`), confirmed by a `RuleValidator` probe when behavior is in doubt. Resolution stance: **erased only with positive evidence** (verified operand typing, legacy/H-model-only constructs, §8 un-buildables), **kernel-delegated otherwise** — each such row records the law's gist so the queue doubles as a law inventory. |
| `CalculationErrorType` | **0 — resolved 2026-06-10** | Same method as the parser enum, but the oracle is kernel-core-calc's check sources and the probes are computation experiments (`Computation` + `compute-doc`). |
| `FehlertextErrorType` | **0 — resolved 2026-06-10** | Same method; oracle is kernel-core-service's error-text rendering source (index/message semantics). |

Caveats that hold across all four paths:

- **A code listed in two sources needs both treatments.** The sections overlap (the 300 count is per-section); resolving a code's metamodel row via its rule does not classify the parser-enum row of the same name — that one still fires from different check code.
- **License discipline for the metamodel path:** the MM_Common rules are EUPL-1.2 — classify in **own words**; never paste a rule's condition or message text into this ledger or any committed doc (the license-safe rule in `CLAUDE.md`).
- **A resolution is three things, not one:** the bucket, the own-words treatment in the row, and — wherever the row claims a behavior — a law lock (`validate/laws/` accept-reject or `adapter/laws/` fires/does-not-fire). A bucket flip without its lock is an unguarded fact.
- **Batch by family, not row-by-row.** The metamodel section clusters naturally (`DISPLAY_VALUES_*`, `INTERNAL_VALUES_*`, `SORT_*`, …); one explanation pass per cluster beats 103 single experiments.

## `ParserErrorTypesEnum` (kernel-core-parser, reflective CI check)

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_BRACKET_MISSING` | erased | the renderer parenthesizes And/Or mixing structurally |
| `MVK_CATEGORY_NOT_ALLOWED` | kernel-delegated | law: some operations forbid category access on their operands — F4 arbitrates |
| `MVK_CATEGORY_REF_OP` | erased | category access exists only on `EnumRef` (a field handle) — category-on-group unrepresentable |
| `MVK_CATEGORY_UNKOWN` | pre-checked | `EnumRef.category(name)` fails fast on undeclared categories |
| `MVK_CONDITION` | erased + kernel-delegated | the typed renderer emits grammar-valid text structurally; for raw-DSL inputs (`check`, `modify --condition`) the kernel’s parse error is the intended contract |
| `MVK_CONDITION_WITHOUT_REPEATABLE_ROOTGROUP` | erased | H-model form conditions — legacy-only, no authoring surface |
| `MVK_CONDITION_WITH_WILDCARD` | erased | H-model form conditions — legacy-only |
| `MVK_DATEFORMATS_NOT_COMPATIBLE` | kernel-delegated | law: date operands mixed in one aggregate must share compatible formats/precision — model data, F4 arbitrates |
| `MVK_DATE_AND_NONDATE` | kernel-delegated | law: if an aggregate’s first operand is a date, all operands must be dates — F4 arbitrates |
| `MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED` | kernel-delegated | law: extraction ops are barred from certain date/time-construction parameter positions — F4 arbitrates |
| `MVK_DATE_WITH_AND_WITHOUT_YEAR` | kernel-delegated | law: date operands must uniformly carry or omit the year component — model data, F4 arbitrates |
| `MVK_DEPENDENT_COLUMN_SIZE_MISMATCH` | erased | table-backed enum condition (`ValueNotConsistent`) is un-buildable — KERNEL-FINDINGS §8 |
| `MVK_DIFFERENT_GROUPS` | kernel-delegated | law: `SumOfProducts`/distinct-count operands must live in one group — F4 arbitrates |
| `MVK_DIFFERENT_NO_VALUE` | kernel-delegated | law: with `False`, all listed yes/no fields must share the same declared no-representation — model data, F4 arbitrates |
| `MVK_DIFFERENT_ROOT_GROUPS` | erased | H-model row conditions requiring one root group — legacy-only |
| `MVK_DIFFERENT_YES_VALUE` | kernel-delegated | law: with `True`, all listed yes/no fields must share the same declared yes-representation — model data, F4 arbitrates |
| `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION` | pre-checked | `FieldListRef.having` rejects RepetitionNotUnique + nested Having + CustomCondition (the representable forbidden constructs; rest kernel-delegated; locked: `dsl.operators.IterationConditionTest`/`CustomConditionTest`) |
| `MVK_DUPLICATE_PARAM1` | pre-checked | `ListAggregate`/`CountAggregate` reject duplicate operands with the code named (locked: `dsl.operators.AggregateConditionTest`) |
| `MVK_DUPLICATE_PARAM2` | kernel-delegated | law: operands must not contain one another (indirect duplicates) — exact duplicates are pre-checked (`MVK_DUPLICATE_PARAM1`), the containment law stays F4’s |
| `MVK_ENTITY_MOVE_IN_A12_MODEL_CONDITION` | erased | entity moves are a tooling operation rulekit never performs |
| `MVK_ENTITY_MOVE_IN_A12_MODEL_ERRORTEXT` | erased | entity moves are a tooling operation rulekit never performs |
| `MVK_ENUM_VALUES_NOT_IN_COLUMN` | erased | same §8 fact — the table-enum membership condition is un-buildable |
| `MVK_ERROR_FIELDGROUP_ITERATION4_INVALID` | kernel-delegated | law: an error field reached only via a root-group reference must have no repeatable group below that point — F4 arbitrates |
| `MVK_ERROR_FIELDGROUP_ITERATION5_INVALID` | kernel-delegated | law: parallel iteration requires the index field at the error path’s root level — F4 arbitrates |
| `MVK_ERROR_FIELD_AND_ITERATION_INVALID` | kernel-delegated | law: an indirectly referenced error field must be reachable without crossing further repeatable groups — F4 arbitrates |
| `MVK_ERROR_FIELD_INVALID_INDEX` | kernel-delegated | law (parallel iteration): the error-field path’s index field must match the referenced group’s index field by name — F4 arbitrates |
| `MVK_ERROR_FIELD_INVALID_INDEX_TYPE` | kernel-delegated | law (parallel iteration): the two index fields must share a base type — F4 arbitrates |
| `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` | pre-checked | `Rule.build()` requires the error field under the rule’s group (`RK_ERROR_FIELD_OUTSIDE_GROUP`; locked: `RuleAuthoringTest`) |
| `MVK_ERROR_FIELD_NOT_REFERENCED` | pre-checked | `Rule.build()` via `ConditionReferences.covers` (incl. enclosing-group rule) |
| `MVK_ERROR_FIELD_NOT_VALID` | pre-checked | typed handles exist only for model entities; the CLI pre-flight (`ModelPaths`) fails fast on unknown paths |
| `MVK_ERROR_FIELD_NO_INDEX` | kernel-delegated | law (parallel iteration): the error-field path needs an index field in an ancestor group — F4 arbitrates |
| `MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS` | erased | the four-parameter (split-year) date construction is an H-model shape — legacy-only |
| `MVK_FIELDNAME_NOT_UNIQUE` | erased | references render as qualified name-paths from handles — ambiguous short-name references are not emitted |
| `MVK_FIELD_REF_OP` | kernel-delegated | **message label, not a fireable law** (corrected by the 2026-06-10 `Check*Impl` cross-validation): its text ("Feldwert-Operation") only *names* the `[]` op inside other codes' messages — `CheckFeldWertOpImpl` fires `MVK_NO_GROUPS_ALLOWED`/`MVK_NO_WILDCARDS_ALLOWED`/`MVK_INVALID_DATE_TYPE` for the actual laws (field-only, no wildcards, no partly-known date) |
| `MVK_GROUP_IS_EMPTY` | kernel-delegated | law: a referenced non-root group must contain a field — model data, F4 arbitrates |
| `MVK_GROUP_NOT_REPEATABLE` | pre-checked | `RepetitionNotUniqueCondition.from` requires a repeatable reference group (locked: `dsl.operators.IterationConditionTest` + N7 `FromScope` invalidExample) |
| `MVK_INCONSISTENT_TYPES_COMPARED` | erased | cross-kind comparisons (number vs boolean, …) are unrepresentable — comparison methods are kind-typed per ref |
| `MVK_INDEX_FIELD_INVALID` | kernel-delegated | law: a group’s declared index field must exist in that group — model data, F4 arbitrates |
| `MVK_INDEX_TYPE_INCONSISTENT` | kernel-delegated | law: a semantic index’s key field must match the index field’s base type — F4 arbitrates |
| `MVK_INDEX_VALUE_INVALID` | kernel-delegated | law: a semantic index constant must be non-empty and type-applicable for the index field — F4 arbitrates |
| `MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE` | kernel-delegated | law: at most one `RepetitionNotUnique` per condition — two are representable, F4 arbitrates. Candidate pre-check |
| `MVK_INVALID_COMPARE_DEC_PLACES` | pre-checked | `NumberRef.eq/ne(NumberRef)` require equal declared scale (the gate is `==`/`!=`-only; ordering exempt); expression-result scales beyond that stay the kernel’s (D13). Literals exempt (KERNEL-FINDINGS §5). Locked: `validate.laws.NumberLawsTest` |
| `MVK_INVALID_COMPARE_ENUM_TO_ENUM` | kernel-delegated | law: two compared enums must have compatible value sets (matching display/internal value pairs) — model data, the kernel arbitrates |
| `MVK_INVALID_COMPARE_ENUM_TO_STRING` | erased | an enum field compares only to enum values/refs/exprs (`EnumRef.is/isNot/in`) — enum-vs-string-field unrepresentable |
| `MVK_INVALID_COMPARE_TO_CONST` | erased | handles always put a field value on one side; constant-vs-constant unrepresentable (verified via pattern experiments 2026-06-04) |
| `MVK_INVALID_COMPARE_TO_DATE` | erased + pre-checked | only `DateRef`/date expressions compare as dates (`DateOperand`); a number/string field has no date comparison method (locked: N7 `Today` invalidExample). German-format date/time constant literals now implemented (family 1); this code still fires (kernel-delegated) for an ISO-format literal (lexed as a string) and a DATE_TIME field vs a bare string literal |
| `MVK_INVALID_COMPARE_TO_DATE_RANGE` | erased | date-range fields carry no comparison methods (`DateRangeRef` offers presence + range ops only) — range eq/ne unrepresentable |
| `MVK_INVALID_COMPARE_TO_ENUM_OR_STRING` | pre-checked | operation kind must match the computed field (`Calculation.kind()`) |
| `MVK_INVALID_COMPARE_TO_YES` | erased | `ConfirmRef` has no `isFalse()`; Boolean↔Confirm cross-compare unrepresentable |
| `MVK_INVALID_COMPARE_TO_YESNO` | erased | `BoolRef` compares only to `True`/`False` literals |
| `MVK_INVALID_CONST` | erased | the `NumberOfValueInFields` overloads accept only number/string/boolean constants — date/range constants unrepresentable |
| `MVK_INVALID_DATE_FOR_OP` | kernel-delegated | law: a date-extraction source must carry the required components — rulekit does not pre-check date formats |
| `MVK_INVALID_DATE_OR_TIME_EXTRACT_OP` | kernel-delegated | law: the extraction op must match the construction parameter’s component (day/month/year, h/m/s) — F4 arbitrates |
| `MVK_INVALID_DATE_RANGE_FORMAT` | kernel-delegated | law: year-interpreting range formats are barred from general range ops — model data, F4 arbitrates |
| `MVK_INVALID_DATE_STRING` | erased | date/time literals render only the kernel’s accepted shapes; an arbitrary string lexes as a string (drawing the compare-to-date codes instead) |
| `MVK_INVALID_DATE_TYPE` | kernel-delegated | law: ops on partly-known dates must match the known components — rulekit does not pre-check date precision |
| `MVK_INVALID_DATE_TYPE_FOR_OP` | kernel-delegated | `ValueAsDate` requires a *partially known date* field (a `DateType` with a date precision other than FULL); a plain `DateType` / `DATE_FRAGMENT` draws this. rulekit does not pre-check the field's precision — the kernel arbitrates (F4/N7). Verified 2026-06-07 (family 4 / `ValueAsDate`) |
| `MVK_INVALID_DIGIT_FOR_ROUNDING` | pre-checked + kernel-delegated | `Rounding` pre-checks decimal places ≥ 0 (typed `int`); the kernel’s upper bound (≤ 14) stays F4’s |
| `MVK_INVALID_ENTITY` | pre-checked + kernel-delegated | handles + CLI pre-flight pre-check existence; the known exception is the included-field mount-path translation (KERNEL-FINDINGS §10, fork-9) where F4 arbitrates |
| `MVK_INVALID_INTERVAL` | pre-checked | `RangeAsString/AsNumber` enforce the full interval law (1-based, 0 < start ≤ end) with the code named (tightened from ≥ 0 on 2026-06-10; locked: `dsl.operators.RangeOperandTest`) |
| `MVK_INVALID_ITERATION_IN_FILTER_CONDITION` | kernel-delegated | a filter may iterate only over levels the filtered field stars; a ref to a field iterating over a different group needs the `$` operator (`[$L/F2]`) — the typed API renders `$` correctly and the kernel arbitrates. KERNEL-FINDINGS §9 (cross-checked vs the kernel-team BA doc), `adapter.laws.CorrelationLawsTest` |
| `MVK_INVALID_LENGTH_OF_RAW_TYPE` | kernel-delegated | law: no-value-validation fields allow only the strict `Length(f) > n` shape — model data, F4 arbitrates |
| `MVK_INVALID_NUMBER_FOR_EXP` | kernel-delegated | law: an exponent must be a whole number (no fractional digits) — `Arithmetic POWER` operands are untyped `Calculation`s, F4 arbitrates |
| `MVK_INVALID_OUTER_ITERATION` | kernel-delegated | `$` correlation: **authoring** renders `[$path]` only inside a `Having` filter, kernel arbitrates legality (`adapter.OuterCorrelationAuthoringTest`); **read** converts all field-vs-field kinds + `CurrentRepetition($)` + presence predicates (`NoFieldFilled($a,$b)`) + value-list operands (`FieldListRef.outer`) and routes filtered field-list quantifiers through `flattenScope` (`OuterCorrelationReadTest`/`OuterCorrelationQuantifierReadTest`); the **count-check backstop** (`verifyOuterPreserved`) keeps any un-threaded construct Opaque/verbatim — never silent corruption (`adapter.OuterCorrelationBackstopTest`). Corpus-1 lift 87.0%→95.0% with **0 `$` opaques** (every `$` rule converts). KERNEL-FINDINGS §9, KERNEL-INTEGRATION-NOTES `$` item |
| `MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON` | kernel-delegated | law: the range-overlap condition takes exactly one unstarred date-range field — F4 arbitrates |
| `MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON` | erased | `PatternCondition` left side is always a bare string field value — expressions unrepresentable (KERNEL-FINDINGS §7) |
| `MVK_INVALID_PARAMS_FOR_OP` | erased | H-model row-number uniformity law — legacy-only, no H-model authoring surface |
| `MVK_INVALID_PATTERN` | pre-checked | `StringRef.matches/violates` fail fast via `Pattern.compile` (necessary condition only — the portable-subset gate stays kernel-delegated; locked: `validate.laws.PatternLawsTest`) |
| `MVK_INVALID_PREDEFINED_TYPE` | kernel-delegated | dormant — no firing site found in kernel 30.8.1; nothing to pre-check |
| `MVK_INVALID_RAW_TYPE` | kernel-delegated | law: no-value-validation fields are barred from most ops — model data, F4 arbitrates |
| `MVK_INVALID_ROOT_GROUP` | pre-checked | root-group handles exist only for model entities; CLI pre-flight fails fast on unknown names |
| `MVK_INVALID_ROOT_GROUP_CONSTR` | erased | H-model form constructs are rejected in A12 models and have no rulekit surface — legacy-only |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION` | pre-checked | `EnumRef.in/notIn` check every value against the enum domain (locked: `dsl.operators.ValueListConditionTest`) |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_CATEGORY` | kernel-delegated | law: a constant compared under category access must be a valid value of that category — model data, F4 arbitrates |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION_OR_CATEGORY` | pre-checked | the `Dsl` enum value-list quantifiers check every value against every listed field's domain |
| `MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON` | pre-checked | `EnumRef.is`/`CategoryRef.is` validate against the model domain |
| `MVK_INVALID_SUPPRESSED_WARNING` | kernel-delegated | `@SuppressWarning(NAME)` supports exactly one warning — `MVK_INVALID_COMPARE_DEC_PLACES` (the equality scale gate's escape hatch); any other name is rejected. Quirk: the rejection surfaces the *attempted* name as the diagnostic code, this code's text arrives as the message. rulekit never authors the directive (probe-verified 2026-06-10; locked: `validate.laws.DirectiveLawsTest`) |
| `MVK_INVALID_TABLE_ENUM` | erased | table-backed (dynamic) enums are not DM-JSON-serializable (KERNEL-FINDINGS §8) — no loadable model can carry one |
| `MVK_INVALID_TIME` | kernel-delegated | law: day/month/year shifts and differences need a date component (not time-only operands) — F4 arbitrates |
| `MVK_INVALID_TYPE` | kernel-delegated | law: substring/interval ops take string, predefined-type, or extensible-enum fields — `RangeAs*` takes an untyped `FieldRef`, F4 arbitrates |
| `MVK_INVALID_TYPES_FOR_COMPARISON` | erased | value-list handles bind the value type to the field kind (`StringRef.in(String…)`, `NumberRef.in(long…)`; locked: N7 `FieldValueNotIncludedInValueList` invalidExample) |
| `MVK_INVALID_TYPE_FOR_COMPARISON` | erased | no ordering methods on non-orderable handles (`StringRef` has no `le`); N7 invalid examples |
| `MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON` | erased | only `StringRef` offers `matches`/`violates` (enums/numbers/booleans excluded by type; locked: N7 `PatternMatched` invalidExample) |
| `MVK_INVALID_TYPE_FOR_TOLERANCE_OP` | kernel-delegated | law: tolerance comparisons (`DiffersWithToleranceRange*`) take numeric/amount operands only — authored, operand kind left to F4 |
| `MVK_INVALID_UNSPECIFIED_TYPE` | erased | H-model law about unspecified-type fields — legacy-only |
| `MVK_INVALID_WILDCARD` | pre-checked | `BoundModel.flatten` rejects a star on a non-repeatable group with the code named (locked: `validate.laws.IterationLawsTest`) |
| `MVK_INVALID_WILDCARD_FOR_RULEGROUP` | erased | the rule group is a plain `GroupRef` — a starred rule-group reference is unrepresentable |
| `MVK_INVALID_WILDCARD_REL` | erased | `DslRenderer.relativize` emits plain `../` segments — a starred `..` is unrepresentable in rendered output; the kernel law itself is locked (`validate.laws.PathLawsTest.aStarOnTheRelativeUpNavigationIsRejected_useAbsolute`). Stale "pending" flipped 2026-06-10 |
| `MVK_ITERATION_RESTRICTION_IN_A12_MODEL` | erased | bounded iteration ranges are unrepresentable — rulekit renders only the unrestricted star |
| `MVK_MAX_GROUP_RESTRICTION_TO_ONE` | erased | context-restriction rules are not a rulekit authoring surface (rules + computations only) |
| `MVK_MAX_LFDNR_RESTRICTION_TO_ONE` | erased | H-model form-count restriction — legacy-only |
| `MVK_MISSING_CONST_IN_PATTERN_COMP` | erased | the regex parameter is a Java `String` — a field reference on the right is unrepresentable (locked: `validate.laws.PatternLawsTest`) |
| `MVK_MISSING_ERROR_FIELD` | pre-checked | `Rule.build()` requires `errorField(...)` (`RK_INCOMPLETE_RULE`; locked: `RuleAuthoringTest`) |
| `MVK_MISSING_YEAR` | kernel-delegated | law: a year-less date construction requires the model to declare a base year — model data, F4 arbitrates |
| `MVK_MULTIPLE_INDEX_FIELDS` | kernel-delegated | law: at most one group on a path may declare index fields — model data, F4 arbitrates |
| `MVK_MULTIPLE_INDEX_FIELDS2` | kernel-delegated | law: a group may declare at most one index field — model data, F4 arbitrates |
| `MVK_NEG_CONDITION_IN_ITERATION` | kernel-delegated | by design (decided 2026-06-04 in slice 7a): the law is a full truth-propagation algorithm (`IterationNotValidVisitor`, ~800 lines, context-dependent positivity) — not replicated; F4 is the gate |
| `MVK_NON_NUMERIC` | kernel-delegated | law: a date-shift amount must be numeric — the shift amount is an untyped `Calculation`, F4 arbitrates |
| `MVK_NOT_IN_GROUP` | kernel-delegated | law: with a `FromScope` (`@From`) all operands must descend from that group — F4 arbitrates |
| `MVK_NOT_IN_RULEGROUP` | kernel-delegated | law: all operands must lie inside the rule’s group scope — depends on rule placement, F4 arbitrates |
| `MVK_NOT_IN_SAME_PATH` | kernel-delegated | law: `RepetitionNotUnique` groups must lie on one hierarchy path (no parallel branches) — F4 arbitrates |
| `MVK_NOT_ON_ERRORFIELD_PATH` | kernel-delegated | dormant — no firing site found in kernel 30.8.1 (legacy-leaning); nothing to pre-check |
| `MVK_NOT_SORTABLE` | pre-checked | `ListAggregate` Max/Min require NUMBER operands; the **date** variant (`Dsl.latestOf`/`earliestOf` → `DateListAggregate`) is implemented (`adapter.DateAggregateReadTest`). Locked: N7 `MaxValue` invalidExample |
| `MVK_NO_BASE_YEAR` | kernel-delegated | law: the `BaseYear` constant requires a declared model base year — model data, F4 arbitrates |
| `MVK_NO_BOOLY_ALLOWED` | kernel-delegated | law: `FirstFilledValue` forbids boolean/confirm operands (string/enum/number/date only) — F4 arbitrates |
| `MVK_NO_CONST` | kernel-delegated | law: rounding/date-extraction sources must be fields, not constants — a literal `Calculation` operand is representable, F4 arbitrates |
| `MVK_NO_CONVERT_IN_NUMBER` | kernel-delegated | law: `FieldValueAsNumber` requires every possible value (incl. enum/category values) to parse as a number within the digit budget — model data, F4 arbitrates |
| `MVK_NO_DATE` | erased | date operations take `DateRef`/date expressions (`DateOperand`) — a non-date source is unrepresentable |
| `MVK_NO_DATE_CONSTANT` | kernel-delegated | law: date extraction takes fields/expressions, not string literals — F4 arbitrates |
| `MVK_NO_DATE_RANGE` | kernel-delegated | law: range-specific ops take date-range operands only — F4 arbitrates |
| `MVK_NO_DIGIT_PATTERN` | kernel-delegated | law: a string field feeding a date-construction component must carry a digits-only pattern — model data, F4 arbitrates |
| `MVK_NO_EMPTY` | kernel-delegated | law: the predefined-type code name in `Valid`/`ValidType` must be non-empty — representable, F4 arbitrates. Candidate pre-check |
| `MVK_NO_ENUMERATION` | erased | only `EnumRef` offers `category()` |
| `MVK_NO_FIELDS_ALLOWED` | erased | `GroupRef` ≠ `FieldRef` — group predicates take groups only |
| `MVK_NO_GROUPS_ALLOWED` | erased | field predicates take `FieldRef` only |
| `MVK_NO_INDEX_FIELD` | kernel-delegated | semantic index `[Field For …]` needs the group to declare `indexFieldName`; rulekit index does not yet expose it (slice 7f) — locked: N7 `SemanticIndex` invalidExample |
| `MVK_NO_ITERATION_FOR_WILDCARD` | kernel-delegated | `$` correlation is implemented (2026-06-07); a `$` on the filtered side needs a non-`$` inner ref, which the typed `[inner] == [$outer]` form produces — the kernel arbitrates the degenerate case. KERNEL-FINDINGS §9, `adapter.laws.CorrelationLawsTest` |
| `MVK_NO_NUMBER` | pre-checked | `ListAggregate` Sum requires NUMBER operands (locked: N7 `Sum` invalidExample) |
| `MVK_NO_NUMBER_FOR_CALC` | kernel-delegated | law: `*`/`/`/`-` take numeric operands only — `Arithmetic` operands are untyped `Calculation`s, F4 arbitrates |
| `MVK_NO_STRING` | erased + kernel-delegated | `Length` is erased (offered by `StringRef` only); `FieldValueAsString` takes any field — its string-kind law stays F4’s |
| `MVK_NO_STRING_OR_ENUM` | kernel-delegated | law: `NumberOfValueInFields` with a string constant takes string/enum fields only — field kinds left to F4 |
| `MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM` | kernel-delegated | law: code-validity conditions take string/enum/extensible-enum/predefined-type fields — F4 arbitrates |
| `MVK_NO_STRING_OR_NUMBER_FOR_ADD` | kernel-delegated | law: `+` is numeric addition or string concatenation, never mixed (and no display-value enum concat) — operand kinds left to F4 |
| `MVK_NO_TYPEYESNO` | kernel-delegated | law: with the `False` constant all listed fields must be yes/no-typed — F4 arbitrates |
| `MVK_NO_TYPE_YES_OR_YESNO` | kernel-delegated | law: with the `True` constant all listed fields must be yes- or yes/no-typed — F4 arbitrates |
| `MVK_NO_WILDCARD` | kernel-delegated | repeatable group without `*`; pre-check would need full iteration-model analysis — **confirmed 7f**: under parallel iteration a plain cross-group ref is legal, so a naive check false-positives (locked: `validate.laws.IterationLawsTest`) |
| `MVK_NO_WILDCARDS_ALLOWED` | erased | scalar slots take `FieldRef` only; starred lists are `FieldListRef`, accepted only by list slots (locked: `validate.laws.IterationLawsTest`) |
| `MVK_NO_WILDCARDS_G_ALLOWED` | kernel-delegated | law: `AllGroupsFilled`-style group ops forbid starred group operands in some positions — F4 arbitrates |
| `MVK_NUMBER_AND_NON_NUMBER` | pre-checked | `CountAggregate` NumberOfDifferentValues homogeneity check |
| `MVK_ONLY_CONSTANTS_USED` | kernel-delegated | law: date-validity conditions must reference at least one field — all-constant shapes are pointless and rejected by F4 |
| `MVK_ONLY_ENUMS_ALLOWED` | erased | same §8 fact — un-buildable construct |
| `MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED` | erased | `BoolRef`/`ConfirmRef` offer no `in`/`notIn` (locked: `validate.laws.ValueListLawsTest` + N7 `FieldValueIncludedInValueList` invalidExample) |
| `MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED` | pre-checked | `CountAggregate` NumberOfDifferentValues rejects boolean/confirm operands |
| `MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED_NO_FIELD` | kernel-delegated | dormant — no firing site found in kernel 30.8.1; nothing to pre-check |
| `MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED` | kernel-delegated | law: `NumberOfDifferentValues` operands restricted to string/enum/number/date kinds — F4 arbitrates |
| `MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED` | kernel-delegated | law: a `Having` filter must not touch parallel-iterated entities — F4 arbitrates (same stance as the `$`/iteration rows, `adapter.laws.CorrelationLawsTest` area) |
| `MVK_PARALLEL_ITERATION_INVALID` | kernel-delegated | law: parallel iteration is rejected when the condition could fire with no filled instance (ambiguous error site) — F4 arbitrates |
| `MVK_PARALLEL_ITERATION_NOT_ALLOWED` | kernel-delegated | law: `RepetitionNotUnique` must not mix with parallel iteration over the same groups — F4 arbitrates |
| `MVK_PARAMSIZE_INVALID1` | erased | arity laws (exactly one operand) are structural — the typed signatures fix operand counts |
| `MVK_PARAMSIZE_INVALID2` | erased | arity laws (exactly two operands) are structural in the typed signatures |
| `MVK_PARAMSIZE_INVALIDFN` | erased | arity laws (more than one operand) are structural — list ops use the `(first, second, rest…)` idiom |
| `MVK_PARAMSIZE_INVALIDGN` | erased | `Dsl.numberOfFilledGroups(first, second, …)` requires ≥2 groups by signature (locked: N7 invalidExample) |
| `MVK_PARAMSIZE_INVALIDN` | erased | `Dsl.numberOfFilledFields` signatures require ≥2 fields or ≥1 group (locked: N7 invalidExample) |
| `MVK_PARAMSIZE_INVALID_2` | erased | arity laws (exactly two elements) are structural in the typed signatures |
| `MVK_PARTIAL_DATE_NOT_ALLOWED` | kernel-delegated | law: partly-known dates are barred from most date ops — rulekit does not pre-check date precision (same stance as `MVK_INVALID_DATE_TYPE_FOR_OP`) |
| `MVK_POINT_IN_A12_MODEL` | erased | the `.*` path syntax is never emitted by the renderer |
| `MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED` | kernel-delegated | law: an `IfFilled` marker in the error text must name an entity the condition references — message text is free-form, F4 arbitrates |
| `MVK_REF_ERROR_ENTITY_IF_NOT_FILLED_NOT_REFERENCED` | kernel-delegated | law: same for `IfNotFilled` markers — F4 arbitrates |
| `MVK_REPEATABLE_GROUP_MISSING` | kernel-delegated | position-dependent (key repeatable *relative to the rule group*) — verified + locked (`validate.laws.IterationLawsTest`); pre-check candidate once `Rule.build()` consults the iteration model (7e) |
| `MVK_REPEATABLE_LEVEL_REQUIRED` | kernel-delegated | law: an aggregate needs at least one repeatable operand level — F4 arbitrates |
| `MVK_ROOT_GROUP_IS_EMPTY` | kernel-delegated | law: an empty root group must not be referenced — model data, F4 arbitrates |
| `MVK_ROOT_GROUP_REFERENCED` | pre-checked | `CountAggregate` rejects root-group refs with the code named (locked: `dsl.operators.AggregateConditionTest`) |
| `MVK_ROOT_GROUP_WITH_OTHER_PARAMETERS` | kernel-delegated | law: a root group may only be an aggregate’s sole operand — F4 arbitrates |
| `MVK_RULE_GROUP_IS_EMPTY_OR_INVALID` | pre-checked + kernel-delegated | existence pre-checked (handles + CLI pre-flight); the emptiness half is model data F4 arbitrates |
| `MVK_SEMANTIC_INDEX_AND_WILDCARD` | kernel-delegated | law: no wildcard at the same level as a semantic index — F4 arbitrates |
| `MVK_SEMANTIC_INDEX_BELOW_WILDCARD` | kernel-delegated | law: no semantic index below a wildcard in the same path — F4 arbitrates |
| `MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX` | kernel-delegated | law: a semantic index’s referenced field must lie outside the indexed group — F4 arbitrates |
| `MVK_SEMANTIC_INDEX_INVALID_ITER` | kernel-delegated | an entity semantic-index that itself iterates; rulekit offers only the string-constant `forIndex` form (7f) |
| `MVK_SEMANTIC_INDEX_NOT_ALLOWED` | kernel-delegated | law: some operations/contexts forbid semantic-index notation — F4 arbitrates |
| `MVK_SEMANTIC_INDEX_NO_FIELD` | kernel-delegated | law: a semantic index’s reference must be a field, not a group — F4 arbitrates |
| `MVK_SEMANTIC_INDEX_REF_OP` | kernel-delegated | law: a semantic index’s referenced field must have value validation enabled — model data, F4 arbitrates |
| `MVK_SEMANTIC_INDEX_WITH_CATEGORY` | kernel-delegated | law: no category access inside a semantic-index field reference — F4 arbitrates |
| `MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX` | kernel-delegated | law: semantic indices must not nest — F4 arbitrates |
| `MVK_SEMANTIC_INDEX_WITH_WILDCARD` | kernel-delegated | law: a semantic-index field reference must carry no wildcards — F4 arbitrates |
| `MVK_STRING_ENUM_AND_NON_STRING_ENUM` | pre-checked | `CountAggregate` NumberOfDifferentValues homogeneity check (locked: N7 invalidExample) |
| `MVK_STRING_VALUE_NOT_IN_COLUMN` | erased | same §8 fact — un-buildable construct |
| `MVK_TABLE_ENUM_EXPECTED` | erased | same §8 fact — table-enum-requiring ops are unreachable without a table-backed enum in the model |
| `MVK_TOO_MANY_CONST_FOR_CALC_OP` | kernel-delegated | law: Min/Max operand lists allow at most one constant — representable, F4 arbitrates. Candidate pre-check |
| `MVK_TOO_MANY_DIV_FOR_CALC` | kernel-delegated | law: at most one division per calculation subtree (no chained fractions) — nested `Arithmetic` is representable, F4 arbitrates. Candidate pre-check |
| `MVK_TOO_MANY_PARENT_GROUPS` | erased | the kernel-relative `../` form is computed from real handle paths — over-walking the hierarchy is unrepresentable |
| `MVK_TOO_MANY_POW_FOR_CALC` | kernel-delegated | law: power ops must not nest without parentheses — representable, F4 arbitrates |
| `MVK_VARYING_TYPES_NOT_ALLOWED` | erased | the `Dsl` value-list quantifiers take all-`StringRef` or all-`EnumRef` lists (locked: `validate.laws.ValueListLawsTest` + N7 `AtLeastOneFieldValueIncludedInValueList` invalidExample) |
| `MVK_WILDCARDS_REQUIRED` | erased | `having` is offered only by `FieldListRef` (a starred list), never by plain `FieldRef` |
| `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED` | kernel-delegated | law: the unrestricted star must sit at the lowest repeatable level — path shapes from refs normally satisfy it, F4 arbitrates |
| `MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED` | kernel-delegated | law: only the lowest repeatable level may carry the star — F4 arbitrates |
| `MVK_WRONG_DATE_FORMAT_FOR_OP` | kernel-delegated | law: the operation requires a specific date format on its operand — `Date(d,m,y)` construction is now built (stale “pending (slice 9b)” flipped 2026-06-10); format fit is model data, F4 arbitrates |

## `CalculationErrorType` (kernel-core-calc, reflective CI check)

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_CALCULATION_FIELD_AND_NO_CALCULATION_RULE` | kernel-delegated | dormant — no active firing site found in kernel 30.8.1 (the calculated-field/rule pairing is checked by the other codes here) |
| `MVK_CALC_INVALID_ACTION_TYPE` | erased | law: only ERROR-severity rules may drive a calculation — the generated self-validation rule’s severity is the kernel’s; rulekit exposes no severity slot on computations |
| `MVK_ERROR_CALC_CYCLE` | kernel-delegated | law: computations must form an acyclic dependency graph — only direct self-reference is pre-checked (`MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD`); cross-computation cycles stay F4’s |
| `MVK_ERROR_COMPARISON_WITH_OR` | erased | normalized-form law (precondition joined to the comparison by And, never Or) — the rule text is kernel-generated from the typed computation, never hand-written |
| `MVK_ERROR_EMPTY_PRECONDITION` | pre-checked | `Computation.build()` rejects mixed alternatives; no `.otherwise()` API exists |
| `MVK_ERROR_INDEX_AS_CALCULATED_FIELD` | kernel-delegated | law: an index field cannot be calculated — whether the chosen field is an index field is model data, F4 arbitrates |
| `MVK_ERROR_INVALID_COMPARISON` | erased | normalized-form law (the self-check comparison must be an inequality/tolerance op) — kernel-generated, unreachable from rulekit |
| `MVK_ERROR_INVALID_ENUM_CALCULATION` | kernel-delegated | law: an enum-valued computation may produce only constants/enums/categories compatible with the target’s value set — kind is pre-checked (`MVK_INVALID_COMPARE_TO_ENUM_OR_STRING`), value-set fit is model data F4 arbitrates |
| `MVK_ERROR_INVALID_FIELDNAME` | kernel-delegated | law: the computed field’s name must carry no dot/colon — the field is an existing model field whose name the metamodel laws already police |
| `MVK_ERROR_INVALID_FIELD_GATE` | erased | normalized-form law (the generated rule must open with the computed field’s fill gate) — kernel-generated, unreachable from rulekit |
| `MVK_ERROR_INVALID_LANGUAGE_FEATURE` | kernel-delegated | law: computation preconditions must not use custom conditions, `RepetitionNotUnique`, or the legacy count-given op — representable, F4 arbitrates. Candidate pre-check |
| `MVK_ERROR_ITERATION_UNCHECKED` | erased | H-model-only iteration-synchronization law — legacy-only |
| `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` | pre-checked | `Computation.build()` checks preconditions and operations |
| `MVK_ERROR_RESTRICTED_CALCULATION_RULE` | erased | law: no iteration-interval restriction on calc rules — bounded iteration is unrepresentable anyway (same fact as `MVK_ITERATION_RESTRICTION_IN_A12_MODEL`) |
| `MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD` | erased | the computed-field slot is a plain field handle/path — semantic-index or category decoration on it is unrepresentable |
| `MVK_ERROR_WRONG_CALCULATED_FIELD` | erased | law: the calculated field must equal the rule’s error field — the generated rule derives its error field from the computed field, mismatch unreachable |
| `MVK_ERROR_WRONG_ROUNDING_MODE` | erased | normalized-form law (rounding mode must match the comparison’s tolerance) — kernel-generated, unreachable from rulekit |
| `MVK_GROUP_AS_CALCULATED_FIELD` | erased | the computed-field slot is a field handle; the CLI pre-flight resolves the path against model fields — a group target is unrepresentable |

## `FehlertextErrorType` (kernel-core-service, reflective CI check; its non-`MVK_` members like `RULE_HAS_NO_ERRORTEXT` are out of ledger scope)

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_INDEX_FOR_ERROR_TEXT_INVALID` | kernel-delegated | law: a semantic index may appear in an error text only when the rule’s condition also uses it — message text is free-form, F4 arbitrates |
## `MM_Common` metamodel rules (a12-kernel-mm; opt-in CI check against the sibling clone)

**Resolved 2026-06-10 by the rule-explanation dogfood** (the path mapped in "How the queue resolves" above). All 105 codes' carrier rules — 134, all in `MM_Common_v2` — were read and explained with rulekit's own verbs and classified from the laws themselves, in own words (conditions/messages not reproduced — EUPL). Dogfood score: `read --all` converts **192/192** of the metamodel's rules fully typed; `explain` succeeds on **all 134** MVK carriers (30 distinct operators exercised). The **n/a-structural** rows give the owning entity + the law's gist. Two write-path probes grounded the kernel-delegated rows: a `vk_vk`-prefixed rule name (rejected pre-persist, `MVK_RULE_NAME_INVALID`) and a blank message text (treated as a missing message) — neither mutated the model file. Details: KERNEL-FINDINGS "Reserved rule-name prefix" + §13 supplement.

| Code | Bucket | rulekit treatment / lock |
|---|---|---|
| `MVK_ADDITIONAL_CHECK_INVALID` | n/a-structural | date-type law: the extra date check requires a year-bearing format |
| `MVK_AMOUNT_INT_PART_MISSING` | n/a-structural | number-type law: amount types must declare the integer-part places |
| `MVK_ARITY_TOO_BIG` | n/a-structural | number-type law: integer + fractional places must fit the header’s digit budget |
| `MVK_CATEGORY_NAME_DUPLICATE` | n/a-structural | enum-category law: category names unique |
| `MVK_CATEGORY_VALUES_NOT_ALLOWED` | n/a-structural | enum-type law: a table-backed enum must not also declare category values |
| `MVK_CATEGORY_VALUE_DUPLICATE` | n/a-structural | enum-category law: each internal value mapped at most once per category |
| `MVK_CATEGORY_VALUE_INVALID` | n/a-structural | enum-category law: category values must be valid (custom check) |
| `MVK_CONDITION_MISSING` | pre-checked | `Rule.build()` requires `when(...)` (`RK_INCOMPLETE_RULE`; locked: `RuleAuthoringTest`/`PrecheckDiagnosticTest`) |
| `MVK_COUNT_OF_VALUES_INVALID` | n/a-structural | enum-type law: each language block must cover all internal values |
| `MVK_DATE_RANGE_FORMAT_INVALID_CODEGEN` | n/a-structural | date-type law: reference-year range formats cannot go through codegen |
| `MVK_DEPENDENT_COLUMNS_DUPLICATE` | n/a-structural | enum-type law: dependent columns unique |
| `MVK_DEPENDENT_COLUMNS_EQUALS_COLUMN` | n/a-structural | enum-type law: a dependent column must differ from the main column |
| `MVK_DEPENDENT_COLUMNS_NOT_ALLOWED` | n/a-structural | enum-type law: dependent columns require the table+column pair |
| `MVK_DEPENDENT_COLUMN_NAME_MISSING` | n/a-structural | enum-type law: a dependent-column row needs its name |
| `MVK_DISPLAY_VALUES_DUPLICATE` | n/a-structural | enum-type law: display values unique per language block (also ext. enum) |
| `MVK_DISPLAY_VALUES_MISSING` | n/a-structural | ext.-enum-type law: an extensible enum needs display values |
| `MVK_DISPLAY_VALUES_NOT_ALLOWED` | n/a-structural | enum-type law: a table-backed enum must not also declare inline display values |
| `MVK_DISPLAY_VALUE_INVALID` | n/a-structural | enum-type law: display values must be valid (custom; also ext. enum) |
| `MVK_DISPLAY_VALUE_TOO_LONG` | n/a-structural | ext.-enum-type law: display values must respect the type’s max length |
| `MVK_ERROR_CODE_MISSING` | pre-checked | `Rule.build()` requires a non-blank error code (`RK_INCOMPLETE_RULE`; locked: `RuleAuthoringTest`/`PrecheckDiagnosticTest`) |
| `MVK_ERROR_FIELD_MISSING` | pre-checked | `Rule.build()` requires `errorField(...)`, plus the under-group containment check `RK_ERROR_FIELD_OUTSIDE_GROUP` (locked: `RuleAuthoringTest`) |
| `MVK_ERROR_MESSAGE_AND_NO_PATTERN` | n/a-structural | string-type law: a type-level error message is only allowed together with a pattern |
| `MVK_ERROR_MESSAGE_AND_PATTERN_INVALID` | n/a-structural | string-type law: a pattern-bearing type must carry a custom error message |
| `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` | pre-checked + n/a-structural | typed builder requires a message per declared locale (KERNEL-FINDINGS §13; locked: `RuleAuthoringTest`); the CLI `add` path delegates the same law to post-write re-validation (never mutates on failure). Also carried as a string-type-messages law — n/a-structural there |
| `MVK_ERROR_MESSAGE_MISSING` | pre-checked | builders require ≥1 message (locked: `RuleAuthoringTest`); a blank-text message serializes as missing and draws this code at F4 instead (KERNEL-FINDINGS §13; locked: `validate.laws.RuleSlotLawsTest`) |
| `MVK_FORMAT_DATE_RANGE_MISSING` | n/a-structural | date-type law: a date-range type needs a range format |
| `MVK_FORMAT_MISSING` | n/a-structural | date-type law: a date type needs a format |
| `MVK_FRACT_DIGITS_INVALID` | n/a-structural | number-type law: the fixed/max fractional-digits pair must be consistent |
| `MVK_FRACT_DIGITS_MISSING` | n/a-structural | number-type law: numeric/amount types must declare fractional digits |
| `MVK_INDEX_FIELD_DUPLICATE` | n/a-structural | index law: a group’s index fields must be distinct |
| `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` | n/a-structural | enum-type law: per-language rows fill internal and display value together |
| `MVK_INTERNAL_VALUES_DUPLICATE` | n/a-structural | enum-type law: internal values unique (declared list and per-language blocks) |
| `MVK_INTERNAL_VALUES_MISSING` | n/a-structural | enum-type law: an inline (non-table) enum needs internal values |
| `MVK_INTERNAL_VALUES_NOT_ALLOWED` | n/a-structural | enum-type law: a table-backed enum must not also declare inline internal values |
| `MVK_INTERNAL_VALUE_INVALID` | n/a-structural | enum-type law: internal values must be XML-safe (custom) and rows must reference declared internal values |
| `MVK_INTERNAL_VALUE_MISSING` | n/a-structural | enum-type law: a per-language value row needs its internal value |
| `MVK_INVALID_SORTINFO` | n/a-structural | (root-)group law: sort info is only allowed on a repeatable group |
| `MVK_INVALID_SORT_PRIO` | n/a-structural | sort law: a priority must not exceed the number of sort entries (priorities form 1..n) |
| `MVK_LANGUAGES_DUPLICATE` | n/a-structural | type-text law: message/hint-list languages unique per type |
| `MVK_LANGUAGE_DUPLICATE` | pre-checked | `LocalizedText.checkDeclared` rejects duplicate locales with the code named (locked: `RuleAuthoringTest`). **Corrected 2026-06-10:** the DM-JSON load tier silently tolerates duplicates (one text wins) — this metamodel rule fires only in the model-as-document tier; tolerance locked: `validate.laws.RuleSlotLawsTest` |
| `MVK_LANGUAGE_FOR_VALUES_MISSING` | n/a-structural | hint-list law: a list with values must name its language |
| `MVK_LANGUAGE_INVALID` | pre-checked + n/a-structural | rule carrier: `LocalizedText.checkDeclared` rejects a message locale the model does not declare (stricter than runtime, which ignores extra-locale texts; locked: `RuleAuthoringTest`); the type-level text carriers are n/a-structural |
| `MVK_LANGUAGE_MISSING` | n/a-structural | type-text law: a type-level message/values block must name its language |
| `MVK_LINE_BREAK_AND_MAX_LENGTH_1_INVALID` | n/a-structural | string-type law: permitting line breaks with max length 1 is contradictory |
| `MVK_MAX_DIGITS_MISSING` | n/a-structural | header law: the model-wide digit budget for numeric types must be set |
| `MVK_MAX_LENGTH_TOO_BIG` | n/a-structural | number-type law: max length must fit the digit budget (incl. sign) |
| `MVK_MAX_LENGTH_TOO_SMALL` | n/a-structural | number-type law: max length must leave room for the fractional digits |
| `MVK_MAX_MISSING` | n/a-structural | (root-)group law: the max repetition count must be declared |
| `MVK_MAX_TOO_BIG` | n/a-structural | (root-)group law: the max repetition count is bounded above (tier-specific cap) |
| `MVK_MESSAGE_FOR_LANGUAGE_MISSING` | kernel-delegated | a blank message text is representable, but it serializes as a missing message and draws `MVK_ERROR_MESSAGE_MISSING` instead (probe 2026-06-10); the language-row-without-text shape that fires THIS code is not produced by rulekit — F4 arbitrates |
| `MVK_MIN_LENGTH_BIGGER_MAX_LENGTH` | n/a-structural | string/number-type law: min length ≤ max length |
| `MVK_MIN_LENGTH_TOO_BIG` | n/a-structural | number-type law: min length must fit the digit budget (incl. sign) |
| `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE` | n/a-structural | number-type law: min value ≤ max value |
| `MVK_MISSING_CATEGORY_VALUE` | n/a-structural | enum-category law: a mapping row with an internal value needs its category value |
| `MVK_MISSING_INTERNAL_VALUE` | n/a-structural | enum-category law: a mapping row with a category value needs its internal value |
| `MVK_MISSING_VALUE_FOR_CATEGORY` | n/a-structural | enum-category law: a category must map every internal value |
| `MVK_NAME_ENDS_WITH_COLON_H_DV` | n/a-structural | field law (legacy): names must not end with ":" |
| `MVK_NAME_FIELDS_DUPLICATE` | n/a-structural | model law: field name+group pairs unique model-wide (new-world) |
| `MVK_NAME_GROUP_DUPLICATE` | n/a-structural | model law: group names must be unique |
| `MVK_NAME_INVALID_A12_DV` | n/a-structural | field law: name character/format validity (custom check, new-world variant) |
| `MVK_NAME_INVALID_DOT_COLON_A12_DV` | n/a-structural | field law: new-world names must not contain "." or ":" |
| `MVK_NAME_INVALID_DOT_COLON_H_DV` | n/a-structural | field law: legacy variant of the dot/colon name restriction |
| `MVK_NAME_INVALID_H_DV` | n/a-structural | field law: name character/format validity (custom check, legacy variant) |
| `MVK_NAME_MISSING` | pre-checked + n/a-structural | rule carrier: `Rule.build()` requires a non-blank name (`RK_INCOMPLETE_RULE`; locked: `RuleAuthoringTest`/`PrecheckDiagnosticTest`); the field/group/root-group/enum-category name carriers are n/a-structural |
| `MVK_NAME_ROOT_GROUP_DUPLICATE` | n/a-structural | model law: root-group names must be unique |
| `MVK_NAME_RULES_DUPLICATE` | kernel-delegated | a same-group name collision is rejected by the kernel’s deserialize/convert tier (“two child elements with the same name”, an `IllegalArgumentException`) **before** this metamodel rule can fire — the earlier tier shadows it (locked: `validate.laws.RuleSlotLawsTest`); `add` never mutates on failure. Candidate friendlier pre-check via `ModelIndex` |
| `MVK_NAME_STARTS_WITH_DIGIT` | n/a-structural | root-group law: names must not begin with a digit (new-world models) |
| `MVK_NAME_STARTS_WITH_DIGIT_A12_DV` | n/a-structural | field law: new-world names must not start with a digit |
| `MVK_NAME_STARTS_WITH_DIGIT_H_DV` | n/a-structural | field law (legacy): qualified names must not start with a digit |
| `MVK_NAME_STARTS_WITH_XML` | n/a-structural | (root-)group law: names must not begin with "xml" (new-world models) |
| `MVK_NAME_STARTS_WITH_XML_A12_DV` | n/a-structural | field law: new-world names must not begin with "xml" |
| `MVK_NO_VALUE_MISSING` | n/a-structural | yes/no-type law: the "no" representation must be declared |
| `MVK_NO_VAL_AND_HINT_LISTS` | n/a-structural | string-type law: no-value-validation excludes hint lists |
| `MVK_OPTIONAL_DATE_TYPE_INVALID` | n/a-structural | date-type law: the partially-known-date marker is only valid with compatible formats |
| `MVK_PATTERN_INVALID` | n/a-structural | string-type law: a declared pattern must be valid (custom check) |
| `MVK_PROGRAMMING_LANGUAGE_MISSING` | n/a-structural | header law: the codegen target language must be named |
| `MVK_ROOT_GROUP_NAME_INVALID` | n/a-structural | root-group law: must not be named "id" (case-insensitive) |
| `MVK_ROW_INVALID` | n/a-structural | field law: multi-row fields are legacy-only — forbidden in new-world models |
| `MVK_ROW_TOO_BIG` | n/a-structural | field law (legacy): multi-row fields capped at 100 000 rows |
| `MVK_RULE_NAME_INVALID` | pre-checked | `Rule.build()` rejects the reserved `vk_vk` prefix (any letter case) with the code named (locked: `RuleAuthoringTest`); the kernel law itself locked in `validate.laws.RuleSlotLawsTest` (KERNEL-FINDINGS “Reserved rule-name prefix”) |
| `MVK_SEVERITY_MISSING` | pre-checked | `Rule.build()` requires `severity(...)`; the typed `Severity` enum makes invalid values unrepresentable (locked: `RuleAuthoringTest`) |
| `MVK_SORT_FIELD_DUPLICATE` | n/a-structural | sort law: sort fields unique within a sort info |
| `MVK_SORT_INFO_MISSING` | n/a-structural | sort law: field, type, and priority of a sort entry fill together (all-or-none) |
| `MVK_SORT_PRIO_DUPLICATE` | n/a-structural | sort law: sort priorities unique |
| `MVK_SORT_PRIO_VALUE_MISSING` | n/a-structural | sort law: priorities must be contiguous — priority k requires k filled entries |
| `MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK` | n/a-structural | string-type law: no-value-validation requires the line-break flag to be set explicitly |
| `MVK_STRING_TYPE_PARAMS_INCONSISTENT` | n/a-structural | string-type law: no-value-validation excludes pattern/template/min-length |
| `MVK_SUPP_LANGUAGES_DUPLICATE` | n/a-structural | header law: declared languages must be pairwise distinct |
| `MVK_SUPP_LANGUAGES_MISSING` | n/a-structural | header law: the model must declare at least one supported language |
| `MVK_S_VALUE_INVALID` | n/a-structural | enum-type law: the sValue must be a declared internal value |
| `MVK_S_VALUE_NOT_ALLOWED` | n/a-structural | enum-type law: table-backed enums take no sValue |
| `MVK_TABLE_AND_COLUMN_NAME` | n/a-structural | enum-type law: table and column name fill together (all-or-none) |
| `MVK_TOO_MANY_INDEX_FIELDS` | n/a-structural | index law: at most one index field per group |
| `MVK_TYPE_MISSING` | n/a-structural | field law: a field needs an inline type or a type-definition reference |
| `MVK_UNSPECIFIED_TYPE_NO_CODEGEN` | n/a-structural | field law: the "unspecified" placeholder type cannot go through codegen |
| `MVK_VALUES_DUPLICATE` | n/a-structural | hint-list law: values unique per list |
| `MVK_VALUES_FOR_LANGUAGE_MISSING` | n/a-structural | enum-type law: display values must exist for every supported language |
| `MVK_VALUE_INVALID` | n/a-structural | hint-list law: values must be valid for the type (custom check) |
| `MVK_VALUE_LANGUAGES_DUPLICATE` | n/a-structural | enum-type law: one values block per language |
| `MVK_VALUE_LANGUAGE_INVALID` | n/a-structural | enum-type law: a values block’s language must be a supported language |
| `MVK_VALUE_TOO_LONG` | n/a-structural | hint-list law: values must respect the type’s max length |
| `MVK_VALUE_TOO_SHORT` | n/a-structural | hint-list law: values must respect the type’s min length |
| `MVK_YES_NO_INVALID` | n/a-structural | yes/no-type law: yes and no representations must differ |
| `MVK_YES_NO_INVALID_2` | n/a-structural | yes/no-type law: the yes/no pair must be one of the sanctioned encodings |
| `MVK_YES_VALUE_MISSING` | n/a-structural | yes/no-type law: the "yes" representation must be declared |
*Classified: 300 of 300 (codes may appear in more than one source) — the queue drained 2026-06-10 via the per-source resolution paths above; new codes arriving with a kernel bump enter as `unclassified` and follow the same paths.*
