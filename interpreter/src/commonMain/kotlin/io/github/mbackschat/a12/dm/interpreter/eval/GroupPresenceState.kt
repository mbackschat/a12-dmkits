package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Tri

/**
 * Validation-stage presence of one group instance. Formal conversion and error marking are independent kernel
 * stages. Relevance is independently [GroupRelevance.PARTIAL] or [GroupRelevance.FULL]: positive presence is
 * knowable from any relevant descendant, while absence is knowable only when the complete group is relevant.
 */
data class GroupPresenceState(
    val filled: Boolean,
    val erroneous: Boolean,
    val relevance: GroupRelevance = GroupRelevance.FULL,
) {
    val definitelyFilled: Boolean get() = relevance != GroupRelevance.NONE && filled
    val definitelyEmpty: Boolean get() = relevance == GroupRelevance.FULL && !filled && !erroneous

    /**
     * The three-valued PRESENCE PRODUCT — "is this group filled?" decided from admitted content first, so a
     * group holding both admitted content and an independent formal error is filled rather than undecided. Two
     * consumers read it: the GROUP-fill quantifiers tally it per operand (`Interpreter.groupFillStates`), and the
     * scalar `GroupNotFilled` leaf is its Kleene complement ([negativeLeaf]). Kept here, beside the state it
     * reads, because a pure per-operand semantic belongs in the runtime layer a future generator would call
     * rather than inside the tree-walk (IF10).
     *
     * **The scalar `GroupFilled` leaf deliberately does NOT read it, and must not be unified onto it.** That leaf
     * answers `Tri.of(filled)` for any relevant group, which differs here in two cells — a partially relevant
     * empty group and a fully relevant content-free erroneous one, where this product is UNKNOWN and `GroupFilled`
     * is FALSE. Unlike [negativeLeaf], those two cells ARE kernel-observable, so a tidying refactor that made
     * `GroupFilled` "consistent" with this product would be a behavioural regression with a differential to
     * match. `GroupPresenceLeafProjectionTest` asserts both leaves against these states for that reason.
     */
    val fillProduct: Tri get() = when {
        definitelyFilled -> Tri.TRUE
        definitelyEmpty -> Tri.FALSE
        else -> Tri.UNKNOWN
    }

    /**
     * The scalar `GroupNotFilled` leaf — [fillProduct]'s Kleene complement, so admitted content decides FALSE
     * before an independent error is considered and a content-free erroneous group stays UNKNOWN.
     *
     * <p>This is an ALIGNMENT with a12-kernel-lean's `SPEC-2026-09-02-03`, taken on the owner's 2026-09-03
     * decision as a coherence choice rather than a correctness one. No kernel channel sees this leaf: rule
     * firing cannot, because the condition language has no negation combinator and Kleene `And`/`Or` are
     * monotone; message polarity cannot (KF233, measured on both codegen strategies); and neither can a
     * `Having` predicate carrying it, because a conjunct that is not true drops its own row and leaves the
     * aggregate available at its ordinary value (KF234, measured). What the alignment buys is that the leaf
     * and the tally now project ONE presence notion instead of two. Reopen only if a leaf-observable channel
     * is found.
     */
    val negativeLeaf: Tri get() = Kleene.not(fillProduct)
}

/** How much of a group is validation-relevant for this concrete row. */
enum class GroupRelevance { NONE, PARTIAL, FULL }
