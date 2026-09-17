package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonNull
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
 * **The FLOOR is not carrier-dependent, and A12's side of it was already measured** — [KERNEL-FINDINGS
 * KF221], `adapter.laws.GroupRepeatabilityDomainLawsTest`: the domain is any integer `> 0`, and `0`, `null`
 * and `-1` draw `MVK_MAX_VALUES_INVALID`. What this family did with that until 2026-09-16 was nothing: it
 * held only the upper half, so a stored `"repeatability":0` was reported by A12 and silent here. The two
 * boundary cells KF221 did not carry were measured the same day and extend it there — the signed 32-bit
 * floor still draws the class, one below it is refused as malformed input exactly as one above the ceiling
 * is. The silence was one of the false-pass routes the coverage residue names
 * (PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN, the codegen tier).
 *
 * **The sibling law demanding the count be DECLARED has no stored spelling and is deliberately silent.**
 * `CodeGenValidationDataCollector` writes the count it derived rather than the member it read, so
 * `MVK_MAX_MISSING` cannot fire on a stored model — an omitted `repeatability` is simply a group that
 * repeats once.
 */
internal fun TrackedFactBase.groupRepetitionCountFinding(group: TrackedDeclaration): Finding? {
    if (group.declaredKind != DeclaredKind.GROUP) {
        return null
    }
    val member = group.body?.get("repeatability") ?: return null
    val cap = if (group.isRootGroup()) ROOT_GROUP_CAP else NESTED_GROUP_CAP
    // An explicit `null` is the DOMAIN's defect and not the absent key, and the two are one line apart
    // here: absence means a group that simply does not repeat, while a declared null draws
    // `MVK_MAX_VALUES_INVALID` with the same *greater than 0* wording `0` does (KF221). Reading the
    // member through `longOrNull` alone collapses them, which left this silent on a value A12 refuses.
    if (member is JsonNull) {
        return group.invalidCount("null")
    }
    val declared = (member as? JsonPrimitive)?.longOrNull ?: return null
    return when {
        // Both directions are the same refusal — the member does not fit A12's signed 32-bit slot — so
        // they share one message rather than stating it twice.
        declared > READABLE_CEILING || declared < READABLE_FLOOR -> Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "`${group.path}` declares a `repeatability` of $declared, which A12's reader cannot hold " +
                "in the signed 32-bit member it declares — the source is refused before any check runs, " +
                "so no verdict about this model exists",
            group.path,
        )

        declared <= 0 -> group.invalidCount(declared.toString())

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

/** The domain's own class, for a count A12 requires to be at least 1 — `0`, a negative, or a literal null. */
private fun TrackedDeclaration.invalidCount(declared: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_MAX_VALUES_INVALID",
    "`$path` declares a `repeatability` of $declared and A12 requires at least 1, since a group " +
        "repeating a non-positive number of times has no index range to address",
    path,
)

/** A root group's own cap, measured: 999999999 is admitted and one more is not. */
private const val ROOT_GROUP_CAP = 999999999L

/** …and a nested group's, measured on the same walk: an order of magnitude higher. */
private const val NESTED_GROUP_CAP = 1999999999L

/** Above this the member does not fit A12's `Group.repeatability`, and the source is never read. */
private const val READABLE_CEILING = 2147483647L

/** …and below this, symmetrically: the signed 32-bit floor, past which the reader refuses the source. */
private const val READABLE_FLOOR = -2147483648L

/** A root group is the one segment below the model root; every other group hangs under one. */
private fun TrackedDeclaration.isRootGroup(): Boolean = path.count { it == '/' } == 1
