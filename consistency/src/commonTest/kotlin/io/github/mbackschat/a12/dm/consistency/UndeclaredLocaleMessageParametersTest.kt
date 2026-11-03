package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of KF659: an undeclared locale's message must spell each parameter as a declared text does,
 * or A12's code generation cannot build the model.
 *
 * Every row mirrors one measured against the real Kernel by
 * `rulekit.validate.laws.ErrorTextLocaleScopeLawsTest.anUndeclaredLocalesParametersMustBeSpelledAsADeclaredTextSpellsThem`
 * or `…whichEntriesTheErrorTextCheckerExamines`. The defective-declared-text row is the liveness control: it draws a
 * class this family cannot produce, so a fixture the checker could not read fails there instead of greening the
 * rows that expect nothing.
 */
class UndeclaredLocaleMessageParametersTest {

    @Test
    fun anUndeclaredLocalesParametersMustBeSpelledAsADeclaredTextSpellsThem() {
        val actual = linkedMapOf(
            "a valid field no declared text names" to onTheRule(PLAIN, "Voir ${p("Reference")}"),
            "the same spelling in the declared texts" to onTheRule("See ${p("Reference")}", "Voir ${p("Reference")}"),
            "the same field spelled another way" to onTheRule("See ${p("Reference")}", "Voir ${p("/Order/Reference")}"),
            "an empty pair" to onTheRule(PLAIN, "Voir $D$D ici"),
            "an odd dollar" to onTheRule(PLAIN, "Voir $D ici"),
            "the base year, in a model without one" to onTheRule(PLAIN, "depuis ${p("BaseYear")}"),
            "a control character" to onTheRule(PLAIN, "a\tb"),
            "a control character beside a name no declared text names" to onTheRule(PLAIN, "Voir\t${p("Nope")}"),
            "a defective declared text beside it — the liveness control" to
                onTheRule("See ${p("Nonexistent")}", "Voir ${p("Nonexistent")}"),
            "a computation's message, a name no declared text names" to onTheComputation(PLAIN, "Voir ${p("Reference")}"),
            "a computation's message, the same spelling" to
                onTheComputation("See ${p("Reference")}", "Voir ${p("Reference")}"),
        )
        val expected = linkedMapOf(
            "a valid field no declared text names" to UNBUILT,
            "the same spelling in the declared texts" to none,
            "the same field spelled another way" to UNBUILT,
            "an empty pair" to none,
            "an odd dollar" to UNBUILT,
            "the base year, in a model without one" to UNBUILT,
            "a control character" to none,
            "a control character beside a name no declared text names" to UNBUILT,
            // Code generation never runs beside an error, so the declared texts' own class is all A12 reports.
            "a defective declared text beside it — the liveness control" to listOf("INVALID_ENTITY", "INVALID_ENTITY"),
            "a computation's message, a name no declared text names" to UNBUILT,
            "a computation's message, the same spelling" to none,
        )
        assertEquals(expected, actual, "which undeclared-locale parameters A12's code generation can build")
    }

    private fun onTheRule(declared: String, undeclared: String): List<String> = codes("ProbeRule", element(
        "Rule", "ProbeRule", buildJsonObject {
            put("errorEntityRelPath", "../Reference")
            put("errorCode", "PROBE")
            put("errorCondition", "FieldNotFilled(Reference)")
            put("severity", "ERROR")
            put("errorMessage", messages(declared, undeclared))
        },
    ))

    private fun onTheComputation(declared: String, undeclared: String): List<String> = codes("AmountCalc", element(
        "Computation", "AmountCalc", buildJsonObject {
            put("computedFieldRelPath", "../Amount")
            putJsonArray("computationAlternatives") {
                addJsonObject {
                    put("precondition", "FieldFilled(Reference)")
                    put("operation", "1")
                }
            }
            put("errorMessage", messages(declared, undeclared))
        },
    ))

    /** The two declared locales' texts, then one `fr_FR` entry the model does not declare. */
    private fun messages(declared: String, undeclared: String): JsonArray = buildJsonArray {
        for ((locale, text) in listOf("en_US" to declared, "de_DE" to declared, "fr_FR" to undeclared)) {
            addJsonObject {
                put("locale", locale)
                put("text", text)
            }
        }
    }

    private fun element(type: String, name: String, body: JsonObject): JsonObject = buildJsonObject {
        put("type", type)
        put("id", "/Order/$name")
        put("name", name)
        put(type, body)
    }

    private fun codes(name: String, probe: JsonObject): List<String> =
        checkRead(ModelSource(model(probe))).findings.filter { it.at == "/Order/$name" && it.justifiesRejection }
            .map { it.code }

    private fun model(probe: JsonObject): String = buildJsonObject {
        putJsonObject("header") {
            put("id", "order-messages")
            put("modelVersion", "29.4.0")
            putJsonArray("locales") {
                addJsonObject { put("code", "en_US") }
                addJsonObject { put("code", "de_DE") }
            }
        }
        putJsonObject("content") {
            putJsonObject("modelInfo") { put("name", "order-messages") }
            putJsonObject("modelRoot") {
                putJsonArray("rootGroups") {
                    addJsonObject {
                        put("type", "Group")
                        put("id", "/Order")
                        put("name", "Order")
                        putJsonObject("Group") {
                            putJsonArray("elements") {
                                field("Reference", "StringType")
                                field("Amount", "NumberType")
                                add(probe)
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    private fun JsonArrayBuilder.field(name: String, type: String) = addJsonObject {
        put("type", "Field")
        put("id", "/Order/$name")
        put("name", name)
        putJsonObject("Field") { putJsonObject("fieldType") { put("type", type) } }
    }

    private fun p(name: String): String = "$D$name$D"

    private companion object {
        const val D = '$'
        const val PLAIN = "The reference is required."
        val UNBUILT = listOf(CK_MODEL_NOT_BUILDABLE)
        val none = emptyList<String>()
    }
}
