package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The frozen M5-RISK composition experiment in CONFORMANCE-PLAN; no production exceptions are fitted here. */
class CheckerCompositionSweepTest {
    @Test
    fun composedChildrenHaveOnlyLedgeredDifferences() {
        val rows = rows()
        assertEquals(320, rows.map { it.model }.distinct().size)
        assertEquals(64, rows.count { it.expected == null })
        assertModelLedger(rows, 320, "composed-children")
    }

    private enum class Wrapper {
        ABS, ROUND, MIN_LEFT, MAX_RIGHT;

        fun apply(child: NumberExpr): NumberExpr = when (this) {
            ABS -> Dsl.abs(child)
            ROUND -> Dsl.roundDown(child, 0)
            MIN_LEFT -> Dsl.min(child, Dsl.number(7))
            MAX_RIGHT -> Dsl.max(Dsl.number(11), child)
        }
    }

    private enum class Leaves(val expected: String?) {
        VALID(null), STRING("MVK_NO_NUMBER_FOR_CALC"), MISSING("MVK_INVALID_ENTITY"),
        STRING_THEN_MISSING("MVK_NO_NUMBER_FOR_CALC"), MISSING_THEN_STRING("MVK_INVALID_ENTITY");

        fun mutate(text: String): String {
            require(text.countOccurrences("[Left]") == 1 && text.countOccurrences("[Right]") == 1)
            return when (this) {
                VALID -> text
                STRING -> text.replace("[Left]", "[Label]")
                MISSING -> text.replace("[Left]", "[Missing]")
                STRING_THEN_MISSING -> text.replace("[Left]", "[Label]").replace("[Right]", "[Missing]")
                MISSING_THEN_STRING -> text.replace("[Left]", "[Missing]").replace("[Right]", "[Label]")
            }
        }
    }

    private fun rows(): List<ConditionCase> = buildList {
        val host = DocumentModelDraft.named("order-composed-children").locale("en_US")
            .rootGroup("Order") { it
                .numberField("Gate") { field -> field.fractionDigits(0) }
                .numberField("Left") { field -> field.fractionDigits(0) }
                .numberField("Right") { field -> field.fractionDigits(0) }
                .numberField("Result") { field -> field.fractionDigits(0) }
                .stringField("Label") { } }
            .build().toJson()
        val validator = RuleValidator()
        for (language in listOf(ConditionLanguage.EN, ConditionLanguage.DE)) {
            val json = ModelDocument.read(host)
                .setConditionLanguage(if (language == ConditionLanguage.EN) "en_US" else "de_DE").toJson()
            val model = BoundModel.ofJson(json)
            val gate = model.num("/Order/Gate")
            val left = model.num("/Order/Left")
            val right = model.num("/Order/Right")
            val result = model.num("/Order/Result")
            val base = Dsl.min(left.times(2), right.times(3))
            for (inner in Wrapper.entries) for (outer in Wrapper.entries) {
                val expression = outer.apply(inner.apply(base))
                val rule = model.rule(model.group("/Order")).name("CompositionProbe").errorField(gate)
                    .`when`(gate.filled().and(expression.gt(0))).code("COMPOSITION_PROBE")
                    .message(Dsl.en("Check order values")).build().draft(language)
                val computation = model.computation(result).name("CompositionProbe")
                    .`when`(gate.le(0)).calc(Dsl.number(0))
                    .`when`(gate.gt(0)).calc(expression)
                    .message(Dsl.en("Check order result")).build().draft(language)
                for (leaves in Leaves.entries) {
                    val id = "$language:$inner:$outer:$leaves"
                    add(ConditionCase("rule:$id", validator.inject(json,
                        rule.withErrorCondition(leaves.mutate(rule.errorCondition()))), leaves.expected))
                    val alternatives = computation.alternatives().mapIndexed { index, alternative ->
                        if (index == 0) alternative else DraftComputation.Alternative(alternative.precondition(),
                            leaves.mutate(alternative.operation()), alternative.toleranceRangeOp())
                    }
                    val mutated = DraftComputation(computation.groupPath(), computation.name(),
                        computation.computedFieldRelPath(), computation.commonPrecondition(), alternatives,
                        computation.errorCodesToSuppress(), computation.roundingMode(), computation.messages())
                    add(ConditionCase("computation:$id", validator.inject(json, mutated), leaves.expected))
                }
            }
        }
    }
}

private fun String.countOccurrences(value: String) = split(value).size - 1
