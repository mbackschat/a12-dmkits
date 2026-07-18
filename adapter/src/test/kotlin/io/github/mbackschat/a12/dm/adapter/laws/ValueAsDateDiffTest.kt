package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `ValueAsDate(field, FirstDay|LastDay)` over a partial (DAY_OPTIONAL) date field
 * (`ApproxDate`, `dd.MM.yyyy`). A day-omitted value is `00.MM.yyyy`: FirstDay → the month's first day,
 * LastDay → its last day; a FULL value resolves to itself for both. Compared to a German date literal (the
 * catalog form). Deterministic — no clock.
 */
class ValueAsDateDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(AD, Kind.DATE, format = "dd.MM.yyyy", datePrecision = DatePrecision.DAY_OPTIONAL))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. ABSOLUTE paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)
    private fun firstDay(literal: String): String =
        render(m.field(AD).filled().and(Dsl.valueAsDateFirstDay(m.field(AD)).eq(Dsl.dateValue(literal))))
    private fun lastDay(literal: String): String =
        render(m.field(AD).filled().and(Dsl.valueAsDateLastDay(m.field(AD)).eq(Dsl.dateValue(literal))))

    private fun agree(cond: String, approx: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ApproxDate", "VA", cond), "VA",
            RuntimeLaws.doc().group("/Order", 1).field(AD, approx, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "VA", "../ApproxDate")))
        val iDoc = def.newDocument().field(AD, approx, 1, 1)
        val i = DmInterpreter(def).validate(iDoc).messages.any { it.code == "VA" }
        assertEquals(k, i, "$msg ($approx)")
    }

    private val partial = "00.06.2024"   // June 2024, day unspecified
    private val full = "15.06.2024"

    // FirstDay → 01.06.2024
    @Test fun firstDayOfPartialIsFirst() = agree(firstDay("01.06.2024"), partial, "FirstDay of a day-omitted June is the 1st")
    @Test fun firstDayNotLast() = agree(firstDay("30.06.2024"), partial, "FirstDay is not the 30th")
    // LastDay → 30.06.2024
    @Test fun lastDayOfPartialIsMonthEnd() = agree(lastDay("30.06.2024"), partial, "LastDay of a day-omitted June is the 30th")
    // ordering against a literal
    @Test fun firstDayAfterStartOf2020() = agree(render(m.field(AD).filled().and(Dsl.valueAsDateFirstDay(m.field(AD)).after(Dsl.dateValue("01.01.2020")))), partial, "the earliest possible date is after 2020")
    @Test fun lastDayBeforeEndOf2030() = agree(render(m.field(AD).filled().and(Dsl.valueAsDateLastDay(m.field(AD)).before(Dsl.dateValue("31.12.2030")))), partial, "the latest possible date is before 2031")

    // A FULL value resolves to itself for both day-specifications.
    @Test fun fullValueFirstDayIsItself() = agree(firstDay("15.06.2024"), full, "a full date's FirstDay is itself")
    @Test fun fullValueLastDayIsItself() = agree(lastDay("15.06.2024"), full, "a full date's LastDay is itself")

    private companion object {
        const val AD = "/Order/ApproxDate"
    }
}
