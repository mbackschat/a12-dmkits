package io.github.mbackschat.a12.dm.consistency

internal sealed interface ReportFinding { val finding: Finding }
internal sealed interface OwnedFinding : ReportFinding { val owner: DeclarationKey }

internal data class MessageFinding(
    override val owner: DeclarationKey,
    val messageOrdinal: Int,
    override val finding: Finding,
) : OwnedFinding

/**
 * Where in A12's rule list a model-wide finding is stated. [readsTheModelAsWritten] marks the stages whose rule walks
 * the model as read rather than what the converter kept, so a computation the converter withholds still carries them
 * (`adapter.laws.CheckerDuplicateIdAgreementTest`).
 */
internal enum class ModelStage(val readsTheModelAsWritten: Boolean = false) {
    /**
     * A12's two version sentences, which its rule list asks first: they precede every class and withhold none, measured
     * beside a computation cycle, a time zone and a missing field ([KERNEL-FINDINGS KF650]).
     */
    VERSION(readsTheModelAsWritten = true),

    /**
     * A12's id sentences, which its rule list asks right after the version sentences and before code generation; they
     * withhold nothing, because the rule that states them is not the one that builds ([KERNEL-FINDINGS KF661]).
     */
    IDENTITY(readsTheModelAsWritten = true),

    DECLARATION, METAMODEL, GENERATION,

    /**
     * A12's basic sentences: its LAST consistency rule, after code generation, so they follow every class and an
     * earlier refusal never withholds them ([KERNEL-FINDINGS KF626]). A defect that also fails A12's build is
     * stated by A12 only where a filter removes its declaration; beside a kept, unbuilt model the sentence is the
     * checker's own addition, naming what the build failure does not.
     */
    BASIC(readsTheModelAsWritten = true),
}

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

/**
 * The computations A12's converter leaves out because their computed field names nothing ([KERNEL-FINDINGS KF652]):
 * an empty `computedFieldRelPath`, or one that resolves to nothing the model as read declares. Only their target's
 * own sentence is reported, and their basic sentences; every other finding about them is withheld.
 *
 * Withheld here rather than left out of the declaration view ([ConversionDrops]) because the cause is a body member
 * and a resolution, which the declaration-set shape that view is charged as does not cover. Two targets are not
 * among them, because each leaves no model at all: one the reader's path arithmetic refuses, and one the converter
 * itself left out ([Reference.Undeclared.leftOutByConversion]).
 */
internal fun TrackedFactBase.withheldComputations(): Set<DeclarationKey> = declarations()
    .filter { it.declaredKind == DeclaredKind.COMPUTATION }
    .filter { computation ->
        val reference = computation.declaredReference() ?: return@filter false
        reference.written.isEmpty() || (reference.target as? Reference.Undeclared)?.leftOutByConversion == false
    }
    .mapTo(mutableSetOf()) { it.key }

/** Cells hold local outcomes; every report re-enters the outer stages against the current model. */
internal fun ExecutedChecks.reportFindings(facts: TrackedFactBase): List<Finding> {
    val withheld = facts.withheldComputations()
    val withheldPaths = withheld.mapTo(HashSet()) { it.path }
    val local = cells.values.flatMap { it.contributions }
        .filterNot { it is OwnedFinding && it.owner in withheld }
        .filterNot { it is ModelFinding && !it.stage.readsTheModelAsWritten &&it.finding.code !in CONVERTER_SENTENCE_CODES &&
            it.finding.at in withheldPaths }
    val targets = facts.declarations().flatMap { element ->
        val reference = element.declaredReference() ?: return@flatMap emptyList()
        reference.findings().map { DeclarationFinding(element.key, DeclarationStage.TARGET, it) }
    }
    val conditions = selectedConditions(facts).filterNot { it.owner in withheld }.map {
        DeclarationFinding(it.owner, DeclarationStage.CONDITION, it.finding)
    }
    val declarations = facts.selectDeclarations(local.filterIsInstance<OwnedFinding>()
        .filterNot { it is ConditionFinding } + targets + conditions)
    val computations = facts.declarations().filter { it.declaredKind == DeclaredKind.COMPUTATION }
        .mapTo(mutableSetOf()) { it.key }
    val reported = local.filterIsInstance<ModelFinding>()
    // A12's ordering gates run only on a model its earlier entity walk admitted (KERNEL-FINDINGS KF589).
    val walkRefused = local.any { facts.refusesTheEntityWalk(it.finding) }
    val model = if (walkRefused) reported.filterNot { it.finding.code in ORDERING_GATE_CODES } else reported
    val selected = (model.filter { it.stage == ModelStage.DECLARATION } +
        declarations.filter { it.owner in computations } +
        model.filter { it.stage == ModelStage.METAMODEL } +
        declarations.filterNot { it.owner in computations }).map { it.finding }
    fun staged(stage: ModelStage) = model.filter { it.stage == stage }.map { it.finding }
    // A12's converter states its sentences and checks on, so they withhold nothing later (KERNEL-FINDINGS KF652).
    val judged = if (selected.any { it.justifiesRejection && it.code !in CONVERTER_SENTENCE_CODES }) selected
    else selected + staged(ModelStage.GENERATION)
    return staged(ModelStage.VERSION) + staged(ModelStage.IDENTITY) + judged + staged(ModelStage.BASIC)
}
