package io.github.mbackschat.a12.dm.consistency

/**
 * One producer's output, and the value every fact it read HAD when it read it.
 *
 * **The invalidation rule is the whole of [readValues], and it is sound rather than careful.** A producer is a
 * pure function of the facts it reads through the barrier, so if each of those facts has the same value in
 * another model, the execution is identical — same branches, therefore the same reads, therefore the same
 * output. That argument needs no knowledge of what any family does, which is exactly what a hand-kept
 * footprint could not offer: a transcript cannot be too narrow, because it IS the read
 * (`docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` "L-b's design is DECIDED").
 *
 * **It holds PRE-composition output, deliberately.** `checkPrepared` filters the composed finding list
 * through four model-wide passes, and those read state the producer never saw — so a cell of composed
 * findings would be keyed on a transcript that does not cover its own content. The passes re-run every time
 * and cost `O(findings)`.
 */
internal class CheckCell(
    val findings: List<Finding>,
    val gaps: List<CoverageGap>,
    val readValues: Map<FactRead, Any?>,
)

/**
 * The cells one check can hand to the next check over a NEARBY model — an editor's keystroke, in practice.
 *
 * Keyed by the registration object, which is a singleton in [CHECK_PRODUCERS] and compares by identity, so
 * there is no name to spell two ways and no key to collide.
 *
 * **Absence means work, never a guess.** A producer with no cell, or one whose recorded facts have moved, is
 * simply run — so a family added later is slow rather than wrong, and there is no state in which this store
 * can answer for a producer it has not seen.
 */
internal class CheckCells(private val byProducer: Map<CheckProducer, CheckCell>) {

    /**
     * [producer]'s cell when every fact it read still has the value it had, else null.
     *
     * The comparison is by VALUE and not by the fact's identity, which is the difference between a cache and
     * a bug: two models name the same facts and that is precisely when reuse must be decided on their
     * contents.
     */
    fun validIn(model: PreparedModel, producer: CheckProducer): CheckCell? {
        val cell = byProducer[producer] ?: return null
        return cell.takeIf { it.readValues.all { (read, had) -> model.valueOf(read) == had } }
    }

    companion object {
        /** No cells — a first check, and the value every caller outside the snapshot path passes. */
        val NONE = CheckCells(emptyMap())
    }
}

/** What [producer] produced against [tracked], as a cell keyed on the values it read out of [model]. */
internal fun cellOf(producer: CheckProducer, tracked: TrackedFactBase, model: PreparedModel): CheckCell {
    val findings = producer.findingsIn(tracked)
    val gaps = producer.gapsIn(tracked)
    return CheckCell(
        findings = findings,
        gaps = gaps,
        readValues = tracked.observedWhileProducing(producer).associateWith { model.valueOf(it) },
    )
}

/**
 * The value [read] names, read WITHOUT charging it — the store's side of the barrier rather than a family's.
 *
 * It takes the untracked [PreparedModel] on purpose: recording a read here would charge the store's own
 * validation to whichever producer happened to be running, and there is no producer running when a cell is
 * being validated. Nothing reachable from a family can call it, because no family holds a [PreparedModel].
 */
internal fun PreparedModel.valueOf(read: FactRead): Any? = when (read) {
    FactRead.DeclarationSetShape -> setShapeValue()
    // ALL bodies at that path, because the transcript names a path and a model may declare two elements at
    // one — A12 refuses such a model and this checker has to report on it rather than fail, so the path is
    // not a unique key and a value covering only the first body would miss an edit to the second.
    is FactRead.DeclarationContent -> bodiesByPath[read.path].orEmpty()
    is FactRead.ModelMember -> when (read.fact) {
        ModelFact.CONDITION_LANGUAGE -> conditionLanguage
        ModelFact.DECLARED_LOCALES -> declaredLocales
        ModelFact.FIELD_REF_BY_SHORT_NAME_ALLOWED -> fieldRefByShortNameAllowed
        ModelFact.HAS_BASE_YEAR -> hasBaseYear
        ModelFact.SUPPORTED_CHARACTERS -> supportedCharacters
        ModelFact.UNREADABLE_LOCALE_CODES -> unreadableLocaleCodes
        ModelFact.DECLARED_TYPE_DEFINITION_IDS -> declaredTypeDefinitionIds
    }
}

/**
 * The set shape as a comparable value: every declaration but its body, plus which paths resolve.
 *
 * **The body must be OUT and the resolvable set must be IN, and both halves are the alphabet's contract**
 * ([FactRead.DeclarationSetShape]). Comparing `elements` itself would drag every body in and collapse the
 * shape onto the content, so one keystroke would fell every cell that merely iterated — the failure the
 * alphabet exists to prevent. Leaving the resolvable set out would be the opposite and worse: a
 * `variantFilter` decides which declarations EXIST for resolution, so a cell that resolved a reference would
 * survive an edit that deleted its referent.
 *
 * The resolvable half is the KEY set rather than the map, because resolution only ever hands back a path
 * ([Reference.Resolved] carries one and nothing else) — so the elements behind those keys are reached through
 * the cursor, and charged there, if a family reads them at all.
 *
 * A [List] of anonymous rows rather than a record: nothing reads its members, so a type would add a name
 * without adding a reader, and `List`'s structural equality is exactly the comparison wanted.
 */
private fun PreparedModel.setShapeValue(): Any = elements.map {
    listOf(it.path, it.name, it.kind, it.id, it.origin)
} to resolvableByPath.keys.toSet()
