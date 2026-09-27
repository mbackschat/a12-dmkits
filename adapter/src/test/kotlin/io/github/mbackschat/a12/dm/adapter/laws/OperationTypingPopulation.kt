package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator

/** Fixed R3b-T0 witnesses; predictions and controls are in CONFORMANCE-PLAN's pre-run matrix. */
internal object OperationTypingPopulation {
    private val validator = RuleValidator()
    private val host = DocumentModelDraft.named("order-operation-types").locale("en_US")
        .rootGroup("Order") { root -> root
            .numberField("Total") { it.fractionDigits(2) }
            .numberField("Qty") { it.fractionDigits(0) }
            .stringField("Label") { }
            .booleanField("Approved") { }
            .confirmField("Consented") { }
            .dateField("Day") { it.format("yyyy-MM-dd") }
            .dateField("OtherDay") { it.format("dd.MM.yyyy") }
            .dateField("Month") { it.format("yyyy-MM") }
            .dateField("MonthOnly") { it.format("MM") }
            .stringField("LabelTarget") { }
            .numberField("QtyTarget") { it.fractionDigits(0) }
            .dateField("DayTarget") { it.format("yyyy-MM-dd") }
            .group("Items") { items -> items.repeatable(9)
                .numberField("Count") { it.fractionDigits(0) }
                .stringField("Label") { }
                .dateField("Day") { it.format("yyyy-MM-dd") }
                .dateField("OtherDay") { it.format("dd.MM.yyyy") }
                .dateField("Month") { it.format("yyyy-MM") } } }
        .build().toJson()

    fun rows(): List<ConditionCase> {
        val english = linkedMapOf(
            "[Label] == [Qty] + 1" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "[Qty] + 1 == [Label]" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "[Label] == {[Qty] + 1}" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "[Label] == [Qty] * 2" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "{[Label] + \"x\"} == [Qty]" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "[Qty] + 1 > [Label]" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "[Approved] == [Qty] + 1" to "MVK_INVALID_COMPARE_TO_YESNO",
            "[Consented] == [Qty] + 1" to "MVK_INVALID_COMPARE_TO_YES",
            "[Day] == [Qty] + 1" to "MVK_INVALID_COMPARE_TO_DATE",
            "[Qty] + 1 > 0" to null,
            "[Qty] + 1 == [Qty]" to null,
            "[Total] + 1 == [Qty]" to "MVK_INVALID_COMPARE_DEC_PLACES",
            "[Qty] * [Qty] == [Qty]" to null,
            "[Qty] / 2 == [Qty]" to "MVK_INVALID_COMPARE_DEC_PLACES",
            "RoundDown([Qty] / 2, 0) == [Qty]" to null,
            "[Label] + \"x\" == \"y\"" to null,
            "[Qty] + [Label] > 0" to "MVK_NO_STRING_OR_NUMBER_FOR_ADD",
            "[Label] * 2 > 0" to "MVK_NO_NUMBER_FOR_CALC",
            "Min([Day], [Month]) == [Day]" to "MVK_DATEFORMATS_NOT_COMPATIBLE",
            "Max([Month], [Day]) == [Day]" to "MVK_DATEFORMATS_NOT_COMPATIBLE",
            "Min([Day], [OtherDay]) == [Day]" to null,
            "Min([Day], [OtherDay]) == [Label]" to "MVK_INVALID_COMPARE_TO_DATE",
            "Min([Qty] + 1, [Qty]) > 0" to null,
            "Min([Day], [Qty] + 1) == [Day]" to "MVK_DATE_AND_NONDATE",
            "Min([Qty] + 1, [Day]) > 0" to "MVK_NOT_SORTABLE",
            "Min([Day], Max([Month], [Day])) == [Day]" to "MVK_DATEFORMATS_NOT_COMPATIBLE",
            "[Qty] + [Label] >" to "MVK_NO_STRING_OR_NUMBER_FOR_ADD",
            "[Qty] + [Label] +" to "MVK_NO_STRING_OR_NUMBER_FOR_ADD",
            "[Qty] + [Label] %" to "MVK_LEXER_STANDARD_ERROR",
            "[Qty] + [Label] > %" to "MVK_NO_STRING_OR_NUMBER_FOR_ADD",
            "Min([Day], [Month]) %" to "MVK_LEXER_STANDARD_ERROR",
            "Min([Day], [Month]) > %" to "MVK_DATEFORMATS_NOT_COMPATIBLE",
        )
        val german = english.filterKeys { it in setOf("[Label] == [Qty] + 1", "[Qty] + 1 > 0",
            "Min([Day], [Month]) == [Day]", "Min([Day], [OtherDay]) == [Day]") }
        val filters = linkedMapOf(
            "[Items/Count] + 1 > 0" to null,
            "[Items/Label] == [Items/Count] + 1" to "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING",
            "Min([Items/Day], [Items/Month]) == [Items/Day]" to "MVK_DATEFORMATS_NOT_COMPATIBLE",
            "Min([Items/Day], [Items/OtherDay]) == [Items/Day]" to null,
        ).mapKeys { (body, _) -> "Sum(Items*/Count Having $body) > 0" }
        val yearless = "Min([MonthOnly], [Month]) == [Month]"
        return rules(english, ConditionLanguage.EN) + rules(german, ConditionLanguage.DE) +
            rules(filters, ConditionLanguage.EN) + computations() +
            rules(mapOf(yearless to "MVK_DATEFORMATS_NOT_COMPATIBLE"), ConditionLanguage.EN) +
            rules(mapOf(yearless to null), ConditionLanguage.EN, ModelDocument.read(host).setBaseYear(2024).toJson(), "base-year:")
    }

