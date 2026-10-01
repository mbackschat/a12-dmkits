package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact typed bytes and kernel verdicts and explicit silence from MessageGroupDottedBoundaryAgreementTest, replayed on both hosts. */
class MessageGroupDottedBoundaryTest {
    @Test
    fun reservedSuffixesStayUnjudgedWhileQuotedNamesReachLookup() {
        val rows = SyntaxFixtureData.messageGroupDotted.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(44, rows.size)
        assertEquals(rows.size, rows.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        for (row in rows) {
            val expected = row.getValue("portableClasses").jsonArray.map { it.jsonPrimitive.content }
            val actual = checkRead(ModelSource(row.getValue("model").jsonPrimitive.content)).findings
                .filter { it.justifiesRejection }.map { it.code }
            assertEquals(expected, actual, row.getValue("id").jsonPrimitive.content)
        }
    }
}
