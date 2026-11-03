package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of `rulekit.validate.laws.LiteralIndexKeyLawsTest`: an Enumeration index admits its
 * declared tokens as literal semantic-index keys
 * ([KERNEL-FINDINGS KF479]).
 *
 * Every row mirrors one measured against the kernel and names A12's class. Each runs under both
 * condition languages, because the key readers find the index by the language's own keyword, and a reader that
 * knew only `For` would be silent on every German key. The kernel measured the German spelling on four rows.
 */
class LiteralIndexKeyTest {

    /** The endorsement model, its one rule at the root reporting on `Reference` when [condition] holds. */
    private fun codes(condition: String, language: ConditionLanguage): List<String> {
        val (filled, and) = when (language) {
            ConditionLanguage.EN -> "FieldFilled" to "And"
            ConditionLanguage.DE -> "FeldAngegeben" to "Und"
        }
        val translated = when (language) {
            ConditionLanguage.EN -> condition
            ConditionLanguage.DE -> condition.replace("Sum(", "Summe(").replace(" For ", " Fuer ")
                .replace(" And ", " Und ")
        }
        return checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"policy-literal-key","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelConfig":{"conditionLanguage":{"code":"${language.code}"}},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":5,"indexFieldName":"ClaimRef",
                     "elements":[
                      {"type":"Field","id":"f1","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                        "NumberType":{"maxFractionalDigits":2}}}}
                    ]}},
                    {"type":"Group","id":"g2","name":"Endorsements","Group":{"repeatability":5,"indexFieldName":"Status",
                     "elements":[
                      {"type":"Field","id":"f3","name":"Status","Field":{"fieldType":{"type":"EnumerationType",
                        "EnumerationType":{"values":[
                          {"value":"OPEN","label":[{"locale":"en_US","text":"Open endorsement"}]},
                          {"value":"CLOSED","label":[{"locale":"en_US","text":"Closed endorsement"}]}]}}}},
                      {"type":"Field","id":"f4","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                        "NumberType":{"maxFractionalDigits":2}}}}
                    ]}},
                    {"type":"Group","id":"g3","name":"Riders","Group":{"repeatability":5,"elements":[
                      {"type":"Field","id":"f5","name":"RiderRef","Field":{"fieldType":{"type":"StringType"}}}
                    ]}},
                    {"type":"Group","id":"g4","name":"Instalments","Group":{"repeatability":5,"indexFieldName":"Seq",
                     "elements":[
                      {"type":"Field","id":"f6","name":"Seq","Field":{"fieldType":{"type":"NumberType",
                        "NumberType":{"maxFractionalDigits":0}}}},
                      {"type":"Field","id":"f7","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                        "NumberType":{"maxFractionalDigits":2}}}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"$filled(Reference) $and ${translated.replace("\"", "\\\"")}",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.justifiesRejection }.map { it.code }
    }

    private fun sum(keyed: String): String = "Sum($keyed) > 0"

    private fun enumerationKey(constant: String): String = sum("Endorsements/Amount For \"$constant\"")

    @Test
    fun anEnumerationIndexAdmitsExactlyItsTokens() = ConditionLanguage.entries.forEach { language ->
        val actual = linkedMapOf(
            "a declared token" to codes(enumerationKey("OPEN"), language),
            "an undeclared value" to codes(enumerationKey("PENDING"), language),
            "a declared token in another case" to codes(enumerationKey("open"), language),
            "a declared token's label" to codes(enumerationKey("Open endorsement"), language),
            "a declared token ahead of an unaddressed read" to
                codes("${enumerationKey("OPEN")} And $UNADDRESSED", language),
            "an undeclared value ahead of an unaddressed read" to
                codes("${enumerationKey("PENDING")} And $UNADDRESSED", language),
            "an unaddressed read ahead of a declared token" to
                codes("$UNADDRESSED And ${enumerationKey("OPEN")}", language),
        )
        val expected = linkedMapOf(
            "a declared token" to listOf("MVK_PARAMSIZE_INVALIDN"),
            "an undeclared value" to listOf("MVK_INDEX_VALUE_INVALID"),
            "a declared token in another case" to listOf("MVK_INDEX_VALUE_INVALID"),
            "a declared token's label" to listOf("MVK_INDEX_VALUE_INVALID"),
            "a declared token ahead of an unaddressed read" to listOf("MVK_PARAMSIZE_INVALIDN"),
            "an undeclared value ahead of an unaddressed read" to listOf("MVK_INDEX_VALUE_INVALID"),
            "an unaddressed read ahead of a declared token" to listOf("MVK_NO_WILDCARD"),
        )
        assertEquals<Map<String, List<String>>>(expected, actual, "which constant keys an enumeration index admits, $language")
    }

    private companion object {
        const val UNADDRESSED = "[Riders/RiderRef] == \"a\""
    }
}
