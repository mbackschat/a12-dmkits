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
 */
internal fun TrackedFactBase.fieldValueReadOperandFindings(): List<Finding> = declarations().flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).flatMap { (member, text) ->
        fieldValueReadsIn(text, conditionLanguage).mapNotNull { read ->
            readDefect(read, group, element)?.let { defect -> finding(defect, element, member, read) }
        }
    }
}

private class ReadDefect(val code: String, val written: String, val because: String)

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
        DeclaredKind.GROUP -> return if (read.category != null) null else ReadDefect(
            "MVK_NO_GROUPS_ALLOWED", written,
            "names the group `${target.path}`, and a field-value read takes one field",
        )
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
            ReadDefect(
                "MVK_NO_WILDCARDS_ALLOWED", written,
                "is read across a repetition, and a field-value read takes one addressed value — aggregate the " +
                    "list, or let the rule iterate the repeating group",
            )
        }
    }
    // A type definition or an unspecified type has no class this reader can know, so both type gates stay silent.
    target.baseTypeClass() ?: return null
    return when {
        target.waivesValueValidation() -> ReadDefect(
            "MVK_INVALID_RAW_TYPE", written,
            "waives value validation, so A12 holds its content unparsed and there is no value to read",
        )
        declaresAPartialPrecision(target) -> ReadDefect(
            "MVK_INVALID_DATE_TYPE", written,
            "is a partially known date, which a field-value read cannot take — resolve it with `ValueAsDate`",
        )
        else -> null
    }
}

private fun finding(defect: ReadDefect, element: TrackedDeclaration, member: String, read: FieldValueRead) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `[${defect.written}]` ${defect.because}",
    element.path,
    TextSpan(member, read.at, read.end),
)
