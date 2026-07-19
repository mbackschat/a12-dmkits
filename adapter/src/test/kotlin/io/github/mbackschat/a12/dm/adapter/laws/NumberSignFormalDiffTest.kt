package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **number-sign formal errors** the interpreter must match exactly (KERNEL-FINDINGS §3).
 * Surfaced by the `InterpreterKernelFuzzTest`: the kernel rejects a **signed zero** (`-0`, `-0.00` →
 * `zahlNullEnthaeltVorzeichen`) and a **leading plus** (`+5`, `+0` → `zahlMitPlus`), two checks the interpreter
 * was missing (`-0` slipped through as valid; `+5` was mis-coded `zahlHatUngueltigeZeichen`). Both are NUMBER
 * sign-formatting errors — fixed together as a class. The probe also pinned the **order**: signed-zero is
 * checked *before* the decimal-separator/scale checks (so `-0.0` on a scale-0 field is signed-zero, not a
 * decimal-separator error). For each value, the interpreter's NUMBER (`zahl…`) formal codes must equal the
 * kernel's over the same field.
 */
class NumberSignFormalDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val model = ModelDef(
        listOf(FieldDef(UP, Kind.NUMBER, scale = 2), FieldDef(BQ, Kind.NUMBER, scale = 0)),
        emptyList(),
    )

    /** The kernel's vs the interpreter's NUMBER formal-error codes for [field] = [value] must match. */
    private fun agree(field: String, value: String) {
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(field, value, 1, 1)
        val kernel = RuntimeLaws.messages(order, kDoc).map { it.code }.filter { it.startsWith("zahl") }.sorted()
        val iDoc = Document(model.kinds).field(field, value, 1, 1)
        val interp = DmInterpreter(model).validateFull(iDoc).map { it.code }.filter { it.startsWith("zahl") }.sorted()
        assertEquals(kernel, interp, "$field = '$value'  (kernel=$kernel interp=$interp)")
    }

    @Test fun signedZeroIsAFormalError() {
        listOf("-0", "-0.0", "-0.00").forEach { agree(UP, it) }
        listOf("-0", "-0.0").forEach { agree(BQ, it) }   // BQ is scale 0: signed-zero wins over decimal-separator
    }

    @Test fun leadingPlusIsAFormalError() {
        listOf("+0", "+5", "+0.5").forEach { agree(UP, it) }
        agree(BQ, "+1")
    }

    @Test fun validSignedAndZeroNumbersAreClean() {
        listOf("-5", "-0.5", "-0.01", "0", "0.00").forEach { agree(UP, it) }
        listOf("-1", "0").forEach { agree(BQ, it) }
    }

    @Test
    fun numberSignFormattingHasGeneratedJavaTriEvidence() {
        val engine = RuntimeLaws.kernelJavaEngine(order)
        listOf(
            Triple(UP, "-0.00", "zahlNullEnthaeltVorzeichen"),
            Triple(BQ, "-0.0", "zahlNullEnthaeltVorzeichen"),
            Triple(UP, "+5", "zahlMitPlus"),
        ).forEach { (field, value, expected) ->
            val kDoc = RuntimeLaws.doc().group("/Order", 1).field(field, value, 1, 1)
            val groovyDynamic = RuntimeLaws.messages(order, kDoc).map { it.code() }
                .filter { it.startsWith("zahl") }.sorted()
            val javaStatic = engine.messages(engine.instance(kDoc)).map { it.code() }
                .filter { it.startsWith("zahl") }.sorted()
            val iDoc = Document(model.kinds).field(field, value, 1, 1)
            val interpreter = DmInterpreter(model).validateFull(iDoc).map { it.code }
                .filter { it.startsWith("zahl") }.sorted()

            assertEquals(listOf(expected), groovyDynamic, "$field = '$value' must exercise number-sign formatting")
            assertEquals(groovyDynamic, javaStatic, "$field = '$value': groovy-dynamic vs generated Java")
            assertEquals(groovyDynamic, interpreter, "$field = '$value': kernel vs interpreter")
        }
    }

    private companion object {
        const val UP = "/Order/UnitPrice"           // scale 2
        const val BQ = "/Order/BackorderQuantity"   // scale 0
    }
}
