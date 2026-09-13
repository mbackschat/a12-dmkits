package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B0: the entities a condition names are bound to declarations, and each failure reports A12's own code.
 *
 * The model is one shape throughout — a root group with a field and a subgroup — so every row varies only
 * the condition text. That matters most for the first case, where the two candidate readings of `..` differ
 * by exactly one level and both spellings look correct.
 */
class ConditionBindingTest {

    private fun model(condition: String, language: String = "en_US") = ModelSource(
        """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},"content":{
          "modelConfig":{"conditionLanguage":{"code":"$language"},"fieldRefByShortNameAllowed":false},
          "modelRoot":{"rootGroups":[
            {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
              {"type":"Field","id":"f0","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":20,"elements":[
                {"type":"Field","id":"f1","name":"Amount","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Rule","id":"r0","name":"Check","Rule":{"errorCode":"C",
                  "errorMessage":[{"locale":"en_US","text":"x"}],
                  "errorEntityRelPath":"../Amount","errorCondition":"$condition"}}
              ]}}
            ]}}
          ]}}}
        """.trimIndent(),
    )

    private fun codes(condition: String, language: String = "en_US") =
        check(model(condition, language)).findings.map { it.code }

    /**
     * The discriminating case for the whole slice. A rule at `/Order/Lines/Check` reaches `/Order/Code` as
     * `../Code` in its CONDITION and as `../../Code` in its error target, because a condition climbs from
     * the declaring group and the error target climbs from the declaring element. A resolver that used one
     * rule for both would report exactly one of these two rows wrong, and both spellings read as natural.
     */
    @Test
    fun aConditionPathClimbsFromTheDeclaringGroupNotTheElement() {
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldFilled(../Code)"))
        assertEquals(
            listOf("MVK_INVALID_ENTITY"),
            codes("FieldFilled(Amount) And FieldFilled(../Lines/Code)"),
        )
    }

    /**
     * The same climb when the operand is a GROUP, mirroring the Kernel rows of
     * `rulekit.validate.laws.ConditionReferencePathLawsTest.theClimbIsTheSameWhenTheOperandIsAGroup`.
     *
     * The bare own-group name is the row that needs the lock: `GroupFilled(Lines)` in a rule declared IN
     * `/Order/Lines` reads as naming that group and names `/Order/Lines/Lines`, which nothing declares. A
     * resolver that special-cased it to the declaring group would admit a model A12 rejects — and the
     * spelling that DOES name it, `../Lines`, sits beside it as the control.
     */
    @Test
    fun aGroupOperandClimbsByTheSameRuleAsAFieldOperand() {
        assertEquals(emptyList(), codes("GroupFilled(../Lines)"))
        assertEquals(emptyList(), codes("GroupFilled(/Order/Lines)"))
        assertEquals(listOf("MVK_INVALID_ENTITY"), codes("GroupFilled(Lines)"))
        assertEquals(listOf("MVK_INVALID_ROOT_GROUP"), codes("GroupFilled(/Nope)"))
    }

