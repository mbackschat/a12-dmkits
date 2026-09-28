package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator

/** The finite role and completion population of the archived CHECKER-RECONSTRUCTION-PROPOSAL, R3b-N. */
internal object NumericNamePopulation {
    private val host = DocumentModelDraft.named("order-numeric-names").locale("en_US")
        .rootGroup("Order") { root -> root
            .numberField("Total") { it.fractionDigits(0) }
            .numberField("Qty") { it.fractionDigits(0) }
            .dateField("Placed") { it.format("yyyy-MM-dd") }
            .group("Items") { items -> items.repeatable(9).indexField("Code")
                .stringField("Code") { }
                .numberField("Count") { it.fractionDigits(0) } } }
        .build().toJson()
    private val validator = RuleValidator()
    private const val ENTITY = "MVK_INVALID_ENTITY"
    private const val TOKEN = "MVK_UNEXPECTED_TOKEN"

    fun rows(): List<ConditionCase> {
        val english = linkedMapOf(
            "FieldFilled(5)" to ENTITY,
            "FieldFilled(05)" to ENTITY,
            "AbsValue(5) > 0" to ENTITY,
            "MinValue(1 + Items*/Count) > 1" to ENTITY,
            "[5] > 0" to ENTITY,
            "FieldFilled(Items*/5)" to ENTITY,
            "FieldFilled(5/)" to TOKEN,
            "FieldFilled(\$5/)" to ENTITY,
            "FieldFilled(5.)" to TOKEN,
            "FieldFilled(5 %)" to "MVK_LEXER_STANDARD_ERROR",
            "FieldFilled(5) %" to ENTITY,
            "Sum(Items/Count For 5) > 0" to ENTITY,
            "Sum(Items/Count For 5.5) > 0" to TOKEN,
            "Sum(Items/Count For \"5\") > 0" to "MVK_PARAMSIZE_INVALIDN",
            "[Total] > 5" to null,
            "RoundUpValue(Total, 5) > 0" to null,
            "CustomCondition 5" to null,
            "Date(5, \"1\", \"2020\") > [Placed]" to ENTITY,
            "Date(\"5\", \"1\", \"2020\") > [Placed]" to null,
            "FieldFilled(5/Count)" to ENTITY,
            "FieldFilled(5.5)" to TOKEN,
        )
        val german = linkedMapOf(
            "FeldAngegeben(5)" to ENTITY,
            "FeldAngegeben(05)" to ENTITY,
            "AufrundenWert(Total, 5) > 0" to null,
            "ApplikationsBedingung 5" to null,
        )
        return rules(english, ConditionLanguage.EN) + rules(german, ConditionLanguage.DE) + computations()
    }

    private fun rules(suffixes: Map<String, String?>, language: ConditionLanguage): List<ConditionCase> {
        val json = ModelDocument.read(host).setConditionLanguage(if (language == ConditionLanguage.EN) "en_US" else "de_DE").toJson()
        val model = BoundModel.ofJson(json)
        val total = model.num("/Order/Total")
        val probe = model.rule(model.group("/Order")).name("NumericProbe").errorField(total)
            .`when`(total.filled()).code("NUMERIC_PROBE").message(Dsl.en("Check order values")).build().draft(language)
        val prefix = probe.errorCondition() + if (language == ConditionLanguage.EN) " And " else " Und "
        return suffixes.map { (suffix, expected) ->
            ConditionCase("$language:$suffix", validator.inject(json, probe.withErrorCondition(prefix + suffix)), expected)
        }
    }

    private fun computations(): List<ConditionCase> {
        val model = BoundModel.ofJson(host)
        val total = model.num("/Order/Total")
        val probe = model.computation(model.num("/Order/Qty")).name("NumericProbe")
            .commonPrecondition(total.filled()).calc(total.plus(5))
            .message(Dsl.en("Check order total")).build().draft()
        return listOf("AbsValue(5)" to ENTITY, "[Total] + 5" to null).map { (operation, expected) ->
            val draft = DraftComputation(probe.groupPath(), probe.name(), probe.computedFieldRelPath(),
                probe.commonPrecondition(), listOf(DraftComputation.Alternative(null, operation)),
                probe.errorCodesToSuppress(), probe.roundingMode(), probe.messages())
            ConditionCase("computation:$operation", validator.inject(host, draft), expected)
        }
    }
}
