package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.FieldValueRead
import io.github.mbackschat.a12.dm.consistency.syntax.fieldValueReadsIn

/**
 * What A12's field-value read `[ … ]` demands of the one entity it names.
 *
 * `CheckFeldWertOpImpl.check` asks four gates of the spec, each of them throwing, and no other production
 * reads a bracket ([KERNEL-FINDINGS KF473], `rulekit.validate.laws.FieldValueReadOperandLawsTest`):
 *
 * | gate, in order | class |
 * |---|---|
 * | the spec names a group | `MVK_NO_GROUPS_ALLOWED` |
 * | it carries a repetition star | `MVK_NO_WILDCARDS_ALLOWED` |
 * | the field waives value validation | `MVK_INVALID_RAW_TYPE` |
 * | the field is a partially known date | `MVK_INVALID_DATE_TYPE` |
 *
 * **The gates are the bracket's, wherever it stands** — either side of a comparison, inside `{ … }`, an
 * operator's argument, a computation's operation, a scalar value list's `[field]` entry — and they are raised
 * at the closing bracket, during the parse and so ahead of any gate of the construct around it. A starred read
 * compared with a string literal draws the star's class and not a kind mismatch, and a value list's entry is
 * judged before the list's own subject ([scalarValueListGateFindings] asks nothing an entry's bracket asks).
 *
 * **It judges only a spec the reference reader passes** ([addressedTargetOf]): one that resolves, stars no
 * level that does not repeat, and leaves unstarred only repeatable levels the declaration's TARGET iterates. A
 * computation's target counts as a rule's error field does, so into `Lines/Qty` the target's own group
 * `[Lines]` passes and draws the group class (`adapter.laws.CheckerFieldValueReadCarrierAgreementTest`). Every
 * other spec meets the reader's own class first — `MVK_INVALID_ENTITY`, `MVK_INVALID_WILDCARD`,
 * `MVK_NO_WILDCARD`, or `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` where the target lies outside the declaring group.
 * A star on a level the rule binds still counts, because `hasWildCards` asks the spelling. **A filter's body
 * is the exception**: there no unstarred level stops the read, the filter's own level or any other, since the
 * filter chain asks about repetitions only once the whole body has been read.
 *
 * **In a filter's body the star is [filterWildcardFindings]'**, whose reach there is measured on its own
 * conditions; this family asks the other three gates there. One gate split between two families is DG94 in
 * CONSISTENCY-GAPS. That is what separates a GROUP read in a body,
 * refused at its bracket, from the filter chain's `MVK_NO_ITERATION_FOR_WILDCARD`, raised only once the whole
 * filtered spec has been read.
 *
 * **A category behind the path leaves the star gate standing.** The spec's category gates run when the spec
 * ends and so ahead of the bracket's (`CheckEntitySpecImpl.checkCategory`, [categoryReferenceFindings]). They
 * refuse a group themselves, so this family leaves a categorized group to that family, but
 * a category an enumeration declares passes them, and a read of one through a starred `Claims` level then
 * draws the star's class — measured as a computation's whole operation, where it also answers ahead of the
 * enumeration-source gate (`adapter.laws.CheckerKeyedOperandAgreementTest`). This family judges such a read
 * only where the category gates pass, and asks nothing else of it: an enumeration neither waives value
 * validation nor is a date.
 *
 * **Silent on a spec carrying a clause, the `$` marker or the rule-context keyword**, whose own gates run
 * first and are unmeasured beside these.
 *
 * **A computation's TARGET is read too**, as the `[<target>]` of the comparison each alternative is lowered
 * to ([loweredComparisonOf]), ahead of the operation: into a partially known date it draws
 * `MVK_INVALID_DATE_TYPE`, into a raw text `MVK_INVALID_RAW_TYPE`
 * (`adapter.laws.CheckerComputationJoinedConditionAgreementTest`). Only those two gates can answer there, since
 * A12's builder refuses a group target before any of them ([computedTargetKindFindings]) and a target carries
 * no star.
 */
internal fun TrackedFactBase.fieldValueReadOperandFindings(): List<Finding> = declarations().flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).flatMap { (member, text) ->
        val target = loweredComparisonOf(element, body, member, text)?.targetRead?.let { read ->
            readDefect(read, group, element)?.let { defect -> targetFinding(defect, element, member, read) }
        }
        listOfNotNull(target) + fieldValueReadsIn(text, conditionLanguage).mapNotNull { read ->
            readDefect(read, group, element)?.let { defect -> finding(defect, element, member, read) }
        }
    }
}

/** Whether one of the field-value read's gates refuses [read], as far as this family may judge it. */
internal fun TrackedFactBase.fieldValueReadIsRefused(
    read: FieldValueRead,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean = readDefect(read, declaringGroup, element) != null

/** The field-value read's gates, in the order `CheckFeldWertOpImpl.check` asks them. */
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
        everyLevelAddressed = read.inAFilterBody) ?: return null
    val target = declarationAt(addressed) ?: return null
    when (target.declaredKind) {
        // The spec's own category gate asks the group question first ([categoryReferenceFindings]).
        DeclaredKind.GROUP -> return if (read.category != null) null else ReadDefect(ReadGate.GROUP, written, target.path)
        DeclaredKind.FIELD -> Unit
        else -> return null
    }
    if (read.category != null && read.category !in target.enumerationBody()?.declaredCategoryNames().orEmpty()) {
        return null
    }
    if ('*' in written) {
        return if (read.inAFilterBody) {
            null
        } else {
            ReadDefect(ReadGate.STAR, written, target.path)
        }
    }
    // A type definition or an unspecified type has no class this reader can know, so both type gates stay silent.
    target.baseTypeClass() ?: return null
    return when {
        target.waivesValueValidation() -> ReadDefect(ReadGate.RAW, written, target.path)
        declaresAPartialPrecision(target) -> ReadDefect(ReadGate.PARTIAL, written, target.path)
        else -> null
    }
}

private fun finding(defect: ReadDefect, element: TrackedDeclaration, member: String, read: FieldValueRead): Finding {
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
        "in `$member`: `[${defect.written}]` $because",
        element.path,
        TextSpan(member, read.at, read.end),
    )
}

/** A computation target's own read, whose corrective is about what to compute INTO rather than how to read. */
private fun targetFinding(defect: ReadDefect, element: TrackedDeclaration, member: String, read: FieldValueRead): Finding? {
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
        "in `$member`: A12 compares the computed field `[${defect.written}]` with this operation, and it $because",
        element.path,
        TextSpan(member, read.at, read.end),
    )
}
