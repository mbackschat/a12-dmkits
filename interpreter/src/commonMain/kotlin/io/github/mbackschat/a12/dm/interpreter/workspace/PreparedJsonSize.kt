package io.github.mbackschat.a12.dm.interpreter.workspace

import io.github.mbackschat.a12.dm.interpreter.ModelPreparationLimits
import io.github.mbackschat.a12.dm.interpreter.ModelPreparationResource
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

/** The compact-JSON byte size of one string value, stopping at [stopAfter] + 1. */
internal fun quotedJsonUtf8Size(value: String, stopAfter: Long): Long =
    CappedQuotedJsonCounter(stopAfter).apply { add(value) }.count

/** A capped compact-JSON string counter that can measure a derived value without first concatenating it. */
internal class CappedQuotedJsonCounter(
    stopAfter: Long,
) {
    private val counter = CappedByteCounter(stopAfter).apply { add(2) }

    val count: Long get() = counter.count
    val exceeded: Boolean get() = counter.exceeded

    fun add(value: String) {
        add(value, 0, value.length)
    }

    fun add(value: String, start: Int, end: Int) {
        require(start in 0..end && end <= value.length) {
            "string byte-count range is outside the source"
        }
        var index = start
        while (index < end && !counter.exceeded) {
            val codeUnit = value[index].code
            when {
                codeUnit == 0x22 || codeUnit == 0x5c -> counter.add(2)
                codeUnit == 0x08 ||
                    codeUnit == 0x09 ||
                    codeUnit == 0x0a ||
                    codeUnit == 0x0c ||
                    codeUnit == 0x0d -> counter.add(2)
                codeUnit in 0x00..0x1f -> counter.add(6)
                codeUnit <= 0x7f -> counter.add(1)
                codeUnit <= 0x7ff -> counter.add(2)
                codeUnit in 0xd800..0xdbff &&
                    index + 1 < end &&
                    value[index + 1].code in 0xdc00..0xdfff -> {
                    counter.add(4)
                    index++
                }
                codeUnit in 0xd800..0xdfff ->
                    throw IllegalArgumentException("prepared JSON contains an unpaired UTF-16 surrogate")
                else -> counter.add(3)
            }
            index++
        }
    }

    fun add(value: Char) {
        when (val codeUnit = value.code) {
            0x22, 0x5c -> counter.add(2)
            0x08, 0x09, 0x0a, 0x0c, 0x0d -> counter.add(2)
            in 0x00..0x1f -> counter.add(6)
            in 0x20..0x7f -> counter.add(1)
            in 0x80..0x7ff -> counter.add(2)
            in 0xd800..0xdfff ->
                throw IllegalArgumentException("prepared JSON contains an isolated UTF-16 surrogate")
            else -> counter.add(3)
        }
    }
}

/**
 * Exact final-representation budget for a shape-preserving preparation pass. String replacements and additions are
 * measured before concatenation/materialization, so the prepared-byte ceiling bounds allocations rather than merely
 * diagnosing an already-built oversized tree.
 */
internal class PreparedJsonByteBudget(
    root: JsonObject,
    limits: ModelPreparationLimits,
) {
    private val maximum = limits.maxPreparedBytes.toLong()
    private var current = compactJsonUtf8Size(root, stopAfter = maximum)

    init {
        if (current > maximum) {
            fail()
        }
    }

    fun maximumReplacementBytes(oldValue: String): Long =
        maximum - current + quotedJsonUtf8Size(oldValue, stopAfter = Long.MAX_VALUE)

    fun commitStringReplacement(oldValue: String, newQuotedBytes: Long) {
        val oldQuotedBytes = quotedJsonUtf8Size(oldValue, stopAfter = Long.MAX_VALUE)
        if (newQuotedBytes > maximum - current + oldQuotedBytes) {
            fail()
        }
        current = current - oldQuotedBytes + newQuotedBytes
    }

    /**
     * Charge a string member's final representation before joining [parts]. Existing members preserve object shape;
     * a missing member additionally pays its quoted key, colon, and optional comma.
     */
    fun materializeStringMember(
        owner: JsonObject,
        key: String,
        vararg parts: String,
    ): String {
        val previous = owner[key]
        val previousBytes = previous?.let {
            compactJsonUtf8Size(it, stopAfter = Long.MAX_VALUE)
        } ?: 0L
        val memberAdditionBytes = if (previous == null) {
            quotedJsonUtf8Size(key, stopAfter = Long.MAX_VALUE) +
                objectMemberAdditionPunctuationBytes(owner.size)
        } else {
            0L
        }
        val allowance = maximum - current + previousBytes - memberAdditionBytes
        if (allowance < 0) {
            fail()
        }
        val counter = CappedQuotedJsonCounter(allowance)
        parts.forEach(counter::add)
        if (counter.exceeded) {
            fail()
        }
        current = current - previousBytes + memberAdditionBytes + counter.count
        return buildString(parts.sumOf(String::length)) {
            parts.forEach(::append)
        }
    }

    fun verify(root: JsonObject) {
        check(compactJsonUtf8Size(root, stopAfter = maximum) == current) {
            "incremental prepared-byte accounting drifted from the final compact JSON"
        }
    }

    private fun fail(): Nothing =
        throw resourceLimit(
            resource = ModelPreparationResource.PREPARED_BYTES,
            maximum = maximum,
            actual = maximum + 1,
        )
}

/** Braces plus one colon per member and one comma between members; keys and values are counted separately. */
private fun objectPunctuationBytes(memberCount: Int): Long =
    2L + memberCount.toLong() + maxOf(0, memberCount - 1).toLong()

private fun objectMemberAdditionPunctuationBytes(existingMemberCount: Int): Long =
    objectPunctuationBytes(existingMemberCount + 1) - objectPunctuationBytes(existingMemberCount)

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
        if (exceeded) {
            return
        }
        val measured = quotedJsonUtf8Size(value, ceiling - count)
        add(measured)
    }
}
