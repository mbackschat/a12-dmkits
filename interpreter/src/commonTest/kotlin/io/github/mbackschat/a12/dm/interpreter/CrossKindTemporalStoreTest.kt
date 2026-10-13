package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + Node peer of `adapter.laws.FirstFilledDateCrossKindTargetDiffTest` (IF397, KERNEL-FINDINGS KF222): a
 * computed or constant value is stored in the format its target DECLARES, whatever kind declares it. A TIME field
 * declared `dd.MM.yyyy` stores a selected date dotted, a TIME field declared ISO turns a dotted constant into ISO,
 * and a downstream computation reads the dotted cell back as a date through the cascade's overlay.
 */
class CrossKindTemporalStoreTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(REPORTED, Kind.DATE, format = ISO),
            FieldDef(TIME_DOT, Kind.TIME, format = DOTTED),
            FieldDef(DATE_DOT, Kind.DATE, format = DOTTED),
            FieldDef(CONST_ISO, Kind.TIME, format = ISO),
            FieldDef(CHAINED, Kind.DATE, format = ISO),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(TIME_DOT, ROOT, "FirstFilledValue(Reported, DateDot)"),
            ComputationDef(CONST_ISO, ROOT, "\"05.03.2024\""),
            ComputationDef(CHAINED, ROOT, "FirstFilledValue(TimeDot, DateDot)"),
        ),
    )

    @Test
    fun theDeclaredFormatRendersTheStoreOnEitherKind() {
        val document = model.newDocument().group(ROOT, 1).field(REPORTED, "2024-03-05", 1, 1)
        val results = DmInterpreter(model).compute(document).associate { it.path to "${it.outcome}|${it.value}" }
        assertEquals("VALUE|05.03.2024", results.getValue(TIME_DOT))
        assertEquals("VALUE|2024-03-05", results.getValue(CONST_ISO))
        assertEquals("VALUE|2024-03-05", results.getValue(CHAINED))
    }

    private companion object {
        const val ROOT = "/Claim"
        const val REPORTED = "/Claim/Reported"
        const val TIME_DOT = "/Claim/TimeDot"
        const val DATE_DOT = "/Claim/DateDot"
        const val CONST_ISO = "/Claim/ConstIso"
        const val CHAINED = "/Claim/Chained"
        const val ISO = "yyyy-MM-dd"
        const val DOTTED = "dd.MM.yyyy"
    }
}
