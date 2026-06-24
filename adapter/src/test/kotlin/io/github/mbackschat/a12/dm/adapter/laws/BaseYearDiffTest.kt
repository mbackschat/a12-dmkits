package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
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
    private val fields = listOf(FieldDef(OD, Kind.DATE), FieldDef(QTY, Kind.NUMBER))
    private val baseYear = 2020

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
        val c = "FieldFilled(OrderDate) And [OrderDate] >= BaseYear"
        agreeDate(c, "2020-01-01", "the base-year start is on/after itself")
        agreeDate(c, "2019-12-31", "the day before is not on/after the base-year start")
        agreeDate(c, "2024-06-01", "a later date is on/after the base-year start")
    }

    @Test fun dateBeforeBaseYearStart() {
        val c = "FieldFilled(OrderDate) And [OrderDate] < BaseYear"
        agreeDate(c, "2019-12-31", "the day before is before the base-year start")
        agreeDate(c, "2020-01-01", "the start itself is not before the base-year start")
    }

    // Number polymorphism — BaseYear is the year number 2020.
    @Test fun numberVsBaseYear() {
        agreeNumber("FieldFilled(Quantity) And [Quantity] > BaseYear", "2021", "2021 > 2020")
        agreeNumber("FieldFilled(Quantity) And [Quantity] > BaseYear", "5", "5 not > 2020")
        agreeNumber("FieldFilled(Quantity) And [Quantity] == BaseYear", "2020", "2020 equals the base year number")
    }

    private companion object {
        const val OD = "/Order/OrderDate"
        const val QTY = "/Order/Quantity"
    }
}
