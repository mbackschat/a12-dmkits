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
 * could not have, measured by the probe in `docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md` ("L-b's method
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
}

/**
 * What one family read while producing its findings.
 *
 * A SET rather than a list: reuse asks whether a fact was read, never how often or in what order, so a family
 * that reads one declaration twice must not key differently from one that reads it once.
 */
internal class ReadTranscript {
    private val reads = mutableSetOf<FactRead>()

    fun record(read: FactRead) {
        reads += read
    }

    /** A snapshot, so a later read cannot mutate a transcript a cell is already keyed on. */
    fun observed(): Set<FactRead> = reads.toSet()
}
