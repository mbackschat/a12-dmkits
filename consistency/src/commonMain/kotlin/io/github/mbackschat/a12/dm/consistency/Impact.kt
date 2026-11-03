package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject

/**
 * What a candidate edit changed about the model's DECLARATIONS, before anything is said about consequences.
 *
 * A rename appears as one removal and one addition rather than as a rename of its own: nothing in the source
 * records that the two are the same declaration, and inventing that link would be a guess a consumer would
 * then read as a fact.
 *
 * @param modified paths declared on both sides whose OWN declaration differs. A group's `elements` are
 *     excluded from that comparison on purpose — adding a field to a group would otherwise report the group
 *     as modified and, through it, every declaration addressing anything inside it, which is the noise that
 *     makes an impact report unreadable. The added field is already reported as an addition.
 * @param shortNamesMoved field short names whose set of carriers differs between the two models, which is
 *     what can rebind a bare reference ([DependencyOn.ShortName]).
 * @param shortNameFallbackChanged the model's own `fieldRefByShortNameAllowed` moved, so EVERY bare
 *     reference the fallback resolved may bind differently.
 */
data class ModelDelta(
    val added: List<String>,
    val removed: List<String>,
    val modified: List<String>,
    val typeDefinitionsAdded: List<String>,
    val typeDefinitionsRemoved: List<String>,
    val shortNamesMoved: List<String>,
    val shortNameFallbackChanged: Boolean,
)

/** Why a change reaches a declaration. */
enum class ImpactReason {

    /** It references an element the change added, removed or re-declared — or one inside it. */
    REFERENCES_A_CHANGED_ELEMENT,

    /** It types a field by a `content.typeDefinitions` entry the change added or removed. */
    BINDS_A_CHANGED_TYPE_DEFINITION,

    /** A bare name it resolves through the model-wide fallback can now bind elsewhere. */
    RESOLVES_A_MOVED_SHORT_NAME,

    /**
     * It reads a field that a reached COMPUTATION computes, so the value it reads is produced by a
     * declaration the change reached.
     *
     * This is the transitive arm, and it is a dependency claim rather than a behavioural one: A12's
     * consistency verdict on this declaration may well be unchanged. It is reported because an author
     * reviewing a change to a computation's inputs has to re-read what consumes its output, and nothing
     * else in the report says so.
     */
    READS_A_CHANGED_COMPUTED_FIELD,
}

/** One declaration reached by the change, with the dependency that reaches it. */
data class Impact(
    val at: String,
    val on: DependencyOn,
    val member: String,
    val written: String,
    val reason: ImpactReason,
)

/**
 * Which declarations a candidate edit reaches, and whether it broke any of them.
 *
 * @param edit the verdict half, unchanged — [impactOf] does not re-derive validity, it delegates to
 *     [checkEdit], so "does the impact analysis agree with a fresh check" is true by construction rather
 *     than by comparison.
 * @param gaps what the ANALYSIS could not establish: a dependency it could not follow, a mount this source
 *     does not carry, a source it could not read. Distinct from either nested report's coverage gaps, which
 *     are about what the CHECK did not reach.
 */
data class ImpactReport(
    val delta: ModelDelta,
    val impacts: List<Impact>,
    val edit: EditReport,
    val gaps: List<CoverageGap>,
)

/** The declarations reached, in the order they were reached, each named once. */
val ImpactReport.affected: List<String> get() = impacts.map { it.at }.distinct()

/**
 * Report which declarations a candidate [edited] model reaches, relative to its [baseline].
 *
 * **It answers a different question from [checkEdit], and the difference is the whole slice.** An edit report
 * says what broke; this says what has to be re-read — and those part company exactly where a declaration is
 * reached without becoming invalid, which is the ordinary case for a rename or a kind change and the case an
 * author cannot enumerate by hand.
 *
 * **The analysis may over-report and may not under-report.** A reader shown one declaration too many loses a
 * minute; an author told a declaration was unaffected when it broke loses the trust that makes the report
 * worth reading. So an element dependency reaches a referent's ANCESTORS as well as the referent — rescoping
 * a group moves everything addressed through it, and nothing textually references the group — and a bare
 * reference the model-wide fallback resolved depends on the NAME as well as on what it currently names.
 * `ImpactTest.everyNewlyInvalidDeclarationIsAlsoReportedAsAffected` holds that direction to the check's own
 * verdict, so a carrier the graph misses fails a test rather than under-reporting quietly.
 *
 * **No claim is made about runtime behaviour or intent.** A reached declaration is one whose consistency may
 * have moved, never one whose computed values have — including the transitive arm, which reports a dependency
 * on a computation's output rather than a change in it.
 */
