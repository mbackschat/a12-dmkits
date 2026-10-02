package io.github.mbackschat.a12.dm.consistency

/** The star gate precedes no-value-validation; both follow membership (KF279, MessageValueSuffixGateOrderLawsTest). */
internal fun TrackedFactBase.messageValueDefect(path: String, element: TrackedDeclaration): String? {
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return when {
        path in pathsReadThroughAStarAlone(element, group) -> "PARAM_VALUE_WITH_STARREFERENCE"
        declarationAt(path)?.waivesValueValidation() == true -> "INVALID_NO_VALUE_VALIDATION"
        else -> null
    }
}

/**
 * The paths this declaration reads ONLY through a repetition wildcard.
 *
 * The gate is about how the CONDITION binds the field, not about how the parameter spells it: the measured
 * message writes `Lines/Sku` with no star at all and is refused because the condition's only read of that
 * field carries one. A single unstarred read anywhere clears it, which is why this collects both forms and
 * subtracts rather than testing the first read it finds.
 */
private fun TrackedFactBase.pathsReadThroughAStarAlone(element: TrackedDeclaration, group: String): Set<String> {
    val starred = mutableSetOf<String>()
    val plain = mutableSetOf<String>()
    messageConditionReferences(element).forEach { written ->
        val resolved = (resolveEntity(group, written.replace("*", ""), element.origin) as? Reference.Resolved)
            ?.path ?: return@forEach
        val footprint = messageReferenceFootprint(resolved)
        if ('*' in written) starred += footprint else plain += footprint
    }
    return starred - plain
}
