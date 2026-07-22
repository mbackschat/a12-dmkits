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

/** JVM+Node lock for SPEC-2026-07-23-02; the adapter peer anchors it to both kernel strategies. */
class RangeAsNumberWrapperTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(ANCHOR, Kind.STRING),
            FieldDef(CODE, Kind.STRING, maxLength = 4),
            FieldDef(DOWN, Kind.NUMBER, scale = 0),
            FieldDef(UP, Kind.NUMBER, scale = 2),
            FieldDef(ACCOUNTING, Kind.NUMBER, scale = 0),
            FieldDef(MAGNITUDE, Kind.NUMBER, scale = 0),
            FieldDef(FOLLOWUP, Kind.NUMBER, scale = 0),
        ),
        rules = listOf(
            rule("DOWN", "RoundDown(RangeAsNumber(Code, 1, 3)) == 125"),
            rule("UP", "RoundUp(RangeAsNumber(Code, 1, 3), 2) == 125.00"),
            rule("ACCOUNTING", "RoundAccounting(RangeAsNumber(Code, 1, 3)) == 125"),
            rule("ABS", "Abs(RangeAsNumber(Code, 1, 3)) == 125"),
            rule("ROUND_GE", "RoundDown(RangeAsNumber(Code, 1, 3)) >= 0"),
            rule("ROUND_LE", "RoundDown(RangeAsNumber(Code, 1, 3)) <= 0"),
            rule("ABS_GE", "Abs(RangeAsNumber(Code, 1, 3)) >= 0"),
            rule("ABS_LE", "Abs(RangeAsNumber(Code, 1, 3)) <= 0"),
        ),
        computations = listOf(
            ComputationDef(DOWN, ROOT, "RoundDown(RangeAsNumber(Code, 1, 3))"),
            ComputationDef(UP, ROOT, "RoundUp(RangeAsNumber(Code, 1, 3), 2)"),
            ComputationDef(ACCOUNTING, ROOT, "RoundAccounting(RangeAsNumber(Code, 1, 3))"),
            ComputationDef(MAGNITUDE, ROOT, "Abs(RangeAsNumber(Code, 1, 3))"),
            ComputationDef(FOLLOWUP, ROOT, "[Down] + 1"),
        ),
    )

    private fun rule(code: String, condition: String) = RuleDef("$ROOT/${code}Rule", ROOT, condition, code, "../Code")

    @Test
    fun selectedFallbackAndMissingValuesKeepTheirWrapperPolarity() {
        val selected = document("125X")
        assertEquals(
            listOf("ABS_GE|VALUE_ERROR", "ABS|VALUE_ERROR", "ACCOUNTING|VALUE_ERROR", "DOWN|VALUE_ERROR", "ROUND_GE|VALUE_ERROR", "UP|VALUE_ERROR"),
            signals(selected),
        )
        assertEquals(
            listOf("ABS_GE|VALUE_ERROR", "ABS_LE|VALUE_ERROR", "ROUND_GE|VALUE_ERROR", "ROUND_LE|VALUE_ERROR"),
            signals(document("AB")),
        )
        assertEquals(
            listOf("ABS_GE|VALUE_ERROR", "ABS_LE|OMISSION_ERROR", "ROUND_GE|VALUE_ERROR", "ROUND_LE|OMISSION_ERROR"),
            signals(document(null)),
        )
    }

    @Test
    fun computationKeepsAuthoredScaleAndFormalPoison() {
        assertEquals(
            listOf(
                "$ACCOUNTING|VALUE|125",
                "$DOWN|VALUE|125",
                "$FOLLOWUP|VALUE|126",
                "$MAGNITUDE|VALUE|125",
                "$UP|VALUE|125",
            ),
            outcomes(document("125X")),
        )

        val invalid = document("TOO-LONG")
        TARGETS.forEach { invalid.field(it, "9", 1, 1) }
        assertEquals(
            listOf(
                "$ACCOUNTING|CLEARED",
                "$DOWN|CLEARED",
                "$FOLLOWUP|CLEARED",
                "$MAGNITUDE|CLEARED",
                "$UP|CLEARED",
            ),
            outcomes(invalid),
        )
    }

    private fun document(code: String?): Document {
        val document = model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        if (code != null) document.field(CODE, code, 1, 1)
        return document
    }

    private fun signals(document: Document) = DmInterpreter(model).validateFull(document).filter { it.code in CODES }.map {
        "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}"
    }.sorted()

    private fun outcomes(document: Document) = DmInterpreter(model).compute(document).filter { it.path in TARGETS }.map {
        when (it.outcome) {
            ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
            ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
            ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
        }
    }.sorted()

    private companion object {
        const val ROOT = "/Parcel"
        const val ANCHOR = "/Parcel/Anchor"
        const val CODE = "/Parcel/Code"
        const val DOWN = "/Parcel/Down"
        const val UP = "/Parcel/Up"
        const val ACCOUNTING = "/Parcel/Accounting"
        const val MAGNITUDE = "/Parcel/Magnitude"
        const val FOLLOWUP = "/Parcel/Followup"
        val TARGETS = setOf(DOWN, UP, ACCOUNTING, MAGNITUDE, FOLLOWUP)
        val CODES = setOf("DOWN", "UP", "ACCOUNTING", "ABS", "ROUND_GE", "ROUND_LE", "ABS_GE", "ABS_LE")
    }
}
