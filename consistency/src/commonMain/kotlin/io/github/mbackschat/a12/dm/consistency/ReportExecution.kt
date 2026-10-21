package io.github.mbackschat.a12.dm.consistency

internal sealed interface ReportFinding { val finding: Finding }
internal sealed interface OwnedFinding : ReportFinding { val owner: DeclarationKey }

internal data class MessageFinding(
    override val owner: DeclarationKey,
    val messageOrdinal: Int,
    override val finding: Finding,
) : OwnedFinding

internal enum class ModelStage { DECLARATION, METAMODEL, GENERATION }

internal data class ModelFinding(val stage: ModelStage, override val finding: Finding) : ReportFinding

/** R5's outer stages retain occurrence ownership until the public projection. */
internal enum class DeclarationStage {
    INDEPENDENT, TARGET, PREAMBLE, CONDITION,
    CALCULATION_TARGET, CALCULATION_STRUCTURE, CALCULATION_NORMAL_FORM,
    CALCULATION_ALTERNATIVES, CALCULATION_EXTRACTION, MESSAGE,
}

/**
 * @param walkPosition where in A12's walk over a computation's alternatives this finding is raised
 *     ([walkPositionOf]), for the classes that walk interleaves: A12 reports a target read and the missing
 *     precondition in alternative order, not grouped by class ([KERNEL-FINDINGS KF181]). Null keeps the
 *     finding where its producer put it.
 */
internal data class DeclarationFinding(
    override val owner: DeclarationKey,
    val stage: DeclarationStage,
    override val finding: Finding,
    val walkPosition: Int? = null,
) : OwnedFinding

/** KF339 and KF375 stop one declaration; KF338 gates its messages after condition checking. */
internal fun selectDeclaration(
    owner: DeclarationKey,
    contributions: List<OwnedFinding>,
): List<OwnedFinding> {
    require(contributions.all { it.owner == owner })
    require(contributions.none { it is ConditionFinding })
    val byStage = contributions.filterIsInstance<DeclarationFinding>().groupBy { it.stage }
    return buildList {
        addAll(byStage[DeclarationStage.INDEPENDENT].orEmpty())
        for (stage in listOf(DeclarationStage.TARGET, DeclarationStage.PREAMBLE, DeclarationStage.CONDITION)) {
            val local = byStage[stage].orEmpty()
            addAll(local)
            if (local.any { it.finding.justifiesRejection }) return@buildList
        }
        for (stage in listOf(DeclarationStage.CALCULATION_TARGET, DeclarationStage.CALCULATION_STRUCTURE,
            DeclarationStage.CALCULATION_NORMAL_FORM, DeclarationStage.CALCULATION_ALTERNATIVES,
            DeclarationStage.CALCULATION_EXTRACTION)) {
            val local = inWalkOrder(byStage[stage].orEmpty())
            addAll(local)
            if (local.any { it.finding.justifiesRejection }) break
        }
        addAll(byStage[DeclarationStage.MESSAGE].orEmpty())
        addAll(contributions.filterIsInstance<MessageFinding>().sortedBy { it.messageOrdinal })
    }
}

/** [findings] with the positioned ones in walk order among the places they hold; the others stay put. */
private fun inWalkOrder(findings: List<DeclarationFinding>): List<DeclarationFinding> {
    val positioned = findings.filter { it.walkPosition != null }.sortedBy { it.walkPosition }.iterator()
    return findings.map { if (it.walkPosition != null) positioned.next() else it }
}

/** R5-E0/E1 order computations before authored rules, preserving local notification occurrences. */
internal fun TrackedFactBase.selectDeclarations(
    contributions: List<OwnedFinding>,
): List<OwnedFinding> {
    val byOwner = contributions.groupBy { it.owner }
    val declarations = declarations()
    val owners = declarations.mapTo(mutableSetOf()) { it.key }
    require(byOwner.keys.all { it in owners })
    val ordered = declarations.filter { it.declaredKind == DeclaredKind.COMPUTATION } +
        declarations.filter { it.declaredKind != DeclaredKind.COMPUTATION }
    return ordered.flatMap { selectDeclaration(it.key, byOwner[it.key].orEmpty()) }
}

/** Cells hold local outcomes; every report re-enters the outer stages against the current model. */
internal fun ExecutedChecks.reportFindings(facts: TrackedFactBase): List<Finding> {
    val local = cells.values.flatMap { it.contributions }
    val targets = facts.declarations().flatMap { element ->
        element.declaredReference()?.findings().orEmpty().map {
            DeclarationFinding(element.key, DeclarationStage.TARGET, it)
        }
    }
    val conditions = selectedConditions(facts).map {
        DeclarationFinding(it.owner, DeclarationStage.CONDITION, it.finding)
    }
    val declarations = facts.selectDeclarations(local.filterIsInstance<OwnedFinding>()
        .filterNot { it is ConditionFinding } + targets + conditions)
    val computations = facts.declarations().filter { it.declaredKind == DeclaredKind.COMPUTATION }
        .mapTo(mutableSetOf()) { it.key }
    val suppressed = facts.variantFilterSuppressed()
    fun visible(finding: Finding): Boolean =
        finding.code in VARIANT_BLIND_CODES || finding.at == null || finding.at !in suppressed
    val reported = local.filterIsInstance<ModelFinding>()
    // A12's ordering gates run only on a model its earlier entity walk admitted (KERNEL-FINDINGS KF589).
    val walkRefused = local.any { visible(it.finding) && facts.refusesTheEntityWalk(it.finding) }
    val model = if (walkRefused) reported.filterNot { it.finding.code in ORDERING_GATE_CODES } else reported
    val selected = (model.filter { it.stage == ModelStage.DECLARATION } +
        declarations.filter { it.owner in computations } +
        model.filter { it.stage == ModelStage.METAMODEL } +
        declarations.filterNot { it.owner in computations }).map { it.finding }.filter(::visible)
    return if (selected.any { it.justifiesRejection }) selected else selected +
        model.filter { it.stage == ModelStage.GENERATION }.map { it.finding }.filter(::visible)
}

/**
 * The codes a variant filter never takes away: a source A12 cannot build is unbuildable whatever the filter
 * deletes, and the empty-code sentence is stated before the filter is applied ([KERNEL-FINDINGS KF579]).
 */
internal val VARIANT_BLIND_CODES = setOf(CK_MODEL_NOT_BUILDABLE, RK_ERROR_CODE_EMPTY)
