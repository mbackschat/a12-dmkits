package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PreparedJsonSizeTest {

    @Test
    fun countsTheCompactSerializedUtf8FormExactly() {
        val values = listOf(
            JsonNull,
            JsonPrimitive(true),
            JsonPrimitive(12_345),
            JsonPrimitive("plain"),
            JsonPrimitive("\"\\\b\t\n\u000c\r\u0001"),
            JsonPrimitive("Euro € and emoji 😀"),
            JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive(false), JsonNull)),
            JsonObject(
                linkedMapOf(
                    "quoted\"key" to JsonPrimitive("value"),
                    "nested" to JsonArray(listOf(JsonPrimitive("é"), JsonPrimitive(9))),
                ),
            ),
        )

        for (value in values) {
            assertEquals(
                value.toString().encodeToByteArray().size.toLong(),
                compactJsonUtf8Size(value, stopAfter = Long.MAX_VALUE),
                value.toString(),
            )
        }
    }

    @Test
    fun refusesAnUnpairedSurrogateBeforeSerialization() {
        val unpairedSurrogate = buildString {
            append(0xd800.toChar())
        }

        assertFailsWith<IllegalArgumentException> {
            compactJsonUtf8Size(JsonPrimitive(unpairedSurrogate), stopAfter = Long.MAX_VALUE)
        }
        assertFailsWith<IllegalArgumentException> {
            compactJsonUtf8Size(
                JsonObject(mapOf(unpairedSurrogate to JsonPrimitive("key"))),
                stopAfter = Long.MAX_VALUE,
            )
        }
    }

    @Test
    fun stopsAtTheFirstByteBeyondTheConfiguredCeiling() {
        val value = JsonObject(mapOf("payload" to JsonPrimitive("x".repeat(1_000))))

        assertEquals(101L, compactJsonUtf8Size(value, stopAfter = 100L))
    }

    @Test
    fun doesNotEnumerateAnOversizedContainerAfterItsFrameExceedsTheCeiling() {
        val elements = object : AbstractList<JsonElement>() {
            override val size: Int = 1_000

            override fun get(index: Int): JsonElement =
                error("an already-oversized array must not enumerate its children")
        }

        assertEquals(3L, compactJsonUtf8Size(JsonArray(elements), stopAfter = 2L))
    }
}