    private fun rules(suffixes: Map<String, String?>, language: ConditionLanguage,
        source: String = host, prefixId: String = ""): List<ConditionCase> {
        val json = ModelDocument.read(source).setConditionLanguage(if (language == ConditionLanguage.EN) "en_US" else "de_DE").toJson()
        val model = BoundModel.ofJson(json)
        val total = model.num("/Order/Total")
        val probe = model.rule(model.group("/Order")).name("TypingProbe").errorField(total)
            .`when`(total.filled()).code("TYPING_PROBE").message(Dsl.en("Check order values")).build().draft(language)
        val prefix = probe.errorCondition() + if (language == ConditionLanguage.EN) " And " else " Und "
        return suffixes.map { (suffix, expected) ->
            ConditionCase("$prefixId$language:$suffix", validator.inject(json, probe.withErrorCondition(prefix + suffix)), expected)
        }
    }

    private fun computations(): List<ConditionCase> {
        val model = BoundModel.ofJson(host)
        val total = model.num("/Order/Total")
        val rows = listOf(
            Triple("LabelTarget", "[Qty] + 1", "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING"),
            Triple("QtyTarget", "[Qty] + 1", null),
            Triple("QtyTarget", "[Total] + 1", "MVK_INVALID_COMPARE_DEC_PLACES"),
            Triple("QtyTarget", "RoundDown([Total] + 1, 0)", null),
            Triple("DayTarget", "Min([Day], [Month])", "MVK_DATEFORMATS_NOT_COMPATIBLE"),
            Triple("DayTarget", "Min([Day], [OtherDay])", null),
        )
        return rows.map { (target, operation, expected) ->
            val source = model.field("/Order/${target.removeSuffix("Target")}")
            val probe = model.computation(model.field("/Order/$target")).name("TypingProbe")
                .commonPrecondition(total.filled()).calc(source.expression())
                .message(Dsl.en("Check order result")).build().draft()
            val draft = DraftComputation(probe.groupPath(), probe.name(), probe.computedFieldRelPath(),
                probe.commonPrecondition(), listOf(DraftComputation.Alternative(null, operation)),
                probe.errorCodesToSuppress(), probe.roundingMode(), probe.messages())
            ConditionCase("computation:$target:$operation", validator.inject(host, draft), expected)
        }
    }
}
