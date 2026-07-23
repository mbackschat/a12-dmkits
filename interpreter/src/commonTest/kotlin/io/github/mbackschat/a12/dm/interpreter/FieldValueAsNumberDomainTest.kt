package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM+Node lock for SPEC-2026-07-22-13 and SPEC-2026-07-23-07. The adapter tri-differential anchors
 * these cases to dynamic Groovy and generated Java; this suite pins category projection, the UTF-16
 * `char`-unit decimal-digit class (non-ASCII BMP admitted, a supplementary-plane decimal code point
 * rejected because its surrogate units are tested separately), symmetric empty fill, and formal-read
 * poison on both interpreter hosts.
 */
class FieldValueAsNumberDomainTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(
                SOURCE,
                Kind.STRING,
                enumValues = listOf("LEADING", "NEGATIVE", "LIMIT", "UNICODE", "ASTRAL"),
                // "𝟑" is U+1D7D1 MATHEMATICAL BOLD DIGIT THREE — a supplementary-plane decimal
                // code point. A code-point parser would read it as 3; the kernel and interpreter iterate
                // UTF-16 char units, so each surrogate half fails the digit test and the token is rejected.
                enumCategories = mapOf("Numeric" to listOf(".5", "-1.25", "123456789012345", "١٢.٥", "𝟑")),
            ),
            FieldDef(EXPECTED, Kind.NUMBER, scale = 2),
            FieldDef(PROJECTED, Kind.NUMBER, scale = 2),
        ),
        rules = listOf(
            RuleDef("$ROOT/EQRule", ROOT, "FieldValueAsNumber(Source -> Numeric) == [Expected]", "EQ", "../Source"),
            RuleDef("$ROOT/LERule", ROOT, "FieldValueAsNumber(Source -> Numeric) <= 0", "LE", "../Source"),
            RuleDef("$ROOT/GERule", ROOT, "FieldValueAsNumber(Source -> Numeric) >= 0", "GE", "../Source"),
        ),
        computations = listOf(ComputationDef(PROJECTED, ROOT, "FieldValueAsNumber(Source -> Numeric)")),
    )

    private fun signals(source: String?, expected: String): List<String> {
        val doc = model.newDocument().group(ROOT, 1).field(EXPECTED, expected, 1, 1)
        if (source != null) doc.field(SOURCE, source, 1, 1)
        return DmInterpreter(model).validateFull(doc).filter { it.code in CODES }.map {
            "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}"
        }.sorted()
    }

    @Test
    fun selectedCategoryTokensUseTheCompleteDecimalGrammar() {
        assertEquals(listOf("EQ|VALUE_ERROR", "GE|VALUE_ERROR"), signals("LEADING", "0.50"))
        assertEquals(listOf("EQ|VALUE_ERROR", "LE|VALUE_ERROR"), signals("NEGATIVE", "-1.25"))
        assertEquals(listOf("EQ|VALUE_ERROR", "GE|VALUE_ERROR"), signals("LIMIT", "123456789012345"))
        assertEquals(listOf("EQ|VALUE_ERROR", "GE|VALUE_ERROR"), signals("UNICODE", "12.50"))
    }

    @Test
    fun missingSourceIsSymmetricZeroAndInvalidSourceIsUnavailable() {
        assertEquals(
            listOf("EQ|OMISSION_ERROR", "GE|OMISSION_ERROR", "LE|OMISSION_ERROR"),
            signals(null, "0"),
        )
        assertEquals(emptyList(), signals("BOGUS", "0"))
        // SPEC-2026-07-23-07 — a supplementary-plane decimal code point is non-convertible (its two
        // surrogate char units each fail the digit test), so the read is unavailable — no rule fires.
        assertEquals(emptyList(), signals("ASTRAL", "3"))
    }

    @Test
    fun computationConvertsUnicodeAndPoisonsAFormalRead() {
        fun outcome(source: String): String {
            val doc = model.newDocument().group(ROOT, 1).field(SOURCE, source, 1, 1)
                .field(PROJECTED, "9.00", 1, 1)
            return DmInterpreter(model).compute(doc).single { it.path == PROJECTED }.let {
                when (it.outcome) {
                    ComputeOutcome.VALUE -> "VALUE|${it.value}"
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED"
                }
            }
        }
        assertEquals("VALUE|12.5", outcome("UNICODE"))
        assertEquals("CLEARED", outcome("BOGUS"))
        // A supplementary-plane decimal code point does not convert, so the computation clears its target.
        assertEquals("CLEARED", outcome("ASTRAL"))
    }

    private companion object {
        const val ROOT = "/Tariff"
        const val SOURCE = "/Tariff/Source"
        const val EXPECTED = "/Tariff/Expected"
        const val PROJECTED = "/Tariff/Projected"
        val CODES = setOf("EQ", "LE", "GE")
    }
}
