package io.github.mbackschat.a12.dm.consistency

/**
 * Whether any group holds two children of the same name — the collision A12 cannot build a model through.
 *
 * **The demand spans KINDS and stops at the group.** Two rules of one name in one group, a rule named after
 * a sibling FIELD, and a rule named after a sibling GROUP are all refused; the same name in two different
 * groups is fine, and so is a rule named after a field in another group. A "rule names must be unique"
 * reading passes two of those three refusals, which is why the kinds are measured rather than assumed
 * (`ElementNameUniquenessLawsTest`, kernel 30.8.1, 2026-09-12).
 *
 * **And it is case-SENSITIVE**, unlike the `vk_vk` prefix law on the same member, which the Kernel matches in
 * any letter case. Two laws on one slot with opposite case rules is exactly the pair a checker unifies by
 * accident, so `Probe` beside `probe` in one group is admitted and gets no finding.
 *
 * **The outcome is not a verdict.** A12 throws while building the model from inside `checkConsistency` and
 * reports no `MVK_*` code at all, so every other finding in the same report is this checker's own reading —
 * which is what [CK_MODEL_NOT_BUILDABLE] and the coverage gap beside it say.
 */
fun PreparedModel.elementNameFindings(): List<Finding> =
    elements.groupBy { it.path }.filterValues { it.size > 1 }.map { (path, collided) ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "this group holds ${collided.size} children named `${path.substringAfterLast('/')}` " +
                "(${collided.joinToString(", ") { it.kind }}) — A12 cannot build a model with two children " +
                "of one name under one group, whatever kinds they are, so nothing in it is judged",
            path,
        )
    }
