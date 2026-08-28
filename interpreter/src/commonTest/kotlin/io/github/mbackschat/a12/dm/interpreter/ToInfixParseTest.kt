package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.ConditionLanguage
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `To` (DE `Zu`) is a reserved kernel grammar literal, and `readArgs` reads it as an infix arm the way it
 * reads `In`. `ValueNotConsistent(field To value, dependent1, …)` is a real kernel construct
 * (`EntityListAndValueListConditionName.ValueNotConsistent`) dmkits deliberately does not model — see
 * `KernelConstructParityTest`/`KeywordReverseParityTest`.
 *
 * The keyword table entry is what registers `To` as a canonical keyword at all: `ENGLISH_KEYWORDS` is
 * derived from `GERMAN_TO_CANONICAL`'s values, so the one entry serves both languages and `matchKeyword`
 * matches neither spelling without it.
 *
 * Reading the infix must NOT make the call evaluable. It only carries the call past this grammar boundary,
 * so `parsePrimary`'s "not a supported condition shape" check is what refuses it. The distinction is
 * load-bearing: a rule the interpreter cannot PARSE scores as calling a kernel-valid rule wrong, while a
 * rule it parses but cannot evaluate is the honest report.
 */
class ToInfixParseTest {

    @Test
    fun anEnglishToInfixReachesTheUnsupportedRefusal() {
        assertFailsWith<UnsupportedConstructException> {
            parseCondition(
                """ValueNotConsistent(Status To Category, "pending", Region, Channel)""",
                "/Order",
                ConditionLanguage.EN,
            )
        }
    }

    /** The authored DE spelling, which canonicalizes to the same literal before `readArgs` sees it. */
    @Test
    fun theGermanSpellingReadsTheSameWay() {
        assertFailsWith<UnsupportedConstructException> {
            parseCondition(
                """WertNichtKonsistent(Status Zu Category, "pending", Region, Channel)""",
                "/Order",
                ConditionLanguage.DE,
            )
        }
    }
}
