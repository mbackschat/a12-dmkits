package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — a value aggregate over a
 * **GROUP star**: `Sum(Lines*)` (the star on the GROUP, no field segment — the IG56 E2 second value-string
 * minimal, a corpus `RoundAccounting(Sum(NumbersSum*), 2)`). The kernel expands a group operand to its
 * contained FIELDS (the `ExpandService` behavior the fill quantifiers already had — IF49/IF51) and feeds
 * their cells to the combiner; the interpreter read cells at the GROUP path itself — no field declared there —
 * so `Sum` computed the empty-selection 0 (`kernel VALUE|617` vs `interp VALUE|0`, the sweep's kind-equal
 * value-string class).
 *
 * The multi-field and NESTED-subgroup arms pin the expansion's extent from the kernel; the aggregate siblings
 * (`MinValue`/`MaxValue`/`NumberOfDifferentValues`), the malformed-cell poison, the empty group, and a
 * validation-side rule (the shared `starOperandVals` path) ride the same chokepoint.
 */
class GroupStarValueAggregateDiffTest {

    private companion object {
        const val TOTAL = "/Invoice/Total"
        const val GATE = "/Invoice/Reference"
        const val LINES = "/Invoice/Lines"
        const val AMOUNT = "/Invoice/Lines/Amount"
        const val CHARGES = "/Invoice/Charges"
        const val FEE = "/Invoice/Charges/Fee"
        const val TAX = "/Invoice/Charges/Tax"
        const val EXTRAS = "/Invoice/Charges/Extras"
        const val SURCHARGE = "/Invoice/Charges/Extras/Surcharge"
    }

    private val base = DocumentModelDraft.named("invoice").locale("en_US")
        .rootGroup("Invoice") { g ->
            g.stringField("Reference") { }
            g.numberField("Total") { it.fractionDigits(0, 2) }
            g.numberField("DistinctCount") { it.fractionDigits(0, 0) }
            g.group("Lines") { l -> l.repeatable(10).numberField("Amount") { it.fractionDigits(0, 2) } }
            g.group("Charges") { c ->
                c.repeatable(10)
                    .numberField("Fee") { it.fractionDigits(0, 2) }
                    .numberField("Tax") { it.fractionDigits(0, 2) }
                c.group("Extras") { e -> e.repeatable(10).numberField("Surcharge") { it.fractionDigits(0, 2) } }
            }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    /** The typed aggregate over a GROUP star — `AggregateOperand`, the shape DG32 added; every path is
     *  written once and validated at the call site instead of living in an operation string. */
    private fun starOf(groupPath: String) = m.flattenGroup("$groupPath*")

    private fun render(calculation: Calculation): String = DslRenderer().render(calculation)

    private fun computeModel(operation: String): String =
        RuntimeLaws.withComputation(base, "/Invoice", "AggComp", "../Total", operation)

    private fun computeModel(aggregate: NumberExpr): String = computeModel(render(aggregate.expression()))

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both { kDoc.group(path, *reps); iDoc.group(path, *reps); return this }
        fun field(path: String, v: String, vararg reps: Int): Both { kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this }
    }

    private fun Both.lines(vararg amounts: String): Both {
        amounts.forEachIndexed { i, a -> group(LINES, 1, i + 1).field(AMOUNT, a, 1, i + 1, 1) }
        return this
    }

    /** Tri-check the Total outcome of [operation] over the instance [build] creates. */
    private fun agreeCompute(aggregate: NumberExpr, expectTotal: String, msg: String, build: (Both) -> Unit) =
        agreeCompute(computeModel(aggregate), expectTotal, msg, build)

