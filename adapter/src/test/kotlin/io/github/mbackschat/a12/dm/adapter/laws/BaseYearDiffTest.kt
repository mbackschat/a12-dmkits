package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
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
 * Differential — the `BaseYear` point-in-time constant (the order fixture declares `modelInfo.baseYear = 2020`).
 * It is **polymorphic** by the comparison's other operand (kernel-probed): the base-year-START **date**
 * (`2020-01-01`) against a date field, or the year **number** (`2020`) against a number field. The base year
 * is model config (not the clock), so no `EvalClock` is needed. Fully deterministic.
 */
class BaseYearDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(OD, Kind.DATE), FieldDef(QTY, Kind.NUMBER), FieldDef(SCHEDULED, Kind.DATE_TIME))
    private val baseYear = 2020

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines — no free-text condition literals. ABSOLUTE paths; both engines accept them.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = DslRenderer().render(c)

    private fun agreeDate(cond: String, date: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../OrderDate", "BY", cond), "BY",
            RuntimeLaws.doc().group("/Order", 1).field(OD, date, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "BY", "../OrderDate")), baseYear = baseYear)
        val i = DmInterpreter(def).validate(Document(def.kinds).field(OD, date, 1, 1)).messages.any { it.code == "BY" }
        assertEquals(k, i, "$msg ($date)")
    }

    private fun agreeNumber(cond: String, qty: String, msg: String) {
        val k = RuntimeLaws.fires(RuntimeLaws.withRule(order, "/Order", "R", "../Quantity", "BN", cond), "BN",
            RuntimeLaws.doc().group("/Order", 1).field(QTY, qty, 1, 1))
        val def = ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, "BN", "../Quantity")), baseYear = baseYear)
        val i = DmInterpreter(def).validate(Document(def.kinds).field(QTY, qty, 1, 1)).messages.any { it.code == "BN" }
        assertEquals(k, i, "$msg ($qty)")
    }

    // Date polymorphism — BaseYear is the base-year start 2020-01-01.
    @Test fun dateOnOrAfterBaseYearStart() {
        val c = render(m.date(OD).filled().and(m.date(OD).onOrAfter(Dsl.baseYear())))
        agreeDate(c, "2020-01-01", "the base-year start is on/after itself")
        agreeDate(c, "2019-12-31", "the day before is not on/after the base-year start")
        agreeDate(c, "2024-06-01", "a later date is on/after the base-year start")
    }

    @Test fun dateBeforeBaseYearStart() {
        val c = render(m.date(OD).filled().and(m.date(OD).before(Dsl.baseYear())))
        agreeDate(c, "2019-12-31", "the day before is before the base-year start")
        agreeDate(c, "2020-01-01", "the start itself is not before the base-year start")
    }

    // Number polymorphism — BaseYear is the year number 2020.
    @Test fun numberVsBaseYear() {
        val gt = render(m.num(QTY).filled().and(m.num(QTY).gt(Dsl.baseYearNumber())))
        agreeNumber(gt, "2021", "2021 > 2020")
        agreeNumber(gt, "5", "5 not > 2020")
        agreeNumber(render(m.num(QTY).filled().and(m.num(QTY).eq(Dsl.baseYearNumber()))), "2020",
            "2020 equals the base year number")
    }

    @Test
    fun dateTimeOrderingUsesTheBaseYearStartOnTheCommonTimeline() {
        val condition = render(m.dateTime(SCHEDULED).onOrAfter(Dsl.baseYear()))
        val code = "BDT"
        val modelJson = RuntimeLaws.withRule(order, "/Order", "DateTimeBaseYear", "../ScheduledAt", code, condition)
        val definition = ModelDef(
            fields,
            listOf(RuleDef("/Order/DateTimeBaseYear", "/Order", condition, code, "../ScheduledAt")),
            baseYear = baseYear,
        )
        val java = RuntimeLaws.kernelJavaEngine(modelJson)

        listOf(
            "2019-12-31T23:59:59" to false,
            "2020-01-01T00:00:00" to true,
            "2020-01-01T00:00:01" to true,
        ).forEach { (value, expected) ->
            val kernelDoc = RuntimeLaws.doc().group("/Order", 1).field(SCHEDULED, value, 1, 1)
            val interpreterDoc = Document(definition.kinds).field(SCHEDULED, value, 1, 1)
            val dynamic = RuntimeLaws.fires(modelJson, code, kernelDoc)
            val generated = java.messages(java.instance(kernelDoc)).any { it.code() == code }
            val interpreted = DmInterpreter(definition).validate(interpreterDoc).messages.any { it.code == code }

            assertEquals(expected, dynamic, "$value — dynamic Groovy")
            assertEquals(dynamic, generated, "$value — generated Java")
            assertEquals(dynamic, interpreted, "$value — interpreter")
        }
    }

    @Test
    fun baseYearRangeBoundsAreTheFirstAndLastCalendarDays() {
        val cases = listOf(
            render(m.date(OD).eq(Dsl.startOfBaseYear())) to "2020-01-01",
            render(m.date(OD).eq(Dsl.endOfBaseYear())) to "2020-12-31",
        )
        cases.forEachIndexed { index, (condition, matchingDate) ->
            val code = "BR$index"
            val model = RuntimeLaws.withRule(order, "/Order", "R$index", "../OrderDate", code, condition)
            val definition = ModelDef(
                fields,
                listOf(RuleDef("/Order/R$index", "/Order", condition, code, "../OrderDate")),
                baseYear = baseYear,
            )
            val java = RuntimeLaws.kernelJavaEngine(model)

            listOf(matchingDate to true, "2020-06-15" to false).forEach { (date, expected) ->
                val kernelDoc = RuntimeLaws.doc().group("/Order", 1).field(OD, date, 1, 1)
                val interpreterDoc = Document(definition.kinds).field(OD, date, 1, 1)
                val dynamic = RuntimeLaws.fires(model, code, kernelDoc)
                val generated = java.messages(java.instance(kernelDoc)).any { it.code() == code }
                val interpreter = DmInterpreter(definition).validate(interpreterDoc).messages.any { it.code == code }

                assertEquals(expected, dynamic, "$condition on $date — dynamic Groovy")
                assertEquals(dynamic, generated, "$condition on $date — generated Java")
                assertEquals(dynamic, interpreter, "$condition on $date — interpreter")
            }
        }
    }

    private companion object {
        const val OD = "/Order/OrderDate"
        const val QTY = "/Order/Quantity"
        const val SCHEDULED = "/Order/ScheduledAt"
    }
}
