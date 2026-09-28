package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check for the kernel-legal Java-Pattern dialect. The limited A12K-30 blacklist rejects
 * some constructs, but it is not a Java/JavaScript portability proof. JVM `java.util.regex.Pattern` is the
 * normative engine; the multiplatform `PatternDialectParityTest` separately locks Node to these JVM results.
 */
class PatternDialectDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(PC to Kind.STRING)
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun agree(rx: String, value: String, expected: Boolean, msg: String, matched: Boolean = true) {
        val cond = render(if (matched) m.str(PC).matches(rx) else m.str(PC).violates(rx))
        val model = RuntimeLaws.withRule(order, "/Order", "PD", "../ProductCode", "PDIA", cond)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(PC, value, 1, 1)
        val groovy = RuntimeLaws.fires(model, "PDIA", kDoc)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains("PDIA")
        val interp = Interpreter(SingleRowDoc("/Order", mapOf(PC to value), kinds)).fires(parseCondition(cond, "/Order"))
        assertEquals(expected, groovy, "$msg — discriminator")
        assertEquals(groovy, javaStatic, "$msg — groovy-dynamic vs generated Java [$cond over '$value']")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter [$cond over '$value']")
    }

    // ── the kernel-LEGAL divergent constructs (JS reads them wrong; the translation must fix them) ────────────
    @Test fun horizontalWhitespaceMatches() = agree("\\d+\\h\\d+", "12 34", true, "\\d+\\h\\d+ matches space-separated digits")
    @Test fun horizontalWhitespaceNoMatch() = agree("\\d+\\h\\d+", "1234", false, "\\h requires an actual horizontal-ws char")
    @Test fun negatedHorizontalMatches() = agree("\\H+", "ABC", true, "\\H matches non-horizontal-ws")
    @Test fun negatedHorizontalNoMatch() = agree("\\H+", "A C", false, "a space breaks \\H+")

    @Test
    fun admittedJavaDivergencesHaveTriEngineEvidence() {
        listOf(
            Case("\\s", "\u00a0", false, "default Java \\s is ASCII"),
            Case(".", "\u0085", false, "Java dot excludes NEL"),
            Case("(a|(b))\\2c", "ac", false, "an unmatched backreference fails"),
            Case("[a-z&&c-e]+", "a", false, "class intersection excludes a"),
            Case("\\N{LATIN CAPITAL LETTER A}", "A", true, "named character"),
            Case("\\X", "e\u0301", true, "extended grapheme"),
            Case("\\b{g}A\\b{g}", "A", true, "grapheme boundary"),
            Case("(?i:abc)", "ABC", true, "scoped inline flag"),
            Case("(?i)[abc]+", "CBA", true, "global case-insensitive class"),
            Case("(?i)[a-c]+", "D", false, "global case-insensitive range preserves its boundary"),
            Case("(?i)[a-z&&c-e]+", "CDE", true, "case-insensitive class intersection"),
            Case("(?i:[a-c]+)DEF", "AbCDEF", true, "scoped case-insensitive range"),
            Case("(?i:[a-c]+)DEF", "AbCdef", false, "scoped case-insensitive range restores the enclosing flags"),
        ).forEach { agree(it.pattern, it.value, it.expected, it.description) }
        agree("\\s", "\u00a0", true, "PatternViolated uses the same dialect", matched = false)
    }

    @Test
    fun classAlgebraAndDecodedCaseClosure() {
        listOf(
            Case("[a\\-\\D]", "-", true, "escaped hyphen before complement"),
            Case("[\\-\\S]", "-", true, "escaped hyphen sibling"),
            Case("[\\D-a]", "-", true, "hyphen after set member"),
            Case("[\\S-a]", " ", false, "set member boundary"),
            Case("(?U)[\\S]", "\u00a0", false, "Unicode complement flag"),
            Case("(?U)[\\S]", "x", true, "Unicode complement positive control"),
            Case("[a&&]", "a", true, "omitted right operand"),
            Case("[a&&]", "b", false, "identity retains its boundary"),
            Case("[&&a]", "a", true, "omitted left operand"),
            Case("[a^&&^]", "^", true, "literal intersection result"),
            Case("[a^&&^]", "a", false, "intersection removes other members"),
            Case("[a&&b]", "a", false, "empty intersection"),
            Case("[^a&&b]", "a", true, "complement of empty intersection"),
            Case("[\\&&]", "&", true, "escaped delimiter"),
            Case("[z&&^-z]", "z", true, "literal range endpoint"),
            Case("[\\D^-z]", "2", false, "complement union"),
            Case("(?i)\\x41", "a", true, "decoded case closure"),
            Case("(?i)[Z-a]", "z", true, "mixed interval closure"),
            Case("(?i)[Z-a]", "b", false, "interval closure boundary"),
        ).forEach { agree(it.pattern, it.value, it.expected, it.description) }
    }

    private companion object {
        data class Case(val pattern: String, val value: String, val expected: Boolean, val description: String)
        const val PC = "/Order/ProductCode"
    }
}
