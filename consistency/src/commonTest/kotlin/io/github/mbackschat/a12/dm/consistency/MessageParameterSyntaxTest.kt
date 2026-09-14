package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the dollar count an error message has to balance.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.MessageParameterMembershipLawsTest` and `…MessageMultipleDefectLawsTest`. The
 * silent rows carry the risk in both directions: `$$` outside a parameter is A12's escape and reporting it
 * would be an over-rejection, while a control character anywhere pre-empts this class entirely.
 */
class MessageParameterSyntaxTest {

    private val dollars = "WRONG_NUMBER_OF_DOLLARS"

    @Test
    fun whichTextsOpenAParameterTheyNeverClose() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no dollar at all — the control"] = reporting("see it")
        actual["a balanced parameter — the control"] = reporting("see \$Premium\$")
        actual["a lone dollar"] = reporting("cost 5\$")
        actual["an unterminated parameter around a real field"] = reporting("see \$Premium")
        actual["a COMPLETE parameter before an unterminated one"] = reporting("see \$Premium\$ and \$Adjuster")
        actual["an escaped dollar, which is no parameter"] = reporting("cost 5\$\$ today")
        actual["an escaped dollar running into a name"] = reporting("\$\$Premium\$ x")
        actual["two parameters sharing a dollar pair"] = reporting("see \$Premium\$\$Adjuster\$")
        actual["an unterminated parameter behind a TAB"] = reporting("see\t \$Adjuster")

        val expected = mutableMapOf<String, List<String>>()
        expected["no dollar at all — the control"] = emptyList()
        expected["a balanced parameter — the control"] = emptyList()
        expected["a lone dollar"] = listOf(dollars)
        expected["an unterminated parameter around a real field"] = listOf(dollars)
        // A12 refuses the text WHOLE and says nothing about `Premium`, which is the row that makes this a
        // family rather than a branch: the membership class an author would expect here never comes.
        expected["a COMPLETE parameter before an unterminated one"] = listOf(dollars)
        // `$$` OUTSIDE a parameter is the escape, so this text is well-formed and reporting would be an
        // over-rejection — the one direction this project forbids.
        expected["an escaped dollar, which is no parameter"] = emptyList()
        // …and the same pair running into a name leaves a `$` that opens a parameter never closed, so the
        // escape reading does not rescue it. The two rows only differ in what follows the pair.
        expected["an escaped dollar running into a name"] = listOf(dollars)
        // Inside a parameter the first `$` closes it, so a shared pair is two balanced parameters, not an
        // escape — the row that decides the scan's one ambiguous position.
        expected["two parameters sharing a dollar pair"] = emptyList()
        // The CHARACTER gate pre-empts this class wherever the character sits, measured on both its arms.
        expected["an unterminated parameter behind a TAB"] = emptyList()
        assertEquals(expected, actual, "which texts open a parameter they never close")
    }

    /**
     * The TAB row above is silent because the gate pre-empts, not because the model failed to load.
     *
     * A raw tab inside a JSON string is the kind of fixture that can be refused before any family runs, and
     * a row asserting SILENCE cannot tell that apart from the law it means to lock. So this asserts the
     * character class is actually reported on that same text — the positive control the silent row needs.
     */
    @Test
    fun theTabRowIsSilencedByTheGateAndNotByAnUnreadableFixture() {
        val codes = check(ModelSource(model("see\t \$Adjuster"))).findings.map { it.code }
        assertEquals(listOf("INVALID_CHARACTERS"), codes.filter { it.startsWith("INVALID_CHAR") })
        assertEquals(false, codes.contains(dollars), codes.toString())
    }

    /** The finding names the repair, since the escape is what an author who wanted a dollar sign needed. */
    @Test
    fun theFindingNamesTheEscape() {
        val finding = check(ModelSource(model("see \$Premium"))).findings.single { it.code == dollars }
        assertEquals("/Policy/Probe", finding.at)
        assertEquals(true, finding.explain.contains("\$\$"), finding.explain)
    }

    private fun reporting(text: String): List<String> =
        check(ModelSource(model(text))).findings.filter { it.code == dollars }.map { it.code }

    /** An insurance desk whose condition reads the one field the rows name. */
    private fun model(text: String): String = """
        {"header":{"id":"policy-dollar-count","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f1","name":"Adjuster","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(Premium)","errorEntityRelPath":"../Premium",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$text"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
