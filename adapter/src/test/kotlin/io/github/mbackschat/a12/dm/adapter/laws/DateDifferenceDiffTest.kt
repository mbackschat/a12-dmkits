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
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — `DifferenceInMonths` / `DifferenceInYears`: whole elapsed periods (signed, toward zero) with
 * the start day clamped to the end month, so a month-end step counts as a full month. The exact value is
 * pinned by `== k` (k from the kernel), with the tricky edges: `2020-01-15`→`2020-02-14` = 0 (not yet a full
 * month), `2020-01-31`→`2020-02-29` = 1 (month-end), `2020-02-29`→`2024-02-28` = 3 years (not yet 4). Driven
 * from parsed text; the date fields are filled.
 */
class DateDifferenceDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(OD, Kind.DATE), FieldDef(DD, Kind.DATE))

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. The renderer emits absolute paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)
    // DifferenceIn<Unit>(OrderDate, DeliveryDate) — the unit selects the Dsl builder; `fn` names it for messages.
    private fun difference(fn: String): NumberExpr = when (fn) {
        "DifferenceInMonths" -> Dsl.differenceInMonths(m.date(OD), m.date(DD))
        "DifferenceInYears" -> Dsl.differenceInYears(m.date(OD), m.date(DD))
        else -> error("unmapped difference fn: $fn")
    }

    /** Assert the interpreter agrees with the kernel that `fn(OrderDate, DeliveryDate) == k`. */
    private fun pin(fn: String, a: String, b: String, k: Int) {
        val cond = render(difference(fn).eq(k.toLong()))
        val kf = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../DeliveryDate", "DD", cond), "DD",
            RuntimeLaws.doc().group("/Order", 1).field(OD, a, 1, 1).field(DD, b, 1, 1))
        val if_ = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "DD", "../DeliveryDate"))))
            .validate(Document(kinds).field(OD, a, 1, 1).field(DD, b, 1, 1)).messages.any { it.code == "DD" }
        assertEquals(true, kf, "kernel: $fn($a,$b) == $k")   // sanity: the expected value is the kernel's
        assertEquals(kf, if_, "$fn($a,$b) == $k")
    }

    @Test fun differenceInMonths() {
        pin("DifferenceInMonths", "2020-01-15", "2020-02-15", 1)
        pin("DifferenceInMonths", "2020-01-15", "2020-02-14", 0)   // not yet a full month
        pin("DifferenceInMonths", "2020-01-31", "2020-02-29", 1)   // month-end counts as full
        pin("DifferenceInMonths", "2020-01-15", "2020-04-15", 3)
        pin("DifferenceInMonths", "2020-12-15", "2021-02-15", 2)   // crosses the year
        pin("DifferenceInMonths", "2020-06-15", "2020-06-10", 0)   // backward, < 1 month → 0
        pin("DifferenceInMonths", "2020-02-29", "2020-01-30", -1)  // backward, a full month → signed −1
    }

    @Test fun differenceInYears() {
        pin("DifferenceInYears", "2020-06-15", "2021-06-15", 1)
        pin("DifferenceInYears", "2020-06-15", "2021-06-14", 0)    // not yet a full year
        pin("DifferenceInYears", "2020-06-15", "2023-06-15", 3)
        pin("DifferenceInYears", "2020-02-29", "2024-02-28", 3)    // not quite 4 years
        pin("DifferenceInYears", "1999-02-28", "2000-02-28", 0)    // end-of-Feb maps to Feb 29 > Feb 28 → not a full year
    }

    private val kinds = mapOf(OD to Kind.DATE, DD to Kind.DATE)

    private companion object {
        const val OD = "/Order/OrderDate"
        const val DD = "/Order/DeliveryDate"
    }
}
