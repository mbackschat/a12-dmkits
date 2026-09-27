package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.FieldValueRead
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite

/**
 * The completed field read's group, star, raw-value and partial-Date gates (KF473, KF496).
 *
 * Reference and category gates precede these. Active unmarked filter reads defer repetition addressing to
 * the filter chain; marked reads retain rule-target binding (KF487). The recognizer supplies completion and
 * filter state, including reads whose enclosing construct fails later, so each position has one producer.
 * Computation targets are synthetic reads before their operations (KF483).
 */
internal fun TrackedFactBase.fieldValueReadOperandFindings(): List<Finding> =
    declarations().flatMap { element -> fieldValueReadContributions(element).map { it.finding } }

internal fun TrackedFactBase.fieldValueReadContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val execution = conditionExecution(element) ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return execution.recognition.sites.filterIsInstance<SyntaxSite.FieldRead>().mapNotNull { site ->
        val read = site.read
        val defect = readDefect(read, group, element) ?: return@mapNotNull null
        val span = execution.carrier.semanticSpan(read.at, read.end) ?: return@mapNotNull null
        val finding = if (execution.carrier.at(read.at)?.authored == false)
            targetFinding(defect, element, span)
        else finding(defect, element, span)
        finding?.let { ConditionFinding(element.key, site, it) }
    }
}

/** Whether one of the field-value read's gates refuses [read], as far as this family may judge it. */
internal fun TrackedFactBase.fieldValueReadIsRefused(
    read: FieldValueRead,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean = readDefect(read, declaringGroup, element) != null

/** The field-value read's gates, in the order measured by KF473. */
private enum class ReadGate(val code: String) {
    GROUP("MVK_NO_GROUPS_ALLOWED"),
    STAR("MVK_NO_WILDCARDS_ALLOWED"),
    RAW("MVK_INVALID_RAW_TYPE"),
    PARTIAL("MVK_INVALID_DATE_TYPE"),
}

private class ReadDefect(val gate: ReadGate, val written: String, val target: String)

/** The first gate this read fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.readDefect(
    read: FieldValueRead,
    declaringGroup: String,
    element: TrackedDeclaration,
): ReadDefect? {
    val written = read.path ?: return null
    // In a filter's body the levels a read crosses are the filter chain's question, asked only after the body
    // has been read, so a group there meets its bracket first — whether or not the filter iterates it
    // (`rulekit.validate.laws.FieldValueReadOperandLawsTest.theGatesReachEveryCarrierOfTheRead`).
    val addressed = addressedTargetOf(written, declaringGroup, element, indexSelected = false,
        everyLevelAddressed = read.inAFilterBody && !read.outerMarker) ?: return null
    val target = declarationAt(addressed) ?: return null
    when (target.declaredKind) {
        // The spec's own category gate asks the group question first ([categoryContributions]).
        DeclaredKind.GROUP -> return if (read.category != null) null else ReadDefect(ReadGate.GROUP, written, target.path)
        DeclaredKind.FIELD -> Unit
        else -> return null
    }
    if (read.category != null && read.category !in target.enumerationBody()?.declaredCategoryNames().orEmpty()) {
        return null
    }
    if ('*' in written) {
        return ReadDefect(ReadGate.STAR, written, target.path)
    }
    // A type definition or an unspecified type has no class this reader can know, so both type gates stay silent.
    target.baseTypeClass() ?: return null
    return when {
        target.waivesValueValidation() -> ReadDefect(ReadGate.RAW, written, target.path)
        declaresAPartialPrecision(target) -> ReadDefect(ReadGate.PARTIAL, written, target.path)
        else -> null
    }
}

private fun finding(defect: ReadDefect, element: TrackedDeclaration, span: TextSpan): Finding {
    val because = when (defect.gate) {
        ReadGate.GROUP -> "names the group `${defect.target}`, and a field-value read takes one field"
        ReadGate.STAR -> "is read across a repetition, and a field-value read takes one addressed value — " +
            "aggregate the list, or let the rule iterate the repeating group"
        ReadGate.RAW -> "waives value validation, so A12 holds its content unparsed and there is no value to read"
        ReadGate.PARTIAL -> "is a partially known date, which a field-value read cannot take — resolve it with " +
            "`ValueAsDate`"
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        defect.gate.code,
        "in `${span.member}`: `[${defect.written}]` $because",
        element.path,
        span,
    )
}

/** A computation target's own read, whose corrective is about what to compute INTO rather than how to read. */
private fun targetFinding(defect: ReadDefect, element: TrackedDeclaration, span: TextSpan): Finding? {
    val because = when (defect.gate) {
        // A group target never reaches the read, and a target path is never starred.
        ReadGate.GROUP, ReadGate.STAR -> return null
        ReadGate.RAW -> "waives value validation, so there is no value to compare the result with — compute into " +
            "a field that validates its value"
        ReadGate.PARTIAL -> "is a partially known date, which a field-value read cannot take — compute into a " +
            "date of full precision"
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        defect.gate.code,
        "in `${span.member}`: A12 compares the computed field `[${defect.written}]` with this operation, and it $because",
        element.path,
        span,
    )
}
