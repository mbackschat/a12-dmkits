package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whether `NumberOfValueInFields` over a Boolean or Confirm column consumes partial relevance as the Number column
 * does (a12-kernel-lean SPEC-2026-10-06-02, which holds it from source): the same nine relevance selections as
 * [KERNEL-FINDINGS KF209], over a two-row column, for a Boolean `True` count, a Boolean `False` count and a Confirm
 * count. Every row pins the exact fired code set on both kernel strategies and the interpreter.
 *
 * Own domain (an e-commerce order whose items carry gift-wrap and terms flags).
 */
class PartialBooleanValueCountDiffTest {

    private val json = DocumentModelDraft.named("order-flags").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Ack") { }
                .group("Items") {
                    it.repeatable(50).booleanField("Wrapped") { }.booleanField("Returned") { }.confirmField("Agreed") { }
                }
        }.build().toJson()

    private val model = run {
        val bound = BoundModel.ofJson(json)
        val order = bound.group(ORDER)
        val ack = bound.str(ACK)
        fun rule(code: String, count: io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr) =
            bound.rule(order).name(code).errorField(ack).`when`(ack.filled().and(count.gt(0)))
                .code(code).severity(Severity.ERROR).message(Dsl.en("value count")).build()
        listOf(
            rule(WRAPPED, Dsl.numberOfValueInFields(true, bound.flatten("$ITEMS*/Wrapped"))),
            rule(RETURNED, Dsl.numberOfValueInFields(false, bound.flatten("$ITEMS*/Returned"))),
            rule(AGREED, Dsl.numberOfValueInFields(true, bound.flatten("$ITEMS*/Agreed"))),
        ).fold(json) { modelJson, rule -> TypedRules.inject(modelJson, rule) }
    }
    private val def = ModelLoader.load(model)
    private val engine by lazy { RuntimeLaws.kernelJavaEngine(model) }

    @Test
    fun theBooleanAndConfirmCountsSelectTheValueListExtentGate() {
        val selections = listOf(
            "field wildcard alone" to wildcard(1, 0, 1),
            "field wildcard + concrete group rows" to wildcard(1, 0, 1) + concreteRows,
            "field wildcard + a concrete identifier for the same field" to wildcard(1, 0, 1) + wildcard(1, 1, 1),
            "concrete field identifier + wildcard group" to wildcard(1, 1, 1) + Sel(ITEMS, listOf(0, 0)),
            "all-semantic field wildcard + concrete group rows" to wildcard(0, 0, 0) + concreteRows,
            "field wildcard + physical-root concrete group rows" to wildcard(1, 0, 1) + physicalRows,
            "both concrete group rows only" to concreteRows,
            "row 1 only" to listOf(concreteRows[0]),
            "row 2 only" to listOf(concreteRows[1]),
        )
        val all = sortedSetOf(WRAPPED, RETURNED, AGREED)
        val expected = List(6) { all } + List(3) { sortedSetOf<String>() }
        assertEquals(
            selections.mapIndexed { i, (label, _) -> "$label | ${expected[i]}" },
            selections.map { (label, selection) -> "$label | ${verdicts(selection)}" },
        )
    }

    /** The control: with every cell relevant each count fires, so the silences above are the relevance's. */
    @Test
    fun everyCountReachesItsColumnUnderFullRelevance() {
        assertEquals(setOf(WRAPPED, RETURNED, AGREED), verdicts(wildcard(0, 0, 0) + Sel(ITEMS, listOf(0, 0))))
    }

    /** The fired codes, asserted equal across dynamic Groovy, generated Java and the interpreter. */
    private fun verdicts(selection: List<Sel>): Set<String> {
        val kDoc = RuntimeLaws.doc().group(ORDER, 1).field(ACK, "X", 1, 1)
        val iDoc = def.newDocument().group(ORDER, 1).field(ACK, "X", 1, 1)
        for (row in 1..2) {
            kDoc.group(ITEMS, 1, row).field(WRAPPED_PATH, "true", 1, row, 1).field(RETURNED_PATH, "false", 1, row, 1)
                .field(AGREED_PATH, "true", 1, row, 1)
            iDoc.group(ITEMS, 1, row).field(WRAPPED_PATH, "true", 1, row, 1).field(RETURNED_PATH, "false", 1, row, 1)
                .field(AGREED_PATH, "true", 1, row, 1)
        }
        val relevant = selection + Sel(ACK, listOf(0, 0))
        val kRelevant = relevant.map { Relevant.of(it.path, *it.reps.toIntArray()) }.toSet()
        val iRelevant = relevant.map { RelevantEntity(it.path, it.reps) }.toSet()
        val dynamic = RuntimeLaws.messagesPart(model, kDoc, kRelevant).map { it.code() }.filter { it in CODES }.toSortedSet()
        val static = engine.messagesPart(kDoc, kRelevant).map { it.code() }.filter { it in CODES }.toSortedSet()
        val interpreter = DmInterpreter(def).validatePart(iDoc, iRelevant).messages.map { it.code }
            .filter { it in CODES }.toSortedSet()
        assertEquals(dynamic, static, "dynamic Groovy vs generated Java")
        assertEquals(dynamic, interpreter, "kernel vs interpreter")
        return dynamic
    }

    private data class Sel(val path: String, val reps: List<Int>)

    /** The same relevance coordinates on each of the three counted columns. */
    private fun wildcard(vararg reps: Int) =
        listOf(WRAPPED_PATH, RETURNED_PATH, AGREED_PATH).map { Sel(it, reps.toList()) }

    private val concreteRows = listOf(Sel(ITEMS, listOf(0, 1)), Sel(ITEMS, listOf(0, 2)))
    private val physicalRows = listOf(Sel(ITEMS, listOf(1, 1)), Sel(ITEMS, listOf(1, 2)))

    private companion object {
        const val ORDER = "/Order"
        const val ACK = "/Order/Ack"
        const val ITEMS = "/Order/Items"
        const val WRAPPED_PATH = "/Order/Items/Wrapped"
        const val RETURNED_PATH = "/Order/Items/Returned"
        const val AGREED_PATH = "/Order/Items/Agreed"
        const val WRAPPED = "NVW"
        const val RETURNED = "NVR"
        const val AGREED = "NVA"
        val CODES = setOf(WRAPPED, RETURNED, AGREED)
    }
}
