package io.github.mbackschat.a12.dm.interpreter.workspace

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Count the compact JSON representation without materializing it. The walk is iterative because caller metadata may
 * be more deeply nested than the bounded document-model element tree.
 */
internal fun compactJsonUtf8Size(element: JsonElement, stopAfter: Long): Long {
    require(stopAfter >= 0) { "stopAfter must not be negative" }
    val counter = CappedByteCounter(stopAfter)
    val pending = mutableListOf(element)
    while (pending.isNotEmpty() && !counter.exceeded) {
        when (val candidate = pending.removeAt(pending.lastIndex)) {
            is JsonObject -> {
                counter.add(objectPunctuationBytes(candidate.size))
                if (counter.exceeded) {
                    continue
                }
                for ((key, value) in candidate) {
                    counter.addQuoted(key)
                    if (counter.exceeded) {
                        break
                    }
                    pending += value
                }
            }
            is JsonArray -> {
                counter.add(arrayPunctuationBytes(candidate.size))
                if (!counter.exceeded) {
                    candidate.forEach(pending::add)
                }
            }
            is JsonPrimitive -> {
                if (candidate.isString) {
                    counter.addQuoted(candidate.content)
                } else {
                    counter.add(
                        checkNotNull(strictUtf8ByteCount(candidate.content)) {
                            "non-string JSON primitive contains an unpaired UTF-16 surrogate"
                        },
                    )
                }
            }
        }
    }
    return counter.count
}

/** Braces plus one colon per member and one comma between members; keys and values are counted separately. */
private fun objectPunctuationBytes(memberCount: Int): Long =
    2L + memberCount.toLong() + maxOf(0, memberCount - 1).toLong()

/** Brackets plus one comma between elements; element representations are counted separately. */
private fun arrayPunctuationBytes(elementCount: Int): Long =
    2L + maxOf(0, elementCount - 1).toLong()

private class CappedByteCounter(
    private val ceiling: Long,
) {
    var count = 0L
        private set

    val exceeded: Boolean
        get() = count > ceiling

    fun add(amount: Long) {
        if (exceeded) {
            return
        }
        val remaining = ceiling - count
        count = if (amount > remaining) {
            if (ceiling == Long.MAX_VALUE) Long.MAX_VALUE else ceiling + 1L
        } else {
            count + amount
        }
    }

    fun addQuoted(value: String) {
        add(2)
        var index = 0
        while (index < value.length && !exceeded) {
            val codeUnit = value[index].code
            when {
                codeUnit == 0x22 || codeUnit == 0x5c -> add(2)
                codeUnit == 0x08 ||
                    codeUnit == 0x09 ||
                    codeUnit == 0x0a ||
                    codeUnit == 0x0c ||
                    codeUnit == 0x0d -> add(2)
                codeUnit in 0x00..0x1f -> add(6)
                codeUnit <= 0x7f -> add(1)
                codeUnit <= 0x7ff -> add(2)
                codeUnit in 0xd800..0xdbff &&
                    index + 1 < value.length &&
                    value[index + 1].code in 0xdc00..0xdfff -> {
                    add(4)
                    index++
                }
                codeUnit in 0xd800..0xdfff ->
                    throw IllegalArgumentException("prepared JSON contains an unpaired UTF-16 surrogate")
                else -> add(3)
            }
            index++
        }
    }
}
