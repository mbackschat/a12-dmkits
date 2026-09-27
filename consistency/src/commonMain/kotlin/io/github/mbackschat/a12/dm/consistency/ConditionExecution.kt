package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionSite
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.Recognition
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.filterBodiesIn
import io.github.mbackschat.a12.dm.consistency.syntax.operandOf
import io.github.mbackschat.a12.dm.consistency.syntax.RecognizedEntity

/** R5 keeps the producing occurrence and reached site separate from the public editor projection. */
internal data class ConditionFinding(override val owner: DeclarationKey, val site: ConditionSite, override val finding: Finding) : OwnedFinding {
    init {
        require(site is PostParseCheck || finding.span != null) { "a reached parse action needs an editor span" }
    }
}

/** The implemented normal-rule checks in proposal Appendix A.3 order; codes do not select a stage. */
internal enum class PostParseCheck : ConditionSite {
    PARALLEL_ITERATION, FILTERED_ENTITY, FILTER_PARALLEL_ITERATION, FILTER_SEMANTIC_INDEX,
    REPEATED_UNIQUENESS, RAW_LENGTH, ERROR_TARGET_ITERATION,
}

/** The entity action's local gates (proposal A.2); each gate can issue several notifications. */
internal enum class EntityCheck { OUTER_MARKER, ADDRESS, EMPTY_GROUP, FILTER, CATEGORY }

internal data class EntityCheckSite(val entity: SyntaxSite.Completion, val check: EntityCheck) : ConditionSite

internal enum class PathCheck { ADDRESS, SUFFIX, WILDCARD }

internal data class PathCheckSite(val step: SyntaxSite.Completion, val check: PathCheck) : ConditionSite

internal fun TrackedFactBase.entityContributions(
    element: TrackedDeclaration,
    gate: EntityCheck,
    execution: ConditionExecution? = conditionExecution(element),
    check: (RecognizedEntity, TextSpan) -> Finding?,
): List<ConditionFinding> = execution?.recognition?.entities.orEmpty().mapNotNull { entity ->
    val span = execution!!.carrier.semanticSpan(entity.site.start, entity.site.end) ?: return@mapNotNull null
    check(entity, span)?.let { ConditionFinding(element.key, EntityCheckSite(entity.site, gate), it) }
}

internal fun TrackedFactBase.callContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
    check: (String, ConditionCall) -> Finding?,
): List<ConditionFinding> = callListContributions(element, execution) { member, call ->
    listOfNotNull(check(member, call))
}

internal fun TrackedFactBase.callListContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
    check: (String, ConditionCall) -> List<Finding>,
): List<ConditionFinding> {
    if (execution == null) return emptyList()
    val bodies = filterBodiesIn(execution.carrier.text, conditionLanguage)
    return execution.recognition.sites.filterIsInstance<SyntaxSite.Completion>().flatMap { site ->
        if (site.kind != SyntaxSite.Kind.CALL) return@flatMap emptyList()
        val shape = execution.callsByStart.getValue(site.start)
        val member = execution.carrier.semanticSpan(shape.at, shape.end)?.member ?: return@flatMap emptyList()
        val call = ConditionCall(shape.operator, shape.at,
            shape.operands.map { operandOf(it, conditionLanguage, shape.source) }, shape.end,
            bodies.any { it.at <= shape.at && shape.end <= it.at + it.text.length })
        check(member, call).map { finding ->
            val editorSpan = finding.span?.let { execution.carrier.semanticSpan(it.start, it.end) }
            ConditionFinding(element.key, site, finding.copy(span = editorSpan))
        }
    }
}

/** One joined condition supplies the syntax and semantic producers' shared occurrence identities. */
internal class ConditionExecution(
    val owner: DeclarationKey,
    val carrier: ConditionText,
    val recognition: Recognition,
) {
    val callsByStart = recognition.calls.associateBy { it.at }
    val entitiesByStart = recognition.sites.filterIsInstance<SyntaxSite.Completion>()
        .filter { it.kind == SyntaxSite.Kind.ENTITY }.associateBy { it.start }

    private val sites: List<ConditionSite> = recognition.sites.flatMap { site ->
        when {
            site is SyntaxSite.Completion && site.kind == SyntaxSite.Kind.ENTITY ->
                EntityCheck.entries.map { EntityCheckSite(site, it) }
            site is SyntaxSite.Completion && site.kind == SyntaxSite.Kind.PATH_STEP ->
                PathCheck.entries.map { PathCheckSite(site, it) }
            else -> listOf(site)
        }
    } +
        if (recognition.failure == null) PostParseCheck.entries else emptyList()

    fun postParse(site: PostParseCheck, findings: () -> List<Finding>): List<ConditionFinding> =
        if (recognition.failure == null) findings().map { ConditionFinding(owner, site, it) } else emptyList()

    fun selected(contributions: List<ConditionFinding>): List<ConditionFinding> {
        val atSite = contributions.filter { it.owner == owner }.groupBy { it.site }
        require(atSite.keys.all { it in sites }) { "a condition finding must name a reached site" }
        return buildList {
            for (site in sites) {
                val local = atSite[site].orEmpty()
                addAll(local)
                if (local.any { it.finding.justifiesRejection }) break
            }
        }
    }
}

internal fun TrackedFactBase.conditionExecution(element: TrackedDeclaration): ConditionExecution? {
    val body = element.body ?: return null
    val carrier = if (element.declaredKind == DeclaredKind.COMPUTATION) {
        val target = computedTargetOf(element) ?: return null
        val group = element.path.substringBeforeLast('/')
        computationCondition(body, target.removePrefix("$group/"), conditionLanguage) ?: return null
    } else {
        val condition = conditionTexts(element.kind, body).singleOrNull() ?: return null
        ConditionText().apply { append(condition.second, condition.first, authored = true) }
    }
    return ConditionExecution(element.key, carrier,
        ConditionRecognizer(carrier.text, conditionLanguage).recognize(SyntaxEntry.CONDITION))
}
