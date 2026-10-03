package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC-2026-10-02-08: comparison direction, lexical scale and equality are independent axes. */
class FilterNumberComparisonAgreementTest {
    @Test
    fun ordinaryComparisonOperatorsKeepTheirScaleGateInsideFilters() {
        val base = DocumentModelDraft.named("invoice-filter-comparisons").locale("en_US")
            .rootGroup("Invoice") { root -> root.stringField("Reference") { }
                root.group("Rows") { it.repeatable(2).numberField("V") { n -> n.fractionDigits(0) }
                    .numberField("W") { n -> n.fractionDigits(2) }.stringField("S") { } }
            }.build().toJson()
        val group = EditSession.read(base).group("/Invoice")
        val v = group.num("Rows/V"); val w = group.num("Rows/W")
        val operators = listOf(CompareOp.EQ, CompareOp.NE, CompareOp.LT, CompareOp.LE, CompareOp.GT, CompareOp.GE)
        data class Row(val id: String, val condition: Condition, val codes: List<String>)
        val rows = buildList {
            for (op in operators) {
                for (literal in listOf("6", "1.5", "1.0", "1.50")) for (reverse in listOf(false, true)) {
                    val atom = NumberLiteral.of(literal)
                    val condition = if (reverse) ComparisonCondition(atom, op, v.value()) else ComparisonCondition(v.value(), op, atom)
                    add(Row("$op/$literal/$reverse", condition, if (op in listOf(CompareOp.EQ, CompareOp.NE) && '.' in literal)
                        listOf("MVK_INVALID_COMPARE_DEC_PLACES") else emptyList()))
                }
                add(Row("$op/fields", ComparisonCondition(v.value(), op, w.value()),
                    if (op in listOf(CompareOp.EQ, CompareOp.NE)) listOf("MVK_INVALID_COMPARE_DEC_PLACES") else emptyList()))
            }
            add(Row("scale-two-equality", ComparisonCondition(w.value(), CompareOp.EQ, NumberLiteral.of("1.505")), listOf("MVK_INVALID_COMPARE_DEC_PLACES")))
            add(Row("scale-two-ordering", w.lt("1.505"), emptyList()))
            add(Row("constants", ComparisonCondition(NumberLiteral.of(1), CompareOp.LT, NumberLiteral.of(2)), listOf("MVK_INVALID_COMPARE_TO_CONST")))
            add(Row("arithmetic", v.asNumber().plus(1).lt(6), emptyList()))
            add(Row("coordinate", Dsl.currentRepetition(group.group("Rows")).lt(6), emptyList()))
        }
        assertEquals(59, rows.size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val models = rows.map { row ->
            val rule = group.rule { it.name("CompareFilter").code("FILTER").errorField(group.str("Reference"))
                .`when`(group.str("Reference").filled().and(Dsl.sum(group.flatten("Rows*/V").having(row.condition)).gt(0)))
                .message(Dsl.en("Check amount.")).build() }
            row to TypedRules.inject(base, rule)
        }
        assertEquals(rows.map { it.id to it.codes }, models.map { (row, json) -> row.id to MessageParameterProbes.kernel(json) }, "kernel verdicts")
        assertEquals(rows.map { it.id to it.codes }, models.map { (row, json) -> row.id to MessageParameterProbes.portable(json) }, "portable verdicts")
    }
}
