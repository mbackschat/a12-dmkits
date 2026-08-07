package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.RuntimeLaws.Relevant
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Lives in the `laws` package, not `perf`: the kernel's `messagesPart` oracle and its `Relevant` record
 * are package-private there. The `perf` TAG decides the lane, not the directory.
 *
 * `SumOfProducts` under PARTIAL validation, with the relevant set given as a COMPLETE CONCRETE ENUMERATION —
 * the shape the routine gate does not reach. `PerfGateTest`'s twenty scenarios are all FULL validation and
 * contain no `validatePart` call at all, so a change to the partial-relevance gate is invisible to it.
 *
 * This exists because that gate acquired a per-operator completeness predicate: `SumOfProducts` evaluates under
 * a complete concrete enumeration but not a partial one (`laws/PartialValidationAggregateDiffTest`), and
 * deciding that costs a scan over the relevant set per repeatable level. Enumerating every row makes the
 * relevant set grow with the document, which is precisely where such a predicate would go quadratic.
 *
 * Reported against the KERNEL, not against a previous interpreter number: a newly measured scenario has no
 * prior of its own, and the project's target is to beat the kernel rather than to avoid regressing ourselves.
 * The assertion is a scaling sanity bound; absolute numbers are printed for the record and are NOT a release
 * bar (a bar needs IF137 certification).
 */
@Tag("perf")
class PartialSumOfProductsPerfTest {

    @Test
    fun completeConcreteEnumerationStaysSubQuadraticAndBeatsTheKernel() {
        val small = measure(SMALL)
        val large = measure(LARGE)
        val factor = LARGE.toDouble() / SMALL
        val growth = large.interpreterMs / small.interpreterMs.coerceAtLeast(0.001)

        println("[perf] partial-SOP/complete-concrete@$SMALL  interp=${ms(small.interpreterMs)} " +
            "kernel=${ms(small.kernelMs)} ratio=${"%.2f".format(small.kernelMs / small.interpreterMs)}×")
        println("[perf] partial-SOP/complete-concrete@$LARGE  interp=${ms(large.interpreterMs)} " +
            "kernel=${ms(large.kernelMs)} ratio=${"%.2f".format(large.kernelMs / large.interpreterMs)}×")
        println("[perf] partial-SOP growth ${"%.2f".format(growth)}× for ${factor.toInt()}× rows " +
            "(linear≈$factor, quadratic≈${factor * factor})")

        assertTrue(growth < factor * factor * 0.75) {
            "completeness predicate scales quadratically: ${"%.2f".format(growth)}× for ${factor.toInt()}× rows " +
                "(quadratic≈${factor * factor}). It is consulted per row, so it must be decided once per star path."
        }
        assertTrue(large.interpreterMs < large.kernelMs) {
            "interpreter must beat the kernel on this scenario: interp=${ms(large.interpreterMs)} " +
                "kernel=${ms(large.kernelMs)}"
        }
    }

    private class Reading(val interpreterMs: Double, val kernelMs: Double)

    private fun measure(rows: Int): Reading {
        val json = DocumentModelDraft.named("cart").locale("en_US")
            .rootGroup("Cart", Consumer { g: GroupBuilder ->
                g.numberField("Total", Consumer { })
                    .group("Lines", Consumer<GroupBuilder> {
                        it.repeatable(rows).numberField("Qty", Consumer { }).numberField("Price", Consumer { })
                    })
            })
            .build().toJson()
        val m = BoundModel.ofJson(json)
        val rule = DslRenderer().render(
            Dsl.fieldFilled(m.field(TOTAL))
                .and(Dsl.sumOfProducts(m.flatten("$LINES*/Qty"), m.flatten("$LINES*/Price")).gt(100000)))
        val kModel = RuntimeLaws.withRule(json, CART, "SOP", "../Total", "SOP", rule)
        val def = ModelDef(
            listOf(FieldDef(TOTAL, Kind.NUMBER), FieldDef(QTY, Kind.NUMBER), FieldDef(PRICE, Kind.NUMBER)),
            listOf(RuleDef("$CART/SOP", CART, rule, "SOP", "../Total")),
            repeatableGroups = setOf(LINES), declaredReps = mapOf(LINES to rows),
        )

        fun kDoc() = RuntimeLaws.doc().group(CART, 1).field(TOTAL, "1", 1, 1).also { d ->
            for (r in 1..rows) d.group(LINES, 1, r).field(QTY, "3", 1, r, 1).field(PRICE, "7", 1, r, 1)
        }
        fun iDoc() = def.newDocument().group(CART, 1).field(TOTAL, "1", 1, 1).also { d ->
            for (r in 1..rows) d.group(LINES, 1, r).field(QTY, "3", 1, r, 1).field(PRICE, "7", 1, r, 1)
        }
        val kRel = buildSet { add(Relevant.of(TOTAL, 0, 0)); for (r in 1..rows) add(Relevant.of(LINES, 0, r)) }
        val iRel = buildSet<RelevantEntity> {
            add(RelevantEntity.anyRep(TOTAL)); for (r in 1..rows) add(RelevantEntity(LINES, listOf(0, r)))
        }

        val interpreter = DmInterpreter(def)
        val iDocument = iDoc()
        repeat(WARMUP) { interpreter.validatePart(iDocument, iRel) }
        val iStart = System.nanoTime()
        repeat(REPEATS) { interpreter.validatePart(iDocument, iRel) }
        val iMs = (System.nanoTime() - iStart) / 1e6 / REPEATS

        val kDocument = kDoc()
        repeat(WARMUP) { RuntimeLaws.messagesPart(kModel, kDocument, kRel) }
        val kStart = System.nanoTime()
        repeat(REPEATS) { RuntimeLaws.messagesPart(kModel, kDocument, kRel) }
        val kMs = (System.nanoTime() - kStart) / 1e6 / REPEATS

        return Reading(iMs, kMs)
    }

    private fun ms(v: Double) = "${"%.1f".format(v)}ms"

    private companion object {
        const val CART = "/Cart"; const val TOTAL = "/Cart/Total"
        const val LINES = "/Cart/Lines"; const val QTY = "/Cart/Lines/Qty"; const val PRICE = "/Cart/Lines/Price"
        const val SMALL = 250; const val LARGE = 1000
        const val WARMUP = 3; const val REPEATS = 5
    }
}
