package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.Alt
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.ROOT
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.computation
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.premium
import io.github.mbackschat.a12.dm.adapter.laws.PremiumProbes.rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-10-02-03 on our own premium carrier: where the division check runs and how far it walks, and what a
 * lone extrema leader must be.
 *
 * The computation rows write the scale-2 `Result` from the scale-2 `Amount` and `Rate` (the repeatable rows
 * write `Lines/LineResult` the same way), so a row the walk lets through reaches the target's scale rule and
 * draws `MVK_INVALID_COMPARE_DEC_PLACES` rather than being admitted — that class is the "passes" verdict here.
 * Ids are a12-kernel-lean's (`number-operation-division-walk-2026-10-02`); the `L…` leader rows re-express their
 * node-gate checkpoint's leader claim. The pairs no compositional count separates are `P01` against `P02` and
 * `P03` against `h03`; the `o…` rows are our own controls for the fresh mark an addition gives each operand.
 * The portable checker is held to every row.
 */
class DivisionWalkAgreementTest {
    data class Row(val id: String, val model: String, val classes: List<String>)

    private val base = premium()

    fun rows(): List<Row> = buildList {
        fun root(id: String, operation: String, vararg classes: String) =
            add(Row(id, computation(base, ROOT, "Result", listOf(Alt(null, operation))), classes.toList()))
        fun line(id: String, operation: String, vararg classes: String) =
            add(Row(id, computation(base, "$ROOT/Lines", "LineResult", listOf(Alt(null, operation))), classes.toList()))
        fun condition(id: String, condition: String, vararg classes: String) =
            add(Row(id, rule(base, "Amount", condition), classes.toList()))
        // spec-enclosing.txt
        root("h01", "[Amount] * Max([Amount] / [Rate], [Rate] / [Amount])", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("h02", "Max([Amount] / [Rate], [Rate] / [Amount]) / [Amount]", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("h03", "[Amount] + Max([Amount] / [Rate], [Rate] / [Amount])", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("h04", "Max([Amount] / [Rate], [Rate] / [Amount], [Amount] / [Rate]) * [Amount]", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("h05", "[Amount] / Max([Rate] / [Amount], [Amount])", "MVK_TOO_MANY_DIV_FOR_CALC")
        condition("v01", "Abs(Max([Amount] / [Rate], [Rate] / [Amount])) > 0")
        condition("v02", "[Amount] + Max([Amount] / [Rate], [Rate] / [Amount]) > 0", "MVK_TOO_MANY_DIV_FOR_CALC")
        condition("v03", "Max([Amount] ^ 2 ^ 2, [Rate]) > 0", "MVK_TOO_MANY_POW_FOR_CALC")
        condition("v04", "[Amount] * Max([Amount] / [Rate], [Rate] / [Amount]) > 0", "MVK_TOO_MANY_DIV_FOR_CALC")
        condition("v05", "Max([Amount] / [Rate], [Rate] / [Amount]) / [Amount] > 0", "MVK_TOO_MANY_DIV_FOR_CALC")
        // spec-top.txt
        root("j01", "Max(Max([Amount] / [Rate], [Rate] / [Amount]), 1)", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j02", "[Amount] + Abs(Max([Amount] / [Rate], [Rate] / [Amount]))", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("j03", "[Amount] * Max([Amount] / [Rate], 1)", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j04", "[Amount] + Max([Amount] / [Rate], 1)", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j05", "{Max([Amount] / [Rate], [Rate] / [Amount])}", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j06", "[Amount] + Max({[Amount] / [Rate]}, [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j08", "Abs(Max([Amount] / [Rate], [Rate] / [Amount])) + [Amount]", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("j09", "RoundDown(Max([Amount] / [Rate], [Rate] / [Amount]), 2)")
        root("j10", "Max(Abs(Max([Amount] / [Rate], [Rate] / [Amount])), 1)", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j12", "Max([Amount] / [Rate], [Rate] / [Amount]) ^ 2", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("j13", "{Max([Amount] / [Rate], [Rate] / [Amount])} + [Amount]", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("j14", "[Amount] + Max([Amount] / [Rate], [Rate])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j15", "[Amount] - Min([Amount] / [Rate], [Rate] / [Amount])", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("j16", "Max([Amount], Max([Amount] / [Rate], [Rate] / [Amount]))", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("j17", "Max([Amount] / [Rate], [Rate] / [Amount]) + Max([Amount], 1)", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("j18", "2 ^ Max([Amount] / [Rate], [Rate] / [Amount])", "MVK_INVALID_NUMBER_FOR_EXP")
        // spec-cut.txt
        root("P01", "[Amount] / Max({[Rate]}, [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P02", "[Amount] / Max([Rate] / [Amount], {[Rate]})", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("P03", "[Amount] + [Amount] + Max([Amount] / [Rate], [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P04", "[Amount] / Max([Rate] + 1, [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P05", "[Amount] * Max([Rate] / {[Amount]}, [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P06", "Max([Amount] / [Rate], [Rate] / [Amount]) + [Amount] + [Amount]", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("P07", "[Amount] - [Amount] + Max([Amount] / [Rate], [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P10", "Max([Amount] / [Rate], [Rate] / [Amount]) * {[Amount]}", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("P11", "{[Amount]} * Max([Amount] / [Rate], [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P12", "[Amount] ^ 2 * Max([Amount] / [Rate], [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P13", "Max([Amount] / [Rate], [Rate] / [Amount]) * [Amount] ^ 2", "MVK_TOO_MANY_DIV_FOR_CALC")
        root("P14", "[Amount] / Max([Rate] ^ 2, [Rate] / [Amount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("P15", "[Amount] / Max([Rate] / [Amount], [Rate] ^ 2)", "MVK_TOO_MANY_DIV_FOR_CALC")
        condition("R08", "[Amount] + [Amount] + Max([Amount] / [Rate], [Rate] / [Amount]) > 0")
        condition("R01", "[Amount] / Max({[Rate]}, [Rate] / [Amount]) > 0")
        condition("R02", "[Amount] / Max([Rate] / [Amount], {[Rate]}) > 0", "MVK_TOO_MANY_DIV_FOR_CALC")
        // spec-repeatable.txt
        line("p01", "Max([LineAmount] / [LineRate] / [LineAmount], [LineRate])", "MVK_TOO_MANY_DIV_FOR_CALC")
        line("p02", "[LineAmount] * Max([LineAmount] / [LineRate], [LineRate] / [LineAmount])", "MVK_TOO_MANY_DIV_FOR_CALC")
        line("p03", "Max([LineAmount] / [LineRate], [LineRate] / [LineAmount])", "MVK_INVALID_COMPARE_DEC_PLACES")
        line("p04", "Max([LineAmount] ^ 2 ^ 2, [LineRate])", "MVK_TOO_MANY_POW_FOR_CALC")
        line("p05", "[LineAmount] ^ [LineRate]", "MVK_INVALID_NUMBER_FOR_EXP")
        // the node-gate checkpoint's lone-leader claim
        for ((id, leader) in listOf("L01" to "Max([Note])", "L02" to "Min([Note])", "L03" to "Max([Active])",
            "L04" to "Max([Tier])", "L05" to "Max([Band])", "L06" to "Max([Agreed])", "L07" to "Max([Term])",
            "L08" to "Max({[Note] + [Note2]})")) root(id, leader, "MVK_NOT_SORTABLE")
        root("L09", "Max([Start])", "MVK_INVALID_COMPARE_TO_DATE")
        root("L10", "Max([Amount])")
        root("L11", "Max([Note], [Nope])", "MVK_INVALID_ENTITY")
        condition("L12", "Max([Note]) > 0", "MVK_NOT_SORTABLE")
        // our own controls: an addition or subtraction gives each operand its own mark, and a braced quotient
        // is its own walk, so neither pair below meets a second division (KF45's plain-arithmetic region)
        root("o01", "[Amount] / [Rate] + [Rate] / [Amount]", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("o02", "[Amount] / [Rate] - [Rate] / [Amount]", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("o03", "[Amount] / {[Rate] / [Amount]}", "MVK_INVALID_COMPARE_DEC_PLACES")
        root("o04", "{[Amount] / [Rate] / [Amount]}", "MVK_TOO_MANY_DIV_FOR_CALC")
    }

    @Test
    fun theDivisionCheckWalksThroughCallsAndStopsAtTheFirstBraceAdditionOrPower() {
        val rows = rows()
        assertEquals(59, rows.count { !it.id.startsWith("o") }, "frozen witness rows")
        assertEquals(rows.size, rows.map { it.id }.toSet().size, "observations retain independent identities")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to kernel(it.model) }, "kernel classes")
        assertEquals(rows.map { it.id to it.classes }, rows.map { it.id to portable(it.model) }, "portable classes")
    }
}
