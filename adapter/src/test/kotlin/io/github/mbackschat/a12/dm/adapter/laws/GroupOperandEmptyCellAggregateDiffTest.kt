package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — **what an EMPTY cell does to each of the four value aggregates over a group
 * operand**, [IG136] item (f).
 *
 * **Why one measurement did not cover four operators.** `starVals` feeds `Sum`, `MinValue`, `MaxValue` and
 * `NumberOfDifferentValues`, and only `Sum` was measured ([IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)'s
 * sixth instance). For `Sum` the empty-cell question is **invariant** — dropping a cell and adding zero are the
 * same thing — so that row could not distinguish "empties are dropped" from "the numeric empty is substituted",
 * and the other three are exactly where the two readings diverge. That makes `Sum` the wrong operator to
 * generalize from, and it is carried below as a CONTROL rather than as evidence.
 *
 * **Each of the other three needs its own document to discriminate, and one of them needs a negative value.**
 *
 * | aggregate | document | drop-empties | substitute-zero |
 * |---|---|---|---|
 * | `MinValue` | one cell `7`, one empty | `7` | `0` |
 * | `MaxValue` | one cell `-3`, one empty | `-3` | `0` |
 * | `NumberOfDifferentValues` | one cell `7`, one empty | `1` distinct | `2` distinct |
 * | `Sum` (control) | one cell `7`, one empty | `7` | `7` |
 *
 * `MaxValue` is the one that forced a signed declaration: with a positive value both readings answer the same
 * number, so a `max` over an all-positive selection cannot separate them at all. That is the same shape as the
 * one-nested-row and the `>=`-bar traps this class keeps producing — a fixture on which the two candidate
 * behaviours coincide.
 *
 * Every aggregate is authored through the typed surface, which reaches the group form here
 * (`Dsl.sum`/`minValue`/`maxValue`/`numberOfDifferentValues` take an `AggregateOperand`, and `GroupListRef` is
 * one) — unlike the DATE extrema and the value-list quantifiers
 * ([DG35](../../../../../../../../../../../docs/DEV-API-GAPS.md)).
 */
class GroupOperandEmptyCellAggregateDiffTest {

    private companion object {
        const val REF = "/Order/Reference"
        const val BILLING = "/Order/Billing"
        const val FIRST = "/Order/Billing/First"
        const val SECOND = "/Order/Billing/Second"
        const val CODE = "GECA"
    }

    /**
     * A non-repeatable group of two SIGNED same-scale Numbers. Signed because `MaxValue` cannot discriminate
     * otherwise; same scale and same kind so no comparability gate can be the answer; non-repeatable because that
     * is the shape whose operand is legally spelled unstarred.
     */
    private val base = DocumentModelDraft.named("order").locale("en_US")
        .rootGroup("Order") { g ->
            g.stringField("Reference") { }
            g.group("Billing") { b ->
                b.numberField("First") { it.fractionDigits(0, 0).min("-999").max("999") }
                b.numberField("Second") { it.fractionDigits(0, 0).min("-999").max("999") }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    private fun billing() = m.flattenGroup(BILLING)

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    private fun assertAdmitted(codes: List<String>, msg: String) =
        assertTrue(codes.none { it.startsWith("MVK_") }) {
            "$msg — the kernel REJECTED the shape (${codes.filter { it.startsWith("MVK_") }}), so this row would " +
                "compare two silences while reading as agreement"
        }

    /**
     * The full `errorPath|type` signature of every message carrying [CODE], sorted — NOT a boolean.
     *
     * A boolean makes a message of the wrong TYPE read as agreement, and that is not hypothetical: the ninth
     * instance of this defect class shipped green under a boolean lock written in the same commit as its own fix,
     * and the tenth and thirteenth were both found by widening a projection rather than by adding a case. The
     * kernel's own verdict is still asserted as a boolean (`expected`), because that is what each row's document
     * is designed to produce; what is compared ACROSS engines is the signature.
     */
    private fun signature(messages: List<RuntimeLaws.Msg>): List<String> =
        messages.filter { it.code() == CODE }.map { "${it.field()}|${it.type().name}" }.sorted()

    private fun agreeFires(condition: String, expected: Boolean, msg: String, build: (Both) -> Unit) {
        val kModel = RuntimeLaws.withRule(base, "/Order", "R", "../Reference", CODE, condition)
        val def = ModelLoader.load(kModel)

        val dynamic = Both(RuntimeLaws.doc(), def.newDocument()).also(build)
        val kernelMessages = RuntimeLaws.messages(kModel, dynamic.kDoc)
        assertAdmitted(kernelMessages.map { it.code() }, msg)
        val groovy = signature(kernelMessages)

        val staticDoc = Both(RuntimeLaws.doc(), def.newDocument()).also(build).kDoc
        val javaStatic = signature(
            RuntimeLaws.kernelJavaEngine(kModel).messagesModern(staticDoc, null, java.util.Locale.US))

        val interpreter = DmInterpreter(def).validateFull(dynamic.iDoc).filter { it.code == CODE }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }
            .sorted()

        assertEquals(expected, groovy.isNotEmpty(), "$msg — the kernel groovy-dynamic verdict")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interpreter, "$msg — kernel vs INTERPRETER")
    }

