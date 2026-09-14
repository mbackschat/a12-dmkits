package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * How many times a group may repeat, against the cap A12 puts on it.
 *
 * **The cap depends on the CARRIER and the two are an order of magnitude apart** — a root group is capped at
 * 999 999 999 and a nested one at 1 999 999 999 ([KERNEL-FINDINGS KF365],
 * `rulekit.validate.laws.GroupIndexAndSortReferenceLawsTest`). A12 states the law twice, once per carrier,
 * and a checker holding one bound would refuse a legal nested group or miss an illegal root one. Both were
 * walked rather than one measured and the other assumed.
 *
 * **Past a signed 32-bit count A12 stops READING**, so the model reaches no verdict and this reports the
 * unbuildable tier instead of naming a class A12 never issued. That boundary is measured at both cells: the
 * ceiling itself draws the cap's class, one past it makes the source unreadable.
 *
 * **The sibling law demanding the count be DECLARED has no stored spelling and is deliberately silent.**
 * `CodeGenValidationDataCollector` writes the count it derived rather than the member it read, so
 * `MVK_MAX_MISSING` cannot fire on a stored model — an omitted `repeatability` is simply a group that
 * repeats once.
 */
fun PreparedModel.groupRepetitionCountFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.GROUP }.mapNotNull { group ->
        val declared = (group.body?.get("repeatability") as? JsonPrimitive)?.longOrNull
            ?: return@mapNotNull null
        val cap = if (group.isRootGroup()) ROOT_GROUP_CAP else NESTED_GROUP_CAP
        when {
            declared > READABLE_CEILING -> Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                CK_MODEL_NOT_BUILDABLE,
                "`${group.path}` declares a `repeatability` of $declared, which A12's reader cannot hold " +
                    "in the signed 32-bit member it declares — the source is refused before any check runs, " +
                    "so no verdict about this model exists",
                group.path,
            )

            declared > cap -> Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_MAX_TOO_BIG",
                "`${group.path}` may repeat $declared times and A12 caps a " +
                    (if (group.isRootGroup()) "root" else "nested") + " group at $cap",
                group.path,
            )

            else -> null
        }
    }

/** A root group's own cap, measured: 999999999 is admitted and one more is not. */
private const val ROOT_GROUP_CAP = 999999999L

/** …and a nested group's, measured on the same walk: an order of magnitude higher. */
private const val NESTED_GROUP_CAP = 1999999999L

/** Above this the member does not fit A12's `Group.repeatability`, and the source is never read. */
private const val READABLE_CEILING = 2147483647L

/** A root group is the one segment below the model root; every other group hangs under one. */
private fun DeclaredElement.isRootGroup(): Boolean = path.count { it == '/' } == 1
