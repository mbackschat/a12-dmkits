package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM **and** JS) eval lock for `DateFragmentType` (WI-9 / IF57). The kernel differential
 * ([io.github.mbackschat.a12.dm.adapter.laws] `DateFragmentComparisonDiffTest`) is the primary oracle but is
 * JVM-only; this pins the same completion behavior on the JS target too — a fragment resolves to its earliest
 * concrete ISO date (missing month/day → `01`, missing year → the model base year), so it compares like a
 * plain date. Runs on `jvmTest` + `jsNodeTest` under `:interpreter:check`.
 */
class DateFragmentEvalTest {

    private val CE = "/Card/CardExpiry"; private val OD = "/Card/OrderDate"; private val BM = "/Card/BillMonth"

    /** A model with a yyyy-MM fragment, a full date, and an MM fragment; one rule `cond` (code "R"). */
    private fun model(cond: String, baseYear: Int? = null) = ModelDef(
        fields = listOf(
            FieldDef(CE, Kind.DATE, format = "yyyy-MM", datePrecision = DatePrecision.FRAGMENT),
            FieldDef(OD, Kind.DATE, format = "yyyy-MM-dd"),
            FieldDef(BM, Kind.DATE, format = "MM", datePrecision = DatePrecision.FRAGMENT),
        ),
        rules = listOf(RuleDef("/Card/R", "/Card", cond, "R", "../CardExpiry")),
        baseYear = baseYear,
    )

    private fun fires(cond: String, baseYear: Int? = null, vararg fv: Pair<String, String>): Boolean {
        val def = model(cond, baseYear)
        val doc = def.newDocument().group("/Card", 1)
        for ((f, v) in fv) doc.field(f, v, 1, 1)
        return DmInterpreter(def).validate(doc).messages.any { it.code == "R" }
    }

    @Test
    fun yearMonthFragmentCompletesDayToFirstForComparison() {
        // "2024-06" → 2024-06-01: equals a full 2024-06-01, not 2024-06-15.
        assertEquals(true, fires("[$CE] == [$OD]", fv = arrayOf(CE to "2024-06", OD to "2024-06-01")))
        assertEquals(false, fires("[$CE] == [$OD]", fv = arrayOf(CE to "2024-06", OD to "2024-06-15")))
        assertEquals(true, fires("[$CE] < [$OD]", fv = arrayOf(CE to "2024-06", OD to "2024-06-15")))
    }

    @Test
    fun monthFragmentCompletesYearToBaseYear() {
        // With baseYear 2024, "06" (MM) → 2024-06-01, so it compares to a year-bearing full date.
        assertEquals(true, fires("[$BM] == [$OD]", baseYear = 2024, fv = arrayOf(BM to "06", OD to "2024-06-01")))
        assertEquals(false, fires("[$BM] == [$OD]", baseYear = 2024, fv = arrayOf(BM to "06", OD to "2023-06-01")))
    }

    @Test
    fun malformedFragmentSuppressesTheReferencingRule() {
        // A month-out-of-range fragment is a formal error (datumFormatFalsch) → the operand is Unknown → the
        // rule is neither fired nor passed (suppressed), so it does not fire.
        assertEquals(false, fires("[$CE] == [$OD]", fv = arrayOf(CE to "2024-13", OD to "2024-01-01")))
    }
}
