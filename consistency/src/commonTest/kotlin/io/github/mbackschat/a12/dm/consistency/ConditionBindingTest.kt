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
        {"header":{"id":"order"},"content":{
          "modelConfig":{"conditionLanguage":{"code":"$language"},"fieldRefByShortNameAllowed":false},
          "modelRoot":{"rootGroups":[
            {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
              {"type":"Field","id":"f0","name":"Code","Field":{}},
              {"type":"Group","id":"g1","name":"Lines","Group":{"elements":[
                {"type":"Field","id":"f1","name":"Amount","Field":{}},
                {"type":"Rule","id":"r0","name":"Check","Rule":{"errorCode":"C",
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

    @Test
    fun anUndeclaredConditionEntityIsReportedAtItsRule() {
        val report = check(model("FieldFilled(Nope)"))

        assertEquals(ConsistencyStatus.Invalid, report.status)
        assertEquals(listOf("MVK_INVALID_ENTITY"), report.findings.map { it.code })
        assertEquals("/Order/Lines/Check", report.findings.single().at)
        assertTrue(CheckFamily.CONDITION_REFERENCES in report.checksCompleted)
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

    /** A computation's texts are all read, and the finding names the member so an author knows which. */
    @Test
    fun everyDslBearingMemberOfAComputationIsRead() {
        val source = ModelSource(
            """
            {"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Total","Field":{}},
                {"type":"Computation","id":"c0","name":"Comp","Computation":{
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
        assertEquals(3, check(source).findings.count { it.code == "MVK_INVALID_ENTITY" })
    }

    /**
     * A missing computed field stops A12 before any verdict, so the report says the rest is the checker's
     * own reading. Reporting the findings without that gap would present them as A12's.
     */
    @Test
    fun anUnbuildableComputedTargetIsAlsoACoverageGap() {
        val source = ModelSource(
            """
            {"header":{"id":"order"},"content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Computation","id":"c0","name":"Comp","Computation":{
                  "computedFieldRelPath":"../Nope",
                  "computationAlternatives":[{"operation":"{ 1 }"}]}}
              ]}}
            ]}}}
            """.trimIndent(),
        )
        val report = check(source)

        assertEquals(listOf(CK_COMPUTED_FIELD_NOT_FOUND), report.findings.map { it.code })
        assertTrue(
            report.gaps.any { it.at == "/Order/Comp" },
            "the build failure must be stated as a gap, not only as a finding: ${report.gaps}",
        )
    }
}
