package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.kernel
import io.github.mbackschat.a12.dm.adapter.laws.MessageParameterProbes.portable
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** KF167/KF181: numeric result scale and immediate wrapper gates on both condition carriers. */
class NumericOperationGateAgreementTest {
    private data class Row(val id: String, val model: String, val classes: List<String>)

    @Test
    fun firstFilledScaleSummarizesEveryExpandedField() {
        val rows = buildList {
            for ((order, scales) in listOf("false" to listOf(0, 2), "true" to listOf(2, 0), "equal" to listOf(2, 2))) {
                val base = DocumentModelDraft.named("invoice-first-scale").locale("en_US")
                    .rootGroup("Invoice") { root -> root
                        .numberField("Whole") { it.fractionDigits(0) }
                        .numberField("Fractional") { it.fractionDigits(2) }
                        .group("Inputs") { inputs -> inputs
                            .numberField("First") { it.fractionDigits(scales[0]) }
                            .numberField("Second") { it.fractionDigits(scales[1]) } } }
                    .build().toJson()
                val invoice = EditSession.read(base).group("/Invoice")
                val expressions = listOf(
                    "fields" to Dsl.firstFilledNumber(invoice.field("Inputs/First"), invoice.field("Inputs/Second")),
                    "group" to Dsl.firstFilledNumber(BoundModel.ofJson(base).firstFilledGroup("/Invoice/Inputs")),
                )
                for ((form, operation) in expressions) for ((target, scale) in listOf("Whole" to 0, "Fractional" to 2)) {
                    val expected = if (scale == 2) emptyList() else listOf("MVK_INVALID_COMPARE_DEC_PLACES")
                    addAll(carriers(base, "/Invoice", target, "$order-$form-$scale", operation, expected))
                }
            }
        }
        agreement(rows, 24)
    }

    @Test
    fun wrappersJudgeTheImmediateOperandOnBothCarriers() {
        val base = PremiumProbes.premium()
        val premium = EditSession.read(base).group(PremiumProbes.ROOT)
        val field = premium.num("Base").asNumber()
        val rows = buildList {
            for ((name, wrap) in wrappers()) {
                for ((position, operand) in listOf("literal" to Dsl.number(2), "field" to field,
                    "arithmetic" to field.minus(2))) {
                    val expected = if (position == "literal") listOf("MVK_NO_CONST") else emptyList()
                    addAll(carriers(base, PremiumProbes.ROOT, "Total", "$name-$position", wrap(operand), expected))
                }
                addAll(carriers(base, PremiumProbes.ROOT, "Total", "$name-nested", wrap(Dsl.number(2)).plus(field),
                    listOf("MVK_NO_CONST")))
                // The typed numeric factory excludes text; the draft boundary is the input under test.
                val operation = "$name([Note])"
                add(Row("$name-text-rule", PremiumProbes.rule(base, "Total", "$operation == [Total]"), listOf("MVK_NO_NUMBER")))
                add(Row("$name-text-computation", PremiumProbes.computation(base, PremiumProbes.ROOT, "Total",
                    listOf(PremiumProbes.Alt(null, operation))), listOf("MVK_NO_NUMBER")))
            }
        }
        agreement(rows, 40)
    }

    private fun carriers(base: String, group: String, target: String, id: String, operation: NumberExpr,
        classes: List<String>): List<Row> {
        val owner = EditSession.read(base).group(group)
        val field = owner.num(target)
        val rule = owner.rule { it.name("ProbeRule").errorField(field).`when`(operation.eq(field.asNumber()))
            .code("NUMERIC_GATE").severity(Severity.ERROR).message(Dsl.en("Check the amount.")).build() }
        val computation = owner.computation(field) { it.name("ProbeComputation").calc(operation.expression())
            .message(Dsl.en("Calculate the amount.")).build() }
        return listOf(Row("$id-rule", ModelWriter.addRule(base, rule), classes),
            Row("$id-computation", ModelWriter.addComputation(base, computation), classes))
    }

    private fun wrappers(): List<Pair<String, (NumberExpr) -> NumberExpr>> = listOf(
        "Abs" to { Dsl.abs(it) }, "RoundUp" to { Dsl.roundUp(it) },
        "RoundDown" to { Dsl.roundDown(it) }, "RoundAccounting" to { Dsl.roundAccounting(it) },
    )

    private fun agreement(rows: List<Row>, count: Int) {
        assertEquals(count, rows.size)
        assertEquals(count, rows.map { it.id }.toSet().size)
        val stored = HarvestedModels.expand(com.fasterxml.jackson.databind.ObjectMapper().readTree(
            checkNotNull(javaClass.getResource("/checker-near-miss/numeric-operation-gates.json")).readText()))
            .filter { it["population"].asInt() == count }
        assertEquals(rows, stored.map { Row(it["id"].asText(), it["model"].asText(),
            it["classes"].map { code -> code.asText() }) }, "exact portable replay")
        val expected = rows.map { it.id to it.classes }
        assertEquals(expected, rows.map { it.id to kernel(it.model) }, "kernel predictions")
        assertEquals(expected, rows.map { it.id to portable(it.model) }, "portable classes")
    }
}
