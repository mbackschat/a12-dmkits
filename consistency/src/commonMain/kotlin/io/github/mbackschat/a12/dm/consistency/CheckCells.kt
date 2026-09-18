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
 * Keyed by [CellKey] — the registration object, which is a singleton in [CHECK_PRODUCERS] and compares by
 * identity, plus the [DeclarationKey] a per-declaration cell answers about. So there is no name to spell two
 * ways, and the one collision a path alone would allow (two declarations at one path, which A12 refuses and
 * this checker reports on) is separated by that key's ordinal.
 *
 * **Absence means work, never a guess.** A producer with no cell, or one whose recorded facts have moved, is
 * simply run — so a family added later is slow rather than wrong, and there is no state in which this store
 * can answer for a producer it has not seen.
 */
internal class CheckCells(private val byKey: Map<CellKey, CheckCell>) {

    /**
     * The cell at [key] when every fact it read still has the value it had, else null.
     *
     * The comparison is by VALUE and not by the fact's identity, which is the difference between a cache and
     * a bug: two models name the same facts and that is precisely when reuse must be decided on their
     * contents.
     */
    fun validIn(model: PreparedModel, key: CellKey): CheckCell? {
        val cell = byKey[key] ?: return null
        return cell.takeIf { it.readValues.all { (read, had) -> model.valueOf(read) == had } }
    }

    companion object {
        /** No cells — a first check, and the value every caller outside the snapshot path passes. */
        val NONE = CheckCells(emptyMap())
    }
}

/**
 * Run [produce] in [key]'s own bucket and key what it produced on the values it read out of [model].
 *
 * The two output channels ride one [Pair] rather than two calls because a cell is one decision: a producer
 * whose findings were reused while its gaps were recomputed would report two halves of two different models.
 * They cannot be swapped by accident — `List<Finding>` and `List<CoverageGap>` are different types.
 */
internal fun TrackedFactBase.cellFor(
    key: CellKey,
    model: PreparedModel,
    produce: () -> Pair<List<Finding>, List<CoverageGap>>,
): CheckCell {
    val (findings, gaps) = whileProducing(key, produce)
    return CheckCell(
        findings = findings,
        gaps = gaps,
        readValues = observedWhileProducing(key).associateWith { model.valueOf(it) },
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
    FactRead.DeclarationSetShape -> declarationSetShape
    // ALL bodies at that path, because the transcript names a path and a model may declare two elements at
    // one — A12 refuses such a model and this checker has to report on it rather than fail, so the path is
    // not a unique key and a value covering only the first body would miss an edit to the second.
    is FactRead.DeclarationContent -> bodiesByPath[read.path].orEmpty()
    // `null` when nothing is declared there any more, which differs from every real row and so fells the
    // cell — the one outcome a removed declaration must produce.
    is FactRead.DeclarationRow -> rowsByPath[read.path]?.getOrNull(read.amongSamePath)
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
