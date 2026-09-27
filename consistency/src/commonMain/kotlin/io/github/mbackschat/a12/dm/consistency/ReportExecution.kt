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

internal data class DeclarationFinding(
    override val owner: DeclarationKey,
    val stage: DeclarationStage,
    override val finding: Finding,
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
            val local = byStage[stage].orEmpty()
            addAll(local)
            if (local.any { it.finding.justifiesRejection }) break
        }
        addAll(byStage[DeclarationStage.MESSAGE].orEmpty())
        addAll(contributions.filterIsInstance<MessageFinding>().sortedBy { it.messageOrdinal })
    }
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
    val model = local.filterIsInstance<ModelFinding>()
    val suppressed = facts.variantFilterSuppressed()
    fun visible(finding: Finding): Boolean =
        finding.code == CK_MODEL_NOT_BUILDABLE || finding.at == null || finding.at !in suppressed
    val selected = (model.filter { it.stage == ModelStage.DECLARATION } +
        declarations.filter { it.owner in computations } +
        model.filter { it.stage == ModelStage.METAMODEL } +
        declarations.filterNot { it.owner in computations }).map { it.finding }.filter(::visible)
    return if (selected.any { it.justifiesRejection }) selected else selected +
        model.filter { it.stage == ModelStage.GENERATION }.map { it.finding }.filter(::visible)
}
