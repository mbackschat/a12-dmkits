package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule

/** DG94's action-order discriminators, shared by the kernel trace and O1 (CONFORMANCE-PLAN, R3b-F). */
object FieldReadPopulation {
    val host: String = DocumentModelDraft.named("order-field-reads").locale("en_US")
        .rootGroup("Order") { root -> root
            .numberField("Total") { it.fractionDigits(0) }
            .group("Items") { items -> items.repeatable(9)
                .numberField("Count") { it.fractionDigits(0) }
                .stringField("Raw") { it.lineBreaks().noValueValidation() }
                .dateField("Estimate") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) } }
            .group("Shipping") { it.numberField("Cost") { n -> n.fractionDigits(0) } } }
        .build().toJson()

    private val model = BoundModel.ofJson(host)
    private val total = model.num("/Order/Total")
    private val prefix = RuntimeLaws.render("/Order", total.filled()) + " And "

    data class Row(val rule: DraftRule, val expected: String?)

    fun rows(): List<Row> {
        fun filter(body: String) = "Sum(Items*/Count Having $body) > 0"
        val star = "MVK_NO_WILDCARDS_ALLOWED"
        val group = "MVK_NO_GROUPS_ALLOWED"
        val raw = "MVK_INVALID_RAW_TYPE"
        val suffixes = linkedMapOf(
            "[Total] > 0" to null,
            "[Items*/Count] > 0" to star,
            "[Total] == [Items*/Count]" to star,
            filter("[Items/Count] > 0") to null,
            filter("[Items*/Count] > 0") to star,
            filter("Abs([Items*/Count]) > 0") to star,
            filter("[\$Items*/Count] > 0") to star,
            "Sum(Items*/Count Having [Items*/Count] >" to star,
            "Sum(Items*/Count Having [Items*/Count" to "MVK_EXPECTED_TOKEN_NOT_FOUND",
            "] [Items*/Count] > 0" to "MVK_UNEXPECTED_TOKEN",
            "[Items*/Count] %" to "MVK_LEXER_STANDARD_ERROR",
            "[Items*/Count %] > 0" to "MVK_LEXER_STANDARD_ERROR",
            filter("[Items*] > 0") to group,
            filter("[Items*/Missing] > 0") to "MVK_INVALID_ENTITY",
            filter("[Shipping*/Cost] > 0") to "MVK_INVALID_WILDCARD",
            filter("[Items*/Raw] == \"x\"") to star,
            filter("[Items/Raw] == \"x\"") to raw,
            filter("[Items*/Estimate] < Today") to star,
            filter("[Items/Estimate] < Today") to "MVK_INVALID_DATE_TYPE",
            filter("[Shipping] > 0") to group,
            filter("[Items/Raw] == [Items*/Raw]") to raw,
            filter("[Items*/Raw] == [Items/Raw]") to star,
        )
        return rowsOf(suffixes)
    }

    fun boundaryRows(): List<Row> {
        val star = "MVK_NO_WILDCARDS_ALLOWED"
        val lexer = "MVK_LEXER_STANDARD_ERROR"
        return rowsOf(linkedMapOf(
            "[Items*/Count]" to star,
            "[Items*/Count] %" to lexer,
            "[Items*/Count]   %" to lexer,
            "[Items*/Count] > %" to star,
            "[Items*/Count] ) %" to star,
            "[Items*/Count] ;; marker\n%" to star,
            "[Total] %" to lexer,
            "[Total] > 0" to null,
        ))
    }

    private fun rowsOf(suffixes: Map<String, String?>): List<Row> {
        val probe = model.rule(model.group("/Order")).name("ReadProbe").errorField(total)
            .`when`(total.filled()).code("READ_PROBE")
            .message(io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en("Check order values")).build().draft()
        return suffixes.map { (suffix, expected) -> Row(probe.withErrorCondition(prefix + suffix), expected) }
    }
}
