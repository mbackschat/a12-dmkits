package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.conditionShapeFailure
import io.github.mbackschat.a12.dm.consistency.syntax.operationShapeFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The condition parser, held to the two bars that matter and in that order.
 *
 * **It must accept everything the Kernel accepts.** The corpus is the catalog's own DSL, executed against the
 * real Kernel in CI, so a rejection here is a condition A12 admits — the one direction this checker may not
 * take. That bar is 100% and unconditional, in both condition languages.
 *
 * **Then it must reject what the Kernel rejects**, on the shapes measured through `checkConsistency`
 * ([KERNEL-FINDINGS KF300], `rulekit.validate.laws.RuleConditionSyntaxLawsTest`). Without this half a parser
 * that accepted every token sequence would pass the first bar perfectly, which is exactly the failure a
 * deliberately loose parser invites.
 */
class ConditionShapeTest {

    @Test
    fun everyTextTheKernelAcceptsParses() {
        val rejected = mutableMapOf<String, String>()
        for (row in ConditionCorpus.conditions()) {
            conditionShapeFailure(row.en, ConditionLanguage.EN)?.let { rejected["EN ${row.id}"] = report(row.en, it) }
            conditionShapeFailure(row.de, ConditionLanguage.DE)?.let { rejected["DE ${row.id}"] = report(row.de, it) }
        }
        for (row in ConditionCorpus.operations()) {
            operationShapeFailure(row.en, ConditionLanguage.EN)?.let { rejected["EN ${row.id}"] = report(row.en, it) }
            operationShapeFailure(row.de, ConditionLanguage.DE)?.let { rejected["DE ${row.id}"] = report(row.de, it) }
        }
        assertEquals(emptyMap(), rejected, "every one of these texts is DSL the Kernel accepts")
    }

    /**
     * The shapes A12 refuses, with the code its parser gives, and a valid control for each.
     *
     * The `ranOut` column is what picks between the two codes: only the shape that reaches the end of the
     * text with a delimiter still open draws `MVK_EXPECTED_TOKEN_NOT_FOUND`. A bare reference reaches the end
     * too, with nothing open, and is `MVK_UNEXPECTED_TOKEN` — which is why the end of the text is not by
     * itself enough to pick the code.
     */
    @Test
    fun theMeasuredRejectShapesAreRejectedWithTheCodeA12Gives() {
        val actual = mutableMapOf<String, String>()
        for ((label, text) in REJECTED) {
            actual[label] = verdict(text)
        }
        for ((label, text) in ACCEPTED) {
            actual[label] = verdict(text)
        }

        val expected = mutableMapOf<String, String>()
        expected["punctuation that is no condition"] = "MVK_UNEXPECTED_TOKEN"
        expected["an unknown name used as a call"] = "MVK_UNEXPECTED_TOKEN"
        expected["a German keyword in an English model"] = "MVK_UNEXPECTED_TOKEN"
        expected["a bare field reference"] = "MVK_UNEXPECTED_TOKEN"
        expected["an extra closing parenthesis"] = "MVK_UNEXPECTED_TOKEN"
        expected["an unclosed value bracket"] = "MVK_UNEXPECTED_TOKEN"
        expected["a bracket closing a parenthesis"] = "MVK_UNEXPECTED_TOKEN"
        expected["a stray closing bracket"] = "MVK_UNEXPECTED_TOKEN"
        expected["an unclosed argument list"] = "MVK_EXPECTED_TOKEN_NOT_FOUND"
        expected["a predicate call — the control"] = ACCEPTS
        expected["a comparison — the control"] = ACCEPTS
        expected["a parenthesised condition — the control"] = ACCEPTS
        expected["a filtered aggregate — the control"] = ACCEPTS
        assertEquals(expected, actual, "the parser's verdict on the measured shapes")
    }

    private fun verdict(text: String): String {
        val failure = conditionShapeFailure(text, ConditionLanguage.EN) ?: return ACCEPTS
        return if (failure.ranOutWithDelimiterOpen) "MVK_EXPECTED_TOKEN_NOT_FOUND" else "MVK_UNEXPECTED_TOKEN"
    }

    private fun report(text: String, failure: io.github.mbackschat.a12.dm.consistency.syntax.ShapeFailure) =
        "at ${failure.at} found '${failure.found}' in: $text"

    private companion object {
        const val ACCEPTS = "ACCEPTED"

        val REJECTED = listOf(
            "punctuation that is no condition" to "@@@",
            "an unknown name used as a call" to "NotAnOperator(Tier)",
            "a German keyword in an English model" to "FeldAngegeben(Tier)",
            "a bare field reference" to "Tier",
            "an extra closing parenthesis" to "FieldFilled(Tier))",
            "an unclosed value bracket" to "FieldFilled([Tier)",
            "a bracket closing a parenthesis" to "FieldFilled(Tier]",
            "a stray closing bracket" to "FieldFilled(Tier)]",
            "an unclosed argument list" to "FieldFilled(Tier",
        )

        val ACCEPTED = listOf(
            "a predicate call — the control" to "FieldFilled(Tier)",
            "a comparison — the control" to "[Tier] > 1",
            "a parenthesised condition — the control" to "(FieldFilled(Tier) And FieldFilled(AutoRenew))",
            "a filtered aggregate — the control" to
                "NumberOfFilledFields(Addons*/Name Having [Addons/Name] == \"x\") > 0",
        )
    }
}
