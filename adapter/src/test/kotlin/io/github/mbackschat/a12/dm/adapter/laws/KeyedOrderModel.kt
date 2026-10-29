package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator

/**
 * An order's lines and the warehouse picks that serve them, each keyed by its own number: the carrier of the
 * keyed-operand differentials (KERNEL-FINDINGS KF605). One builder, so every family is measured on the same model.
 */
internal object KeyedOrderModel {
    val json: String = DocumentModelDraft.named("order-keyed-gates").locale("en_US")
        .rootGroup("Order") { o -> o
            .numberField("Total") { n -> n.fractionDigits(0) }
            .group("Lines") { l -> l.repeatable(10).indexField("LineNo")
                .stringField("LineNo") { }
                .stringField("Sku") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .dateField("ShipOn") { d -> d.format("yyyy-MM-dd") }
                .stringField("RawRef") { s -> s.noValueValidation().lineBreaks() }
                .numberField("Total") { n -> n.fractionDigits(0) }
                .group("Boxes") { b -> b.stringField("Tag") { } } }
            .group("Notes") { n -> n.repeatable(10)
                .stringField("Text") { } }
            .group("Picks") { p -> p.repeatable(10).indexField("Ref")
                .stringField("Ref") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .numberField("Price") { n -> n.fractionDigits(2) }
                .dateField("ShipOn") { d -> d.format("dd.MM.yyyy") }
                .dateField("ShipIso") { d -> d.format("yyyy-MM-dd") }
                .dateRangeField("Window") { it.format("yyyy-MM-dd").separator("/") }
                .stringField("RawNote") { s -> s.noValueValidation().lineBreaks() } } }
        .build().toJson()

    private val validator = RuleValidator()

    /** A12's classes and the checker's, each sorted, for one rule or computation declared at `/Order`. */
    fun judge(errorField: String, condition: String, computed: Boolean = false): Pair<List<String>, List<String>> {
        val (result, injected) = if (computed) {
            val draft = DraftComputation("/Order", "KeyedProbe", "../$errorField", null,
                listOf(DraftComputation.Alternative(null, condition, null)), listOf())
            validator.check(json, draft) to validator.inject(json, draft)
        } else {
            val draft = DraftRule("/Order", "KeyedProbe", "../$errorField", condition, "PROBE", "ERROR")
            validator.check(json, draft) to validator.inject(json, draft)
        }
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = rejectingCodes(injected)
        return a12.sorted() to ours.sorted()
    }
}