    @Test
    fun anUndeclaredConditionEntityIsReportedAtItsRule() {
        val report = check(model("FieldFilled(Nope)"))

        assertEquals(ConsistencyStatus.Invalid, report.status)
        assertEquals(listOf("MVK_INVALID_ENTITY"), report.findings.map { it.code })
        assertEquals("/Order/Lines/Check", report.findings.single().at)
        assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted)
    }

    /**
     * A condition language A12 does not support is a refusal, not a fallback: the Kernel rejects the model
     * outright, so there is no reading of its conditions to report. Answering in some default language
     * would invent verdicts A12 never reaches.
     */
    @Test
    fun anUnsupportedConditionLanguageStopsTheAnalysis() {
        val report = check(model("FieldFilled(Amount)", language = "fr_FR"))

        assertEquals(ConsistencyStatus.Invalid, report.status)
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), report.findings.map { it.code })
        assertEquals(emptySet(), report.checksCompleted)
        assertEquals(true, report.findings.single().explain.contains("fr_FR"))
    }

    @Test
    fun aConditionPathAboveTheRootIsItsOwnRejection() {
        assertEquals(
            listOf("MVK_TOO_MANY_PARENT_GROUPS"),
            codes("FieldFilled(Amount) And FieldFilled(../../Code)"),
        )
    }

    @Test
    fun anAbsolutePathIsResolvedFromTheModelRoot() {
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldFilled(/Order/Code)"))
        assertEquals(listOf("MVK_INVALID_ENTITY"), codes("FieldFilled(/Lines/Amount)"))
    }

    /**
     * A keyword contributes no reference, so a condition of nothing but operators reports nothing. Without
     * the keyword table every one of these names would be looked up and reported undeclared.
     */
    @Test
    fun keywordsAreNotLookedUp() {
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldNotFilled(Amount)"))
    }

    /**
     * The model's declared condition language reaches the binding, not just the tokenizer: `And` is an
     * ordinary field name in a German model, so a German model that declares such a field resolves it — and
     * the same text under `en_US` reads `And` as an operator, which is why only one of these two rows can
     * report a finding.
     */
    @Test
    fun theModelsConditionLanguageDecidesWhatIsAReference() {
        assertEquals(listOf("MVK_INVALID_ENTITY"), codes("FeldAngegeben(Amount) Und FeldAngegeben(And)", "de_DE"))
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldFilled(Amount)"))
    }

    /**
     * Whitespace is on the lexer's HIDDEN channel and insignificant between any two tokens (KF76), so the
     * spaced spellings must bind exactly like the tight ones. The tight rows are the within-model control.
     */
    @Test
    fun spacingInsideAPathChangesNothing() {
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldFilled(.. / Code)"))
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldFilled(/ Order / Code)"))
        assertEquals(
            codes("FieldFilled(Amount) And FieldFilled(../Nope)"),
            codes("FieldFilled(Amount) And FieldFilled(.. / Nope)"),
        )
    }

    /**
     * `..` may carry a turning-point LABEL naming the ancestor it lands on (`relativeUp: relUp? '..'
     * idName?`), which is a label and not a path segment. Read as a segment it becomes an undeclared
     * reference to a path the author never wrote.
     */
    @Test
    fun aTurningPointLabelIsNotAPathSegment() {
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldFilled(..Order/Code)"))
    }

    /**
     * A digit-LEADING name is one identifier (`NameOhnePunktZifferAmAnfang`). Split into a number and a
     * name, the reference silently becomes a different, usually declared field — a false negative.
     */
    @Test
    fun aDigitLeadingNameIsOneReference() {
        assertEquals(listOf("MVK_INVALID_ENTITY"), codes("FieldFilled(1Amount)"))
    }

    /**
     * A wildcard names the repetition, not the element, so a starred path binds to the declared group.
     *
     * `Lines` is declared with `repeatability` — A12's own member, an integer defaulting to 1 — because the
     * star has to be a LEGITIMATE one for this row to be about binding. On a fixed group it is a wildcard
     * defect, and the row would be asserting the absence of a finding that belongs there.
     */
    @Test
    fun aStarredPathBindsToTheDeclaredElement() {
        assertEquals(emptyList(), codes("FieldFilled(Amount) And FieldFilled(../Lines*/Amount)"))
    }

    /** A computation's texts are all read, and the finding names the member so an author knows which. */
    @Test
    fun everyDslBearingMemberOfAComputationIsRead() {
        val source = ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Total","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Computation","id":"c0","name":"Comp","Computation":{
                  "errorMessage":[{"locale":"en_US","text":"x"}],
                  "computedFieldRelPath":"../Total",
                  "commonPrecondition":"FieldFilled(NopeOne)",
                  "computationAlternatives":[
                    {"precondition":"FieldFilled(NopeTwo)","operation":"{ [NopeThree] }"}
                  ]}}
              ]}}
            ]}}}
            """.trimIndent(),
        )
        val prepared = prepare(admit(source) as Admission.Admitted)

        assertEquals(
            listOf(
                "commonPrecondition" to "NopeOne",
                "computationAlternatives[0].precondition" to "NopeTwo",
                "computationAlternatives[0].operation" to "NopeThree",
            ),
            prepared.conditionReferences().map { it.member to it.written },
        )
        val findings = check(source).findings.filter { it.code == "MVK_INVALID_ENTITY" }
        assertEquals(3, findings.size)
        assertEquals(3, findings.map { it.explain }.toSet().size, "each finding must name its own member")
    }

    /**
     * A group that MOUNTS another model carries none of its declarations here, so every reference into it
     * would read as undeclared. A12 accepts such a model once the mount resolves, so reporting those
     * references would reject a valid model — the one direction a checker must never take. The report says
     * the fact base is incomplete instead.
     */
    @Test
    fun anUnexpandedMountSuspendsTheReferenceChecksInsteadOfRejecting() {
        val source = ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Address","Group":{"modelAlias":"address-model"}},
                {"type":"Rule","id":"r0","name":"Check","Rule":{"errorCode":"C",
                  "errorMessage":[{"locale":"en_US","text":"x"}],
                  "errorEntityRelPath":"../Address/Street","errorCondition":"FieldFilled(Address/Street)"}}
              ]}}
            ]}}}
            """.trimIndent(),
        )
        val report = check(source)

        assertEquals(ConsistencyStatus.Inconclusive, report.status)
        assertEquals(emptyList(), report.findings)
        assertEquals(emptySet(), report.checksCompleted)
        assertTrue(report.gaps.any { it.at == "/Order/Address" }, "the mount must be stated as a gap")
    }

    /**
     * A missing computed field stops A12 before any verdict, so the report says the rest is the checker's
     * own reading. Reporting the findings without that gap would present them as A12's.
     */
    @Test
    fun anUnbuildableComputedTargetIsAlsoACoverageGap() {
        val source = ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Computation","id":"c0","name":"Comp","Computation":{
                  "errorMessage":[{"locale":"en_US","text":"x"}],
                  "computedFieldRelPath":"../Nope",
                  "computationAlternatives":[{"operation":"{ 1 }"}]}}
              ]}}
            ]}}}
            """.trimIndent(),
        )
        val report = check(source)

        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), report.findings.map { it.code })
        assertTrue(
            report.gaps.any { it.at == "/Order/Comp" },
            "the build failure must be stated as a gap, not only as a finding: ${report.gaps}",
        )
    }
}
