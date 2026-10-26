package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A row past its group's capacity is outside every list reader's domain (KERNEL-FINDINGS KF58), so an over-capacity
 * cell, empty or filled, can never be the unavailable cell a reader stops at: the aggregates, the fill quantifiers,
 * the counts and a `Having`-filtered count all range over the in-capacity rows only. Each verdict below would change
 * if the fourth row were read; the three-row documents are the controls that show each rule can fire.
 *
 * Own domain (a claim's covers).
 */
class OverCapacityRowReadDiffTest {

    private val model: String = run {
        val session = EditSession.read(DocumentModelDraft.named("claim-over-capacity").locale("en_US")
            .rootGroup("Claim") { c -> c.stringField("Ref") { }
                .group("Covers") { v -> v.repeatable(3).numberField("Amount") { it.fractionDigits(0) }
                    .numberField("Bonus") { it.fractionDigits(0) } } }
            .build().toJson())
        val claim = session.group("/Claim")
        val bonus = claim.flatten("Covers*/Bonus")
        val rules: List<Pair<String, Condition>> = listOf(
            "Sum15" to Dsl.sum(bonus).eq(15),
            "Sum20" to Dsl.sum(bonus).eq(20),
            "Max" to Dsl.maxValue(bonus).lt(100),
            "NotAll" to Dsl.notAllFieldsFilled(bonus),
            "Count3" to Dsl.numberOfFilledFields(bonus).eq(3),
            "KeptCount" to Dsl.numberOfFilledFields(claim.flatten("Covers*/Bonus").having(session.group("/Claim/Covers").num("Amount").eq(7))).eq(1),
        )
        for ((code, condition) in rules) claim.addRule {
            it.name(code).code(code).errorField(claim.field("Ref"))
                .`when`(claim.str("Ref").filled().and(condition)).message(Dsl.en("Check the covers.")).build()
        }
        session.toJson()
    }

    private val documents = linkedMapOf(
        "a fourth row with an empty bonus" to """{"Claim":{"Ref":"C-1","Covers":[""" +
            """{"Amount":5,"Bonus":5},{"Amount":5,"Bonus":5},{"Amount":5,"Bonus":5},{"Amount":7}]}}""",
        "a fourth row with a bonus" to """{"Claim":{"Ref":"C-1","Covers":[""" +
            """{"Amount":5,"Bonus":5},{"Amount":5,"Bonus":5},{"Amount":5,"Bonus":5},{"Amount":7,"Bonus":5}]}}""",
        "three rows, the third without a bonus" to """{"Claim":{"Ref":"C-1","Covers":[""" +
            """{"Amount":5,"Bonus":5},{"Amount":5,"Bonus":5},{"Amount":7}]}}""",
        "three rows, the third kept with a bonus" to """{"Claim":{"Ref":"C-1","Covers":[""" +
            """{"Amount":5,"Bonus":5},{"Amount":5,"Bonus":5},{"Amount":7,"Bonus":5}]}}""",
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
    }

    /** The fourth row's bonus, empty or 5, changes no verdict: `Sum` stays 15, three cells are filled, none is
     *  missing, and the filter keeps no row although the fourth row's `Amount` is 7. */
    @Test
    fun theKernelReadsNoRowPastTheCapacity() {
        val expected = mapOf(
            "a fourth row with an empty bonus" to listOf("Count3", "Max", "Sum15"),
            "a fourth row with a bonus" to listOf("Count3", "Max", "Sum15"),
            "three rows, the third without a bonus" to listOf("Max", "NotAll"),
            "three rows, the third kept with a bonus" to listOf("Count3", "KeptCount", "Max", "Sum15"),
        )
        for (dynamic in listOf(true, false)) for ((name, document) in documents) {
            val authored = DocumentDifferential.kernelMessages(model, document, dynamic).map { it.code }
                .filterNot { it.startsWith("zuGrosse") }.sorted()
            assertEquals(expected.getValue(name), authored, "$name, dynamic=$dynamic")
        }
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }
}
