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
 * **Three shapes for two granularities of cell.** A [ModelWide] producer answers about the model and is
 * cached once. [PerDeclaration] and [PerDeclarationWalk] are both asked once per declaration and cached per
 * declaration, so a keystroke fells one declaration's cell instead of the family's whole verdict; they
 * differ in HOW the subject reaches the family — as a parameter, or as a fact base scoped to it — and that
 * difference is what the walk's equivalence claim is about. The split is a sealed hierarchy rather than a
 * nullable lambda so the dispatcher's `when` is exhaustive and a fourth shape cannot be added without every
 * dispatch site being made to account for it.
 */
internal sealed interface CheckProducer {

    /**
     * The families this producer answers for — a SET because one walk can settle several independent claims,
     * as a group's ordering members do.
     */
    val completes: Set<CheckFamily>

    /**
     * Whether A12 raises this producer's classes only once the condition has PARSED, whatever their codes.
     *
     * A code does not own its phase: the filter walk raises `MVK_SEMANTIC_INDEX_NOT_ALLOWED` during the parse, and
     * `ParserService.checkNormalRule` raises it again after the parse for a read the walk skipped
     * ([KERNEL-FINDINGS KF487]). So report assembly reads a finding's phase off the producer that raised it
     * before it reads it off the code.
     */
    val raisesAfterTheParse: Boolean get() = false

    /**
     * A family whose verdict is about the whole model, cached against one transcript.
     *
     * @param findings its findings, empty for a producer whose whole output is a coverage gap.
     * @param gaps its gaps, empty for the great majority that report none.
     */
    class ModelWide(
        override val completes: Set<CheckFamily>,
        val findings: (TrackedFactBase) -> List<Finding> = { emptyList() },
        val gaps: (TrackedFactBase) -> List<CoverageGap> = { emptyList() },
        override val raisesAfterTheParse: Boolean = false,
    ) : CheckProducer

    /**
     * A family asked once per declaration, with the WALK moved out of it into the dispatcher.
     *
     * That move is the point rather than a tidy-up: while the family walked, its transcript named every
     * declaration it opened, so one cell depended on every body and a keystroke anywhere felled it. Asked
     * per declaration, each cell records that declaration's body alone — and the dispatcher's own walk is
     * what handles set membership, since a declaration that is gone is never asked for and a new one has no
     * cell to be wrong.
     *
     * **Which families may take this shape is a per-family claim, not a refactor.** It holds where the
     * verdict about one declaration is a function of that declaration plus facts the pass charges — and a
     * set fact read through [TrackedFactBase.allDeclarations] charges the set shape, so reading one is
     * sound and merely wide. *Corrected 2026-09-18: this said a family that RESOLVES A REFERENCE stays
     * [ModelWide]. Such families are per-declaration today (L-b3c-3a/3b) and the ones that moved read their
     * set facts through `allDeclarations`; what stays [ModelWide] is a family whose subject IS a relation
     * between declarations — a dependency circle, a key's containment in an iteration scope.*
     */
    class PerDeclaration(
        override val completes: Set<CheckFamily>,
        val findings: (TrackedFactBase, TrackedDeclaration) -> List<Finding>,
    ) : CheckProducer

    /**
     * A family that still WALKS, asked once per declaration through a fact base scoped to it.
     *
     * **It is the same granularity as [PerDeclaration] reached without rewriting the family**, and it is a
     * separate kind rather than a flag because it is the kind that can be CHECKED: what it claims is an
     * equivalence — the concatenation of its scoped passes is the list it produced unscoped — and that
     * claim is only testable when the dispatcher knows which producers make it.
     * `CheckFamilyWiringTest.aWalkingFamilyAskedPerDeclarationSaysWhatItSaidOverTheWholeModel` runs that
     * differential over every registration here on this module's fixtures and the parity corpus, and
     * `ScopedWalkBranchDiscriminatorsTest` runs it on models built to reach a family's SECOND branch —
     * which is the population that matters, because per-producer non-vacuity is not per-BRANCH coverage and
     * four families were wrong under the first alone (2026-09-18).
     *
     * The gate matters because the failure is SILENT otherwise. A family that uses the walk to look up
     * ANOTHER declaration — an operand's field, to learn its type — gets one declaration from a scoped base
     * and finds nothing, so it under-reports while charging no set shape at all: the charge-profile
     * invariant cannot see it, and only that family's own tests would. Four families were flipped to this
     * shape on 2026-09-18 and duly failed their own tests; the differential is what turns that luck into a
     * denominator.
     */
    class PerDeclarationWalk(
        override val completes: Set<CheckFamily>,
        val findings: (TrackedFactBase) -> List<Finding>,
    ) : CheckProducer
}

