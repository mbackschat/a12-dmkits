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
        return session.validate().toJson()
    }

    const val ROOT = "/Order"
    const val LINES = "$ROOT/Lines"
    const val CANDIDATE = "$LINES/Candidate"
    const val AMOUNT = "$LINES/Amount"
    val CELL = listOf(1, 2, 1)
}
