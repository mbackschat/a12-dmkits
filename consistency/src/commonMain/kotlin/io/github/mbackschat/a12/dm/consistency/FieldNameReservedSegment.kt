package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage

/**
 * The one field name `MVK_NAME_INVALID_A12_DV` is left to judge: a reserved final dotted segment.
 *
 * `CheckFieldName` delegates to `NameValidationService.checkFieldNameNoMetaModelChecks`, which refuses on two
 * arms — a name outside the identifier pattern `[a-zA-Z0-9_:]+([.][a-zA-Z][a-zA-Z0-9_:]*)*`, and a name that
 * MATCHES it whose last dotted segment is `.value`, case-insensitively, in the English condition language
 * ([KERNEL-FINDINGS KF419], `rulekit.validate.laws.FieldNameReservedSuffixLawsTest`).
 *
 * **Only the second arm is reported, because only the second arm fires.** A complete printable-ASCII sweep
 * over field names draws OTHER classes for every illegal character ([KF367](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf367)), so
 * the pattern arm is pre-empted and a family reproducing it would refuse names A12 takes with a class A12
 * does not use. The reserved segment is the opposite case: every alphabet gate admits the name, which is why
 * no character sweep could reach it.
 *
 * **Its own family rather than part of [CheckFamily.ELEMENT_NAME_ALPHABET]** because it is a reserved WORD
 * and not an alphabet — and because the two are reported TOGETHER: the segment needs a dot before it, the dot
 * draws `MVK_NAME_INVALID_DOT_COLON_A12_DV` from that family, and A12 states this class FIRST. A report
 * carrying only the dot names the half that is not the reason.
 *
 * **What it leaves alone**, under-reporting: a model whose condition language is not English. A12 reserves a
 * different set there — `.Wert`, `.Vordruckzeile`, `.Vordruckname` — and nothing in this estate has measured
 * it, so a checker applying the German list would be reproducing a source read rather than a measurement.
 */
internal fun TrackedFactBase.fieldNameReservedSegmentFinding(field: TrackedDeclaration): Finding? {
    if (conditionLanguage != ConditionLanguage.EN) {
        return null
    }
    if (field.declaredKind != DeclaredKind.FIELD || !field.name.reservesItsFinalSegment() ||
        field.body.deletesAtDefaultVariant()
    ) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_NAME_INVALID_A12_DV",
        "this field's name ends in the segment `.value`, which A12's field-name gate reserves — the " +
            "dot itself is refused beside it, and A12 states this class first",
        field.path,
    )
}

/** A12's field-name identifier pattern; the captured group is the LAST dotted segment when there is one. */
private val A12_FIELD_IDENTIFIER = Regex("[a-zA-Z0-9_:]+([.][a-zA-Z][a-zA-Z0-9_:]*)*")

/**
 * Whether this name matches A12's pattern AND reserves its final segment.
 *
 * The pattern's dotted group is OPTIONAL, so an undotted name matches with the group unset — which is the
 * row that keeps a checker from refusing a field simply named `value`, a name A12 admits.
 */
private fun String.reservesItsFinalSegment(): Boolean {
    val matched = A12_FIELD_IDENTIFIER.matchEntire(this) ?: return false
    return matched.groupValues[1].lowercase() == ".value"
}