/**
 * Which declaration a [CheckProducer.PerDeclaration] cell is about.
 *
 * **The ordinal is not defensive, it is required.** A model may declare two elements at one path — A12
 * refuses such a model and this checker has to report on it rather than fail — so a key of the path alone
 * would give both declarations one cell, and the second would overwrite the first's findings. That drops a
 * finding from the report rather than merely losing reuse. An INDEX into the declaration list would key them
 * apart too and would move every later declaration's key when one is inserted, which is most of what an
 * editor does.
 */
internal data class DeclarationKey(val path: String, val amongSamePath: Int)

/** One declaration as the dispatcher holds it: its key, its cursor, and the fact base scoped to it. */
private data class Walked(
    val at: DeclarationKey,
    val cursor: TrackedDeclaration,
    val scoped: TrackedFactBase,
)

/** What one cell is about: a producer, and for a per-declaration producer the declaration too. */
internal data class CellKey(val producer: CheckProducer, val at: DeclarationKey? = null)

/** One cell a check must produce: what it is about, and how to produce it. */
internal class ScheduledCell(
    val key: CellKey,
    val produce: () -> Pair<List<Finding>, List<CoverageGap>>,
)

/**
 * Every cell one check over [tracked] must produce, in the order their output reaches the report.
 *
 * Producer-major, declarations in declaration order — which is exactly the order a per-declaration family
 * produced while it walked itself, so moving the walk out here changes no report.
 *
 * **The walk charges the COMPOSITION and not any cell**, which `ReadTranscript` argues is sound as well as
 * necessary: set membership is handled by this list existing, since a declaration that is gone is never
 * scheduled and a new one has no cell to be stale.
 *
 * **The ordinal is computed here because this is the only place that sees the whole list.** Two declarations
 * at one path — a model A12 refuses and this checker still reports on — must get two keys, or the second's
 * findings would overwrite the first's and leave the report short.
 */
internal fun schedule(tracked: TrackedFactBase): List<ScheduledCell> {
    val seen = mutableMapOf<String, Int>()
    val walked = tracked.declarations().map { cursor ->
        val ordinal = seen.getOrElse(cursor.path) { 0 }
        seen[cursor.path] = ordinal + 1
        val at = DeclarationKey(cursor.path, ordinal)
        // One scoped view per declaration, shared by every per-declaration producer: the scope holds no
        // facts, but a view per producer per declaration would allocate one for nothing.
        Walked(at, cursor, tracked.scopedTo(at, cursor))
    }
    return CHECK_PRODUCERS.flatMap { producer ->
        when (producer) {
            is CheckProducer.ModelWide -> listOf(
                ScheduledCell(CellKey(producer)) {
                    producer.findings(tracked) to producer.gaps(tracked)
                },
            )
            is CheckProducer.PerDeclaration -> walked.map { (at, cursor, _) ->
                ScheduledCell(CellKey(producer, at)) {
                    // A family taking its declaration as a parameter never calls `declarations()`, so
                    // nothing else would say which declaration its cell answered about
                    // ([FactRead.DeclarationRow]).
                    tracked.chargeDeclarationRow(at)
                    producer.findings(tracked, cursor) to emptyList()
                }
            }
            // No charge here, deliberately: a scoped `declarations()` records the row itself, and the
            // explicit call was measured redundant — a mutant removing it survived every control
            // (2026-09-18). A walk that returned BEFORE iterating would charge no row and be refused by
            // `FamilyChargeProfileTest`, which is the loud outcome rather than a silent one: such a family
            // decided on a model-wide fact and belongs in the parameter form. The refusal reaches only the
            // returns that test's fixture takes, and it declares no Base Year, so the two Base Year families'
            // return on `hasBaseYear` goes unseen. That one is sound: the fact is charged, and the answer it
            // gives is empty whatever the declaration holds.
            is CheckProducer.PerDeclarationWalk -> walked.map { (at, _, scoped) ->
                ScheduledCell(CellKey(producer, at)) { producer.findings(scoped) to emptyList() }
            }
        }
    }
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
): CheckProducer = CheckProducer.ModelWide(setOf(family), findings)

