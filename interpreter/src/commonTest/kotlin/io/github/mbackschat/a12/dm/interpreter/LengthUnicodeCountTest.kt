package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §7 (IG63) — `Length` counts **UTF-16 code units** (Kotlin `String.length`, UTF-16 on both JVM + JS), so a
 * decomposed combining sequence (`e` + `U+0301`) counts as **2**, not 1 grapheme. The JVM-vs-kernel proof is
 * `adapter.laws.AbsLengthDiffTest`; this pins the same counting on the JS target. (Supplementary-plane / astral
 * characters are a recorded residual — KERNEL-FINDINGS §7 — unreachable under any realistic charset, which is BMP.)
 */
class LengthUnicodeCountTest {

    private val S = "/G/S"

    private fun lengthEquals(value: String, n: Int): Boolean {
        val cond = "FieldFilled(S) And Length(S) == $n"
        val def = ModelDef(listOf(FieldDef(S, Kind.STRING)), listOf(RuleDef("/G/R", "/G", cond, "R", "../S")))
        val doc = def.newDocument().group("/G", 1).field(S, value, 1, 1)
        return DmInterpreter(def).validate(doc).messages.any { it.code == "R" }
    }

    @Test
    fun combiningSequenceCountsAsTwoNotOne() {
        assertEquals(true, lengthEquals("e\u0301", 2), "decomposed e + U+0301 is 2 UTF-16 units")
        assertEquals(false, lengthEquals("e\u0301", 1), "not 1 (a grapheme count)")
    }
}
