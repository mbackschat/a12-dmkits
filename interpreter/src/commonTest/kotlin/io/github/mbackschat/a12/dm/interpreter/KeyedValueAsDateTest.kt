package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for `ValueAsDate` over a key-selected cell (KERNEL-FINDINGS KF531): the boundary of
 * the selected row's partial date, in a rule and in a computation. Each row mirrors one the kernel pinned in
 * `adapter.laws.KeyedTemporalOperandDiffTest`, where all three engines agree; the first two rows tell `FirstDay`
 * from `LastDay`, which a keyed read that dropped the boundary would not.
 */
class KeyedValueAsDateTest {

    private val def = ModelDef(
        fields = listOf(
            FieldDef(HOLDER, Kind.STRING), FieldDef(KEY, Kind.STRING),
            FieldDef(LIMIT, Kind.DATE, format = "yyyy-MM-dd"), FieldDef(UNTIL, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(CODE, Kind.STRING),
            FieldDef(REPORTED, Kind.DATE, format = "yyyy-MM-dd", datePrecision = DatePrecision.MONTH_OPTIONAL),
        ),
        rules = listOf(
            RuleDef("/Register/First", "/Register",
                "FieldFilled(Holder) And ValueAsDate(Claims/ReportedAbout For Key, FirstDay) > [Limit]", "FIRST",
                "../Holder"),
            RuleDef("/Register/Last", "/Register",
                "FieldFilled(Holder) And ValueAsDate(Claims/ReportedAbout For Key, LastDay) > [Limit]", "LAST",
                "../Holder"),
        ),
        computations = listOf(
            ComputationDef(UNTIL, "/Register", "ValueAsDate(Claims/ReportedAbout For Key, LastDay)"),
        ),
        repeatableGroups = setOf("/Register/Claims"),
        indexFields = setOf(CODE),
    )

    /** The codes fired and the computed last day, for a key and the claims' reported dates (C1, then C2). */
    private fun observe(key: String, first: String?, second: String?): Pair<List<String>, String> {
        val doc = def.newDocument().group("/Register", 1).field(HOLDER, "H", 1, 1).field(KEY, key, 1, 1)
            .field(LIMIT, "2024-01-10", 1, 1).field(UNTIL, "2000-01-01", 1, 1)
        listOf("C1" to first, "C2" to second).forEachIndexed { i, (code, reported) ->
            doc.group("/Register/Claims", 1, i + 1).field(CODE, code, 1, i + 1, 1)
            reported?.let { doc.field(REPORTED, it, 1, i + 1, 1) }
        }
        val interpreter = DmInterpreter(def)
        val fired = interpreter.validate(doc).messages.map { it.code }.filter { it in setOf("FIRST", "LAST") }.sorted()
        val computed = interpreter.compute(doc).first { it.path == UNTIL }.let {
            when (it.outcome) {
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
            }
        }
        return fired to computed
    }

    @Test fun theFirstDayOfTheSelectedMonthIsNotAfterTheLimit() =
        assertEquals(listOf("LAST") to "VALUE|2024-01-31", observe("C1", "2024-01-00", "2024-02-00"))

    @Test fun anotherKeySelectsAnotherRow() =
        assertEquals(listOf("FIRST", "LAST") to "VALUE|2024-02-29", observe("C2", "2024-01-00", "2024-02-00"))

    @Test fun anUnknownMonthSpansTheYear() =
        assertEquals(listOf("LAST") to "VALUE|2024-12-31", observe("C1", "2024-00-00", "2023-00-00"))

    @Test fun aKeyNoRowCarriesGivesNoValue() =
        assertEquals(emptyList<String>() to "CLEARED", observe("C9", "2024-01-00", "2024-02-00"))

    @Test fun anEmptySelectedCellGivesNoValue() =
        assertEquals(emptyList<String>() to "CLEARED", observe("C2", "2024-01-00", null))

    @Test fun aMalformedSelectedCellGivesNoValue() =
        assertEquals(emptyList<String>() to "CLEARED", observe("C1", "2024-13-00", "2024-02-00"))

    private companion object {
        const val HOLDER = "/Register/Holder"
        const val KEY = "/Register/Key"
        const val LIMIT = "/Register/Limit"
        const val UNTIL = "/Register/ReportedUntil"
        const val CODE = "/Register/Claims/Code"
        const val REPORTED = "/Register/Claims/ReportedAbout"
    }
}
