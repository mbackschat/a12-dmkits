package io.github.mbackschat.a12.dm.consistency

/**
 * One check family's producer, paired with the [CheckFamily] constants it completes.
 *
 * **It exists to remove a duplicated denominator.** `checkPrepared` used to concatenate ninety-seven family
 * calls and then hand-list the hundred-and-three constants those calls complete, in a second literal nothing
 * related to the first — so a family could be wired into the report while `checksCompleted` stayed silent
 * about it, or named while its call site was gone. `CheckFamilyWiringTest` caught the second direction and
 * not the first. Here the advertisement is DERIVED from the list that runs, so the two cannot disagree.
 *
 * **And it is what a per-family cell is keyed on.** Each producer runs inside its own transcript bucket
 * ([TrackedFactBase.whileProducing]), so what one family read is separable from what the next one did. The
 * registration object itself is the bucket's key: it is a singleton in [CHECK_PRODUCERS], compares by
 * identity, and needs no name that could be spelled two ways.
 *
 * @param completes the families this producer answers for — a SET because one walk can settle several
 *     independent claims, as a group's ordering members do.
 * @param findings its findings, empty for a producer whose whole output is a coverage gap.
 * @param gaps its gaps, empty for the great majority that report none.
 */
internal class CheckProducer(
    val completes: Set<CheckFamily>,
    private val findings: (TrackedFactBase) -> List<Finding> = { emptyList() },
    private val gaps: (TrackedFactBase) -> List<CoverageGap> = { emptyList() },
) {
    fun findingsIn(tracked: TrackedFactBase): List<Finding> =
        tracked.whileProducing(this) { findings(tracked) }

    fun gapsIn(tracked: TrackedFactBase): List<CoverageGap> =
        tracked.whileProducing(this) { gaps(tracked) }
}

/**
 * The families `checkPrepared`'s own composition answers for, which therefore have no registration.
 *
 * Resolving a declared reference and resolving a condition's entities are done once for the whole model,
 * ahead of every family, because several families then ask what those passes found rather than re-resolving.
 * They are the boundary L-b's design draws around per-family reuse: a composition pass reads the whole model
 * by definition, so a cell keyed on what it read could never be reused.
 */
internal val COMPOSED_FAMILIES = setOf(CheckFamily.DECLARED_REFERENCES, CheckFamily.CONDITION_REFERENCES)

/** The single-family, findings-only registration, which is the great majority of those below. */
private fun produces(
    family: CheckFamily,
    findings: (TrackedFactBase) -> List<Finding>,
): CheckProducer = CheckProducer(setOf(family), findings)

/**
 * Every family that runs, in the order their findings reach the report.
 *
 * **The order is load-bearing for the findings and not for the gaps.** A report's findings are read in the
 * order they are produced, so this list preserves the concatenation it replaces exactly; the gaps were
 * emitted in an unrelated hand-written order and now follow this one, which is the single observable
 * difference and one no consumer can depend on — a gap is identified by its `at` and its reason.
 *
 * **[CheckFamily.DECLARED_REFERENCES] and [CheckFamily.CONDITION_REFERENCES] are absent on purpose.** They
 * are produced by `checkPrepared`'s own composition passes rather than by a family, which is the boundary
 * L-b's design draws: the composition is outside the per-family cells, so it has no registration to be given
 * one. `checkPrepared` adds them to the derived set and says why.
 */
