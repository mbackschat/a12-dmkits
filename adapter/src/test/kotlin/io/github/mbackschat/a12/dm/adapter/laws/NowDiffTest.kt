package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
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
 * Differential — the clock constant `Now` (the current date-TIME, no operands) inside a date-time comparison
 * `[ScheduledAt] <op> Now`. `Now` is **wall-clock** on the kernel side and **injected** on the interpreter
 * side, so — like [ClockDiffTest] for `Today` — the differential compares with `ScheduledAt` values FAR from
 * any plausible "now" (year 2000 and year 2999); the verdict is then independent of the exact instant on
 * either side, and what's locked is that `Now` behaves as a *date-time* in a chronological comparison.
 *
 * `Now` is the date-time sibling of `Today`: it compares only to a DATE_TIME field (`ScheduledAt`, format
 * `yyyy-MM-dd'T'HH:mm:ss`); the kernel rejects comparing it to a plain date field at code generation (see the
 * `Now` catalog entry). The exact-instant boundary (`== Now`) is the kernel's discouraged moving target and
 * is not locked here. Driven from parsed text; `ScheduledAt` is filled (the catalog guards with `FieldFilled`).
 */
class NowDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(SA, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"))
    private val clock = EvalClock(today = "2025-06-23", now = "2025-06-23T12:00:00")

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun agree(cond: String, instant: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../ScheduledAt", "NW", cond), "NW",
            RuntimeLaws.doc().group("/Order", 1).field(SA, instant, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "NW", "../ScheduledAt")))
        val iDoc = def.newDocument().field(SA, instant, 1, 1)
        val i = DmInterpreter(def, clock).validate(iDoc).messages.any { it.code == "NW" }
        assertEquals(k, i, "$msg ($instant)")
    }

    @Test fun scheduledAfterNow() {
        val c = render(m.dateTime(SA).filled().and(m.dateTime(SA).after(Dsl.now())))
        agree(c, FAR_PAST, "a year-2000 instant is not after now")
        agree(c, FAR_FUTURE, "a year-2999 instant is after now")
    }

    @Test fun scheduledBeforeNow() {
        val c = render(m.dateTime(SA).filled().and(m.dateTime(SA).before(Dsl.now())))
        agree(c, FAR_PAST, "a year-2000 instant is before now")
        agree(c, FAR_FUTURE, "a year-2999 instant is not before now")
    }

    private companion object {
        const val SA = "/Order/ScheduledAt"
        const val FAR_PAST = "2000-01-01T00:00:00"
        const val FAR_FUTURE = "2999-12-31T23:59:59"
    }
}
