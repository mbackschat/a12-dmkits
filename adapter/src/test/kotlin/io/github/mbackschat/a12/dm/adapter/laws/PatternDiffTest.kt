package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.Interpreter
import io.github.mbackschat.a12.dm.interpreter.Kind
import io.github.mbackschat.a12.dm.interpreter.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.parseCondition
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

/**
 * Differential — the `PatternMatched` / `PatternViolated` infix string operators, parsed from condition
 * text and evaluated against the REAL kernel (`RuntimeLaws`). The match is anchored over the WHOLE value;
 * an empty field is NOT evaluated (so neither fires on absence). Same condition text feeds both sides.
 */
class PatternDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(PC to Kind.STRING, CN to Kind.STRING)

    private data class Case(
        val text: String,
        val empties: Set<String> = emptySet(),
        val values: Map<String, String> = emptyMap(),
    )

    private val cases = listOf(
        // PatternViolated: fires when a PRESENT value does not match the whole-value regex.
        Case("[ProductCode] PatternViolated \"[A-Z]{3}\"", values = mapOf(PC to "ABC")),   // matches → no fire
        Case("[ProductCode] PatternViolated \"[A-Z]{3}\"", values = mapOf(PC to "abcd")),  // no match → fires
        Case("[ProductCode] PatternViolated \"[A-Z]{3}\"", values = mapOf(PC to "AB")),    // wrong length → fires
        Case("[ProductCode] PatternViolated \"[A-Z]{3}\"", empties = setOf(PC), values = mapOf(CN to "Acme")), // empty → not evaluated
        // PatternMatched: fires when the whole value matches.
        Case("[ProductCode] PatternMatched \"[A-Z]{3}\"", values = mapOf(PC to "ABC")),    // matches → fires
        Case("[ProductCode] PatternMatched \"[A-Z]{3}\"", values = mapOf(PC to "xy")),     // no match → no fire
        // guarded form from the catalog example (FieldFilled And pattern)
        Case("FieldFilled(ProductCode) And [ProductCode] PatternViolated \"[A-Z]{3}\"",
            empties = setOf(PC), values = mapOf(CN to "Acme")),                            // unfilled → guard false → no fire
    )

    @Test
    fun patternConditionsAgreeWithKernel() {
        val checks = cases.mapIndexed { i, c ->
            val code = "PAT_$i"
            val model = RuntimeLaws.withRule(order, "/Order", "P$i", "../ProductCode", code, c.text)
            val kernel = RuntimeLaws.fires(model, code, setOf("/Order"), c.empties, c.values)
            val interp = Interpreter(SingleRowDoc("/Order", c.values, kinds)).fires(parseCondition(c.text, "/Order"))
            Executable {
                assertEquals(kernel, interp, "\"${c.text}\" over ${c.values}/${c.empties} [k=$kernel i=$interp]")
            }
        }
        assertAll(checks)
    }

    private companion object {
        const val PC = "/Order/ProductCode"
        const val CN = "/Order/CustomerName"
    }
}
