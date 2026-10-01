package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Replays MessageGroupArgumentAgreementTest's exact typed, kernel-measured models on JVM and Node. */
class MessageGroupArgumentTest {
    @Test
    fun anUnreadGroupWithATrailingTokenRemainsUnjudged() {
        val row = SyntaxFixtureData.messageGroupArguments.map { Json.parseToJsonElement(it).jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "unread-sibling-relative" }
        val original = row.getValue("model").jsonPrimitive.content
        val model = original.replace("\$#Returns\$", "\$#Returns-x\$")
        assertNotEquals(original, model)
        // SPEC-2026-09-30-01 leaves this competing-fault order unmeasured; silence is the checker contract.
        assertEquals(emptyList(), checkRead(ModelSource(model)).findings.filter { it.justifiesRejection })
    }

    @Test
    fun groupArgumentLayersAndResolutionKeepTheMeasuredClasses() {
        val rows = SyntaxFixtureData.messageGroupArguments.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(77, rows.size)
        assertEquals(rows.size, rows.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        for (row in rows) {
            val expected = row.getValue("portableClasses").jsonArray.map { it.jsonPrimitive.content }
            val actual = checkRead(ModelSource(row.getValue("model").jsonPrimitive.content)).findings
                .filter { it.justifiesRejection }.map { it.code }
            assertEquals(expected, actual, row.getValue("id").jsonPrimitive.content)
        }
    }
}
