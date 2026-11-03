package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import org.junit.jupiter.api.Test

/**
 * What kernel 31.1.1 makes of an enumeration category token that is not a plain, non-blank string, against the portable
 * checker (KERNEL-FINDINGS KF660).
 *
 * A `null` or blank token (Java's `isBlank`, the em space included) is no token: inside the value list its value draws
 * `MVK_MISSING_CATEGORY_VALUE`, and past it the token draws nothing, where a non-blank surplus token draws
 * `MVK_MISSING_INTERNAL_VALUE`. A number or a boolean binds as its text. An object or an array leaves the model
 * unbuilt, because the reader binds each token as a string. The named control is a model A12 admits, so the classes
 * cannot all be the build failure.
 *
 * Own domain (an insurance cover's tier bands).
 */
class CheckerCategoryTokenAgreementTest {

    @Test
    fun theCheckerReadsACategoryTokenAsA12Does() {
        val rows = linkedMapOf(
            "two plain tokens — the control" to """["HIGH","LOW"]""",
            "two empty" to """["",""]""",
            "one empty" to """["HIGH",""]""",
            "one blank" to """[" ","LOW"]""",
            "one em space" to """["\u2003","LOW"]""",
            "one null" to """[null,"LOW"]""",
            "a number and a boolean" to """[1,true]""",
            "a blank token, one token short" to """[" "]""",
            "a blank token past the values" to """["HIGH","LOW"," "]""",
            "a plain token past the values — the surplus control" to """["HIGH","LOW","MID"]""",
            "object entries" to """[{"value":"HIGH"},{"value":"LOW"}]""",
            "one object entry" to """[{"value":"HIGH"},"LOW"]""",
            "an array token" to """[["HIGH"],"LOW"]""",
        )
        val models = rows.mapValues { (_, tokens) ->
            val tree = MAPPER.readTree(BASE)
            (tree.at("$TIER/categories/0") as ObjectNode).set<JsonNode>("values", MAPPER.readTree(tokens))
            MAPPER.writeValueAsString(tree)
        }
        val missing = setOf("MVK_MISSING_CATEGORY_VALUE")
        val unbuilt = setOf("CK_MODEL_NOT_BUILDABLE")
        val expected = linkedMapOf(
            "two plain tokens — the control" to emptySet(), "two empty" to missing, "one empty" to missing,
            "one blank" to missing, "one em space" to missing, "one null" to missing,
            "a number and a boolean" to emptySet(), "a blank token, one token short" to missing,
            "a blank token past the values" to emptySet(),
            "a plain token past the values — the surplus control" to setOf("MVK_MISSING_INTERNAL_VALUE"),
            "object entries" to unbuilt, "one object entry" to unbuilt, "an array token" to unbuilt,
        )
        assertEquals(expected, models.mapValues { (_, json) -> kernelModelClasses(json) }, "A12's classes")
        assertEquals(expected, models.mapValues { (_, json) -> rejectingCodes(json).toSet() }, "the checker's classes")
        // The multiplicity and the named value, where a blank token and a shortfall meet: one finding per value.
        for (row in listOf("two empty", "a blank token, one token short", "one null")) {
            val json = models.getValue(row)
            val a12 = RuntimeLaws.consistencyMessages(json).map { Regex("internal value '([^']*)'").find(it)!!.groupValues[1] }
            val ours = check(ModelSource(json)).findings.filter { it.code == "MVK_MISSING_CATEGORY_VALUE" }
                .map { Regex("the value `([^`]*)`").find(it.explain)!!.groupValues[1] }
            assertEquals(a12, ours, "$row: the values named, in A12's order")
        }
    }

    private companion object {
        const val TIER = "/content/modelRoot/rootGroups/0/Group/elements/1/Field/fieldType/EnumerationType"
        val MAPPER = ObjectMapper()
        val BASE = """
            {"header":{"id":"cover-bands","modelType":"document","modelVersion":"29.4.0","locales":[{"code":"en_US"}],
              "labels":[{"locale":"en_US","text":"Cover"}],"annotations":[],"modelReferences":[]},
             "content":{"modelInfo":{"name":"cover-bands","variant":"base","revision":"draft","immutable":false},
              "modelConfig":{"decimalSeparator":".","timeZone":"Europe/Berlin","conditionLanguage":{"code":"en_US"}},
              "modelRoot":{"rootGroups":[{"type":"Group","id":"/Cover","name":"Cover","Group":{"elements":[
               {"type":"Field","id":"/Cover/Reference","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
               {"type":"Field","id":"/Cover/Tier","name":"Tier","Field":{"fieldType":{"type":"EnumerationType","EnumerationType":
                 {"values":[{"value":"GOLD"},{"value":"SILVER"}],"categories":[{"name":"Band","values":["HIGH","LOW"]}]}}}}
              ]}}]}}}
        """.trimIndent()
    }
}
