package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * DSL text that simply ENDS too early — A12's `MVK_INCOMPLETE_INPUT`.
 *
 * Measured across word operators, a comparison, arithmetic and a trailing comma
 * ([KERNEL-FINDINGS KF300], `rulekit.validate.laws.RuleConditionSyntaxLawsTest`, kernel 30.8.1, 2026-09-12),
 * so it is one class keyed off the operator catalog's `OPERATOR` kind plus the symbolic operators.
 *
 * **It stops at the catalog's kind boundary and that is measured, not assumed.** A dangling PATH operator
 * (`->`, `For`) is `MVK_UNEXPECTED_TOKEN` instead, so this family must NOT report it — which is the whole
 * reason the last two rows below exist. Grouping the two kinds would emit the wrong code for both.
 */
class ConditionCompletenessTest {

    private fun model(condition: String) = prepare(
        assertIs<Admission.Admitted>(
            admit(
                ModelSource(
                    """
                    {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
                     "content":{"modelRoot":{"rootGroups":[
                      {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                        {"type":"Field","id":"f0","name":"Price","Field":{}},
                        {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                          "errorCondition":${'"'}$condition${'"'},"errorEntityRelPath":"../Price"}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun codesFor(condition: String) = model(condition).conditionCompletenessFindings().map { it.code }

    @Test
    fun textThatEndsOnAnInfixOperatorIsReportedIncomplete() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a complete condition — the control"] = codesFor("FieldFilled(Price)")
        actual["ends with And"] = codesFor("FieldFilled(Price) And")
        actual["ends with Or"] = codesFor("FieldFilled(Price) Or")
        actual["ends with a comparison"] = codesFor("[Price] >")
        actual["ends with arithmetic"] = codesFor("[Price] > 1 +")
        actual["ends with a comma inside a call"] = codesFor("AllFieldsFilled(Price,")
        actual["trailing whitespace after an operator"] = codesFor("FieldFilled(Price) And   ")
        actual["a comment after the operator"] = codesFor("FieldFilled(Price) And ;; note")

        val expected = mutableMapOf<String, List<String>>()
        expected["a complete condition — the control"] = emptyList()
        expected["ends with And"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with Or"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with a comparison"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with arithmetic"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with a comma inside a call"] = listOf("MVK_INCOMPLETE_INPUT")
        // Whitespace and comments are not significant tokens, so the operator is still last.
        expected["trailing whitespace after an operator"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["a comment after the operator"] = listOf("MVK_INCOMPLETE_INPUT")
        assertEquals(expected, actual, "which text this family reports as ending early")
    }

    /**
     * The boundary, reported from the checker side — and it is a DELIMITER boundary, not a kind boundary.
     *
     * These rows were originally justified by the operator catalog's `OPERATOR`/`PATH_OP` split. That account
     * is retracted: `FieldFilled(Price For` — the same keyword with one bracket removed — is
     * `MVK_INCOMPLETE_INPUT` in A12, and `FieldFilled(Price And` is `MVK_UNEXPECTED_TOKEN`, so the kind
     * column predicts both backwards ([KERNEL-FINDINGS KF300],
     * `rulekit.validate.laws.RuleConditionSyntaxLawsTest.whetherTheLastTokenAloneCanDecideTheCode`).
     *
     * The last two rows are the ones that matter: before the narrowing this family reported
     * `MVK_INCOMPLETE_INPUT` for text A12 answers with `MVK_UNEXPECTED_TOKEN`, sending an author to the wrong
     * fix. Silence there is correct; silence on the `For` rows under-reports and is the safe direction.
     */
    @Test
    fun textWithAnUnclosedDelimiterIsNotThisFamilysBusiness() {
        assertEquals(emptyList(), codesFor("FieldFilled([Price ->"), "the category arrow")
        assertEquals(emptyList(), codesFor("FieldFilled([Price For"), "the semantic-index keyword")
        assertEquals(emptyList(), codesFor("FieldFilled(Price For"), "the same keyword, one bracket fewer")
        // A12 says MVK_UNEXPECTED_TOKEN for both of these, and this family used to say MVK_INCOMPLETE_INPUT.
        assertEquals(emptyList(), codesFor("FieldFilled(Price And"), "an operator inside an unclosed call")
        assertEquals(emptyList(), codesFor("FieldFilled([Price) And"), "an operator after an unclosed bracket")
        // Mismatched pairs a DEPTH COUNTER calls balanced — A12 answers both MVK_UNEXPECTED_TOKEN. Both
        // spellings are here because each exercises one closer: drop either and half the kind check is
        // unguarded, since the other row reaches its verdict through the arm that is still intact.
        assertEquals(emptyList(), codesFor("FieldFilled(Price] And"), "a bracket closing a parenthesis")
        assertEquals(emptyList(), codesFor("FieldFilled[Price) And"), "a parenthesis closing a bracket")
    }

    /**
     * A German model's operators are recognised in German, and an English model's are not.
     *
     * The keyword table is per language by construction; this is the row that would fail if the family read
     * the merged set, because `Und` is an ordinary field name in an English model.
     */
    @Test
    fun theOperatorIsRecognisedInTheModelsOwnConditionLanguage() {
        val german = prepare(
            assertIs<Admission.Admitted>(
                admit(
                    ModelSource(
                        """
                        {"header":{"id":"catalog","locales":[{"code":"de_DE"}]},
                         "content":{"modelConfig":{"conditionLanguage":{"code":"de_DE"}},
                          "modelRoot":{"rootGroups":[
                          {"type":"Group","id":"g0","name":"Katalog","Group":{"elements":[
                            {"type":"Field","id":"f0","name":"Preis","Field":{}},
                            {"type":"Rule","id":"r0","name":"PreisRegel","Rule":{
                              "errorCondition":"FeldAngegeben(Preis) Und","errorEntityRelPath":"../Preis"}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        assertEquals(listOf("MVK_INCOMPLETE_INPUT"), german.conditionCompletenessFindings().map { it.code },
            "`Und` is the German And and must be recognised in a German model")
        assertEquals(emptyList(), codesFor("FieldFilled(Price) Und"),
            "`Und` is an ordinary name in an English model, so it does not end the text on an operator")
    }

    /** The finding names the member, since a computation carries several DSL-bearing ones. */
    @Test
    fun theFindingNamesTheMemberThatEndsEarly() {
        val finding = model("FieldFilled(Price) And").conditionCompletenessFindings().single()
        assertEquals("/Catalog/PriceRule", finding.at)
        assertTrue(
            finding.explain.contains("errorCondition") && finding.explain.contains("And"),
            "the text must name the member and the operator left dangling: ${finding.explain}",
        )
    }
}