fun impactOf(baseline: ModelSource, edited: ModelSource): ImpactReport {
    val edit = checkEdit(baseline, edited)
    val before = analysable(baseline)
    val after = analysable(edited)
    if (before == null || after == null) {
        return refusal(edit, CoverageGap(
            null,
            "one of the two sources could not be read, so no dependency in it could be followed and nothing " +
                "can be reported as reached or unreached",
        ))
    }
    // An unexpanded mount means the fact base is missing declarations that ARE declared, in a model this
    // source does not carry. Reporting reached declarations over it would present a partial graph as a
    // complete one, and the declarations it cannot see are exactly the ones a reader would trust it about.
    val unmounted = (before.unexpandedMounts + after.unexpandedMounts).distinct().map {
        CoverageGap(
            it.at,
            "${it.described} and the mount is not expanded here (${it.reason}), so the " +
                "dependency graph is incomplete and no declaration can be reported as unreached",
        )
    }
    if (unmounted.isNotEmpty()) {
        return ImpactReport(EMPTY_DELTA, emptyList(), edit, edit.gaps + unmounted)
    }
    val delta = deltaBetween(before, after)
    return ImpactReport(delta, before.impactsOf(delta), edit, edit.gaps + before.unfollowableDependencies())
}

private val EMPTY_DELTA = ModelDelta(
    emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false,
)

private fun refusal(edit: EditReport, gap: CoverageGap) =
    ImpactReport(EMPTY_DELTA, emptyList(), edit, edit.gaps + gap)

/** The prepared fact base, or null for a source that could not be admitted at all. */
private fun analysable(source: ModelSource): PreparedModel? =
    (admit(source) as? Admission.Admitted)?.let { prepare(it, providers = emptyMap()) }

private fun deltaBetween(before: PreparedModel, after: PreparedModel): ModelDelta {
    val had = before.ownDeclarations()
    val has = after.ownDeclarations()
    return ModelDelta(
        added = has.keys.filter { it !in had },
        removed = had.keys.filter { it !in has },
        modified = had.keys.filter { it in has && has[it] != had[it] },
        typeDefinitionsAdded = after.declaredTypeDefinitionIds.filter { it !in before.declaredTypeDefinitionIds },
        typeDefinitionsRemoved = before.declaredTypeDefinitionIds.filter { it !in after.declaredTypeDefinitionIds },
        shortNamesMoved = movedShortNames(before, after),
        shortNameFallbackChanged =
            before.fieldRefByShortNameAllowed != after.fieldRefByShortNameAllowed,
    )
}

/**
 * Each declaration's own identity, keyed by path — the kind it declares and its body without its children.
 *
 * The kind is part of it because a path whose FIELD became a GROUP is neither added nor removed and is very
 * much changed.
 */
private fun PreparedModel.ownDeclarations(): Map<String, Pair<String, JsonObject?>> {
    val own = LinkedHashMap<String, Pair<String, JsonObject?>>(elements.size)
    elements.forEach { element ->
        val body = element.body?.let { body ->
            if (element.declaredKind == DeclaredKind.GROUP) JsonObject(body - "elements") else body
        }
        own.getOrPut(element.path) { element.kind to body }
    }
    return own
}

/** The field short names whose CARRIERS differ, which is what can rebind a bare reference. */
private fun movedShortNames(before: PreparedModel, after: PreparedModel): List<String> {
    val had = before.carriersByShortName()
    val has = after.carriersByShortName()
    return (had.keys + has.keys).filter { had[it] != has[it] }.sorted()
}

private fun PreparedModel.carriersByShortName(): Map<String, Set<String>> = elements
    .filter { it.declaredKind == DeclaredKind.FIELD }
    .groupBy({ it.path.substringAfterLast('/') }, { it.path })
    .mapValues { (_, paths) -> paths.toSet() }

/**
 * Walk the dependency edges until no new declaration is reached.
 *
 * The fixpoint exists for one arm only — a reached computation contributes the field it computes, which can
 * reach a further declaration that reads it. It terminates because each round either reaches a declaration
 * that was not reached before or stops, and there are finitely many declarations.
 */