/** …and its twin for classes A12 raises only once the condition has parsed ([CheckProducer.raisesAfterTheParse]). */
private fun producesAfterTheParse(
    family: CheckFamily,
    findings: (TrackedFactBase) -> List<Finding>,
): CheckProducer = CheckProducer.ModelWide(setOf(family), findings, raisesAfterTheParse = true)

/** …and its per-declaration twin, for a family whose verdict is about one declaration at a time. */
private fun perDeclaration(
    family: CheckFamily,
    findings: (TrackedFactBase, TrackedDeclaration) -> List<Finding>,
): CheckProducer = CheckProducer.PerDeclaration(setOf(family), findings)

/**
 * A family that still writes its own WALK, asked once per declaration through a fact base scoped to it.
 *
 * The migration shape, and it is not a lesser one: [TrackedFactBase.scopedTo] carries why one pass over one
 * declaration is the same list, and what stops a set-reaching family from being registered this way. A
 * family whose signature takes the declaration outright uses [perDeclaration]; that is the destination, and
 * this is how a family gets there without its body being rewritten.
 */
private fun perDeclarationWalk(
    family: CheckFamily,
    findings: (TrackedFactBase) -> List<Finding>,
): CheckProducer = CheckProducer.PerDeclarationWalk(setOf(family), findings)

/**
 * Every family that runs, in the order their findings reach the report.
 *
 * **The order is load-bearing for the findings and not for the gaps.** A report's findings are read in the
 * order they are produced, so this list preserves the concatenation it replaces exactly; the gaps were
 * emitted in an unrelated hand-written order and now follow this one, which is the single observable
 * difference and one no consumer can depend on — a gap is identified by its `at` and its reason.
 *
 * The order is family-major, so moving an arm into a family of its own reorders what a reader sees, though
 * the report shape promises no order (`docs/CONSISTENCY-API.md`). Splitting [CheckFamily.ERROR_TEXT_PRESENCE]
 * and [CheckFamily.MESSAGE_BASE_YEAR] out of the slot and Base Year families (2026-09-24) did exactly that:
 * `RULE_HAS_NO_ERRORTEXT` now follows every slot finding in the model rather than its own declaration's, and
 * `NO_BASE_YEAR` follows the message-reference classes rather than preceding them.
 *
 * **[CheckFamily.DECLARED_REFERENCES] and [CheckFamily.CONDITION_REFERENCES] are absent on purpose.** They
 * are produced by `checkPrepared`'s own composition passes rather than by a family, which is the boundary
 * L-b's design draws: the composition is outside the per-family cells, so it has no registration to be given
 * one. `checkPrepared` adds them to the derived set and says why.
 */
