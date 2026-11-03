package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which authored message entries the message families judge: exactly the ones A12's error-text checker
 * examines, each declared locale's LAST entry and nothing for an undeclared locale, beside which only code generation's
 * parameter lookup reports
 * (`rulekit.validate.laws.ErrorTextLocaleScopeLawsTest.whichEntriesTheErrorTextCheckerExamines`, [KERNEL-FINDINGS
 * KF197]).
 *
 * One defective text per message family, each beside its declared-locale twin that draws the family's class, so
 * a silent undeclared or replaced row is the entry going unexamined and not a text that never fires. A family
 * judging every authored entry rejects a model A12 admits, which is why every family has a row.
 */
class ErrorTextExaminedEntriesTest {

    @Test
    fun onlyTheEntriesA12ExaminesAreJudged() {
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        DEFECTS.forEach { (family, defect) ->
            val (text, code) = defect
            actual["$family — the declared twin"] = rejections(listOf("en_US" to text, "de_DE" to WELL_FORMED))
            expected["$family — the declared twin"] = listOf(code)
            actual["$family, in an undeclared locale"] =
                rejections(listOf("en_US" to WELL_FORMED, "de_DE" to WELL_FORMED, "fr_FR" to text))
            // No message family judges it; code generation fails the build on a parameter no declared text spells
            // (UNDECLARED_LOCALE_MESSAGE_PARAMETERS, KERNEL-FINDINGS KF659), which the character defect has none of.
            expected["$family, in an undeclared locale"] =
                if (family == "MESSAGE_TEXT_CHARACTERS") emptyList() else listOf(CK_MODEL_NOT_BUILDABLE)
            actual["$family, in a declared locale's replaced entry"] =
                rejections(listOf("en_US" to text, "en_US" to WELL_FORMED, "de_DE" to WELL_FORMED))
            expected["$family, in a declared locale's replaced entry"] = emptyList()
        }
        assertEquals(expected, actual, "which entries the message families judge")
    }

    /**
     * The rows cover every family that judges a message's text: the message-member set without the one that
     * judges presence, which reads every entry on purpose. A new text family without a row fails here.
     */
    @Test
    fun everyTextFamilyHasARow() {
        assertEquals(
            CHECK_PRODUCERS.filterIsInstance<CheckProducer.Messages>().flatMap { it.completes }.toSet().map { it.name }.sorted(),
            DEFECTS.map { it.first }.sorted(),
            "one defective text per message family that judges a text",
        )
    }

    /** The rejecting codes for one rule whose messages are [messages], in authored order. */
    private fun rejections(messages: List<Pair<String, String>>): List<String> {
        val entries = messages.joinToString(",") { (locale, text) ->
            """{"locale":"$locale","text":${JsonPrimitive(text)}}"""
        }
        return checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"order","locales":[{"code":"en_US"},{"code":"de_DE"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"Notes","Field":{"fieldType":{"type":"StringType",
                      "StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldNotFilled(Reference) And FieldFilled(Notes)",
                      "errorEntityRelPath":"../Reference","errorCode":"C1","errorMessage":[$entries]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.justifiesRejection }.map { it.code }
    }

    private companion object {
        const val WELL_FORMED = "The reference is required."

        /** One text per message family, with the family's own class it draws in a declared locale. */
        val DEFECTS = listOf(
            "MESSAGE_PARAMETER_REFERENCE" to ("See \$Nonexistent\$" to "INVALID_ENTITY"),
            "MESSAGE_TEXT_CHARACTERS" to ("a\tb" to "INVALID_CHARACTERS"),
            "MESSAGE_PARAMETER_SYNTAX" to ("a \$ b" to "WRONG_NUMBER_OF_DOLLARS"),
            "MESSAGE_BASE_YEAR" to ("since \$BaseYear\$" to "NO_BASE_YEAR"),
            "ERROR_TEXT_CATEGORY_SUFFIX" to ("see \$Reference -> Band\$" to "FIELD_NO_ENUM"),
            "MESSAGE_VALUE_SUFFIX" to ("see \$Notes.value\$" to "INVALID_NO_VALUE_VALIDATION"),
            "ERROR_TEXT_INDEX_TERMINAL" to ("row \$index(Nope).value\$" to "INVALID_INDEX_FIELD"),
            "ERROR_TEXT_SEMANTIC_INDEX" to ("see \$Reference For \"x\"\$" to "MVK_INDEX_FOR_ERROR_TEXT_INVALID"),
            "MESSAGE_GROUP_PARAMETER" to ("row \$#Nope\$ needs an amount." to "INVALID_GROUP"),
        )
    }
}