private fun PreparedModel.impactsOf(delta: ModelDelta): List<Impact> {
    val edges = dependencyEdges()
    val computes = declaredReferences()
        .filter { it.role == ReferenceRole.COMPUTED_TARGET }
        .mapNotNull { reference ->
            (reference.target as? Reference.Resolved)?.let { reference.declaredAt to it.path }
        }
        .toMap()
    val impacts = LinkedHashMap<Triple<String, DependencyOn, String>, Impact>()
    var frontier = ChangeFrontier(
        paths = (delta.added + delta.removed + delta.modified).toSet(),
        typeDefinitions = (delta.typeDefinitionsAdded + delta.typeDefinitionsRemoved).toSet(),
        shortNames = delta.shortNamesMoved.toSet(),
        anyShortName = delta.shortNameFallbackChanged,
        transitiveWriters = emptyMap(),
    )
    val reached = mutableSetOf<String>()
    while (true) {
        val round = edges.mapNotNull { edge -> frontier.reason(edge)?.let { edge to it } }
        round.forEach { (edge, reason) ->
            impacts.getOrPut(Triple(edge.from, edge.on, edge.member)) {
                Impact(edge.from, edge.on, edge.member, edge.written, reason)
            }
        }
        val newlyReached = round.map { it.first.from }.toSet() - reached
        if (newlyReached.isEmpty()) {
            return impacts.values.toList()
        }
        reached += newlyReached
        frontier = frontier.plusTransitive(
            newlyReached.mapNotNull { writer -> computes[writer]?.let { it to writer } }.toMap(),
        )
    }
}

/**
 * What a change has touched, as the keys an edge is matched against.
 *
 * [transitiveWriters] is held apart from [paths] for two reasons: a reached declaration can be told WHY, and
 * each computed field remembers WHO computes it — a computation does not read its own output, so without the
 * writer the transitive round would reach the very declaration that contributed the field and report it a
 * second time under a reason that is false of it.
 */
private data class ChangeFrontier(
    val paths: Set<String>,
    val typeDefinitions: Set<String>,
    val shortNames: Set<String>,
    val anyShortName: Boolean,
    val transitiveWriters: Map<String, String>,
) {
    fun plusTransitive(computed: Map<String, String>) =
        copy(transitiveWriters = transitiveWriters + computed)

    /** Why this edge is reached, or null when the change does not touch what it depends on. */
    fun reason(edge: DependencyEdge): ImpactReason? = when (val on = edge.on) {
        is DependencyOn.Element -> when {
            paths.any { on.path.isAtOrUnder(it) } -> ImpactReason.REFERENCES_A_CHANGED_ELEMENT
            transitiveWriters.any { (computed, writer) ->
                on.path.isAtOrUnder(computed) && edge.from != writer
            } -> ImpactReason.READS_A_CHANGED_COMPUTED_FIELD
            else -> null
        }
        is DependencyOn.TypeDefinition ->
            ImpactReason.BINDS_A_CHANGED_TYPE_DEFINITION.takeIf { on.id in typeDefinitions }
        is DependencyOn.ShortName ->
            ImpactReason.RESOLVES_A_MOVED_SHORT_NAME.takeIf { anyShortName || on.name in shortNames }
        DependencyOn.Unfollowable -> null
    }
}

/**
 * Whether this path IS the changed one or sits inside it.
 *
 * A reference reaches its referent through every group above it, and nothing in the source references those
 * groups by name — so rescoping a group would reach no declaration at all if the match were equality.
 */
private fun String.isAtOrUnder(changed: String) = this == changed || startsWith("$changed/")

/**
 * The dependencies the analysis could not follow, stated per declaration.
 *
 * A reference that resolves to nothing has no position for a change to be matched against, so a declaration
 * reachable ONLY through it can be missed. That is a limit of the analysis and belongs in `gaps`; that the
 * model is also defective there is a FINDING, and the two answer different questions.
 */
private fun PreparedModel.unfollowableDependencies(): List<CoverageGap> = dependencyEdges()
    .filterNot { it.resolved }
    .map { edge ->
        CoverageGap(
            edge.from,
            "`${edge.written}` in `${edge.member}` does not resolve, so this analysis cannot follow it — a " +
                "declaration reached only through this dependency would not be reported",
        )
    }
    .distinct()
