package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
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
 * Differential — date arithmetic `AddDays` / `AddMonths` / `AddYears` (date → date). `AddDays` is exercised
 * through a `>` comparison; `AddMonths`/`AddYears` pin the exact computed date — including DAY-CLAMPING (Jan
 * 31 +1mo → Feb 29 in a leap year; Feb 29 +1yr → Feb 28) — via `==` against the expected date, so a clamping
 * disagreement with the kernel would surface. Driven from parsed text; the date fields are filled.
 */
class DateArithmeticDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(OD, Kind.DATE), FieldDef(DD, Kind.DATE))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun agree(cond: String, orderDate: String, deliveryDate: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../DeliveryDate", "DA", cond), "DA",
            RuntimeLaws.doc().group("/Order", 1).field(OD, orderDate, 1, 1).field(DD, deliveryDate, 1, 1))
        val i = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "DA", "../DeliveryDate"))))
            .validate(Document(kinds).field(OD, orderDate, 1, 1).field(DD, deliveryDate, 1, 1)).messages.any { it.code == "DA" }
        assertEquals(k, i, "$msg (order=$orderDate, delivery=$deliveryDate)")
    }

    @Test fun addDays() {
        val c = render(m.date(DD).after(Dsl.addDays(m.date(OD), 7L)))   // OrderDate + 7 = 2020-01-08
        agree(c, "2020-01-01", "2020-01-10", "after +7 fires")
        agree(c, "2020-01-01", "2020-01-05", "before +7 no")
        agree(c, "2020-01-01", "2020-01-08", "exactly +7 not >")
    }

    @Test fun addMonths() {
        val c = render(m.date(DD).eq(Dsl.addMonths(m.date(OD), 1L)))
        agree(c, "2020-01-15", "2020-02-15", "plain +1 month")
        agree(c, "2020-01-31", "2020-02-29", "Jan 31 +1mo clamps to Feb 29 (leap)")
        agree(c, "2020-01-31", "2020-03-02", "does NOT roll over to March")
    }

    @Test fun addYears() {
        val c = render(m.date(DD).eq(Dsl.addYears(m.date(OD), 1L)))
        agree(c, "2020-06-15", "2021-06-15", "plain +1 year")
        agree(c, "2020-02-29", "2021-02-28", "Feb 29 +1yr clamps to Feb 28 (non-leap)")
        // end-of-February is preserved: the last day of a non-leap Feb maps to the leap year's Feb 29.
        agree(c, "1999-02-28", "2000-02-29", "Feb 28 (non-leap, end of Feb) +1yr → Feb 29 (leap)")
        agree(c, "1999-02-28", "2000-02-28", "and NOT Feb 28")
        agree(c, "2021-02-27", "2024-02-27", "a non-end-of-Feb day does NOT promote (+3yr → same day)")
    }

    private val kinds = mapOf(OD to Kind.DATE, DD to Kind.DATE)

    private companion object {
        const val OD = "/Order/OrderDate"
        const val DD = "/Order/DeliveryDate"
    }
}
