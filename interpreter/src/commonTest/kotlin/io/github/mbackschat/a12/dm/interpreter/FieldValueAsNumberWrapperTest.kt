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

/** JVM+Node lock for the direct wrapper compositions proved against both kernel strategies upstream. */
class FieldValueAsNumberWrapperTest {

    private val model = ModelDef(
        fields = listOf(
            FieldDef(ANCHOR, Kind.STRING),
            FieldDef(STORED, Kind.STRING, enumValues = listOf("-1.25", ".5")),
            FieldDef(
                BAND,
                Kind.STRING,
                enumValues = listOf("NEGATIVE", "LEADING"),
                enumCategories = mapOf("Numeric" to listOf("-1.25", ".5")),
            ),
            FieldDef(DOWN, Kind.NUMBER, scale = 0),
            FieldDef(UP, Kind.NUMBER, scale = 1),
            FieldDef(ACCOUNTING, Kind.NUMBER, scale = 0),
            FieldDef(MAGNITUDE, Kind.NUMBER, scale = 2),
        ),
        rules = listOf(
            rule("DOWN", "RoundDown(FieldValueAsNumber(Stored)) == -2", "../Stored"),
            rule("UP", "RoundUp(FieldValueAsNumber(Band -> Numeric), 1) == -1.2"),
            rule("ACCOUNTING", "RoundAccounting(FieldValueAsNumber(Stored)) == -1", "../Stored"),
            rule("ABS", "Abs(FieldValueAsNumber(Band -> Numeric)) == 1.25"),
            rule("ROUND_EMPTY", "RoundDown(FieldValueAsNumber(Band -> Numeric)) == 0"),
            rule("ABS_EMPTY_LE", "Abs(FieldValueAsNumber(Band -> Numeric)) <= 0"),
            rule("ABS_EMPTY_GE", "Abs(FieldValueAsNumber(Band -> Numeric)) >= 0"),
        ),
        computations = listOf(
            ComputationDef(DOWN, ROOT, "RoundDown(FieldValueAsNumber(Stored))"),
            ComputationDef(UP, ROOT, "RoundUp(FieldValueAsNumber(Band -> Numeric), 1)"),
            ComputationDef(ACCOUNTING, ROOT, "RoundAccounting(FieldValueAsNumber(Stored))"),
            ComputationDef(MAGNITUDE, ROOT, "Abs(FieldValueAsNumber(Band -> Numeric))"),
        ),
    )

    private fun rule(code: String, condition: String, errorRel: String = "../Band") =
        RuleDef("$ROOT/${code}Rule", ROOT, condition, code, errorRel)

    @Test
    fun presentAndMissingWrapperValuesKeepTheirExactPolarity() {
        val present = model.newDocument().group(ROOT, 1).field(STORED, "-1.25", 1, 1)
            .field(BAND, "NEGATIVE", 1, 1)
        assertEquals(
            listOf(
                "ABS_EMPTY_GE|VALUE_ERROR",
                "ABS|VALUE_ERROR",
                "ACCOUNTING|VALUE_ERROR",
                "DOWN|VALUE_ERROR",
                "UP|VALUE_ERROR",
            ),
            signals(present),
        )

        val missing = model.newDocument().group(ROOT, 1).field(ANCHOR, "active", 1, 1)
        assertEquals(
            listOf("ABS_EMPTY_GE|VALUE_ERROR", "ABS_EMPTY_LE|OMISSION_ERROR", "ROUND_EMPTY|OMISSION_ERROR"),
            signals(missing),
        )
    }

    @Test
    fun computationKeepsWrapperScaleAndFormalPoison() {
        val present = model.newDocument().group(ROOT, 1).field(STORED, "-1.25", 1, 1)
            .field(BAND, "NEGATIVE", 1, 1)
        assertEquals(
            listOf("$ACCOUNTING|VALUE|-1", "$DOWN|VALUE|-2", "$MAGNITUDE|VALUE|1.25", "$UP|VALUE|-1.2"),
            outcomes(present),
        )

        val invalid = model.newDocument().group(ROOT, 1).field(STORED, "BOGUS", 1, 1)
            .field(BAND, "BOGUS", 1, 1).field(DOWN, "9", 1, 1).field(UP, "9.0", 1, 1)
            .field(ACCOUNTING, "9", 1, 1).field(MAGNITUDE, "9.00", 1, 1)
        assertEquals(
            listOf("$ACCOUNTING|CLEARED", "$DOWN|CLEARED", "$MAGNITUDE|CLEARED", "$UP|CLEARED"),
            outcomes(invalid),
        )
    }

    private fun signals(document: Document) =
        DmInterpreter(model).validateFull(document).filter { it.code in CODES }.map {
            "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}"
        }.sorted()

    private fun outcomes(document: Document) =
        DmInterpreter(model).compute(document).filter { it.path in TARGETS }.map {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "${it.path}|VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "${it.path}|CLEARED"
                ComputeOutcome.ERRORED -> "${it.path}|ERRORED"
            }
        }.sorted()

    private companion object {
        const val ROOT = "/Tariff"
        const val ANCHOR = "/Tariff/Anchor"
        const val STORED = "/Tariff/Stored"
        const val BAND = "/Tariff/Band"
        const val DOWN = "/Tariff/Down"
        const val UP = "/Tariff/Up"
        const val ACCOUNTING = "/Tariff/Accounting"
        const val MAGNITUDE = "/Tariff/Magnitude"
        val TARGETS = setOf(DOWN, UP, ACCOUNTING, MAGNITUDE)
        val CODES = setOf("DOWN", "UP", "ACCOUNTING", "ABS", "ROUND_EMPTY", "ABS_EMPTY_LE", "ABS_EMPTY_GE")
    }
}
