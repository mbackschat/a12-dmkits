package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — a `PatternMatched` regex using a Java-`Pattern` **dialect** construct (`\h`
 * horizontal whitespace, `\A`/`\z` input anchors) fires identically in the kernel (groovy-dynamic + generated
 * Java) and the interpreter (IG90). The kernel model-check does not validate pattern syntax, so these are
 * loadable; JVM `java.util.regex.Pattern` is the normative engine, and the interpreter's JS translation is
 * locked JS==JVM separately by the multiplatform `regex.PatternDialectParityTest`. Values carry a SPACE (not a
 * line break) so the STRING field's default newline check never intrudes.
 */
class PatternDialectDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val kinds = mapOf(PC to Kind.STRING)
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun agree(rx: String, value: String, msg: String) {
        val cond = render(m.str(PC).matches(rx))
        val model = RuntimeLaws.withRule(order, "/Order", "PD", "../ProductCode", "PDIA", cond)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(PC, value, 1, 1)
        val groovy = RuntimeLaws.fires(model, "PDIA", kDoc)
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val javaStatic = engine.firedCodesSorted(engine.instance(kDoc)).contains("PDIA")
        val interp = Interpreter(SingleRowDoc("/Order", mapOf(PC to value), kinds)).fires(parseCondition(cond, "/Order"))
        assertEquals(groovy, javaStatic, "$msg — groovy-dynamic vs generated Java [$cond over '$value']")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter [$cond over '$value']")
    }

    /** Assert the kernel model-check REJECTS [rx] as a pattern (`MVK_INVALID_PATTERN`, `isPatternValid`/A12K-30) —
     *  so it never reaches a loadable model and the interpreter needs no translation (parity by construction). */
    private fun rejects(rx: String) {
        val cond = render(m.str(PC).matches(rx))
        val model = RuntimeLaws.withRule(order, "/Order", "PD", "../ProductCode", "PDIA", cond)
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(PC, "x", 1, 1)
        val thrown = runCatching { RuntimeLaws.fires(model, "PDIA", kDoc) }.exceptionOrNull()
        assertEquals(true, thrown?.message?.contains("MVK_INVALID_PATTERN"),
            "kernel must reject '$rx' as JS-incompatible: ${thrown?.message?.take(140)}")
    }

    // ── the kernel-LEGAL divergent constructs (JS reads them wrong; the translation must fix them) ────────────
    @Test fun horizontalWhitespaceMatches() = agree("\\d+\\h\\d+", "12 34", "\\d+\\h\\d+ matches space-separated digits")
    @Test fun horizontalWhitespaceNoMatch() = agree("\\d+\\h\\d+", "1234", "\\h requires an actual horizontal-ws char")
    @Test fun negatedHorizontalMatches() = agree("\\H+", "ABC", "\\H matches non-horizontal-ws")
    @Test fun negatedHorizontalNoMatch() = agree("\\H+", "A C", "a space breaks \\H+")

    // ── the JS-incompatible constructs the kernel FORBIDS (parity by construction — never in a loadable model) ─
    @Test fun kernelRejectsPossessive() = rejects("\\d++")
    @Test fun kernelRejectsAtomicGroup() = rejects("(?>abc)")
    @Test fun kernelRejectsLookbehind() = rejects("(?<=x)abc")
    @Test fun kernelRejectsJavaOnlyAnchor() = rejects("\\Aabc\\z")
    @Test fun kernelRejectsUnicodeProperty() = rejects("\\p{L}+")
    @Test fun kernelRejectsClassIntersection() = rejects("[a-z&&[^bc]]+")

    private companion object {
        const val PC = "/Order/ProductCode"
    }
}
