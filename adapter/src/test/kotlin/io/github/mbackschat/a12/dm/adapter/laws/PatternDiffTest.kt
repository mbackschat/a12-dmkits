package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
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

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. `m.str(p).violates/matches(rx)` is PatternViolated/PatternMatched.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)
    private val rx = "[A-Z]{3}"

    private data class Case(
        val text: String,
        val empties: Set<String> = emptySet(),
        val values: Map<String, String> = emptyMap(),
    )

    private val cases = listOf(
        // PatternViolated: fires when a PRESENT value does not match the whole-value regex.
        Case(render(m.str(PC).violates(rx)), values = mapOf(PC to "ABC")),   // matches → no fire
        Case(render(m.str(PC).violates(rx)), values = mapOf(PC to "abcd")),  // no match → fires
        Case(render(m.str(PC).violates(rx)), values = mapOf(PC to "AB")),    // wrong length → fires
        Case(render(m.str(PC).violates(rx)), empties = setOf(PC), values = mapOf(CN to "Acme")), // empty → not evaluated
        // PatternMatched: fires when the whole value matches.
        Case(render(m.str(PC).matches(rx)), values = mapOf(PC to "ABC")),    // matches → fires
        Case(render(m.str(PC).matches(rx)), values = mapOf(PC to "xy")),     // no match → no fire
        // guarded form from the catalog example (FieldFilled And pattern)
        Case(render(m.field(PC).filled().and(m.str(PC).violates(rx))),
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

    @Test
    fun patternHasGeneratedJavaTriEvidence() {
        val cond = render(m.str(PC).matches(rx))
        val model = RuntimeLaws.withRule(order, "/Order", "PatternJava", "../ProductCode", "PAT_JAVA", cond)
        val values = mapOf(PC to "ABC")
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(PC, "ABC", 1, 1)
        val groovyDynamic = RuntimeLaws.fires(model, "PAT_JAVA", kDoc)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains("PAT_JAVA")
        val interpreter = Interpreter(SingleRowDoc("/Order", values, kinds))
            .fires(parseCondition(cond, "/Order"))

        assertEquals(true, groovyDynamic, "PatternMatched must exercise the matching branch")
        assertEquals(groovyDynamic, javaStatic, "PatternMatched: groovy-dynamic vs generated Java")
        assertEquals(groovyDynamic, interpreter, "PatternMatched: kernel vs interpreter")
    }

    private companion object {
        const val PC = "/Order/ProductCode"
        const val CN = "/Order/CustomerName"
    }
}
