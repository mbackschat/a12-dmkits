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
 * **Two granularities of cell.** A [ModelWide] producer answers about the model and is
 * cached once. [PerDeclaration] and [PerDeclarationWalk] are both asked once per declaration and cached per
 * declaration, so a keystroke fells one declaration's cell instead of the family's whole verdict; they
 * differ in HOW the subject reaches the family — as a parameter, or as a fact base scoped to it — and that
 * difference is what the walk's equivalence claim is about. The split is a sealed hierarchy rather than a
 * nullable lambda so dispatch is exhaustive. [Condition] retains reached-site contributions through the
 * same per-declaration cache boundary, before report selection (reconstruction proposal §5, R5).
 */
internal sealed interface CheckProducer {

    /**
     * The families this producer answers for — a SET because one walk can settle several independent claims,
     * as a group's ordering members do.
     */
    val completes: Set<CheckFamily>

    /**
     * A family whose verdict is about the whole model, cached against one transcript.
     *
     * @param findings its findings, empty for a producer whose whole output is a coverage gap.
     * @param gaps its gaps, empty for the great majority that report none.
     * @param alsoAt findings of the same families that A12 states at another stage of its rule order — one producer
     *     per family keeps the family's claim and its reads in one cell, which a second registration would split.
     */
    class ModelWide(
        override val completes: Set<CheckFamily>,
        val stage: ModelStage,
        val findings: (TrackedFactBase) -> List<Finding> = { emptyList() },
        val gaps: (TrackedFactBase) -> List<CoverageGap> = { emptyList() },
        val alsoAt: Map<ModelStage, (TrackedFactBase) -> List<Finding>> = emptyMap(),
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
    class Condition(
        override val completes: Set<CheckFamily>,
        val gaps: (TrackedFactBase, TrackedDeclaration) -> List<CoverageGap> = { _, _ -> emptyList() },
        val contributions: (TrackedFactBase, TrackedDeclaration) -> List<ConditionFinding>,
    ) : CheckProducer

    class Messages(
        override val completes: Set<CheckFamily>,
        val findings: (TrackedFactBase, TrackedDeclaration, AuthoredMessage) -> List<Finding>,
    ) : CheckProducer

    class Declaration(
        override val completes: Set<CheckFamily>,
        val contributions: (TrackedFactBase, TrackedDeclaration) -> List<OwnedFinding>,
    ) : CheckProducer

    class PerDeclaration(
        override val completes: Set<CheckFamily>,
        val stage: DeclarationStage,
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
        val stage: DeclarationStage,
        val findings: (TrackedFactBase) -> List<Finding>,
        val gaps: (TrackedFactBase) -> List<CoverageGap> = { emptyList() },
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
    val produce: () -> CheckOutput,
)

/**
 * Every cell one check over [tracked] must produce, in stable producer/declaration order.
 *
 * This schedules local work, not public notification order; [reportFindings] consumes its owned outputs.
 *
 * **The walk charges the COMPOSITION and not any cell**, which `ReadTranscript` argues is sound as well as
 * necessary: set membership is handled by this list existing, since a declaration that is gone is never
 * scheduled and a new one has no cell to be stale.
 *
 * Preparation assigns each declaration's occurrence key before any producer can filter the list, so
 * scheduling and target resolution agree even when two declaration kinds share a path (R5).
 */
internal fun schedule(
    tracked: TrackedFactBase,
    producers: List<CheckProducer> = CHECK_PRODUCERS,
): List<ScheduledCell> {
    val walked = tracked.declarations().map { cursor ->
        val at = cursor.key
        // One scoped view per declaration, shared by every per-declaration producer: the scope holds no
        // facts, but a view per producer per declaration would allocate one for nothing.
        Walked(at, cursor, tracked.scopedTo(at, cursor))
    }
    return producers.flatMap { producer ->
        when (producer) {
            is CheckProducer.ModelWide -> listOf(
                ScheduledCell(CellKey(producer)) {
                    CheckOutput(
                        producer.findings(tracked).map { ModelFinding(producer.stage, it) } +
                            producer.alsoAt.flatMap { (stage, findings) -> findings(tracked).map { ModelFinding(stage, it) } },
                        producer.gaps(tracked),
                    )
                },
            )
            is CheckProducer.Condition -> walked.map { (at, cursor, _) ->
                ScheduledCell(CellKey(producer, at)) {
                    tracked.chargeDeclarationRow(at)
                    val contributions = producer.contributions(tracked, cursor)
                    CheckOutput(contributions, producer.gaps(tracked, cursor))
                }
            }
            is CheckProducer.Messages -> walked.map { (at, cursor, _) ->
                ScheduledCell(CellKey(producer, at)) {
                    tracked.chargeDeclarationRow(at)
                    CheckOutput(tracked.messageContributions(cursor) { producer.findings(tracked, cursor, it) })
                }
            }
            is CheckProducer.Declaration -> walked.map { (at, cursor, _) ->
                ScheduledCell(CellKey(producer, at)) {
                    tracked.chargeDeclarationRow(at)
                    CheckOutput(producer.contributions(tracked, cursor))
                }
            }
            is CheckProducer.PerDeclaration -> walked.map { (at, cursor, _) ->
                ScheduledCell(CellKey(producer, at)) {
                    // A family taking its declaration as a parameter never calls `declarations()`, so
                    // nothing else would say which declaration its cell answered about
                    // ([FactRead.DeclarationRow]).
                    tracked.chargeDeclarationRow(at)
                    CheckOutput(producer.findings(tracked, cursor).map { DeclarationFinding(at, producer.stage, it) })
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
                ScheduledCell(CellKey(producer, at)) {
                    CheckOutput(producer.findings(scoped).map { DeclarationFinding(at, producer.stage, it) }, producer.gaps(scoped))
                }
            }
        }
    }
}

/** Declared targets are resolved before condition execution (reconstruction proposal §5, R5). */
internal val COMPOSED_FAMILIES = setOf(CheckFamily.DECLARED_REFERENCES)

/** The single-family, findings-only registration, which is the great majority of those below. */
private fun produces(
    family: CheckFamily,
    findings: (TrackedFactBase) -> List<Finding>,
): CheckProducer = CheckProducer.ModelWide(setOf(family), ModelStage.DECLARATION, findings)

/** …and its per-declaration twin, for a family whose verdict is about one declaration at a time. */
private fun perDeclaration(
    family: CheckFamily,
    stage: DeclarationStage,
    findings: (TrackedFactBase, TrackedDeclaration) -> List<Finding>,
): CheckProducer = CheckProducer.PerDeclaration(setOf(family), stage, findings)

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
    stage: DeclarationStage,
    findings: (TrackedFactBase) -> List<Finding>,
): CheckProducer = CheckProducer.PerDeclarationWalk(setOf(family), stage, findings)

private fun condition(
    family: CheckFamily,
    contributions: (TrackedFactBase, TrackedDeclaration) -> List<ConditionFinding>,
): CheckProducer = CheckProducer.Condition(setOf(family), contributions = contributions)

private fun messages(
    family: CheckFamily,
    check: (TrackedFactBase, TrackedDeclaration, AuthoredMessage) -> List<Finding>,
): CheckProducer = CheckProducer.Messages(setOf(family), check)

private fun syntax(family: CheckFamily): CheckProducer = condition(family) { facts, element ->
    listOfNotNull(facts.conditionExecution(element)?.syntaxContribution(family))
}

private fun path(family: CheckFamily): CheckProducer = condition(family) { facts, element ->
    facts.pathContributions(element).filter { it.family == family }.map { it.contribution }
}

/** Local outputs are cached in registry order; reached sites and outer stages assemble the public report. */
internal val CHECK_PRODUCERS: List<CheckProducer> = listOf(
    CheckProducer.ModelWide(setOf(CheckFamily.DECLARATION_SLOTS), ModelStage.DECLARATION, { it.declarationSlotFindings() },
        alsoAt = mapOf(ModelStage.VERSION to { it.versionFindings() })),
    perDeclarationWalk(CheckFamily.ERROR_TEXT_PRESENCE, DeclarationStage.MESSAGE) { it.errorTextPresenceFindings() },
    CheckProducer.Declaration(setOf(CheckFamily.COMPUTATION_ALTERNATIVES)) { base, at -> base.computationAlternativeContributions(at) },
    perDeclarationWalk(CheckFamily.COMPUTATION_ROUNDING, DeclarationStage.CALCULATION_EXTRACTION) { it.computationRoundingFindings() },
    perDeclarationWalk(CheckFamily.SUPPRESSED_WARNINGS, DeclarationStage.PREAMBLE) { it.suppressedWarningFindings() },
    CheckProducer.Declaration(setOf(CheckFamily.RULE_CONDITION_AND_TARGET)) { base, at ->
        base.ruleMemberContributions(at)
    },
    path(CheckFamily.CONDITION_REFERENCES),
    syntax(CheckFamily.CONDITION_LEXICALITY),
    syntax(CheckFamily.CONDITION_COMPLETENESS),
    condition(CheckFamily.CONNECTIVE_AGREEMENT) { base, at -> base.connectiveContributions(at) },
    syntax(CheckFamily.CONDITION_GRAMMAR),
    produces(CheckFamily.ELEMENT_NAME_UNIQUENESS) { it.elementNameFindings() },
    CheckProducer.ModelWide(
        stage = ModelStage.DECLARATION,
        completes = setOf(
            CheckFamily.GROUP_ORDERING_REFERENCES,
            CheckFamily.GROUP_ORDERING_FIELD_TYPE,
            CheckFamily.GROUP_ORDERING_CARRIER,
        ),
        findings = { it.groupOrderingFindings() },
    ),
    perDeclaration(CheckFamily.GROUP_REPETITION_COUNT, DeclarationStage.INDEPENDENT) { base, at ->
        base.groupRepetitionCountFindings(at)
    },
    perDeclarationWalk(CheckFamily.RULE_PRESENTATION_MEMBERS, DeclarationStage.INDEPENDENT) { it.presentationMemberFindings() },
    perDeclaration(CheckFamily.STRING_SLOT_BINDING, DeclarationStage.INDEPENDENT) { base, at -> base.stringSlotFindings(at) },
    CheckProducer.ModelWide(
        stage = ModelStage.DECLARATION,
        completes = setOf(CheckFamily.FIELD_TYPE_BINDING),
        findings = { it.fieldTypeBindingFindings() },
        gaps = { it.fieldTypeBindingGaps() },
    ),
    perDeclaration(CheckFamily.TEMPORAL_CONFIG, DeclarationStage.INDEPENDENT) { base, at -> base.temporalConfigFindings(at) },
    perDeclaration(CheckFamily.FIELD_NAME_RESERVED_SEGMENT, DeclarationStage.INDEPENDENT) { base, at ->
        listOfNotNull(base.fieldNameReservedSegmentFinding(at))
    },
    perDeclaration(CheckFamily.ELEMENT_NAME_ALPHABET, DeclarationStage.INDEPENDENT) { base, at ->
        base.elementNameAlphabetFindings(at) + base.elementPathSentenceFindings(at)
    },
    // A12 reports an enumeration's invalid characters before its category alignment (KF551).
    CheckProducer.ModelWide(
        stage = ModelStage.DECLARATION,
        completes = setOf(CheckFamily.ENUM_VALUE_CHARACTERS),
        findings = { it.supportedCharacterFindings() },
        gaps = { it.supportedCharacterGaps() },
    ),
    CheckProducer.ModelWide(
        stage = ModelStage.DECLARATION,
        completes = setOf(CheckFamily.ENUM_CATEGORY_ALIGNMENT),
        findings = { it.enumCategoryFindings() },
        gaps = { it.enumCategoryGaps() },
    ),
    CheckProducer.ModelWide(
        stage = ModelStage.DECLARATION,
        completes = setOf(CheckFamily.ENUM_TEXT_FORBIDDEN_CHARACTERS),
        findings = { it.enumTextForbiddenCharacterFindings() },
        gaps = { it.enumTextForbiddenCharacterGaps() },
    ),
    CheckProducer.ModelWide(
        stage = ModelStage.METAMODEL,
        completes = setOf(CheckFamily.MODEL_TEXT_REPERTOIRE),
        findings = { it.modelTextRepertoireFindings() },
    ),
    CheckProducer.ModelWide(
        stage = ModelStage.DECLARATION,
        completes = setOf(CheckFamily.HINT_LIST_VALUES),
        findings = { it.hintListValueFindings() },
        gaps = { it.hintListValueGaps() },
    ),
    condition(CheckFamily.TABLE_ENUM_OPERAND) { base, at -> base.tableEnumContributions(at) },
    CheckProducer.Condition(
        completes = setOf(CheckFamily.H_MODEL_FORM_CONSTRUCT),
        contributions = { base, at -> base.hModelFormContributions(at) },
        gaps = { base, at -> base.scopedTo(at.key, at).hModelFormConstructGaps() },
    ),
    CheckProducer.ModelWide(
        stage = ModelStage.DECLARATION,
        completes = setOf(CheckFamily.STRING_PATTERN_ADMISSION),
        findings = { it.declaredPatternFindings() },
        gaps = { it.declaredPatternGaps() },
    ),
    condition(CheckFamily.EMPTY_GROUP_OPERAND) { base, at -> base.emptyGroupContributions(at) },
    condition(CheckFamily.ERROR_FIELD_REFERENCE) { base, at -> base.errorFieldReferenceContributions(at) },
    CheckProducer.Condition(
        completes = setOf(
            CheckFamily.GROUP_LIST_OPERANDS,
            CheckFamily.GROUP_LIST_ROOT_OPERAND,
            CheckFamily.GROUP_LIST_OPERAND_KIND,
        ),
        contributions = { base, at -> base.groupListContributions(at) },
    ),
    path(CheckFamily.REPETITION_WILDCARD_TARGET),
    perDeclarationWalk(CheckFamily.COMPUTED_TARGET_KIND, DeclarationStage.TARGET) { it.computedTargetKindFindings() },
    perDeclarationWalk(CheckFamily.COMPUTED_TARGET_INDEX_FIELD, DeclarationStage.CALCULATION_TARGET) { it.computedTargetIndexFieldFindings() },
    condition(CheckFamily.RAW_TYPE_LENGTH) { base, at -> base.rawLengthContributions(at) },
    perDeclaration(CheckFamily.COMPUTATION_PRECONDITION_FEATURES, DeclarationStage.CALCULATION_NORMAL_FORM) { base, at ->
        base.computationPreconditionFeatureFindings(at)
    },
    CheckProducer.ModelWide(setOf(CheckFamily.COMPUTATION_CYCLE), ModelStage.GENERATION, { it.computationCycleFindings() }),
    CheckProducer.ModelWide(setOf(CheckFamily.UNDECLARED_LOCALE_MESSAGE_PARAMETERS), ModelStage.GENERATION,
        { it.undeclaredLocaleMessageFindings() }),
    CheckProducer.ModelWide(setOf(CheckFamily.ELEMENT_ID_UNIQUENESS), ModelStage.IDENTITY, { it.elementIdFindings() }),
    CheckProducer.ModelWide(setOf(CheckFamily.BASIC_SENTENCES), ModelStage.BASIC, { it.basicSentenceFindings() }),
    CheckProducer.Declaration(setOf(CheckFamily.COMPUTATION_SELF_READ)) { base, at -> base.computationSelfReadContributions(at) },
    condition(CheckFamily.REPETITION_KEY_LIST) { base, at -> base.repetitionKeyListContributions(at) },
    condition(CheckFamily.REPETITION_KEY_PLACEMENT) { base, at -> base.repetitionKeyPlacementContributions(at) },
    condition(CheckFamily.REPETITION_SCOPE_GROUP) { base, at -> base.repetitionScopeContributions(at) },
    condition(CheckFamily.REPETITION_KEY_CONTAINMENT) { base, at -> base.repetitionContainmentContributions(at) },
    condition(CheckFamily.FILTER_BODY_GATES) { base, at ->
        val execution = base.conditionExecution(at)
        base.filterBodyContributions(at, execution) + base.filterBodyIndexContributions(at, execution)
    },
    condition(CheckFamily.OUTER_MARKER_POSITION) { base, at -> base.outerMarkerContributions(at) },
    condition(CheckFamily.GROUP_CONDITION_WILDCARD) { base, at -> base.groupWildcardContributions(at) },
    condition(CheckFamily.SUM_OF_PRODUCTS_OPERANDS) { base, at -> base.sumOfProductsContributions(at) },
    condition(CheckFamily.OPERAND_LIST_CONSTANT_BUDGET) { base, at -> base.operandListConstantContributions(at) },
    condition(CheckFamily.SEMANTIC_INDEX_STRUCTURE) { base, at -> base.semanticIndexStructureContributions(at) },
    condition(CheckFamily.SEMANTIC_INDEX_KEY) { base, at -> base.semanticIndexKeyContributions(at) },
    condition(CheckFamily.REPETITION_NOT_UNIQUE_COMBINATION) { base, at -> base.repeatedUniquenessContributions(at) },
    condition(CheckFamily.PARALLEL_ITERATION_UNIQUENESS) { base, at -> base.parallelIterationContributions(at) },
    condition(CheckFamily.FILTERED_ENTITY_ITERATION) { base, at ->
        val execution = base.conditionExecution(at)
        base.filteredEntityContributions(at, execution) + base.filterBodyParallelContributions(at, execution)
    },
    CheckProducer.Condition(
        completes = setOf(
            CheckFamily.TEMPORAL_AGGREGATE_OPERANDS,
            CheckFamily.ARITHMETIC_OPERATION_GATES,
            CheckFamily.COMPARISON_OPERATOR_OPERANDS,
            CheckFamily.BOOLEAN_COMPARISON_AGREEMENT,
            CheckFamily.STRING_ENUM_COMPARISON_AGREEMENT,
            CheckFamily.NUMBER_COMPARISON_SCALE,
            CheckFamily.TEMPORAL_COMPARISON_AGREEMENT,
            CheckFamily.PATTERN_COMPARISON_OPERANDS,
        ),
        contributions = { facts, element -> facts.operationContributions(element) },
    ),
    condition(CheckFamily.VALUE_IN_FIELDS_CONSTANT_KIND) { base, at -> base.valueInFieldsConstantContributions(at) },
    condition(CheckFamily.VALUE_LIST_CONDITION_GATES) { base, at -> base.valueListConditionContributions(at) },
    condition(CheckFamily.ENTITY_LIST_ARITY) { base, at -> base.entityListArityContributions(at) },
    condition(CheckFamily.ENTITY_LIST_PRE_GATES) { base, at -> base.entityListPreGateContributions(at) },
    condition(CheckFamily.FIELD_VALUES_NOT_UNIQUE_OPERAND_KIND) { base, at -> base.fieldValuesNotUniqueContributions(at) },
    condition(CheckFamily.FIELD_LIST_OPERATION_OPERANDS) { base, at -> base.fieldListOperationContributions(at) },
    condition(CheckFamily.DATE_ARITHMETIC_OPERANDS) { base, at -> base.dateArithmeticContributions(at) },
    condition(CheckFamily.TEMPORAL_EXTRACTOR_OPERANDS) { base, at -> base.temporalExtractorContributions(at) },
    condition(CheckFamily.DATE_TIME_CONSTRUCTION_OPERANDS) { base, at -> base.dateTimeConstructionContributions(at) },
    condition(CheckFamily.ROUNDING_OPERANDS) { base, at -> base.roundingContributions(at) },
    condition(CheckFamily.FIELD_VALUE_OPERATOR_OPERANDS) { base, at -> base.fieldValueOperatorContributions(at) },
    condition(CheckFamily.FIELD_VALUE_READ_OPERAND) { base, at -> base.fieldValueReadContributions(at) },
    condition(CheckFamily.OPERAND_REPETITION_SCOPE) { base, at -> base.operandScopeContributions(at) },
    condition(CheckFamily.DATE_CONSTRUCTION_SLOT) { base, at -> base.dateConstructionContributions(at) },
    path(CheckFamily.H_MODEL_PATH_SUFFIX),
    condition(CheckFamily.FIELD_OPERAND_KIND) { base, at -> base.fieldOperandKindContributions(at) },
    condition(CheckFamily.FILTERED_OPERAND_KIND) { base, at -> base.filteredOperandContributions(at) },
    condition(CheckFamily.CATEGORY_REFERENCE) { base, at -> base.categoryContributions(at) },
    condition(CheckFamily.ENTITY_LIST_CATEGORY_GATE) { base, at -> base.entityListCategoryContributions(at) },
    condition(CheckFamily.SCALAR_VALUE_LIST_GATES) { base, at -> base.scalarValueListContributions(at) },
    condition(CheckFamily.DATE_ARGUMENT_SLOT) { base, at -> base.dateArgumentContributions(at) },
    condition(CheckFamily.DATE_RANGE_OPERAND_LIST) { base, at -> base.dateRangeOperandContributions(at) },
    condition(CheckFamily.VALIDITY_CONDITION_OPERANDS) { base, at -> base.validityConditionContributions(at) },
    condition(CheckFamily.BASE_YEAR_REQUIREMENT) { base, at -> base.baseYearContributions(at) },
    condition(CheckFamily.RANGE_OPERAND_GATES) { base, at -> base.rangeOperandContributions(at) },
    condition(CheckFamily.VALUE_AS_DATE_OPERAND) { base, at -> base.valueAsDateContributions(at) },
    CheckProducer.Declaration(setOf(CheckFamily.ENUM_COMPUTATION_SOURCE)) { base, at -> base.enumComputationSourceContributions(at) },
    condition(CheckFamily.ERROR_TARGET_ITERATION) { base, at -> base.errorTargetIterationContributions(at) },
    condition(CheckFamily.ERROR_FIELD_ITERATION_BINDING) { base, at -> base.errorFieldIterationBindingContributions(at) },
    CheckProducer.Condition(
        completes = setOf(CheckFamily.ITERATION_GUARD),
        contributions = { base, at -> base.iterationGuardContributions(at) },
        gaps = { base, at -> base.iterationGuardGaps(at) },
    ),
    condition(CheckFamily.ERROR_TARGET_CONTAINMENT) { base, at -> base.errorContainmentContributions(at) },
    CheckProducer.Messages(setOf(CheckFamily.MESSAGE_PARAMETER_REFERENCE, CheckFamily.ERROR_TEXT_SEMANTIC_INDEX,
        CheckFamily.ERROR_TEXT_CATEGORY_SUFFIX, CheckFamily.ERROR_TEXT_INDEX_TERMINAL,
        CheckFamily.MESSAGE_GROUP_PARAMETER, CheckFamily.MESSAGE_VALUE_SUFFIX, CheckFamily.MESSAGE_BASE_YEAR)) {
        base, at, message -> base.messageParameterFindings(at, message)
    },
    messages(CheckFamily.MESSAGE_TEXT_CHARACTERS) { base, at, message -> base.messageTextCharacterFindings(at, message) },
    messages(CheckFamily.MESSAGE_PARAMETER_SYNTAX) { base, at, message -> base.messageParameterSyntaxFindings(at, message) },
    perDeclaration(CheckFamily.STRING_PATTERN_MESSAGE, DeclarationStage.INDEPENDENT) { base, at ->
        base.stringTypeMessageFindings(at)
    },
    produces(CheckFamily.FIELD_TYPE_MESSAGE_TEXT) { it.fieldTypeMessageTextFindings() },
    perDeclarationWalk(CheckFamily.STRING_TYPE_PARAMETERS, DeclarationStage.INDEPENDENT) { it.stringTypeParameterFindings() },
    perDeclarationWalk(CheckFamily.NUMBER_TYPE_DIGITS, DeclarationStage.INDEPENDENT) { it.numberTypeDigitFindings() },
    perDeclarationWalk(CheckFamily.ENUM_VALUE_LABELS, DeclarationStage.INDEPENDENT) { it.enumValueLabelFindings() },
    perDeclarationWalk(CheckFamily.ENUM_S_VALUE_DOMAIN, DeclarationStage.INDEPENDENT) { it.enumSpecialValueFindings() },
    // The one producer that completes no family: most type-definition laws are unexamined even though the
    // constant index-key path can now read an unambiguous String definition. It is registered rather
    // than called beside the loop so that its reads are attributed like every other producer's.
    CheckProducer.ModelWide(stage = ModelStage.DECLARATION, completes = emptySet(), gaps = { it.typeDefinitionBodyGaps() }),
)