internal val CHECK_PRODUCERS: List<CheckProducer> = listOf(
    produces(CheckFamily.DECLARATION_SLOTS) { it.declarationSlotFindings() },
    perDeclarationWalk(CheckFamily.ERROR_TEXT_PRESENCE) { it.errorTextPresenceFindings() },
    perDeclarationWalk(CheckFamily.COMPUTATION_ALTERNATIVES) { it.computationAlternativeFindings() },
    perDeclarationWalk(CheckFamily.COMPUTATION_ROUNDING) { it.computationRoundingFindings() },
    perDeclarationWalk(CheckFamily.SUPPRESSED_WARNINGS) { it.suppressedWarningFindings() },
    perDeclaration(CheckFamily.RULE_CONDITION_AND_TARGET) { base, at ->
        base.ruleConditionAndTargetFindings(at)
    },
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.VARIANT_FILTER),
        findings = { it.variantFilterFindings() },
        gaps = { it.variantFilterGaps() },
    ),
    perDeclaration(CheckFamily.CONDITION_LEXICALITY) { base, at -> base.conditionLexicalFindings(at) },
    perDeclaration(CheckFamily.CONDITION_COMPLETENESS) { base, at -> base.conditionCompletenessFindings(at) },
    perDeclaration(CheckFamily.CONNECTIVE_AGREEMENT) { base, at -> base.connectiveAgreementFindings(at) },
    perDeclaration(CheckFamily.CONDITION_GRAMMAR) { base, at -> base.conditionGrammarFindings(at) },
    produces(CheckFamily.ELEMENT_NAME_UNIQUENESS) { it.elementNameFindings() },
    CheckProducer.ModelWide(
        completes = setOf(
            CheckFamily.GROUP_ORDERING_REFERENCES,
            CheckFamily.GROUP_ORDERING_FIELD_TYPE,
            CheckFamily.GROUP_ORDERING_CARRIER,
        ),
        findings = { it.groupOrderingFindings() },
    ),
    perDeclaration(CheckFamily.GROUP_REPETITION_COUNT) { base, at ->
        listOfNotNull(base.groupRepetitionCountFinding(at))
    },
    perDeclarationWalk(CheckFamily.RULE_PRESENTATION_MEMBERS) { it.presentationMemberFindings() },
    perDeclaration(CheckFamily.STRING_SLOT_BINDING) { base, at -> base.stringSlotFindings(at) },
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.FIELD_TYPE_BINDING),
        findings = { it.fieldTypeBindingFindings() },
        gaps = { it.fieldTypeBindingGaps() },
    ),
    perDeclaration(CheckFamily.TEMPORAL_CONFIG) { base, at -> base.temporalConfigFindings(at) },
    perDeclaration(CheckFamily.FIELD_NAME_RESERVED_SEGMENT) { base, at ->
        listOfNotNull(base.fieldNameReservedSegmentFinding(at))
    },
    perDeclaration(CheckFamily.ELEMENT_NAME_ALPHABET) { base, at ->
        base.elementNameAlphabetFindings(at)
    },
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.ENUM_CATEGORY_ALIGNMENT),
        findings = { it.enumCategoryFindings() },
        gaps = { it.enumCategoryGaps() },
    ),
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.ENUM_VALUE_CHARACTERS),
        findings = { it.supportedCharacterFindings() },
        gaps = { it.supportedCharacterGaps() },
    ),
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.ENUM_TEXT_FORBIDDEN_CHARACTERS),
        findings = { it.enumTextForbiddenCharacterFindings() },
        gaps = { it.enumTextForbiddenCharacterGaps() },
    ),
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.MODEL_TEXT_REPERTOIRE),
        findings = { it.modelTextRepertoireFindings() },
    ),
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.HINT_LIST_VALUES),
        findings = { it.hintListValueFindings() },
        gaps = { it.hintListValueGaps() },
    ),
    perDeclarationWalk(CheckFamily.TABLE_ENUM_OPERAND) { it.tableEnumOperandFindings() },
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.H_MODEL_FORM_CONSTRUCT),
        findings = { it.hModelFormConstructFindings() },
        gaps = { it.hModelFormConstructGaps() },
    ),
    CheckProducer.ModelWide(
        completes = setOf(CheckFamily.STRING_PATTERN_ADMISSION),
        findings = { it.declaredPatternFindings() },
        gaps = { it.declaredPatternGaps() },
    ),
    perDeclarationWalk(CheckFamily.EMPTY_GROUP_OPERAND) { it.emptyGroupOperandFindings() },
    produces(CheckFamily.ERROR_FIELD_REFERENCE) { it.errorFieldReferenceFindings() },
    CheckProducer.ModelWide(
        completes = setOf(
            CheckFamily.GROUP_LIST_OPERANDS,
            CheckFamily.GROUP_LIST_ROOT_OPERAND,
            CheckFamily.GROUP_LIST_OPERAND_KIND,
        ),
        findings = { it.groupListOperandFindings() },
    ),
    perDeclarationWalk(CheckFamily.REPETITION_WILDCARD_TARGET) { it.repetitionWildcardFindings() },
    perDeclarationWalk(CheckFamily.COMPUTED_TARGET_KIND) { it.computedTargetKindFindings() },
    perDeclarationWalk(CheckFamily.COMPUTED_TARGET_INDEX_FIELD) { it.computedTargetIndexFieldFindings() },
    perDeclarationWalk(CheckFamily.RAW_TYPE_LENGTH) { it.rawTypeLengthFindings() },
    perDeclaration(CheckFamily.COMPUTATION_PRECONDITION_FEATURES) { base, at ->
        base.computationPreconditionFeatureFindings(at)
    },
    produces(CheckFamily.COMPUTATION_CYCLE) { it.computationCycleFindings() },
    perDeclarationWalk(CheckFamily.COMPUTATION_SELF_READ) { it.computationSelfReadFindings() },
    perDeclarationWalk(CheckFamily.REPETITION_KEY_LIST) { it.repetitionKeyListFindings() },
    perDeclarationWalk(CheckFamily.REPETITION_KEY_PLACEMENT) { it.repetitionKeyPlacementFindings() },
    perDeclarationWalk(CheckFamily.REPETITION_SCOPE_GROUP) { it.repetitionScopeGroupFindings() },
    produces(CheckFamily.REPETITION_KEY_CONTAINMENT) { it.repetitionKeyContainmentFindings() },
    perDeclarationWalk(CheckFamily.FILTER_WILDCARD) { it.filterWildcardFindings() },
    produces(CheckFamily.FILTER_BODY_GATES) { it.filterBodyGateFindings() },
    producesAfterTheParse(CheckFamily.FILTER_BODY_GATES) { it.filterBodyPostParseIndexFindings() },
    perDeclarationWalk(CheckFamily.OUTER_MARKER_POSITION) { it.outerMarkerPositionFindings() },
    perDeclarationWalk(CheckFamily.GROUP_CONDITION_WILDCARD) { it.groupConditionWildcardFindings() },
    perDeclarationWalk(CheckFamily.SUM_OF_PRODUCTS_OPERANDS) { it.sumOfProductsOperandFindings() },
    perDeclaration(CheckFamily.OPERAND_LIST_CONSTANT_BUDGET) { base, at ->
        base.operandListConstantFindings(at)
    },
    produces(CheckFamily.TEMPORAL_AGGREGATE_OPERANDS) { it.temporalAggregateOperandFindings() },
    produces(CheckFamily.SEMANTIC_INDEX_STRUCTURE) { it.semanticIndexStructureFindings() },
    produces(CheckFamily.SEMANTIC_INDEX_KEY) { it.semanticIndexKeyFindings() },
    perDeclaration(CheckFamily.REPETITION_NOT_UNIQUE_COMBINATION) { base, at ->
        base.repetitionNotUniqueCombinationFindings(at)
    },
    produces(CheckFamily.PARALLEL_ITERATION_UNIQUENESS) { it.parallelIterationUniquenessFindings() },
    produces(CheckFamily.FILTERED_ENTITY_ITERATION) { it.filteredEntityIterationFindings() },
    perDeclarationWalk(CheckFamily.ARITHMETIC_OPERATION_GATES) { it.arithmeticOperationGateFindings() },
    produces(CheckFamily.VALUE_IN_FIELDS_CONSTANT_KIND) { it.valueInFieldsConstantKindFindings() },
    perDeclarationWalk(CheckFamily.VALUE_LIST_CONDITION_GATES) { it.valueListConditionGateFindings() },
    produces(CheckFamily.ENTITY_LIST_ARITY) { it.entityListArityFindings() },
    perDeclarationWalk(CheckFamily.ENTITY_LIST_PRE_GATES) { it.entityListPreGateFindings() },
    perDeclarationWalk(CheckFamily.FIELD_VALUES_NOT_UNIQUE_OPERAND_KIND) {
        it.fieldValuesNotUniqueOperandKindFindings()
    },
    produces(CheckFamily.FIELD_LIST_OPERATION_OPERANDS) { it.fieldListOperationOperandFindings() },
    perDeclarationWalk(CheckFamily.DATE_ARITHMETIC_OPERANDS) { it.dateArithmeticOperandFindings() },
    perDeclarationWalk(CheckFamily.ROUNDING_OPERANDS) { it.roundingOperandFindings() },
    perDeclarationWalk(CheckFamily.FIELD_VALUE_OPERATOR_OPERANDS) { it.fieldValueOperatorOperandFindings() },
    perDeclarationWalk(CheckFamily.FIELD_VALUE_READ_OPERAND) { it.fieldValueReadOperandFindings() },
    perDeclarationWalk(CheckFamily.COMPARISON_OPERATOR_OPERANDS) { it.comparisonOperatorOperandFindings() },
    perDeclarationWalk(CheckFamily.BOOLEAN_COMPARISON_AGREEMENT) { it.booleanComparisonAgreementFindings() },
    perDeclarationWalk(CheckFamily.STRING_ENUM_COMPARISON_AGREEMENT) { it.stringEnumComparisonAgreementFindings() },
    perDeclarationWalk(CheckFamily.NUMBER_COMPARISON_SCALE) { it.numberComparisonScaleFindings() },
    perDeclarationWalk(CheckFamily.TEMPORAL_COMPARISON_AGREEMENT) { it.temporalComparisonAgreementFindings() },
    perDeclarationWalk(CheckFamily.PATTERN_COMPARISON_OPERANDS) { it.patternComparisonOperandFindings() },
    produces(CheckFamily.OPERAND_REPETITION_SCOPE) { it.operandRepetitionScopeFindings() },
    produces(CheckFamily.DATE_CONSTRUCTION_SLOT) { it.dateConstructionSlotFindings() },
    perDeclarationWalk(CheckFamily.H_MODEL_PATH_SUFFIX) { it.hModelPathSuffixFindings() },
    produces(CheckFamily.FIELD_OPERAND_KIND) { it.fieldOperandKindFindings() },
    produces(CheckFamily.FILTERED_OPERAND_KIND) { it.filteredOperandKindFindings() },
    perDeclarationWalk(CheckFamily.CATEGORY_REFERENCE) { it.categoryReferenceFindings() },
    perDeclarationWalk(CheckFamily.ENTITY_LIST_CATEGORY_GATE) { it.entityListCategoryFindings() },
    produces(CheckFamily.SCALAR_VALUE_LIST_GATES) { it.scalarValueListGateFindings() },
    produces(CheckFamily.DATE_ARGUMENT_SLOT) { it.dateArgumentSlotFindings() },
    produces(CheckFamily.DATE_RANGE_OPERAND_LIST) { it.dateRangeOperandListFindings() },
    perDeclarationWalk(CheckFamily.VALIDITY_CONDITION_OPERANDS) { it.validityConditionOperandFindings() },
    perDeclarationWalk(CheckFamily.BASE_YEAR_REQUIREMENT) { it.baseYearRequirementFindings() },
    produces(CheckFamily.RANGE_OPERAND_GATES) { it.rangeOperandGateFindings() },
    perDeclarationWalk(CheckFamily.ENUM_DISPLAY_COMPARABILITY) { it.enumDisplayComparabilityFindings() },
    produces(CheckFamily.VALUE_AS_DATE_OPERAND) { it.valueAsDateOperandFindings() },
    perDeclarationWalk(CheckFamily.ENUM_COMPUTATION_SOURCE) { it.enumComputationSourceFindings() },
    perDeclarationWalk(CheckFamily.ERROR_TARGET_ITERATION) { it.errorTargetIterationFindings() },
    produces(CheckFamily.ERROR_TARGET_CONTAINMENT) { it.errorTargetContainmentFindings() },
    perDeclarationWalk(CheckFamily.MESSAGE_PARAMETER_REFERENCE) { it.messageParameterFindings() },
    perDeclarationWalk(CheckFamily.ERROR_TEXT_SEMANTIC_INDEX) { it.errorTextSemanticIndexFindings() },
    perDeclarationWalk(CheckFamily.ERROR_TEXT_CATEGORY_SUFFIX) { it.errorTextCategorySuffixFindings() },
    produces(CheckFamily.ERROR_TEXT_INDEX_TERMINAL) { it.errorTextIndexTerminalFindings() },
    perDeclarationWalk(CheckFamily.MESSAGE_TEXT_CHARACTERS) { it.messageTextCharacterFindings() },
    perDeclarationWalk(CheckFamily.MESSAGE_PARAMETER_SYNTAX) { it.messageParameterSyntaxFindings() },
    produces(CheckFamily.MESSAGE_GROUP_PARAMETER) { it.messageGroupParameterFindings() },
    produces(CheckFamily.MESSAGE_VALUE_SUFFIX) { it.messageValueSuffixFindings() },
    perDeclarationWalk(CheckFamily.MESSAGE_BASE_YEAR) { it.messageBaseYearFindings() },
    perDeclaration(CheckFamily.STRING_PATTERN_MESSAGE) { base, at ->
        base.stringTypeMessageFindings(at)
    },
    perDeclarationWalk(CheckFamily.STRING_TYPE_PARAMETERS) { it.stringTypeParameterFindings() },
    perDeclarationWalk(CheckFamily.NUMBER_TYPE_DIGITS) { it.numberTypeDigitFindings() },
    perDeclarationWalk(CheckFamily.ENUM_VALUE_LABELS) { it.enumValueLabelFindings() },
    perDeclarationWalk(CheckFamily.ENUM_S_VALUE_DOMAIN) { it.enumSpecialValueFindings() },
    // The one producer that completes no family: `content.typeDefinitions` declare field types no family
    // examines, so its whole output is the disclosure that they were not examined. It is registered rather
    // than called beside the loop so that its reads are attributed like every other producer's.
    CheckProducer.ModelWide(completes = emptySet(), gaps = { it.typeDefinitionBodyGaps() }),
)
