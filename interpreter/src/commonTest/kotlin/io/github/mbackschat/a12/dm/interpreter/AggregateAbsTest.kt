package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
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

/** JVM+Node lock for SPEC-2026-07-23-03; the adapter peer anchors it to both kernel strategies. */
class AggregateAbsTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(ANCHOR, Kind.STRING),
            FieldDef(AMOUNT, Kind.NUMBER, scale = 2),
            FieldDef(SUM_MAGNITUDE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(MIN_MAGNITUDE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(MAX_MAGNITUDE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(DISTINCT_MAGNITUDE, Kind.NUMBER, scale = 0),
            FieldDef(FOLLOWUP, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        rules = listOf(
            rule("SUM_FOLD", "Abs(Sum(Lines*/Amount)) == 2.25"),
            rule("MIN_VALUE", "Abs(MinValue(Lines*/Amount)) == 3.25"),
            rule("MAX_VALUE", "Abs(MaxValue(Lines*/Amount)) == 1.00"),
            rule("DISTINCT", "Abs(NumberOfDifferentValues(Lines*/Amount)) == 2"),
            rule("SUM_BASE_GE", "Sum(Lines*/Amount) >= 0"),
            rule("SUM_ABS_GE", "Abs(Sum(Lines*/Amount)) >= 0"),
            rule("SUM_ABS_LE", "Abs(Sum(Lines*/Amount)) <= 0"),
            rule("MIN_ABS_GE", "Abs(MinValue(Lines*/Amount)) >= 0"),
            rule("MIN_ABS_LE", "Abs(MinValue(Lines*/Amount)) <= 0"),
            rule("MAX_ABS_GE", "Abs(MaxValue(Lines*/Amount)) >= 0"),
            rule("MAX_ABS_LE", "Abs(MaxValue(Lines*/Amount)) <= 0"),
            rule("DISTINCT_BASE_GE", "NumberOfDifferentValues(Lines*/Amount) >= 0"),
            rule("DISTINCT_ABS_GE", "Abs(NumberOfDifferentValues(Lines*/Amount)) >= 0"),
            rule("DISTINCT_ABS_LE", "Abs(NumberOfDifferentValues(Lines*/Amount)) <= 0"),
        ),
        computations = listOf(
            ComputationDef(SUM_MAGNITUDE, ROOT, "Abs(Sum(Lines*/Amount))"),
            ComputationDef(MIN_MAGNITUDE, ROOT, "Abs(MinValue(Lines*/Amount))"),
            ComputationDef(MAX_MAGNITUDE, ROOT, "Abs(MaxValue(Lines*/Amount))"),
            ComputationDef(DISTINCT_MAGNITUDE, ROOT, "Abs(NumberOfDifferentValues(Lines*/Amount))"),
            ComputationDef(FOLLOWUP, ROOT, "[SumMagnitude] + 1"),
        ),
        repeatableGroups = setOf(LINES),
        declaredReps = mapOf(LINES to 2),
    )

    private fun rule(code: String, condition: String) =
        RuleDef("$ROOT/${code}Rule", ROOT, condition, code, "../Lines/Amount")

    @Test
    fun foldOrderEmptyPolarityAndDerivedScaleStayComposed() {
        val present = document(listOf(1 to "-3.25", 2 to "1.00"))
        assertEquals(
            listOf("DISTINCT", "DISTINCT_ABS_GE", "DISTINCT_BASE_GE", "MAX_ABS_GE", "MAX_VALUE", "MIN_ABS_GE", "MIN_VALUE", "SUM_ABS_GE", "SUM_FOLD"),
            signals(present),
        )
        assertEquals(
            listOf(
                "$DISTINCT_MAGNITUDE|VALUE|2",
                "$FOLLOWUP|VALUE|3.25",
                "$MAX_MAGNITUDE|VALUE|1.00",
                "$MIN_MAGNITUDE|VALUE|3.25",
                "$SUM_MAGNITUDE|VALUE|2.25",
            ),
            outcomes(present),
        )

        assertEquals(
            listOf(
                "DISTINCT_ABS_GE",
                "DISTINCT_ABS_LE:OMISSION",
                "DISTINCT_BASE_GE",
                "MAX_ABS_GE",
                "MAX_ABS_LE:OMISSION",
                "MIN_ABS_GE",
                "MIN_ABS_LE:OMISSION",
                "SUM_ABS_GE",
                "SUM_ABS_LE:OMISSION",
                "SUM_BASE_GE:OMISSION",
            ),
            signals(document(emptyList())),
        )
    }

    @Test
    fun formalInvalidityPoisonsWrappedAggregatesAndDependentRead() {
        val invalid = document(listOf(1 to "invalid"))
        TARGETS.forEach { invalid.field(it, "9", 1, 1) }
        assertEquals(
            listOf(
                "$DISTINCT_MAGNITUDE|CLEARED",
                "$FOLLOWUP|CLEARED",
                "$MAX_MAGNITUDE|CLEARED",
                "$MIN_MAGNITUDE|CLEARED",
                "$SUM_MAGNITUDE|CLEARED",
            ),
            outcomes(invalid),
        )
    }

    private fun document(rows: List<Pair<Int, String>>): Document {
        val document = model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        rows.forEach { (row, value) -> document.group(LINES, 1, row).field(AMOUNT, value, 1, row, 1) }
        return document
    }

    private fun signals(document: Document) = DmInterpreter(model).validateFull(document).filter { it.code in CODES }.map {
        "${it.code}${if (it.type == MsgType.OMISSION) ":OMISSION" else ""}"
    }.sorted()

    private fun outcomes(document: Document) = DmInterpreter(model).compute(document).filter { it.path in TARGETS }.map {
        when (it.outcome) {
            ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
            ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
            ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
        }
    }.sorted()

    private companion object {
        const val ROOT = "/Invoice"
        const val ANCHOR = "/Invoice/Anchor"
        const val LINES = "/Invoice/Lines"
        const val AMOUNT = "/Invoice/Lines/Amount"
        const val SUM_MAGNITUDE = "/Invoice/SumMagnitude"
        const val MIN_MAGNITUDE = "/Invoice/MinMagnitude"
        const val MAX_MAGNITUDE = "/Invoice/MaxMagnitude"
        const val DISTINCT_MAGNITUDE = "/Invoice/DistinctMagnitude"
        const val FOLLOWUP = "/Invoice/Followup"
        val TARGETS = setOf(SUM_MAGNITUDE, MIN_MAGNITUDE, MAX_MAGNITUDE, DISTINCT_MAGNITUDE, FOLLOWUP)
        val CODES = setOf(
            "SUM_FOLD", "MIN_VALUE", "MAX_VALUE", "DISTINCT", "SUM_BASE_GE", "SUM_ABS_GE", "SUM_ABS_LE",
            "MIN_ABS_GE", "MIN_ABS_LE", "MAX_ABS_GE", "MAX_ABS_LE", "DISTINCT_BASE_GE", "DISTINCT_ABS_GE",
            "DISTINCT_ABS_LE",
        )
    }
}
