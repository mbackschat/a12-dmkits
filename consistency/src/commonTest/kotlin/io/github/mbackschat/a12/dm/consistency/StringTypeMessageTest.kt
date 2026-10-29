package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the declared string type's pattern/message co-requirement.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.StringTypeMessageAndPatternLawsTest`. Both codes REFUSE models, so a row this
 * checker reports where A12 admits is the one direction it may never take — and the weakened-message rows
 * are where that risk sits, because each leaves the member present while giving A12 nothing.
 */
class StringTypeMessageTest {

    private val patternWithoutMessage = "MVK_ERROR_MESSAGE_AND_PATTERN_INVALID"
    private val messageWithoutPattern = "MVK_ERROR_MESSAGE_AND_NO_PATTERN"
    private val languageMissing = "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING"

    /** A model declaring [locales] whose one string field carries [stringType] as its type body. */
    private fun codes(stringType: String, vararg locales: String = arrayOf("en_US")): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[${locales.joinToString(",") { """{"code":"$it"}""" }}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType",
                  "StringType":{$stringType}}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private val pattern = """"pattern":"[A-Z]{2}[0-9]+""""

    private fun message(locale: String, text: String) =
        """"errorMessage":[{"locale":"$locale","text":"$text"}]"""

    @Test
    fun emptyDeclaredPatternIsInactive() {
        assertEquals(emptyList(), codes("\"pattern\":\"\""))
        assertEquals(listOf(messageWithoutPattern), codes("\"pattern\":\"\"," + message("en_US", "review")))
    }

    @Test
    fun aDeclaredPatternAndItsOwnErrorMessageRequireEachOther() {
        val actual = mutableMapOf<String, List<String>>()
        actual["neither member — the control"] = codes(""""maxLength":40""")
        actual["a pattern and its error message"] =
            codes("""$pattern,${message("en_US", "the reference is malformed")}""")
        actual["a pattern with NO error message"] = codes(pattern)
        actual["an error message with NO pattern"] = codes(message("en_US", "the reference is malformed"))

        // Each row leaves the member PRESENT and gives A12 no text for a declared locale, so an
        // implementation reading presence rather than reach disagrees here and nowhere else.
        actual["a pattern with an EMPTY message list"] = codes("""$pattern,"errorMessage":[]""")
        actual["a pattern with a BLANK message text"] = codes("""$pattern,${message("en_US", "   ")}""")
        actual["a pattern with a message for an UNDECLARED locale"] =
            codes("""$pattern,${message("de_DE", "die Referenz ist fehlerhaft")}""")

        // The same three on the other arm, where A12 admits all of them — the rows that say one predicate
        // serves both directions.
        actual["an EMPTY message list and no pattern"] = codes(""""errorMessage":[]""")
        actual["a BLANK message text and no pattern"] = codes(message("en_US", "   "))
        actual["a message for an UNDECLARED locale and no pattern"] =
            codes(message("de_DE", "die Referenz ist fehlerhaft"))

        // Where A12's READER stops and its verdict starts, which is not where the member's shape suggests.
        actual["a pattern with a message member that is not a list"] =
            codes("""$pattern,"errorMessage":"the reference is malformed"""")
        actual["a pattern with a message entry that is not an object"] =
            codes("""$pattern,"errorMessage":["the reference is malformed"]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["neither member — the control"] = emptyList()
        expected["a pattern and its error message"] = emptyList()
        expected["a pattern with NO error message"] = listOf(patternWithoutMessage)
        expected["an error message with NO pattern"] = listOf(messageWithoutPattern)
        expected["a pattern with an EMPTY message list"] = listOf(patternWithoutMessage)
        expected["a pattern with a BLANK message text"] = listOf(patternWithoutMessage)
        expected["a pattern with a message for an UNDECLARED locale"] = listOf(patternWithoutMessage)
        expected["an EMPTY message list and no pattern"] = emptyList()
        expected["a BLANK message text and no pattern"] = emptyList()
        expected["a message for an UNDECLARED locale and no pattern"] = emptyList()
        expected["a pattern with a message member that is not a list"] = listOf(patternWithoutMessage)
        expected["a pattern with a message entry that is not an object"] = listOf(CK_MODEL_NOT_BUILDABLE)
        assertEquals(expected, actual, "how A12 pairs a declared string pattern with its error message")
    }

    /**
     * The locale tier, which only a model declaring two locales can show.
     *
     * On a one-locale header "every declared locale answered" and "some declared locale answered" are the
     * same claim, so the rows above cannot separate them — and the two disagree on the shape a bilingual
     * model most often carries.
     */
    @Test
    fun aHalfTranslatedPatternMessageIsReportedPerUnansweredLocale() {
        val bilingual = { stringType: String -> codes(stringType, "en_US", "de_DE") }
        val actual = mutableMapOf<String, List<String>>()
        actual["both declared locales answered"] = bilingual(
            """$pattern,"errorMessage":[{"locale":"en_US","text":"malformed"},
               {"locale":"de_DE","text":"fehlerhaft"}]""",
        )
        actual["only the FIRST declared locale answered"] =
            bilingual("""$pattern,${message("en_US", "malformed")}""")
        actual["only the SECOND declared locale answered"] =
            bilingual("""$pattern,${message("de_DE", "fehlerhaft")}""")
        actual["one locale answered, the other BLANK"] = bilingual(
            """$pattern,"errorMessage":[{"locale":"en_US","text":"malformed"},
               {"locale":"de_DE","text":"   "}]""",
        )
        actual["NEITHER declared locale answered, and one undeclared one is"] =
            bilingual("""$pattern,${message("fr_FR", "mal forme")}""")
        actual["a partial message set with NO pattern"] = bilingual(message("en_US", "malformed"))

        val expected = mutableMapOf<String, List<String>>()
        expected["both declared locales answered"] = emptyList()
        expected["only the FIRST declared locale answered"] = listOf(languageMissing)
        expected["only the SECOND declared locale answered"] = listOf(languageMissing)
        expected["one locale answered, the other BLANK"] = listOf(languageMissing)
        expected["NEITHER declared locale answered, and one undeclared one is"] =
            listOf(patternWithoutMessage)
        // Both, because A12 reports both: the locale tier is not a branch of the pairing law.
        expected["a partial message set with NO pattern"] = listOf(messageWithoutPattern, languageMissing)
        assertEquals(expected, actual, "which declared locales a pattern's error message must answer")
    }

    /** The finding says which member is missing and where, since the two codes read almost alike. */
    @Test
    fun eachFindingNamesTheMemberTheDeclarationLacks() {
        val missingMessage = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType",
                      "StringType":{"pattern":"[A-Z]+"}}}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single()
        assertEquals(patternWithoutMessage, missingMessage.code)
        assertEquals("/Catalog/Reference", missingMessage.at)
        assertEquals(true, missingMessage.explain.contains("no error message text"), missingMessage.explain)
    }
}
