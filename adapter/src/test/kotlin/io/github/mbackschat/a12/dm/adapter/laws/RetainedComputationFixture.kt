package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl

internal object RetainedComputationFixture {
    fun modelJson(): String {
        val structure = DocumentModelDraft.named("order-clear-actions").locale("en_US")
            .rootGroup("Order") { order ->
                order.group("Lines") { lines ->
                    lines.repeatable(5)
                        .numberField("Candidate") { it.fractionDigits(0) }
                        .numberField("Amount") { it.fractionDigits(0).max("9") }
                }
                order.numberField("Numerator") { it.fractionDigits(0) }
                    .numberField("Divisor") { it.fractionDigits(0) }
                    .numberField("Quotient") { it.fractionDigits(2, 2) }
                    .group("IndexedLines") { lines ->
                        lines.repeatable(5)
                            .stringField("Code") { }
                            .numberField("Source") { it.fractionDigits(0) }
                            .numberField("Projection") { it.fractionDigits(0) }
                            .indexField("Code")
                    }
            }
            .build()
            .toJson()
        val session = EditSession.read(structure)
        val lines = session.group(LINES)
        val candidate = lines.num("Candidate")
        val amount = lines.num("Amount")
        lines.addComputation(amount) { computation ->
            computation.name("AmountProjection")
                .commonPrecondition(candidate.filled())
                .calc(candidate.value())
                .message(Dsl.en("Project the selected amount."))
                .build()
        }
        val order = session.group(ROOT)
        val numerator = order.num("Numerator")
        val divisor = order.num("Divisor")
        order.addComputation(order.num("Quotient")) { computation ->
            computation.name("QuotientProjection")
                .commonPrecondition(numerator.filled().and(divisor.filled()))
                .calc(Dsl.roundAccounting(numerator.asNumber().dividedBy(divisor.asNumber()), 2).expression())
                .message(Dsl.en("Calculate the rounded quotient."))
                .build()
        }
        val indexedLines = session.group(INDEXED_LINES)
        indexedLines.addComputation(indexedLines.num("Projection")) { computation ->
            computation.name("IndexedProjection")
                .calc(indexedLines.num("Source").value())
                .message(Dsl.en("Project the indexed source."))
                .build()
        }
        return session.validate().toJson()
    }

    const val ROOT = "/Order"
    const val LINES = "$ROOT/Lines"
    const val CANDIDATE = "$LINES/Candidate"
    const val AMOUNT = "$LINES/Amount"
    const val INDEXED_LINES = "$ROOT/IndexedLines"
    val CELL = listOf(1, 2, 1)
}
