package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
internal fun TrackedFactBase.groupRepetitionCountFindings(element: TrackedDeclaration): List<Finding> {
    val own = groupRepetitionCountFinding(element)
    // The class is one verdict per element, so a group refused on its own count does not draw it again.
    val inherited = if (own?.code == "MVK_MAX_VALUES_INVALID" || own?.code == CK_MODEL_NOT_BUILDABLE) null
    else inheritedInvalidCount(element)
    return listOfNotNull(own, inherited, countSentence(element))
}

/**
 * The class an element draws for a non-positive count declared by a group ABOVE it ([KERNEL-FINDINGS KF590]).
 * A12 judges each element's repetition layers, which it derives from every ancestor, so one group's `0` is a
 * defect in each group and field beneath it, and every one of them carries the class once. A rule or computation
 * beneath carries none: the entity walk that judges the layers visits groups and fields only (measured, KF590).
 */
private fun TrackedFactBase.inheritedInvalidCount(element: TrackedDeclaration): Finding? {
    val refused = nonPositiveCountGroupPaths()
    if (refused.isEmpty() || element.declaredKind !in REPETITION_LAYERED) {
        return null
    }
    val ancestor = element.path.split('/').filter { it.isNotEmpty() }.dropLast(1)
        .runningFold("") { prefix, segment -> "$prefix/$segment" }.drop(1)
        .firstOrNull { it in refused } ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_MAX_VALUES_INVALID",
        "`$ancestor` above it declares a `repeatability` below 1, and A12 derives each element's repetition " +
            "from its ancestors, so every group and field beneath that group is refused with it",
        element.path,
    )
}

/** The metamodel's range sentence for the member itself, which A12 states beside the class ([KF590]). */
private fun countSentence(group: TrackedDeclaration): Finding? {
    if (group.declaredKind != DeclaredKind.GROUP) {
        return null
    }
    val member = group.body?.get("repeatability") ?: return null
    val declared = (member as? JsonPrimitive)?.boundRepetitionCount() ?: return null
    val violation = MemberRange.REPEATABILITY.violationOf(declared) ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        violation.code,
        "this group declares `repeatability` $member, outside the range A12 admits; it refuses that with no " +
            "class: \"${violation.sentence}\"",
        group.path,
    )
}

/** Whether a group's [JsonObject] body declares a count A12 reads and holds below 1. */
internal fun JsonObject?.declaresNonPositiveCount(): Boolean {
    val member = this?.get("repeatability") ?: return false
    val declared = (member as? JsonPrimitive)?.boundRepetitionCount() ?: return false
    return declared <= 0
}

private fun TrackedFactBase.groupRepetitionCountFinding(group: TrackedDeclaration): Finding? {
    if (group.declaredKind != DeclaredKind.GROUP) {
        return null
    }
    // The group carrier's one reporter of an envelope A12 cannot bind (UnreadableEnvelopeTest).
    val member = (group.body ?: return unreadableEnvelope(group))["repeatability"] ?: return null
    val cap = if (group.path.isRootGroup()) ROOT_GROUP_CAP else NESTED_GROUP_CAP
    // An explicit `null` is not the absent key: absence is a group that simply does not repeat, while on kernel 31 a
    // declared null is a primitive `int` A12's reader cannot bind (KF221, corrected 2026-11-02), as are an object and
    // an array (RepeatabilityTextReadingDiffTest).
    val primitive = member as? JsonPrimitive ?: return unreadableCount(group, member)
    val declared = primitive.boundRepetitionCount() ?: return unreadableCount(group, primitive)
    return when {
        declared <= 0 -> group.invalidCount(if (primitive.isString) "\"${primitive.content}\"" else declared.toString())

        declared > cap -> Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_MAX_TOO_BIG",
            "`${group.path}` may repeat $declared times and A12 caps a " +
                (if (group.path.isRootGroup()) "root" else "nested") + " group at $cap",
            group.path,
        )

        else -> null
    }
}

/**
 * The count A12's reader holds for a stored `repeatability`, a primitive `int` ([bindJavaPrimitiveInt], KF590,
 * KF593): `" 3 "` and `"\u0663"` are 3, `3.7` is 3 and `1e1` is 10. Null where the reader binds nothing and so leaves
 * no model: the empty text, the text `null` and a JSON null on kernel 31, a no-break space, a fraction or an exponent
 * in text, a count past `Int`, and a Boolean (`RepeatabilityTextReadingDiffTest`).
 */
internal fun JsonPrimitive.boundRepetitionCount(): Int? = bindJavaPrimitiveInt()

private fun unreadableCount(group: TrackedDeclaration, member: JsonElement) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "`${group.path}` declares the `repeatability` $member, which A12's reader cannot bind as a " +
        "whole number — the source is refused before any check runs, so no verdict about this model exists",
    group.path,
)

/** The domain's own class, for a count A12 requires to be at least 1 — `0`, a negative, or a literal null. */
private fun TrackedDeclaration.invalidCount(declared: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_MAX_VALUES_INVALID",
    "`$path` declares a `repeatability` of $declared and A12 requires at least 1, since a group " +
        "repeating a non-positive number of times has no index range to address",
    path,
)

/** The declarations whose repetition layers A12's entity walk judges. */
private val REPETITION_LAYERED = setOf(DeclaredKind.GROUP, DeclaredKind.FIELD)

/** A root group's own cap, measured: 999999999 is admitted and one more is not. */
private const val ROOT_GROUP_CAP = 999999999L

/** …and a nested group's, measured on the same walk: an order of magnitude higher. */
private const val NESTED_GROUP_CAP = 1999999999L
