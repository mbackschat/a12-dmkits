package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * DSL text that simply ENDS too early — A12's `MVK_INCOMPLETE_INPUT`.
 *
 * Measured across the catalog's `OPERATOR` kind, all eleven symbolic operators and a trailing comma
 * ([KERNEL-FINDINGS KF300], `rulekit.validate.laws.RuleConditionSyntaxLawsTest`, kernel 30.8.1, 2026-09-12).
 *
 * **It stops at a DELIMITER boundary, not at a kind boundary** — the kind account that first justified these
 * rows is retracted, and the grid that refutes it is named on the test below. With an unclosed delimiter
 * earlier in the text A12 answers the same trailing operator with a different code, so this family reports
 * nothing there.
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
                        {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
                          "errorCondition":${'"'}$condition${'"'},"errorEntityRelPath":"../Price"}}
                      ]}}
                     ]}}}
                    """.trimIndent(),
                ),
            ),
        ),
    )

    private fun codesFor(condition: String) = model(condition).tracked().conditionCompletenessFindings().map { it.code }

    @Test
    fun textThatEndsOnAnInfixOperatorIsReportedIncomplete() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a complete condition — the control"] = codesFor("FieldFilled(Price)")
        actual["ends with And"] = codesFor("FieldFilled(Price) And")
        actual["ends with Or"] = codesFor("FieldFilled(Price) Or")
        actual["ends with a comparison"] = codesFor("[Price] >")
        actual["ends with arithmetic"] = codesFor("[Price] > 1 +")
        // Filed as punctuation by the tokenizer because each is also a path shape, and silently missing until
        // the full trailing-token grid measured them (KERNEL-FINDINGS KF300).
        actual["ends with subtraction"] = codesFor("[Price] -")
        actual["ends with division"] = codesFor("[Price] /")
        actual["ends with a comma inside a call"] = codesFor("AllFieldsFilled(Price,")
        actual["trailing whitespace after an operator"] = codesFor("FieldFilled(Price) And   ")
        actual["a comment after the operator"] = codesFor("FieldFilled(Price) And ;; note")

        val expected = mutableMapOf<String, List<String>>()
        expected["a complete condition — the control"] = emptyList()
        expected["ends with And"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with Or"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with a comparison"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with arithmetic"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with subtraction"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends with division"] = listOf("MVK_INCOMPLETE_INPUT")
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
    /**
     * A trailing connective is unfinished in every case the lexer resolves — and only those.
     *
     * A12 answers `… And`, `… AND` and `… and` with the same `MVK_INCOMPLETE_INPUT`, while a fourth case is
     * not a terminal at all: read as an entity name it leaves a COMPLETE condition followed by a stray name,
     * which A12 answers `MVK_UNEXPECTED_TOKEN` instead. Measured, both halves, in
     * `rulekit.validate.laws.ConnectiveCaseLawsTest` — the second half being why this family consults the
     * generated terminal table rather than folding case.
     *
     * **The promotion reaches the connectives and nothing else, which is enumerable rather than hoped.** Of
     * the keyword spellings sharing a case-insensitive form, only `and`/`or` (and `und`/`oder`) have a member
     * the catalog classifies; `in` and `to` are uncataloged in all three cases, so no other family moves.
     */
    @Test
    fun aTrailingConnectiveIsUnfinishedInEveryCaseTheLexerResolves() {
        val actual = mutableMapOf<String, List<String>>()
        actual["ends on And — the canonical control"] = codesFor("FieldFilled(Price) And")
        actual["ends on AND"] = codesFor("FieldFilled(Price) AND")
        actual["ends on and"] = codesFor("FieldFilled(Price) and")
        actual["ends on Or"] = codesFor("FieldFilled(Price) Or")
        actual["ends on OR"] = codesFor("FieldFilled(Price) OR")
        // Not a terminal, so A12 reads it as a name and answers a different code entirely. This family must
        // stay silent rather than claim the text is unfinished.
        actual["ends on aNd — a case the bundle does not carry"] = codesFor("FieldFilled(Price) aNd")

        val expected = mutableMapOf<String, List<String>>()
        expected["ends on And — the canonical control"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends on AND"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends on and"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends on Or"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends on OR"] = listOf("MVK_INCOMPLETE_INPUT")
        expected["ends on aNd — a case the bundle does not carry"] = emptyList()
        assertEquals(expected, actual, "which cases of a trailing connective this family reports")
    }

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
                            {"type":"Field","id":"f0","name":"Preis","Field":{"fieldType":{"type":"StringType"}}},
                            {"type":"Rule","id":"r0","name":"PreisRegel","Rule":{
                              "errorCondition":"FeldAngegeben(Preis) Und","errorEntityRelPath":"../Preis"}}
                          ]}}
                         ]}}}
                        """.trimIndent(),
                    ),
                ),
            ),
        )
        assertEquals(listOf("MVK_INCOMPLETE_INPUT"), german.tracked().conditionCompletenessFindings().map { it.code },
            "`Und` is the German And and must be recognised in a German model")
        assertEquals(emptyList(), codesFor("FieldFilled(Price) Und"),
            "`Und` is an ordinary name in an English model, so it does not end the text on an operator")
    }

    /** The finding names the member, since a computation carries several DSL-bearing ones. */
    @Test
    fun theFindingNamesTheMemberThatEndsEarly() {
        val finding = model("FieldFilled(Price) And").tracked().conditionCompletenessFindings().single()
        assertEquals("/Catalog/PriceRule", finding.at)
        assertTrue(
            finding.explain.contains("errorCondition") && finding.explain.contains("And"),
            "the text must name the member and the operator left dangling: ${finding.explain}",
        )
    }
}
