package io.github.mbackschat.a12.dm.adapter

import com.mgmtp.a12.kernel.core.rt.a12internal.validation.ICustomCondition
import com.mgmtp.a12.kernel.core.rt.a12internal.validation.IData
import com.mgmtp.a12.kernel.core.rt.a12internal.validation.IIdentifier
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Bridge mechanics — [KernelCustomConditions.fromCore] adapts a kernel core [ICustomCondition] onto the
 * interpreter SPI. The full firing/polarity differential lives in `laws.CustomConditionDiffTest` (against the
 * real kernel); this pins the data-shaping the differential exercises only through the error field: an
 * existing core condition reading ARBITRARY fields by [IIdentifier] gets the right values, the round-trip of
 * indices (`List<Int>` ⇄ `long[]`) is faithful, the [IData] is read-only, and `null`/value passthrough work.
 */
class KernelCustomConditionsTest {

    private val name = CustomConditionFieldPointer("/Order/CustomerName", listOf(1, 1))
    private val total = CustomConditionFieldPointer("/Order/Items/Total", listOf(1, 2, 1))

    /** A row view backed by a plain map (the interpreter's resolved-row data, stubbed). */
    private fun data(values: Map<CustomConditionFieldPointer, String>) = object : CustomConditionData {
        override fun getValue(field: CustomConditionFieldPointer): String? = values[field]
        override fun fields(): List<CustomConditionFieldPointer> = values.keys.toList()
    }

    private fun check(cond: ICustomCondition, data: CustomConditionData, errorField: CustomConditionFieldPointer = name) =
        KernelCustomConditions.fromCore(cond).check(data, null, emptySet(), errorField)

    @Test fun readsErrorFieldValue() {
        val cond = ICustomCondition { d, _, _, errorField -> d.getValue(errorField) == "ACME" }
        assertTrue(check(cond, data(mapOf(name to "ACME"))), "reads the error field through the bridged IData")
        assertFalse(check(cond, data(mapOf(name to "Other"))), "value mismatch → false")
    }

    @Test fun readsArbitraryFieldByIdentifier() {
        // A condition that constructs its OWN identifier for a different field (deep indices) and reads it.
        val cond = ICustomCondition { d, _, _, _ ->
            d.getValue(idOf("/Order/Items/Total", longArrayOf(1, 2, 1))) == "99"
        }
        assertTrue(check(cond, data(mapOf(total to "99"))), "resolves an arbitrary field (List<Int> ⇄ long[] round-trip)")
        assertFalse(check(cond, data(mapOf(total to "1"))), "different value → false")
    }

    /** A12's IData answers an absent field with `""`, never null; the kernel side is
     *  `adapter.laws.CustomConditionEmptyCellDiffTest`. */
    @Test fun missingFieldIsEmptyAsInA12() {
        val cond = ICustomCondition { d, _, _, errorField -> d.getValue(errorField) == "" && d.getValueForDisplay(errorField) == "" }
        assertTrue(check(cond, data(emptyMap())), "an absent field reads back \"\" through the bridge")
    }

    @Test fun dataIsReadOnly() {
        val cond = ICustomCondition { d, _, _, errorField ->
            assertThrows(UnsupportedOperationException::class.java) { d.addValue(errorField, "x") }
            true
        }
        assertTrue(check(cond, data(mapOf(name to "ACME"))), "a custom condition must not mutate the data")
    }

    @Test fun iteratesPresentFields() {
        val cond = ICustomCondition { d, _, _, _ ->
            val paths = d.map { it.name }.toSet()
            paths == setOf("/Order/CustomerName", "/Order/Items/Total")
        }
        assertTrue(check(cond, data(mapOf(name to "ACME", total to "99"))), "iterator() surfaces every present field")
    }

    @Test fun relevantAndErrorFieldArrive() {
        // The bridge passes the relevant set + error field through to the core condition.
        val cond = ICustomCondition { _, _, _, errorField ->
            errorField.name == "/Order/CustomerName" && errorField.indices.contentEquals(longArrayOf(1, 1))
        }
        assertTrue(check(cond, data(emptyMap())), "the error field's path + indices reach the core condition")
    }

    private companion object {
        fun idOf(path: String, indices: LongArray): IIdentifier = object : IIdentifier {
            override fun getName() = path
            override fun getIndices() = indices
            override fun getUsb() = IIdentifier.USB_NOT_SPECIFIED
        }
    }
}
