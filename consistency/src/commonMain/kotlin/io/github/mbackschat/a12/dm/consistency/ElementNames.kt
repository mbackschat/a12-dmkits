package io.github.mbackschat.a12.dm.consistency

/**
 * Whether any group holds two children of the same name — the collision A12 cannot build a model through.
 *
 * **The demand spans KINDS and stops at the group.** Two rules of one name in one group, a rule named after
 * a sibling FIELD, and a rule named after a sibling GROUP are all refused; the same name in two different
 * groups is fine, and so is a rule named after a field in another group. A "rule names must be unique"
 * reading passes two of those three refusals, which is why the kinds are measured rather than assumed
 * (`ElementNameUniquenessLawsTest`, kernel 30.8.1, 2026-09-12; extended 2026-09-13 to a GROUP and a
 * COMPUTATION taking a sibling's name, each against a fresh-name control). This function keys on the path
 * alone and so applies to every kind, which is sound only because every kind was measured: one pairing A12
 * admitted would turn it into an over-rejection.
 *
 * **And it is case-SENSITIVE**, unlike the `vk_vk` prefix law on the same member, which the Kernel matches in
 * any letter case. Two laws on one slot with opposite case rules is exactly the pair a checker unifies by
 * accident, so `Probe` beside `probe` in one group is admitted and gets no finding.
 *
 * **The outcome is not a verdict.** A12 throws while building the model from inside `checkConsistency` and
 * reports no `MVK_*` code at all, so every other finding in the same report is this checker's own reading —
 * which is what [CK_MODEL_NOT_BUILDABLE] and the coverage gap beside it say.
 */
internal fun TrackedFactBase.elementNameFindings(): List<Finding> =
    // Keyed by PATH, which is the parent plus the name — so a collision is by construction between
    // declarations of one parent, and `at` names the collision itself rather than the parent it sits in.
    allDefaultVariantDeclarations().groupBy { it.path }.filterValues { it.size > 1 }.map { (path, collided) ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "${collided.size} declarations here carry the name `${collided.first().name}` " +
                "(${collided.joinToString(", ") { it.kind }}) — A12 cannot build a model in which two " +
                "declarations of one parent share a name, whatever kinds they are, so nothing in it is judged",
            path,
        )
    }