    /** `First` holds 7 and `Second` is EMPTY — the document for min, distinct-count, and the Sum control. */
    private fun sevenAndAnEmptyCell(b: Both) {
        b.group("/Order", 1).field(REF, "O-1", 1, 1).field(FIRST, "7", 1, 1, 1)
    }

    /** `First` holds -3 and `Second` is EMPTY — the only shape on which `MaxValue` can discriminate. */
    private fun minusThreeAndAnEmptyCell(b: Both) {
        b.group("/Order", 1).field(REF, "O-1", 1, 1).field(FIRST, "-3", 1, 1, 1)
    }

    private fun guarded(condition: io.github.mbackschat.a12.dm.rulekit.dsl.Condition): String =
        DslRenderer().render(m.field(REF).filled().and(condition))

    /** `MinValue` over `{7, empty}` is 7 if the empty is dropped and 0 if it is substituted. */
    @Test
    fun theMinimumIgnoresAnEmptyCell() =
        agreeFires(guarded(Dsl.minValue(billing()).gt(5)), expected = true,
            msg = "MinValue{7, empty} > 5 — dropped gives 7, substituted gives 0", build = ::sevenAndAnEmptyCell)

    /** The paired negative, so the row above is a statement about the value and not about evaluating at all. */
    @Test
    fun theMinimumIsStillTheFilledValue() =
        agreeFires(guarded(Dsl.minValue(billing()).gt(8)), expected = false,
            msg = "MinValue{7, empty} does not exceed 8", build = ::sevenAndAnEmptyCell)

    /** `MaxValue` over `{-3, empty}` is -3 if the empty is dropped and 0 if it is substituted. */
    @Test
    fun theMaximumIgnoresAnEmptyCell() =
        agreeFires(guarded(Dsl.maxValue(billing()).lt(-1)), expected = true,
            msg = "MaxValue{-3, empty} < -1 — dropped gives -3, substituted gives 0",
            build = ::minusThreeAndAnEmptyCell)

    /** `NumberOfDifferentValues` over `{7, empty}` counts 1 if the empty is dropped and 2 if it becomes a zero. */
    @Test
    fun theDistinctCountIgnoresAnEmptyCell() =
        agreeFires(guarded(Dsl.numberOfDifferentValues(billing()).lt(2)), expected = true,
            msg = "NumberOfDifferentValues{7, empty} < 2 — dropped counts 1, substituted counts 2",
            build = ::sevenAndAnEmptyCell)

    /**
     * The `Sum` CONTROL, and it is deliberately labelled as one: dropping an empty cell and adding zero are the
     * same operation, so this row agrees under BOTH readings and is therefore evidence that the fixture evaluates
     * — never evidence about empty cells. Recorded because the whole point of this file is that the previously
     * measured `Sum` row was read as if it were the latter.
     */
    @Test
    fun theSumControlCannotDiscriminateAndIsCarriedAsOne() =
        agreeFires(guarded(Dsl.sum(billing()).gt(5)), expected = true,
            msg = "Sum{7, empty} > 5 — invariant between the two readings", build = ::sevenAndAnEmptyCell)

    /** Both cells filled and distinct, so the distinct-count row above cannot mean "the count is always 1". */
    @Test
    fun theDistinctCountStillSeesTwoFilledValues() =
        agreeFires(guarded(Dsl.numberOfDifferentValues(billing()).lt(2)), expected = false,
            msg = "NumberOfDifferentValues{7, 9} is 2, so it is not below 2") { b ->
            b.group("/Order", 1).field(REF, "O-1", 1, 1)
                .field(FIRST, "7", 1, 1, 1).field(SECOND, "9", 1, 1, 1)
        }
}
