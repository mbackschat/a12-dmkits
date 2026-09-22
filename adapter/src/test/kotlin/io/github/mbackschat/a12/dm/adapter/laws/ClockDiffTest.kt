package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the clock constant `Today` (the current date, no operands), used inside a date
 * comparison `[OrderDate] <op> Today`. `Today` is **wall-clock** on the kernel side and **injected** on the
 * interpreter side, so to keep the differential deterministic we compare with `OrderDate` values FAR from
 * any plausible "today" (the year 2000 and the year 2999) — the comparison's verdict is then independent of
 * the exact current date on either side, and what's locked is that `Today` behaves as a *date* in a
 * chronological comparison (not a number, not Unknown). The exact-value boundary (`== Today`) is locked
 * deterministically in the interpreter's own `commonTest`. Driven from parsed text; `OrderDate` is filled
 * (the catalog guards `Today` comparisons with `FieldFilled`).
 */
class ClockDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(OD, Kind.DATE))
    private val clock = EvalClock(today = "2025-06-23")
    private val kinds = mapOf(OD to Kind.DATE)

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    private fun agree(cond: String, date: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../OrderDate", "TD", cond), "TD",
            RuntimeLaws.doc().group("/Order", 1).field(OD, date, 1, 1))
        val i = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "TD", "../OrderDate"))), clock)
            .validate(Document(kinds).field(OD, date, 1, 1)).messages.any { it.code == "TD" }
        assertEquals(k, i, "$msg ($date)")
    }

    @Test fun orderDateOnOrAfterToday() {
        val c = render(m.date(OD).filled().and(m.date(OD).onOrAfter(Dsl.today())))
        agree(c, FAR_PAST, "a year-2000 order is not on/after today")
        agree(c, FAR_FUTURE, "a year-2999 order is on/after today")
    }

    @Test fun orderDateBeforeToday() {
        val c = render(m.date(OD).filled().and(m.date(OD).before(Dsl.today())))
        agree(c, FAR_PAST, "a year-2000 order is before today")
        agree(c, FAR_FUTURE, "a year-2999 order is not before today")
    }

    private companion object {
        const val OD = "/Order/OrderDate"
        const val FAR_PAST = "2000-01-01"
        const val FAR_FUTURE = "2999-12-31"
    }
}
