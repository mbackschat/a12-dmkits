package io.github.mbackschat.a12.dm.consistency

/**
 * That the model's computations do not depend on each other in a CIRCLE.
 *
 * A12 sorts the calculated fields topologically before generating anything and refuses a model whose
 * dependency graph has a cycle, reporting `MVK_ERROR_CALC_CYCLE`
 * ([KERNEL-FINDINGS KF334], `rulekit.validate.laws.ComputationDependencyCycleLawsTest`). Nothing about the
 * law needs a document: it is decided by which field each computation writes and which fields its own
 * members read, both of which a stored model spells out.
 *
 * **A PRECONDITION read is an edge, measured rather than assumed.** A computation whose operation closes no
 * circle still closes one through its guard, so a family reading only `operation` would admit a model A12
 * refuses. All three DSL-bearing members are read; [ComputationDependencyProfiles] separates ordinary
 * dependencies from positive containing-group scheduling reads (KF511).
 *
 * **A one-node circle is NOT this code.** A computation reading its own target is a cycle of length one in
 * any graph an implementation would build, and A12 answers it with `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD`
 * — owned separately by [computationSelfReadFindings]. The self-loop is dropped here because reporting the
 * cycle class would name a verdict A12 never reached.
 *
 * **The graph is over RESOLVED fields, not declaration sites.** A circle whose two ends sit in different
 * groups — an aggregate over a repeatable group's field, and an absolute read back out of it — is refused
 * exactly like two sibling reads, so keying on the resolved path alone is correct. Measured, because a
 * family built from same-group rows would have inherited a scope it never tested.
 *
 * **A computation a variant filter takes out of the check is no node**: the code generator that draws the
 * cycle generates the default variant, so a circle through an `ONLY`-filtered computation is admitted
 * whichever of its computations carries the filter
 * (`rulekit.validate.laws.CalculationMemberWithholdingLawsTest.aCircleThroughAFilteredComputationIsNoCycle`).
 * Report assembly's own filter cannot stand in for this: it drops a finding AT a suppressed declaration, and
 * the cycle is placed at one computation of the circle, which need not be the filtered one.
 */
internal fun TrackedFactBase.computationCycleFindings(): List<Finding> {
    val writtenBy = computedTargets()
    if (writtenBy.isEmpty()) {
        return emptyList()
    }
    val dependsOn = dependencyEdges(writtenBy)
    return circlesIn(dependsOn).map { circle ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_ERROR_CALC_CYCLE",
            "these computed fields depend on each other in a circle, so A12 cannot decide which to " +
                "evaluate first and refuses the model: ${circle.joinToString(" → ")} → ${circle.first()}. " +
                "Breaking any one of those reads is enough",
            writtenBy.getValue(circle.first()),
        )
    }
}

/** Diagnostic placement uses one declaration per target; dependency collection retains every writer. */
private fun TrackedFactBase.computedTargets(): Map<String, String> {
    val suppressed = variantFilterSuppressed()
    return declarations().filter { it.declaredKind == DeclaredKind.COMPUTATION && it.path !in suppressed }
        .mapNotNull { element -> computedTargetOf(element)?.let { it to element.path } }.toMap()
}

private fun TrackedFactBase.dependencyEdges(writtenBy: Map<String, String>): Map<String, Set<String>> {
    val suppressed = variantFilterSuppressed()
    val computations = declarations().filter {
        it.declaredKind == DeclaredKind.COMPUTATION && it.path !in suppressed && computedTargetOf(it) in writtenBy
    }
    return ComputationDependencyProfiles(this, computations).edges()
}

/**
 * Every circle in [dependsOn], each as one rotation starting at its smallest member.
 *
 * Rotating to the smallest member is what makes a circle's identity independent of where the walk entered
 * it, so the same circle reached from two starting points is reported once and always from the same place.
 * Disjoint circles stay separate because they are separate causes, and a reader fixing one must still see
 * the other.
 *
 * **[settled] is a memo, not part of the answer**, and that is measured rather than assumed: a mutant
 * disabling it survives every control here, because a node is settled only after its whole subtree has been
 * walked, so any back edge into it was already recorded. It bounds the walk on a model with many
 * computations; it changes no report.
 *
 * **Circles that share an edge are reported by their back edges rather than enumerated exhaustively.** A
 * depth-first walk finds every cycle — each contains a back edge — but a family of overlapping cycles
 * collapses onto the ones its back edges name. That direction under-reports, which is safe, and a reader
 * shown one circle through a shared node still has to break it.
 */
private fun circlesIn(dependsOn: Map<String, Set<String>>): List<List<String>> {
    val found = mutableSetOf<List<String>>()
    val onPath = mutableListOf<String>()
    val settled = mutableSetOf<String>()

    fun walk(node: String) {
        val seenAt = onPath.indexOf(node)
        if (seenAt >= 0) {
            found += canonical(onPath.subList(seenAt, onPath.size))
            return
        }
        if (node in settled) {
            return
        }
        onPath += node
        dependsOn[node].orEmpty().forEach { walk(it) }
        onPath.removeAt(onPath.size - 1)
        settled += node
    }

    dependsOn.keys.sorted().forEach { walk(it) }
    return found.sortedBy { it.joinToString("/") }
}

/** [circle] rotated to begin at its smallest member. */
private fun canonical(circle: List<String>): List<String> {
    val start = circle.indexOf(circle.min())
    return circle.subList(start, circle.size) + circle.subList(0, start)
}
