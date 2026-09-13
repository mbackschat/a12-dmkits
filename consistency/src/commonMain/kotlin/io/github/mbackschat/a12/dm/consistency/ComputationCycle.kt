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
 * refuses. All three DSL-bearing members are read, through the same [conditionReferences] every other family
 * uses.
 *
 * **A one-node circle is NOT this code.** A computation reading its own target is a cycle of length one in
 * any graph an implementation would build, and A12 answers it with `MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD`
 * — a law this checker does not carry, because its two pre-emptions need derived-scale inference. So the
 * self-loop is dropped rather than reported: silence there under-reports, which is safe, while reporting the
 * cycle class would name a verdict A12 never reached.
 *
 * **The graph is over RESOLVED fields, not declaration sites.** A circle whose two ends sit in different
 * groups — an aggregate over a repeatable group's field, and an absolute read back out of it — is refused
 * exactly like two sibling reads, so keying on the resolved path alone is correct. Measured, because a
 * family built from same-group rows would have inherited a scope it never tested.
 */
fun PreparedModel.computationCycleFindings(): List<Finding> {
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

/** Each computed field's absolute path, mapped to the computation that writes it. */
private fun PreparedModel.computedTargets(): Map<String, String> = declaredReferences()
    .filter { it.role == ReferenceRole.COMPUTED_TARGET }
    .mapNotNull { declared ->
        (declared.target as? Reference.Resolved)?.path?.let { it to declared.declaredAt }
    }
    // A field written by two computations is a different law; the first writer is enough to place an edge,
    // and this family may not turn that shape into a second opinion about it.
    .toMap()

/**
 * For each computed field, the computed fields its own computation reads.
 *
 * Self-loops are dropped here rather than at the report, so nothing downstream can reintroduce them: they
 * are A12's other law, and this is the only place that knows a read landed on its own writer.
 */
private fun PreparedModel.dependencyEdges(writtenBy: Map<String, String>): Map<String, Set<String>> {
    val targetOfComputation = writtenBy.entries.associate { (path, declaredAt) -> declaredAt to path }
    val edges = mutableMapOf<String, MutableSet<String>>()
    conditionReferences().forEach { reference ->
        val target = targetOfComputation[reference.declaredAt] ?: return@forEach
        val read = (reference.target as? Reference.Resolved)?.path ?: return@forEach
        if (read == target || read !in writtenBy) {
            return@forEach
        }
        edges.getOrPut(target) { mutableSetOf() }.add(read)
    }
    return edges
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
