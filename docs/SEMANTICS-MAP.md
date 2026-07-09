# Semantics map — where each kernel-semantics area lives

One page, keyed by the canonical kernel-truth taxonomy, that answers: *for a given semantics area, where is the canonical prose, where are our verified supplements, which catalog facets carry it machine-readably, which conformance-corpus families replay it, and which tests lock it.* The canonical prose is **[KERNEL-SEMANTICS.md](KERNEL-SEMANTICS.md)** (owner-authored, integrated in-repo 2026-07-09; its 14 numbered sections are the `§n` key everywhere below); our experiment-verified supplements live in [KERNEL-FINDINGS.md](KERNEL-FINDINGS.md) under `§n`-tagged headings; the machine-readable per-operator facets are the `semantics` dimensions of the operator catalog ([CATALOG-SPEC.md](CATALOG-SPEC.md)); the corpus families are the directories under [`corpus/cases/`](../corpus/README.md) whose cases carry the `§n` tag in `meta.sections` (the join key fixed by [CONFORMANCE-CORPUS-SPEC.md](CONFORMANCE-CORPUS-SPEC.md) §3); the locking tests live in the three lock trees — `validate/laws` (kernel accept/reject), `adapter/laws` (runtime fires/does-not-fire differentials against the real kernel), and the interpreter's multiplatform `commonTest` (JVM + JS). The two law tiers and how to run them: [TESTING-SPEC.md](TESTING-SPEC.md).

**How this page stays honest** — it is derivable-and-checkable, never hand-curated status: `cli … SemanticsMapResolveTest` (the dedicated `semanticsMapGuardTest` task) re-derives every column from its live surface and fails the build on drift — a `§n` findings claim without a `§n`-tagged KERNEL-FINDINGS heading, a facet dimension the catalog doesn't carry (or one it carries that the map omits), a corpus cell that disagrees with the committed cases' `meta.sections`, a cited test class that doesn't exist, or a lock-tree class the map doesn't place. A test class's section is its *primary* navigation home (assignment is judgment; existence and completeness are machine-checked).

## The taxonomy × surfaces

| § | Area | KERNEL-FINDINGS | Catalog facets | Corpus families |
|---|---|---|---|---|
| §1 | Truth values and logical evaluation | §1 supplements | — | — |
| §2 | Non-specified (empty) values | §2 supplements | `emptyOperand`, `aggregateIdentity` | — |
| §3 | Formal errors and the "unknown" state | §3 supplements | — | — |
| §4 | The "required" property | §4 supplements | — | — |
| §5 | Numbers and decimals | §5 supplements | `boundary` | `comparison` |
| §6 | Dates and time | §6 supplements | `boundary` | `clock` |
| §7 | Strings and patterns | §7 supplements | — | — |
| §8 | Enumerations | §8 supplements | — | — |
| §9 | Repetition, iteration, and cross-array evaluation | §9 supplements | `iterationRange` | `compute`, `fuzz` |
| §10 | Paths and references | §10 supplements | — | — |
| §11 | Computations | §11 supplements | — | `compute`, `fuzz` |
| §12 | The validation model | §12 supplements | `polarity` | `partial` |
| §13 | Error-message interpolation | §13 supplements | — | — |
| §14 | CustomCondition — the escape hatch | §14 supplements | — | — |

Facet placement follows the dimension's *question*: `emptyOperand` + `aggregateIdentity` answer §2's empty-value semantics, `boundary` answers threshold/interval endpoints (§5 orderings/tolerance, §6 date-range overlap), `iterationRange` answers §9's declared-vs-instantiated ranges, and `polarity` answers the §12 value-vs-omission message typing. Dimension contracts: [CATALOG-SPEC.md](CATALOG-SPEC.md) §3–§4.

## The locking tests

