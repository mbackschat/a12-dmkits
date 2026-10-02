package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact model bytes from the four message agreement grids and their discriminators, on JVM and Node. */
class MessageParameterDecoderTest {
    @Test
    fun formAlternativesRefuseKeysBeforeSubjectLookup() {
        val rows = SyntaxFixtureData.messageForms.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(36, rows.size)
        assertEquals(rows.size, rows.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        for (row in rows) {
            val expected = row.getValue("classes").jsonArray.map { it.jsonPrimitive.content }
            val actual = checkRead(ModelSource(row.getValue("model").jsonPrimitive.content)).findings
                .filter { it.justifiesRejection }.map { it.code }
            assertEquals(expected, actual, row.getValue("id").jsonPrimitive.content)
        }
    }

    @Test
    fun messageParametersReplayTheirKernelClassesAndExplicitDeclines() {
        val rows = SyntaxFixtureData.messageParameters.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(215, rows.size)
        assertEquals(rows.size, rows.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        assertEquals(mapOf("group-walk" to 38, "decoder" to 99, "name-position" to 45, "index-language" to 15,
            "keyed-decline" to 4, "key-follower" to 8, "family-order" to 4, "form-position" to 2),
            rows.groupingBy { it.getValue("grid").jsonPrimitive.content }.eachCount())
        assertEquals(11, rows.count { it.getValue("decline").jsonPrimitive.contentOrNull != null })
        for (row in rows) {
            val reason = row.getValue("decline").jsonPrimitive.contentOrNull
            val expected = if (reason == null) row.getValue("classes").jsonArray.map { it.jsonPrimitive.content }
                else emptyList()
            if (reason != null) assertTrue(reason.isNotBlank(), "a decline needs its bounded reason")
            val actual = checkRead(ModelSource(row.getValue("model").jsonPrimitive.content)).findings
                .filter { it.justifiesRejection }.map { it.code }
            assertEquals(expected, actual, row.getValue("id").jsonPrimitive.content)
        }
    }
}