    private fun agreeCompute(kModel: String, expectTotal: String, msg: String, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).firstOrNull { it.pointer() == "/Invoice[1]/Total" }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).firstOrNull { it.startsWith("$TOTAL|") }
            ?.substringAfter("$TOTAL|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val interp = DmInterpreter(def).compute(b.iDoc).firstOrNull { it.path == TOTAL }
            .let { c -> when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            } }
        assertEquals(expectTotal, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    // ── the corpus shape: a single-number-field group star ─────────────────────────────────────────────────

    @Test fun sumOverSingleFieldGroupStar() =
        agreeCompute(Dsl.sum(starOf(LINES)), "VALUE|30.75", "Sum(Lines*) sums the contained Amount cells") { b ->
            b.group("/Invoice", 1).field(TOTAL, "999.99", 1, 1).lines("10.50", "20.25")
        }

    @Test fun aggregateSiblingsOverGroupStar() {
        agreeCompute(Dsl.minValue(starOf(LINES)), "VALUE|10.5", "MinValue(Lines*)") { b ->
            b.group("/Invoice", 1).field(TOTAL, "999.99", 1, 1).lines("10.50", "20.25")
        }
        agreeCompute(Dsl.maxValue(starOf(LINES)), "VALUE|20.25", "MaxValue(Lines*)") { b ->
            b.group("/Invoice", 1).field(TOTAL, "999.99", 1, 1).lines("10.50", "20.25")
        }
        // NDV's static scale is 0 (`MVK_INVALID_COMPARE_DEC_PLACES` into the frac-2 Total) → a scale-0 target
        val kModel = RuntimeLaws.withComputation(base, "/Invoice", "NdvComp", "../DistinctCount",
            render(Dsl.numberOfDifferentValues(starOf(LINES)).expression()))
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        b.group("/Invoice", 1).field("/Invoice/DistinctCount", "9", 1, 1).lines("10.50", "20.25", "10.50")
        val k = RuntimeLaws.compute(kModel, b.kDoc).firstOrNull { it.pointer() == "/Invoice[1]/DistinctCount" }
            ?.let { if (it.cleared()) "CLEARED" else "VALUE|${it.value().orElse("")}" }
        val i = DmInterpreter(def).compute(b.iDoc).firstOrNull { it.path == "/Invoice/DistinctCount" }
            ?.let { if (it.outcome == ComputeOutcome.VALUE) "VALUE|${it.value}" else it.outcome.name }
        assertEquals("VALUE|2", k, "NDV(Lines*) counts the distinct contained cells")
        assertEquals(k, i, "NDV(Lines*) — kernel vs interpreter")
    }

    // ── the expansion's extent: multiple contained fields + a nested subgroup ──────────────────────────────

    @Test fun sumOverMultiFieldGroupStarWithNestedSubgroup() =
        agreeCompute(Dsl.sum(starOf(CHARGES)), "VALUE|6.5",
            "Sum(Charges*) feeds Fee+Tax cells AND the nested Extras/Surcharge (recursive expansion)") { b ->
            b.group("/Invoice", 1).field(TOTAL, "999.99", 1, 1)
            b.group(CHARGES, 1, 1).field(FEE, "1.00", 1, 1, 1).field(TAX, "2.00", 1, 1, 1)
            b.group(CHARGES, 1, 2).field(FEE, "3.00", 1, 2, 1)
            b.group(EXTRAS, 1, 1, 1).field(SURCHARGE, "0.50", 1, 1, 1, 1)
        }

    /**
     * **The nesting case above places ONE `Extras` row, which cannot discriminate the extent.** With a single
     * nested row, "the expansion visits every row of the nested repetition" and "it visits that repetition at one
     * coordinate" predict the same total, so the sibling row is what turns that lock into a measurement. Two rows
     * under `Charges[1]` plus one under `Charges[2]`: `1.00 + 2.00 + 3.00 + 0.50 + 0.25 + 0.75 = 7.50`.
     *
     * Written because the same one-row blind spot hid a real single-coordinate collapse in
     * `FieldValuesNotUnique`'s group expansion, which shares this recursion's shape.
     */
    @Test fun sumOverAGroupStarVisitsEveryRowOfANestedRepeatableSubgroup() =
        agreeCompute(Dsl.sum(starOf(CHARGES)), "VALUE|7.5",
            "Sum(Charges*) must reach Extras rows 1 AND 2, not only the first") { b ->
            b.group("/Invoice", 1).field(TOTAL, "999.99", 1, 1)
            b.group(CHARGES, 1, 1).field(FEE, "1.00", 1, 1, 1).field(TAX, "2.00", 1, 1, 1)
            b.group(CHARGES, 1, 2).field(FEE, "3.00", 1, 2, 1)
            b.group(EXTRAS, 1, 1, 1).field(SURCHARGE, "0.50", 1, 1, 1, 1)
            b.group(EXTRAS, 1, 1, 2).field(SURCHARGE, "0.25", 1, 1, 2, 1)
            b.group(EXTRAS, 1, 2, 1).field(SURCHARGE, "0.75", 1, 2, 1, 1)
        }

    // ── the shared-path arms: poison, empty selection, and the validation side ─────────────────────────────

    @Test fun malformedCellPoisonsTheGroupStarSum() =
        agreeCompute(Dsl.sum(starOf(LINES)), "CLEARED", "a 3-frac Amount poisons the compute aggregate") { b ->
            b.group("/Invoice", 1).field(TOTAL, "999.99", 1, 1).lines("10.50", "5.123")
        }

    /** Reachability control: a `Having` on a GROUP star is REJECTED (`MVK_NO_GROUPS_ALLOWED` —
     *  "At 'Having': It is not allowed to use a group"). This path meets it as a codegen throw; static
     *  arbitration refuses it as an ordinary diagnostic too, measured 2026-08-31 by
     *  `validate.laws.GroupOperandFilterLawsTest.theSTATICArbitrationRefusesItOnTheCarriersOnlyMeasuredAtCodegenBefore`.
     *  Either way the interpreter's filtered path is unreachable on
     *  a valid model for a group operand — only the unfiltered expansion is live. */
    @Test fun havingOnGroupStarIsRejectedByTheKernel() {
        val kModel = computeModel("Sum(/Invoice/Lines* Having [/Invoice/Lines/Amount] > 15)")
        val doc = RuntimeLaws.doc().group("/Invoice", 1)
        val thrown = runCatching { RuntimeLaws.compute(kModel, doc) }.exceptionOrNull()
        assertEquals(true, thrown?.message?.contains("MVK_NO_GROUPS_ALLOWED"),
            "the kernel rejects a Having on a group star: ${thrown?.message?.take(160)}")
    }

    @Test fun emptyGroupStarSum() =
        agreeCompute(Dsl.sum(starOf(LINES)), "VALUE|0", "no Lines rows → the empty-selection 0") { b ->
            b.group("/Invoice", 1).field(TOTAL, "999.99", 1, 1)
        }

    @Test fun validationRuleOverGroupStarSum() {
        val kModel = RuntimeLaws.withRule(base, "/Invoice", "R", "../Reference", "SUMHIGH",
            "FieldFilled(Reference) And Sum(/Invoice/Lines*) > 25")
        val def = ModelLoader.load(kModel)
        fun fires(vararg amounts: String): Pair<Boolean, Boolean> {
            val b = Both(RuntimeLaws.doc(), def.newDocument())
            b.group("/Invoice", 1).field(GATE, "INV-7", 1, 1).lines(*amounts)
            val k = RuntimeLaws.messages(kModel, b.kDoc).any { it.code() == "SUMHIGH" }
            val i = DmInterpreter(def).validateFull(b.iDoc).any { it.code == "SUMHIGH" }
            return k to i
        }
        val (kHigh, iHigh) = fires("10.50", "20.25")
        assertEquals(true, kHigh, "kernel fires at Sum 30.75 > 25")
        assertEquals(kHigh, iHigh, "interpreter agrees on the firing side")
        val (kLow, iLow) = fires("10.50")
        assertEquals(false, kLow, "kernel silent at Sum 10.50")
        assertEquals(kLow, iLow, "interpreter agrees on the silent side")
    }

    @Test fun validationMessageOverGroupStarRecursivelyReferencesExpandedFieldsNotGroups() {
        val kModel = RuntimeLaws.withRule(base, "/Invoice", "R", "../Reference", "SUMHIGH",
            "FieldFilled(Reference) And Sum(/Invoice/Charges*) > 5")
        val def = ModelLoader.load(kModel)

        fun documents(): Pair<RuntimeLaws.Doc, Document> {
            val both = Both(RuntimeLaws.doc(), def.newDocument())
            both.group("/Invoice", 1).field(GATE, "INV-7", 1, 1)
                .group(CHARGES, 1, 1).field(FEE, "1.00", 1, 1, 1).field(TAX, "2.00", 1, 1, 1)
                .group(EXTRAS, 1, 1, 1).field(SURCHARGE, "3.00", 1, 1, 1, 1)
            return both.kDoc to both.iDoc
        }

        val (dynamicDoc, interpreterDoc) = documents()
        val dynamic = RuntimeLaws.messages(kModel, dynamicDoc).filter { it.code() == "SUMHIGH" }
            .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }
        val (staticDoc, _) = documents()
        val javaStatic = RuntimeLaws.kernelJavaEngine(kModel)
            .messagesModern(staticDoc, null, java.util.Locale.US).filter { it.code() == "SUMHIGH" }
            .map { "${it.field()}|${it.type().name}|${it.referenced().sorted()}" }
        val interpreter = DmInterpreter(def).validateFull(interpreterDoc).filter { it.code == "SUMHIGH" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}|${it.referenced.sorted()}" }
        val expected = listOf(
            "/Invoice[1]/Reference|OMISSION_ERROR|" +
                "[/Invoice[1]/Charges[0]/Extras[0]/Surcharge, /Invoice[1]/Charges[0]/Fee, " +
                "/Invoice[1]/Charges[0]/Tax, /Invoice[1]/Reference]",
        )

        assertEquals(expected, dynamic, "dynamic kernel recursively expands a group-star message ref to contained fields")
        assertEquals(dynamic, javaStatic, "dynamic/static exact group-star message refs")
        assertEquals(dynamic, interpreter, "kernel/interpreter exact group-star message refs")
    }
}
