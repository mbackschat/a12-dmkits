package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for KERNEL-FINDINGS KF568's runtime half: a dotted literal beside a
 * `FirstFilledValue` of dates is a date, typed by the operands the operator returns one of. `2025-06-30` sorts
 * after `01.01.2026` as text and before it as a date, so each case discriminates the two readings. Kernel
 * agreement is `adapter.laws.FirstFilledDateLiteralComparisonDiffTest`.
 */
class FirstFilledDateLiteralTest {

    private fun fires(condition: String, promised: String): Boolean {
        val def = ModelDef(
            fields = listOf(
                FieldDef(REFERENCE, Kind.STRING),
                FieldDef(PROMISED, Kind.DATE, format = "yyyy-MM-dd"),
                FieldDef(REVISED, Kind.DATE, format = "yyyy-MM-dd"),
            ),
            rules = listOf(RuleDef("/Order/R", "/Order", "FieldFilled(Reference) And $condition", "FD", "../Reference")),
        )
        val doc = def.newDocument().field(REFERENCE, "ORD-7781", 1, 1).field(PROMISED, promised, 1, 1)
        return DmInterpreter(def).validate(doc).messages.any { it.code == "FD" }
    }

    @Test fun aFallThroughDateBeforeTheLiteralIsBefore() =
        assertEquals(true to false, fires("FirstFilledValue(RevisedBy, PromisedBy) < \"01.01.2026\"", "2025-06-30") to
            fires("FirstFilledValue(RevisedBy, PromisedBy) > \"01.01.2026\"", "2025-06-30"))

    @Test fun theLiteralsOwnDayIsNeitherBeforeNorAfter() =
        assertEquals(false to false, fires("FirstFilledValue(RevisedBy, PromisedBy) < \"01.01.2026\"", "2026-01-01") to
            fires("FirstFilledValue(RevisedBy, PromisedBy) > \"01.01.2026\"", "2026-01-01"))

    private companion object {
        const val REFERENCE = "/Order/Reference"
        const val PROMISED = "/Order/PromisedBy"
        const val REVISED = "/Order/RevisedBy"
    }
}
