package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.parse.DEFAULT_RESOLVER
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.parse.parseRuleCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A rule condition may open with the scale-gate waiver, the grammar's `preamble? bedingung` (KERNEL-FINDINGS
 * KF75), and the waiver parses to nothing: the condition after it is the whole AST. The spellings below are the
 * ones `rulekit.validate.laws.ScaleWaiverLanguageLawsTest` and the 2026-10-20 `rule check` rows found A12 to
 * admit, padding and a following comment included; each language refuses the other's keyword, and A12 refuses
 * any other name. Runtime agreement is `adapter.laws.RuleConditionPreambleDiffTest`.
 */
class RuleConditionPreambleParseTest {

    @Test
    fun theWaiverIsConsumedAndLeavesTheConditionItPrecedes() {
        val english = parseCondition(CONDITION_EN, BASE)
        listOf(
            "@SuppressWarning($GATE) $CONDITION_EN",
            "@ SuppressWarning ( $GATE ) $CONDITION_EN",
            "@SuppressWarning($GATE) ;;the rate is stored to two places\n$CONDITION_EN",
        ).forEach { text ->
            assertEquals(english, parseRuleCondition(text, BASE, DEFAULT_RESOLVER, ConditionLanguage.EN), text)
        }
        assertEquals(
            parseCondition(CONDITION_DE, BASE, language = ConditionLanguage.DE),
            parseRuleCondition("@UnterdrueckeWarnung($GATE) $CONDITION_DE", BASE, DEFAULT_RESOLVER, ConditionLanguage.DE),
        )
    }

    @Test
    fun theKeywordIsTheModelsLanguageAndTheNameIsTheScaleGate() {
        listOf(
            "@UnterdrueckeWarnung($GATE) $CONDITION_EN" to ConditionLanguage.EN,
            "@SuppressWarning($GATE) $CONDITION_DE" to ConditionLanguage.DE,
            "@SuppressWarning(MVK_NO_WILDCARD) $CONDITION_EN" to ConditionLanguage.EN,
        ).forEach { (text, language) ->
            assertFailsWith<IllegalArgumentException>(text) {
                parseRuleCondition(text, BASE, DEFAULT_RESOLVER, language)
            }
        }
    }

    private companion object {
        const val BASE = "/Policy"
        const val GATE = "MVK_INVALID_COMPARE_DEC_PLACES"
        const val CONDITION_EN = "FieldFilled(Rate) And [Units] == [Rate]"
        const val CONDITION_DE = "FeldAngegeben(Rate) Und [Units] == [Rate]"
    }
}
