package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — the `$` capture source is the ITERATING INSTANCE, and that instance may be a
 * repeatable COMPUTATION target rather than a rule. a12-kernel-lean's `SPEC-2026-09-03-06` corrects a clause
 * that described `$` as binding "the captured outer repetition environment of the iterating rule"; read
 * rule-only, a reimplementation either refuses this legal model or leaves the capture unbound.
 *
 * **The carrier.** `Higher` is a repeatable Number in the same group the filter iterates, computed once per row
 * as the `Sum` of every OTHER row's `Amount` whose `Rank` exceeds THIS row's — `[Lines/Rank] > [$Lines/Rank]`.
 * So each row's answer is decided by the row the computation is currently evaluating, which is exactly the
 * binding under test.
 *
 * **Why exact per-row vectors rather than a threshold.** Each document refutes the reversed marker reading on
 * its own: with ascending ranks the correct reading answers `50, 30, 0` where the reversed one predicts
 * `0, 10, 30`, and with rotated ranks `0, 40, 10` against `50, 0, 20`. A threshold comparison would fire at
 * either candidate. An unbound capture predicts no outcome at all on every row, which neither vector shows.
 *
 * **The rotated document is the control that the answer follows the RANK and not the row position.** Both
 * documents carry the identical amount column in the identical row order; only the rank column is permuted, and
 * the answer permutes with it rather than with the position.
 *
 * Own domain (e-commerce order lines).
 */
// One instance per CLASS: model JSON, the loaded definition and the kernel Java engine are fields, and this
// Gradle task also carries timing assertions — per-method lifecycle would redo codegen for every case.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorrelatedComputationCaptureDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val RANK = "/Order/Lines/Rank"
        const val AMOUNT = "/Order/Lines/Amount"
        const val HIGHER = "/Order/Lines/Higher"
    }

    private val base = DocumentModelDraft.named("order-line-correlation").locale("en_US")
        .rootGroup("Order") { order ->
            order.stringField("Reference") { }
            order.group("Lines") { lines ->
                lines.repeatable(50)
                lines.numberField("Rank") { it.fractionDigits(0) }
                lines.numberField("Amount") { it.fractionDigits(0) }
                lines.numberField("Higher") { it.fractionDigits(0) }
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(base)

    /**
     * `Higher = Sum(Lines*` flatten of `Amount` filtered to rows whose `Rank` beats the CAPTURED row's `)`.
     * The target is repeatable, so the kernel evaluates one instance per `Lines` row and `$` binds that row.
     */
    private val computation = bound.computation(bound.num(HIGHER)).name("HigherComp")
        .group(bound.group(LINES))
        .calc(
            Dsl.sum(
                bound.flatten("$LINES*/Amount")
                    .having(bound.num(RANK).gt(bound.num(RANK).outer().asNumber())),
            ).expression(),
        )
        .message(Dsl.en("Amounts ranked above this line."))
        .build()

    private val modelJson = TypedRules.inject(base, computation)
    private val definition = ModelLoader.load(modelJson)
    private val staticEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    @Test
    fun theProbeModelIsAdmitted() =
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(modelJson),
            "the STATIC half: a per-row computed target carrying a \$-correlated filter is legal, not merely " +
                "tolerated at runtime")

    /** Ascending ranks — the reversed marker reading would answer `0, 10, 30` here. */
    @Test
    fun anAscendingRankColumnBindsTheCaptureToTheComputedRow() = assertEquals(
        listOf("/Order[1]/Lines[1]/Higher|50", "/Order[1]/Lines[2]/Higher|30", "/Order[1]/Lines[3]/Higher|0"),
        outcomes(ranks = listOf("1", "2", "3")),
        "each row sums the amounts of the rows ranked above IT",
    )

    /** The identical amount column under a permuted rank column — the reversed reading predicts `50, 0, 20`. */
    @Test
    fun aRotatedRankColumnPermutesTheAnswerWithTheRankRatherThanThePosition() = assertEquals(
        listOf("/Order[1]/Lines[1]/Higher|0", "/Order[1]/Lines[2]/Higher|40", "/Order[1]/Lines[3]/Higher|10"),
        outcomes(ranks = listOf("3", "1", "2")),
        "the answer follows the captured row's rank, not its position in the list",
    )

    // ---- the instrument ----

    /** Each `Higher` instance as `pointer|value`, asserted equal on both codegen strategies and the interpreter. */
    private fun outcomes(ranks: List<String>): List<String> {
        val amounts = listOf("10", "20", "30")
        val dynamic = RuntimeLaws.compute(modelJson, kernelDocument(ranks, amounts))
            .filter { it.path() == HIGHER }.map { "${it.pointer()}|${signature(it)}" }.sorted()
        // The INDEXED signature leg, not `computeOutcomesSorted`: this target is repeatable, and the plain
        // outcome list renders a bare path that cannot tell three rows apart.
        val static = staticEngine.computationSignaturesSorted(staticEngine.instance(kernelDocument(ranks, amounts)))
            .filter { it.substringBefore('|').endsWith("Higher") }
            .map { it.replace("|VALUE|", "|") }.sorted()
        val interpreter = DmInterpreter(definition).compute(interpreterDocument(ranks, amounts))
            .filter { it.path == HIGHER }
            .map { c ->
                val value = when (c.outcome) {
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED|${c.value}"
                    ComputeOutcome.VALUE -> c.value
                }
                "${formatPointer(c.path, c.indices)}|$value"
            }.sorted()
        assertEquals(dynamic, static, "generated Java vs normative dynamic-Groovy")
        assertEquals(dynamic, interpreter, "interpreter vs normative dynamic-Groovy")
        return dynamic
    }

    private fun signature(c: RuntimeLaws.Computed): String = when {
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED|${c.value().orElse("")}"
        else -> c.value().orElse("")
    }

    private fun kernelDocument(ranks: List<String>, amounts: List<String>): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group(ORDER, 1).field("$ORDER/Reference", "SO-4471", 1, 1)
        ranks.forEachIndexed { i, rank ->
            val r = i + 1
            doc.group(LINES, 1, r).field(RANK, rank, 1, r, 1).field(AMOUNT, amounts[i], 1, r, 1)
                .field(HIGHER, "999", 1, r, 1)   // seeded, so a kernel clear is reported rather than absent
        }
        return doc
    }

    private fun interpreterDocument(ranks: List<String>, amounts: List<String>): Document {
        val doc = definition.newDocument().group(ORDER, 1).field("$ORDER/Reference", "SO-4471", 1, 1)
        ranks.forEachIndexed { i, rank ->
            val r = i + 1
            doc.group(LINES, 1, r).field(RANK, rank, 1, r, 1).field(AMOUNT, amounts[i], 1, r, 1)
                .field(HIGHER, "999", 1, r, 1)
        }
        return doc
    }
}
