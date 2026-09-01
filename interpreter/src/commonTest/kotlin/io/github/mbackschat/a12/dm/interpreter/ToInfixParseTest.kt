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
 * Like `And`/`Und` and `Or`/`Oder`, the fixed token families are case-variant but language-specific:
 * `to`/`To`/`TO` in English and `zu`/`Zu`/`ZU` in German.
 *
 * Reading the infix must NOT make the call evaluable. It only carries the call past this grammar boundary,
 * so `parsePrimary`'s "not a supported condition shape" check is what refuses it. The distinction is
 * load-bearing: a rule the interpreter cannot PARSE scores as calling a kernel-valid rule wrong, while a
 * rule it parses but cannot evaluate is the honest report.
 */
class ToInfixParseTest {

    @Test
    fun everyEnglishToInfixSpellingReachesTheUnsupportedRefusal() {
        for (to in listOf("to", "To", "TO")) {
            assertFailsWith<UnsupportedConstructException>(to) {
                parseCondition(
                    """ValueNotConsistent(Status $to Category, "pending", Region, Channel)""",
                    "/Order",
                    ConditionLanguage.EN,
                )
            }
        }
    }

    @Test
    fun everyGermanToInfixSpellingReachesTheUnsupportedRefusal() {
        for (to in listOf("zu", "Zu", "ZU")) {
            assertFailsWith<UnsupportedConstructException>(to) {
                parseCondition(
                    """WertNichtKonsistent(Status $to Category, "pending", Region, Channel)""",
                    "/Order",
                    ConditionLanguage.DE,
                )
            }
        }
    }

    @Test
    fun toInfixMustMatchTheSelectedConditionLanguage() {
        for ((language, call) in listOf(
            ConditionLanguage.EN to """ValueNotConsistent(Status Zu Category, "pending", Region, Channel)""",
            ConditionLanguage.DE to """WertNichtKonsistent(Status To Category, "pending", Region, Channel)""",
        )) {
            assertFailsWith<IllegalArgumentException>(language.code) {
                parseCondition(call, "/Order", language)
            }
        }
    }
}