| § | validate/laws (accept/reject) | adapter/laws (kernel differential) | interpreter commonTest (JVM+JS) |
|---|---|---|---|
| §1 | `BooleanConfirmLawsTest`, `GroupQuantifierLawsTest`, `RootGroupCountLawsTest` | `BooleanThreeStateLawsTest`, `KleeneDiffTest`, `FieldListQuantifierLawsTest`, `FieldListQuantifierDiffTest`, `GroupListQuantifierDiffTest`, `StarredGroupQuantifierDiffTest`, `NumberOfFilledGroupsStarDiffTest`, `StarQuantifierInvalidCellDiffTest`, `GroupScopedFieldFillDiffTest` | `KleeneMonotonicityPropertyTest`, `LazyEagerPolarityPropertyTest`, `StarredGroupQuantifierTest` |
| §2 | — | `EmptyValueLawsTest`, `EmptyRowGateDiffTest`, `EmptyOperandFiringDiffTest`, `FilledCountLawsTest`, `NumberOfFilledDiffTest`, `NumberOfValueInFieldsDiffTest`, `AbsLengthDiffTest` | `EmptyOperandFiringTest`, `EmptyRowAppendPropertyTest`, `AggregateIdentityPropertyTest` |
| §3 | — | `FormalErrorLawsTest`, `FormalErrorScopeLawsTest`, `BaselineValueFormalDiffTest`, `SupportedCharactersLawsTest`, `FieldConstraintFormalDiffTest`, `NumberSignFormalDiffTest`, `SuppressionDiffTest`, `QuantifierMalformedDiffTest`, `AggMalformedDiffTest`, `ComputedDateGregorianFloorDiffTest` | `LegalCharsetTest`, `NumberDigitFormalTest`, `DateGregorianFloorFormalTest` |
| §4 | — | `RequiredPropertyLawsTest`, `RequirednessDiffTest`, `IndexFieldLawsTest`, `UniquenessDiffTest`, `UniqSuppressionDiffTest`, `IndexAggregateDiffTest`, `FieldValuesNotUniqueDiffTest` | `IndexAggregateTest` |
| §5 | `NumberLawsTest` | `RoundingLawsTest`, `ToleranceComparisonLawsTest`, `ToleranceDiffTest`, `PrecisionDiffTest`, `ArithmeticDiffTest`, `DivPowDiffTest`, `RoundDiffTest`, `FieldValueCoercionDiffTest` | `CompareScaled19Test`, `RoundToTest`, `DecEqualityTest`, `DocumentScaleTest` |
| §6 | `DateExtractionLawsTest`, `DateFragmentArithmeticLawsTest`, `DateFragmentComparabilityLawsTest` | `DateArithmeticLawsTest`, `DateRangeComputationLawsTest`, `DateRangeOverlapLawsTest`, `DateValidityLawsTest`, `ClockDiffTest`, `NowDiffTest`, `BaseYearDiffTest`, `Date4ArgConstructionDiffTest`, `DateAggregateDiffTest`, `DateArithmeticDiffTest`, `DateConstructionDiffTest`, `DateDifferenceDiffTest`, `DateExtractDiffTest`, `DateFragmentComparisonDiffTest`, `DateLiteralDiffTest`, `DateRangeComputeDiffTest`, `DateRangeDiffTest`, `DateRangeFormalDiffTest`, `DateRangeOverlapSemanticsDiffTest`, `DateTimeArithmeticDiffTest`, `DateTimeConstructDiffTest`, `DateTimeExtractDiffTest`, `DifferenceInDaysDiffTest`, `OmittedYearDateDiffTest`, `PlainDateDiffTest`, `TimeDateTimeFormalDiffTest`, `TimeExtractDiffTest`, `ValueAsDateDiffTest`, `RangeCoercionDiffTest` | `DateFormatTest`, `DateFragmentEvalTest`, `DateRangeOverlapTest` |
| §7 | `PatternLawsTest`, `StringOpLawsTest` | `PatternDiffTest` | — |
| §8 | `EnumComparabilityLawsTest`, `ValueListLawsTest` | `EnumCategoryLawsTest`, `EnumCategoryDiffTest`, `ValueListDiffTest`, `ValueListQuantifierDiffTest`, `ValueListQuantifierHavingDiffTest`, `ValueListQuantifierStarDiffTest`, `EntityValueListDiffTest`, `PredefinedTypeValidityDiffTest`, `CustomFieldTypeBoundsDiffTest`, `MultiSelectUniquenessDiffTest` | `CustomFieldTypeMultiplatformTest`, `CustomFieldTypeSpiTest` |
| §9 | `AggregateSlotLawsTest`, `IterationLawsTest`, `SemanticIndexSlotLawsTest`, `SumOfProductsLawsTest` | `AggregateUncertaintyLawsTest`, `CorrelationLawsTest`, `ParallelIterationLawsTest`, `RepetitionLawsTest`, `RepetitionNotUniqueLawsTest`, `SemanticIndexLawsTest`, `FirstFilledValueLawsTest`, `CurrentRepetitionDiffTest`, `FirstFilledValueDiffTest`, `FirstFilledValueKindDiffTest`, `FramedParallelIterationDiffTest`, `WrappedParallelIterationDiffTest`, `ParallelIterationDiffTest`, `GroupFilledDiffTest`, `NonRepGroupFilledContentDiffTest`, `RepeatableRowContentDiffTest`, `HavingDiffTest`, `OuterCorrelationDiffTest`, `RepetitionDiffTest`, `RepetitionNotUniqueDiffTest`, `RepetitionNotUniqueFormalKeyDiffTest`, `RepetitionNotUniqueGuardedDiffTest`, `RepetitionNotUniqueNestedDiffTest`, `SemanticIndexDiffTest`, `SemanticIndexFieldKeyedDiffTest`, `SemanticIndexPresenceDiffTest`, `SumOfProductsDiffTest`, `MultiFieldAggregateDiffTest`, `OverRepetitionDiffTest`, `RuleGroupSelfReferenceDiffTest` | `HashJoinPropertyTest`, `RowPermutationPropertyTest`, `RepetitionNotUniqueFormalKeyTest`, `RepetitionNotUniqueGuardedTest`, `RuleGroupSelfReferenceTest` |
| §10 | `PathLawsTest` | `NamedAncestorPathDiffTest` | `AbsolutePathParseTest`, `ParentRelativePathParseTest`, `NamedAncestorPathParseTest`, `PathWhitespaceParseTest`, `ParentUpWhitespaceParseTest` |
| §11 | — | `ComputationEvaluationLawsTest`, `ComputationValidationLawsTest`, `ComputationGuardedValidationDiffTest`, `ComputationPreconditionDiffTest`, `ComputationValidationDiffTest`, `ComputeApplyDiffTest`, `ComputeCascadeDiffTest`, `ComputeCascadeErroredDiffTest`, `ComputeDiffTest`, `ComputeErrorBucketDiffTest`, `ComputeInvalidOperandDiffTest`, `ComputePerRowIdentityDiffTest`, `ComputePoisonReadDiffTest`, `ComputeTargetFormatDiffTest`, `ComputedNumberScaleDiffTest`, `ConcatComputeDiffTest`, `ConcatInvalidOperandDiffTest`, `CascadeClearedDependencyDiffTest`, `CascadeComputedOperandScaleDiffTest`, `CascadeThroughStarDiffTest`, `CrossGroupConstantComputeScopeDiffTest`, `MultiComputationFieldDiffTest`, `MultiRootComputationPointerDiffTest` | `ComputeGranularityTest`, `ComputeOperandInvalidityTest`, `ComputePoisonTest`, `GuardedComputationValidationTest`, `MultiComputationFieldTest`, `MultiRootComputationPointerTest`, `CrossGroupConstantComputeScopeTest`, `FirstFilledValueEmptyTest`, `CascadeComputedOperandScaleTest` |
| §12 | — | `ErrorTypeLawsTest`, `PolarityLawsTest`, `RuleTestLawsTest`, `TestRuleDiffTest`, `JavaStaticPartialBaselineDiffTest`, `PartialValidationDiffTest`, `PartialValidationAggregateDiffTest`, `PartialValidationGlobalFieldDiffTest`, `PartialValidationRepetitionNotUniqueDiffTest`, `PartialValidationSubsetRepDiffTest`, `PartialValidationUniqueIndexDiffTest`, `PolarityDiffTest`, `AggPolarityDiffTest`, `AggDirectionalPolarityDiffTest`, `DirectionalPolarityDiffTest`, `ArithmeticFillPolarityDiffTest`, `FunctionFillPolarityDiffTest` | `PartialValidationTest`, `SeverityInfoTest`, `ArithmeticFillTest`, `FunctionFillTest` |
| §13 | `RuleSlotLawsTest` | `DisplayNameDiffTest`, `RichMessageDiffTest`, `LocalizedMessageDiffTest`, `FormalErrorMessageDiffTest` | — |
| §14 | — | `CustomConditionDiffTest`, `PartialValidationCustomConditionDiffTest` | `CustomConditionMultiplatformTest` |

