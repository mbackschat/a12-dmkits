package io.github.mbackschat.a12.dm.consistency

/**
 * One fact a check family read out of the fact base, at the granularity reuse is decided on.
 *
 * **The alphabet IS the design, not bookkeeping.** [DeclarationSetShape] and [DeclarationContent] are
 * separate because a family's natural shape is a loop over every declaration, and a transcript that recorded
 * "iterated the declarations" as a dependency on those declarations' CONTENT would make one keystroke
 * invalidate every family that loops — collapsing reuse back to the per-source level L-a already reached,
 * while every equivalence test stayed green. Iterating records the set's SHAPE; reading one declaration's
 * body records that path alone. So a keystroke changes one body, leaves the shape identical, and fells only
 * the cells that read the edited declaration.
 *
 * A transcript is what a family DID read, so it cannot be too narrow — the property a hand-kept footprint
 * could not have, measured by the probe in `docs/archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` ("L-b's method
 * was probed rather than assumed").
 */
internal sealed interface FactRead {

    /**
     * The declaration set's shape: every declaration's path, name, kind, id, origin and whether the default
     * variant deletes it — and no body.
     *
     * Recorded once however many declarations the family then visits, because the shape is one value and a
     * family that iterates depends on all of it.
     *
     * **The variant filter is in the SHAPE deliberately, and it is the crux the design record names.** The
     * resolvable set is `elements` minus what the default variant deletes, and that predicate reads a body
     * member — so tracking it as content would give every reader of the resolvable set a dependency on every
     * body, which is the collapse this alphabet exists to prevent. A `variantFilter` decides which
     * declarations EXIST for resolution, which is structural; and no keystroke in a condition changes one, so
     * the shape stays stable across exactly the edits reuse is for.
     */
    data object DeclarationSetShape : FactRead

    /** One declaration's body, named by the path that identifies it. */
    data class DeclarationContent(val path: String) : FactRead

    /**
     * One declaration's own SHAPE row — its name, kind, id and origin — and no body.
     *
     * **It exists because the cursor's shape members are free, and that is exactly what makes a
     * per-declaration cell incomplete without it.** A family that WALKS the set charges
     * [DeclarationSetShape] and so covers every row it then reads off a cursor. A family the dispatcher
     * hands one declaration to never walks, so nothing in its own bucket says which declaration it was
     * about: the cell key carries the path, and a verdict that turned on `declaredKind` — which is every
     * family that filters by kind — would be reused for a declaration whose kind had changed at that path.
     * So the dispatcher charges the row to the cell it is about, and the cell is a function of what it read
     * again.
     *
     * The ordinal is the same one [DeclarationKey] carries, because a path is not unique.
     */
    data class DeclarationRow(val path: String, val amongSamePath: Int) : FactRead

    /** One model-wide member of the fact base. */
    data class ModelMember(val fact: ModelFact) : FactRead
}

/**
 * A model-wide member of [PreparedModel] that some family reads.
 *
 * An enum rather than a member-name string, so a transcript cannot name a fact that does not exist and two
 * families cannot spell one fact two ways — either would key cells that should share, or share cells that
 * should not. It names the facts the migrated families read, and grows with them: [TrackedFactBase] says why
 * the surface is not opened up front.
 */
internal enum class ModelFact {
    CONDITION_LANGUAGE,
    DECLARED_LOCALES,
    FIELD_REF_BY_SHORT_NAME_ALLOWED,

    /** Whether a base year is bound, and the text A12 cannot bind: one member, `content.modelInfo.baseYear`. */
    BASE_YEAR,
    SUPPORTED_CHARACTERS,
    UNREADABLE_LOCALE_CODES,
    UNBINDABLE_BOOLEANS,

    /** The model-level members A12 cannot bind in the models the entry reaches, read in one closure walk. */
    UNBINDABLE_REACHED_MEMBERS,
    TYPE_DEFINITIONS,

    /**
     * Which groups repeat — a model-wide fact rather than a set-shape one, because repeatability is a BODY
     * member and the shape deliberately carries only path, name, kind, id and origin.
     *
     * It exists because [TrackedFactBase.repeatableGroups] charged [DeclarationSetShape], which a
     * repeatability edit does not move: a cell answered from the repeatable-group list was keyed on less
     * than it read, the one direction that makes reuse unsound. Charging this instead is also strictly more
     * precise than the shape would be — adding an unrelated declaration no longer falls these cells.
     */
    REPEATABLE_GROUP_SET,

    /**
     * Which groups declare a count below 1 — a body member, for the same reason as [REPEATABLE_GROUP_SET], and asked
     * of every element because A12 refuses each element beneath such a group. Reading the ancestors' bodies instead
     * would key every element's cell on its parent's whole body, which a sibling's edit moves.
     */
    NON_POSITIVE_COUNT_GROUPS,
}

/**
 * What was read out of one fact base, bucketed by the CELL that read it.
 *
 * A SET per bucket rather than a list: reuse asks whether a fact was read, never how often or in what order,
 * so a family that reads one declaration twice must not key differently from one that reads it once.
 *
 * **The bucket lives here and not on the fact base, which is the design's own note rather than a
 * preference.** The declaration cursors are memoized per [TrackedFactBase], so giving every producer its own
 * fact base would rebuild the whole cursor list once per family. It is also the honest shape: a scope says
 * who read a fact, not which model it came from.
 *
 * **A nested producer call charges the OUTER producer, deliberately.** A few families ask another family for
 * its verdict rather than re-deriving its predicate ([errorFieldReferenceFindings] asks
 * [filteredEntityIterationFindings] which declarations its gate already answered), and those reads are part
 * of what the outer family depends on. Charging them to the inner one instead would leave the outer cell
 * keyed on less than it read, which is the one direction that makes reuse UNSOUND. The inner family still
 * gets its own bucket from its own registration.
 */
internal class ReadTranscript(private val retention: CellRetention = CellRetention.KEPT) {
    /**
     * The buckets. The `null` key is the COMPOSITION — [checkRun]'s own reads, which belong to no cell and
     * are never reused. **The dispatcher's own walk of the declarations charges there, deliberately**: a
     * per-declaration bucket that recorded the set's shape would fall whenever any declaration was added or
     * removed, undoing the per-declaration key's whole point. What makes that sound is the walk itself —
     * a declaration that is gone is never asked for, and a new one has no cell.
     */
    private val byCell = mutableMapOf<CellKey?, MutableSet<FactRead>>()

    private var producing: CellKey? = null

    fun record(read: FactRead) {
        when (retention) {
            CellRetention.KEPT -> byCell.getOrPut(producing) { mutableSetOf() } += read
            // Nothing will read it back: [checkRun] hands this run's store away as `CheckCells.NONE`, so a
            // recorded read could only be paid for and then dropped.
            CellRetention.DISCARDED -> Unit
        }
    }

    /** Run [produce] with every read it makes charged to [key]. */
    fun <T> whileProducing(key: CellKey, produce: () -> T): T {
        val outer = producing
        producing = key
        try {
            return produce()
        } finally {
            producing = outer
        }
    }

    /** Every fact read through this transcript, whoever read it. */
    fun observed(): Set<FactRead> = byCell.values.flatMapTo(mutableSetOf()) { it }

    /** A snapshot of one cell's bucket, so a later read cannot mutate a key a cell already holds. */
    fun observedWhileProducing(key: CellKey): Set<FactRead> = byCell[key]?.toSet() ?: emptySet()
}