internal val CHECK_PRODUCERS: List<CheckProducer> = listOf(
    produces(CheckFamily.DECLARATION_SLOTS) { it.declarationSlotFindings() },
    produces(CheckFamily.COMPUTATION_ALTERNATIVES) { it.computationAlternativeFindings() },
    produces(CheckFamily.COMPUTATION_ROUNDING) { it.computationRoundingFindings() },
    produces(CheckFamily.SUPPRESSED_WARNINGS) { it.suppressedWarningFindings() },
    produces(CheckFamily.RULE_CONDITION_AND_TARGET) { it.ruleConditionAndTargetFindings() },
    CheckProducer(
        completes = setOf(CheckFamily.VARIANT_FILTER),
        findings = { it.variantFilterFindings() },
        gaps = { it.variantFilterGaps() },
    ),
    produces(CheckFamily.CONDITION_LEXICALITY) { it.conditionLexicalFindings() },
    produces(CheckFamily.CONDITION_COMPLETENESS) { it.conditionCompletenessFindings() },
    produces(CheckFamily.CONNECTIVE_AGREEMENT) { it.connectiveAgreementFindings() },
    produces(CheckFamily.CONDITION_GRAMMAR) { it.conditionGrammarFindings() },
    produces(CheckFamily.ELEMENT_NAME_UNIQUENESS) { it.elementNameFindings() },
    CheckProducer(
        completes = setOf(
            CheckFamily.GROUP_ORDERING_REFERENCES,
            CheckFamily.GROUP_ORDERING_FIELD_TYPE,
            CheckFamily.GROUP_ORDERING_CARRIER,
        ),
        findings = { it.groupOrderingFindings() },
    ),
    produces(CheckFamily.GROUP_REPETITION_COUNT) { it.groupRepetitionCountFindings() },
    produces(CheckFamily.RULE_PRESENTATION_MEMBERS) { it.presentationMemberFindings() },
    produces(CheckFamily.STRING_SLOT_BINDING) { it.stringSlotFindings() },
    CheckProducer(
        completes = setOf(CheckFamily.FIELD_TYPE_BINDING),
        findings = { it.fieldTypeBindingFindings() },
        gaps = { it.fieldTypeBindingGaps() },
    ),
    produces(CheckFamily.TEMPORAL_CONFIG) { it.temporalConfigFindings() },
    produces(CheckFamily.FIELD_NAME_RESERVED_SEGMENT) { it.fieldNameReservedSegmentFindings() },
    produces(CheckFamily.ELEMENT_NAME_ALPHABET) { it.elementNameAlphabetFindings() },
    CheckProducer(
        completes = setOf(CheckFamily.ENUM_CATEGORY_ALIGNMENT),
        findings = { it.enumCategoryFindings() },
        gaps = { it.enumCategoryGaps() },
    ),
    CheckProducer(
        completes = setOf(CheckFamily.ENUM_VALUE_CHARACTERS),
        findings = { it.supportedCharacterFindings() },
        gaps = { it.supportedCharacterGaps() },
    ),
    CheckProducer(
        completes = setOf(CheckFamily.ENUM_TEXT_FORBIDDEN_CHARACTERS),
        findings = { it.enumTextForbiddenCharacterFindings() },
        gaps = { it.enumTextForbiddenCharacterGaps() },
    ),
    CheckProducer(
        completes = setOf(CheckFamily.HINT_LIST_VALUES),
        findings = { it.hintListValueFindings() },
        gaps = { it.hintListValueGaps() },
    ),
    produces(CheckFamily.TABLE_ENUM_OPERAND) { it.tableEnumOperandFindings() },
    CheckProducer(
        completes = setOf(CheckFamily.H_MODEL_FORM_CONSTRUCT),
        findings = { it.hModelFormConstructFindings() },
        gaps = { it.hModelFormConstructGaps() },
    ),
    CheckProducer(
        completes = setOf(CheckFamily.STRING_PATTERN_ADMISSION),
        findings = { it.declaredPatternFindings() },
        gaps = { it.declaredPatternGaps() },
    ),
    produces(CheckFamily.EMPTY_GROUP_OPERAND) { it.emptyGroupOperandFindings() },
    produces(CheckFamily.ERROR_FIELD_REFERENCE) { it.errorFieldReferenceFindings() },
    CheckProducer(
        completes = setOf(
            CheckFamily.GROUP_LIST_OPERANDS,
            CheckFamily.GROUP_LIST_ROOT_OPERAND,
            CheckFamily.GROUP_LIST_OPERAND_KIND,
        ),
        findings = { it.groupListOperandFindings() },
    ),
    produces(CheckFamily.REPETITION_WILDCARD_TARGET) { it.repetitionWildcardFindings() },
    produces(CheckFamily.COMPUTED_TARGET_KIND) { it.computedTargetKindFindings() },
    produces(CheckFamily.COMPUTED_TARGET_INDEX_FIELD) { it.computedTargetIndexFieldFindings() },
    produces(CheckFamily.RAW_TYPE_LENGTH) { it.rawTypeLengthFindings() },
    produces(CheckFamily.COMPUTATION_PRECONDITION_FEATURES) { it.computationPreconditionFeatureFindings() },
    produces(CheckFamily.COMPUTATION_CYCLE) { it.computationCycleFindings() },
    produces(CheckFamily.COMPUTATION_SELF_READ) { it.computationSelfReadFindings() },
    produces(CheckFamily.REPETITION_KEY_PLACEMENT) { it.repetitionKeyPlacementFindings() },
    produces(CheckFamily.REPETITION_SCOPE_GROUP) { it.repetitionScopeGroupFindings() },
    produces(CheckFamily.REPETITION_KEY_CONTAINMENT) { it.repetitionKeyContainmentFindings() },
    produces(CheckFamily.FILTER_WILDCARD) { it.filterWildcardFindings() },
    produces(CheckFamily.FILTER_BODY_GATES) { it.filterBodyGateFindings() },
    produces(CheckFamily.OUTER_MARKER_POSITION) { it.outerMarkerPositionFindings() },
    produces(CheckFamily.GROUP_CONDITION_WILDCARD) { it.groupConditionWildcardFindings() },
    produces(CheckFamily.SUM_OF_PRODUCTS_OPERANDS) { it.sumOfProductsOperandFindings() },
    produces(CheckFamily.OPERAND_LIST_CONSTANT_BUDGET) { it.operandListConstantFindings() },
    produces(CheckFamily.TEMPORAL_AGGREGATE_OPERANDS) { it.temporalAggregateOperandFindings() },
    produces(CheckFamily.SEMANTIC_INDEX_STRUCTURE) { it.semanticIndexStructureFindings() },
    produces(CheckFamily.SEMANTIC_INDEX_KEY) { it.semanticIndexKeyFindings() },
    produces(CheckFamily.REPETITION_NOT_UNIQUE_COMBINATION) { it.repetitionNotUniqueCombinationFindings() },
    produces(CheckFamily.PARALLEL_ITERATION_UNIQUENESS) { it.parallelIterationUniquenessFindings() },
    produces(CheckFamily.FILTERED_ENTITY_ITERATION) { it.filteredEntityIterationFindings() },
    produces(CheckFamily.ARITHMETIC_OPERATION_GATES) { it.arithmeticOperationGateFindings() },
    produces(CheckFamily.VALUE_IN_FIELDS_CONSTANT_KIND) { it.valueInFieldsConstantKindFindings() },
    produces(CheckFamily.VALUE_LIST_CONDITION_GATES) { it.valueListConditionGateFindings() },
    produces(CheckFamily.ENTITY_LIST_ARITY) { it.entityListArityFindings() },
    produces(CheckFamily.ENTITY_LIST_PRE_GATES) { it.entityListPreGateFindings() },
    produces(CheckFamily.FIELD_VALUES_NOT_UNIQUE_OPERAND_KIND) { it.fieldValuesNotUniqueOperandKindFindings() },
    produces(CheckFamily.FIELD_LIST_OPERATION_OPERANDS) { it.fieldListOperationOperandFindings() },
    produces(CheckFamily.DATE_ARITHMETIC_OPERANDS) { it.dateArithmeticOperandFindings() },
    produces(CheckFamily.ROUNDING_OPERANDS) { it.roundingOperandFindings() },
    produces(CheckFamily.FIELD_VALUE_OPERATOR_OPERANDS) { it.fieldValueOperatorOperandFindings() },
    produces(CheckFamily.COMPARISON_OPERATOR_OPERANDS) { it.comparisonOperatorOperandFindings() },
    produces(CheckFamily.BOOLEAN_COMPARISON_AGREEMENT) { it.booleanComparisonAgreementFindings() },
    produces(CheckFamily.STRING_ENUM_COMPARISON_AGREEMENT) { it.stringEnumComparisonAgreementFindings() },
    produces(CheckFamily.NUMBER_COMPARISON_SCALE) { it.numberComparisonScaleFindings() },
    produces(CheckFamily.TEMPORAL_COMPARISON_AGREEMENT) { it.temporalComparisonAgreementFindings() },
    produces(CheckFamily.PATTERN_COMPARISON_OPERANDS) { it.patternComparisonOperandFindings() },
    produces(CheckFamily.OPERAND_REPETITION_SCOPE) { it.operandRepetitionScopeFindings() },
    produces(CheckFamily.DATE_CONSTRUCTION_SLOT) { it.dateConstructionSlotFindings() },
    produces(CheckFamily.H_MODEL_PATH_SUFFIX) { it.hModelPathSuffixFindings() },
    produces(CheckFamily.FIELD_OPERAND_KIND) { it.fieldOperandKindFindings() },
    produces(CheckFamily.FILTERED_OPERAND_KIND) { it.filteredOperandKindFindings() },
    produces(CheckFamily.CATEGORY_REFERENCE) { it.categoryReferenceFindings() },
    produces(CheckFamily.SCALAR_VALUE_LIST_GATES) { it.scalarValueListGateFindings() },
    produces(CheckFamily.DATE_ARGUMENT_SLOT) { it.dateArgumentSlotFindings() },
    produces(CheckFamily.DATE_RANGE_OPERAND_LIST) { it.dateRangeOperandListFindings() },
    produces(CheckFamily.VALIDITY_CONDITION_OPERANDS) { it.validityConditionOperandFindings() },
    produces(CheckFamily.BASE_YEAR_REQUIREMENT) { it.baseYearRequirementFindings() },
    produces(CheckFamily.RANGE_OPERAND_GATES) { it.rangeOperandGateFindings() },
    produces(CheckFamily.ENUM_DISPLAY_COMPARABILITY) { it.enumDisplayComparabilityFindings() },
    produces(CheckFamily.VALUE_AS_DATE_OPERAND) { it.valueAsDateOperandFindings() },
    produces(CheckFamily.ENUM_COMPUTATION_SOURCE) { it.enumComputationSourceFindings() },
    produces(CheckFamily.ERROR_TARGET_ITERATION) { it.errorTargetIterationFindings() },
    produces(CheckFamily.ERROR_TARGET_CONTAINMENT) { it.errorTargetContainmentFindings() },
    produces(CheckFamily.MESSAGE_PARAMETER_REFERENCE) { it.messageParameterFindings() },
    produces(CheckFamily.ERROR_TEXT_SEMANTIC_INDEX) { it.errorTextSemanticIndexFindings() },
    produces(CheckFamily.ERROR_TEXT_CATEGORY_SUFFIX) { it.errorTextCategorySuffixFindings() },
    produces(CheckFamily.ERROR_TEXT_INDEX_TERMINAL) { it.errorTextIndexTerminalFindings() },
    produces(CheckFamily.MESSAGE_TEXT_CHARACTERS) { it.messageTextCharacterFindings() },
    produces(CheckFamily.MESSAGE_PARAMETER_SYNTAX) { it.messageParameterSyntaxFindings() },
    produces(CheckFamily.MESSAGE_GROUP_PARAMETER) { it.messageGroupParameterFindings() },
    produces(CheckFamily.MESSAGE_VALUE_SUFFIX) { it.messageValueSuffixFindings() },
    produces(CheckFamily.STRING_PATTERN_MESSAGE) { it.stringTypeMessageFindings() },
    produces(CheckFamily.STRING_TYPE_PARAMETERS) { it.stringTypeParameterFindings() },
    produces(CheckFamily.NUMBER_TYPE_DIGITS) { it.numberTypeDigitFindings() },
    produces(CheckFamily.ENUM_VALUE_LABELS) { it.enumValueLabelFindings() },
    produces(CheckFamily.ENUM_S_VALUE_DOMAIN) { it.enumSpecialValueFindings() },
    // The one producer that completes no family: `content.typeDefinitions` declare field types no family
    // examines, so its whole output is the disclosure that they were not examined. It is registered rather
    // than called beside the loop so that its reads are attributed like every other producer's.
    CheckProducer(completes = emptySet(), gaps = { it.typeDefinitionBodyGaps() }),
)