## Outside the taxonomy

Lock-tree classes whose subject is not one kernel-semantics section — the concrete-syntax tier, the verification harnesses, and the model/instance plumbing. (The partition is exhaustive: a lock-tree class is either in a § row above or here, and the guard enforces it.)

| Lane | Guards |
|---|---|
| Condition concrete syntax (lexing, comments, directives — KERNEL-FINDINGS "Condition lexing"/"Condition comments"/"Condition directives") | `DirectiveLawsTest`, `CommentConditionDiffTest`, `ParserDiffTest`, `ParserCorpusSweepTest`, `KeywordCaseVariantParseTest`, `CommentSkipParseTest`, `ForIndexArgParseTest`, `ValueListLiteralParseTest`, `ValueListQuantifierHavingParseTest` |
| Operator-catalog verification lanes ([CATALOG-SPEC.md](CATALOG-SPEC.md) §5) | `CatalogFacetProbesTest`, `CatalogParityTest` |
| Conformance corpus — capture, replay, schema ([CONFORMANCE-CORPUS-SPEC.md](CONFORMANCE-CORPUS-SPEC.md)) | `CorpusCaptureTest`, `CorpusReplayTest`, `CorpusSchemaGuardTest`, `CorpusModelPreparerTest`, `CorpusReplayCommonTest`, `CaseJsonTest` |
| Cross-cutting differentials & engine seams (whole-surface, no single section) | `ValidateFullDiffTest`, `InterpreterDiffTest`, `OperatorBatteryDiffTest`, `FullMessageFuzzDiffTest`, `FuzzDiagnosticsTest`, `KernelProbeTest`, `EvalDocSeamLawsTest`, `InterpreterMultiplatformTest`, `DeterminismPropertyTest`, `UnsupportedDegradeTest` |
| Triage / sweep instruments (opt-in, tagged) | `ModelShrinkTest`, `RawAtomShrinkTest`, `RealCorpusSweepTest` |
| DM-JSON loader & instance seeding | `ModelLoaderTest`, `SeedGeneratorTest`, `ValidValuesConstraintTest` |
