package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a control character in a message withholds every parameter class".
 *
 * Mirrors `rulekit.validate.laws.MessageMultipleDefectLawsTest`. Three families read one message text — its
 * characters, its parameters' addresses and their membership — and A12 layers them rather than ordering
 * them: the character gate wins wherever the offending character sits, and once past it EVERY parameter
 * defect is reported, in the order the parameters appear.
 *
 * **That layering is why both halves need rows.** A checker that ran the families independently would report
 * a parameter class A12 withheld; one that copied the condition member's "first defect wins" rule would
 * report one parameter class where A12 gives two.
 */
class MessageMultipleDefectTest {

    private val characters = "INVALID_CHARACTERS"
    private val unread = "INVALID_FIELD"
    private val unresolvable = "INVALID_ENTITY"

    private val tab = 0x09.toChar().toString()

    /** Named in the message but not read by the condition, which reads `Reference` alone. */
    private val unreadParameter = "\$Total\$"
    private val unresolvableParameter = "\$NoSuchField\$"

    @Test
    fun aControlCharacterWithholdsEveryParameterClassWhereverItSits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["plain — the control"] = codes("see the reference")
        actual["a tab alone"] = codes("see${tab}it")
        actual["an unread parameter alone"] = codes("see $unreadParameter")
        actual["an unresolvable parameter alone"] = codes("see $unresolvableParameter")
        actual["a tab BEFORE an unread parameter"] = codes("see$tab $unreadParameter")
        actual["an unread parameter BEFORE a tab"] = codes("see $unreadParameter$tab")
        actual["a tab BEFORE an unresolvable parameter"] = codes("see$tab $unresolvableParameter")
        actual["an unresolvable parameter BEFORE a tab"] = codes("see $unresolvableParameter$tab")

        val expected = mutableMapOf<String, List<String>>()
        expected["plain — the control"] = emptyList()
        expected["a tab alone"] = listOf(characters)
        expected["an unread parameter alone"] = listOf(unread)
        expected["an unresolvable parameter alone"] = listOf(unresolvable)
        // The four rows with teeth, two per parameter class: the character gate is not a race, so the
        // parameter class is gone whether its defect comes first or second.
        expected["a tab BEFORE an unread parameter"] = listOf(characters)
        expected["an unread parameter BEFORE a tab"] = listOf(characters)
        expected["a tab BEFORE an unresolvable parameter"] = listOf(characters)
        expected["an unresolvable parameter BEFORE a tab"] = listOf(characters)
        assertEquals(expected, actual, "a control character withholds every parameter class")
    }

    /**
     * …and past that gate the message reports EVERY parameter defect, in the order they appear.
     *
     * The opposite of the condition member, where an earlier defect withholds a later one. Reporting one
     * class here would under-report, and reporting them in a fixed order would name the wrong parameter
     * first on half the inputs — the message is where an author reads left to right.
     */
    @Test
    fun everyParameterDefectIsReportedInTheOrderTheParametersAppear() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an unread parameter BEFORE an unresolvable one"] =
            codes("see $unreadParameter and $unresolvableParameter")
        actual["an unresolvable parameter BEFORE an unread one"] =
            codes("see $unresolvableParameter and $unreadParameter")

        val expected = mutableMapOf<String, List<String>>()
        expected["an unread parameter BEFORE an unresolvable one"] = listOf(unread, unresolvable)
        expected["an unresolvable parameter BEFORE an unread one"] = listOf(unresolvable, unread)
        assertEquals(expected, actual, "every parameter defect is reported in the order it appears")
    }

    private fun codes(text: String): List<String> =
        checkRead(ModelSource(model(text))).findings.filter { it.at == "/Policy/Probe" }.map { it.code }

    private fun model(text: String): String {
        val escaped = text.map { character ->
            if (character.code < 0x20 || character.code == 0x7F) {
                "\\u" + character.code.toString(16).padStart(4, '0')
            } else {
                character.toString()
            }
        }.joinToString("")
        return """
            {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference)","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$escaped"}]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
