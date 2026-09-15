package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check — the **three compute-only poison PRESCANS**, [IG136] item (c), which the ledger
 * records as unexercised: `SumOfProducts`, `NumberOfValueInFields`, and `NumberOfFilledFields` each carry a
 * prescan that exists only on the compute path and had no test reaching it.
 *
 * **Why an unexercised prescan is worth its own file rather than a note.** A prescan is code that runs only in
 * one mode, and [IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)'s seventh instance is the
 * standing lesson about exactly that shape: an operator is demonstrably right wherever the estate pairs it and
 * wrong on the path nobody paired. These three are the inverse risk — not a missing check but an *unverified*
 * one, which can be too eager as easily as too lax, and neither direction is visible without a measurement.
 *
 * On the compute path a formally-INVALID cell must poison: the kernel's `CalculationCache` marks it invalid, any
 * read throws, and a computation that touches one yields no value — CLEARED, since every target below is seeded
 * ([IF85]). Each operator here is used as the computation's **operation** rather than as a precondition, so the
 * poisoning read is the one producing the value rather than the one gating it.
 */
class ComputePoisonPrescanDiffTest {

    private val PRICE = "/Shop/Pricing/Price"
    private val QTY = "/Shop/Pricing/Qty"
    private val TOTAL = "/Shop/Total"

    /**
     * A SECOND target, at scale 2. `SumOfProducts` over two-decimal prices yields two decimals, and a computed
     * target whose declared scale differs draws `MVK_INVALID_COMPARE_DEC_PLACES` at code generation — so the money
     * operation and the integer counts cannot share one target field.
     */
    private val MONEY = "/Shop/Money"

    /** The starred spellings: the `*` marks the REPEATABLE LEVEL, so it rides `Pricing`, not the leaf. */
    private val PRICE_STAR = "/Shop/Pricing*/Price"
    private val QTY_STAR = "/Shop/Pricing*/Qty"

    /**
     * `Price` declares `fractionDigits(2, 2)`, so `5.5` is FILLED but formally INVALID — the malformed-cell device
     * `ComputeStarAggregatePoisonDiffTest` established. `Qty` is unconstrained so it can never be the malformed
     * one, which keeps each row's divergence attributable to `Price` alone.
     */
    private val base = DocumentModelDraft.named("shop").locale("en_US")
        .rootGroup("Shop", Consumer { g: GroupBuilder ->
            g.numberField("Total", Consumer { })
                .numberField("Money", Consumer { it.fractionDigits(2, 2) })
                .group("Pricing", Consumer { p ->
                    p.repeatable(9)
                        .numberField("Price", Consumer { it.fractionDigits(2, 2) })
                        .numberField("Qty", Consumer { })
                })
        }).build().toJson()

    private class Both(val kDoc: RuntimeLaws.Doc, val iDoc: Document) {
        fun group(path: String, vararg reps: Int): Both {
            kDoc.group(path, *reps); iDoc.group(path, *reps); return this
        }

        fun field(path: String, v: String, vararg reps: Int): Both {
            kDoc.field(path, v, *reps); iDoc.field(path, v, *reps); return this
        }
    }

    private fun agree(
        operation: String,
        msg: String,
        expect: Map<String, String>,
        target: String = "../Total",
        build: (Both) -> Unit,
    ) {
        val kModel = RuntimeLaws.withComputation(base, "/Shop", "TotalComp", target, operation)
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { "${it.path()}@${it.pointer()}" to kSig(it) }

        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.compute(engine.instance(kDoc2))
            .associate { "${it.path()}@${it.pointer()}" to kSig(it) }

        val interp = DmInterpreter(def).compute(b.iDoc)
            .associate { "${it.path}@${formatPointer(it.path, it.indices)}" to iSig(it) }

        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs INTERPRETER")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    /** Row 1 valid, row 2 FILLED-but-malformed. The seeded `Total` makes a kernel clear observable. */
    private fun oneMalformedPriceRow(b: Both) {
        b.group("/Shop", 1).field(TOTAL, "999", 1, 1)
            .field(MONEY, "999.99", 1, 1)
            .group("/Shop/Pricing", 1, 1).field(PRICE, "10.00", 1, 1, 1).field(QTY, "2", 1, 1, 1)
            .group("/Shop/Pricing", 1, 2).field(PRICE, "5.5", 1, 2, 1).field(QTY, "3", 1, 2, 1)
    }

    /** Both rows valid — the positive control that each operation computes at all. */
    private fun allValidRows(b: Both) {
        b.group("/Shop", 1).field(TOTAL, "999", 1, 1)
            .field(MONEY, "999.99", 1, 1)
            .group("/Shop/Pricing", 1, 1).field(PRICE, "10.00", 1, 1, 1).field(QTY, "2", 1, 1, 1)
            .group("/Shop/Pricing", 1, 2).field(PRICE, "20.00", 1, 2, 1).field(QTY, "3", 1, 2, 1)
    }

    @Test
    fun sumOfProductsPrescan() =
        agree("SumOfProducts($PRICE_STAR, $QTY_STAR)",
            "SumOfProducts reaches the malformed row-2 Price",
            mapOf("$MONEY@/Shop[1]/Money" to "CLEARED"), target = "../Money",
            build = ::oneMalformedPriceRow)

    @Test
    fun sumOfProductsComputesWhenEveryCellIsValid() =
        agree("SumOfProducts($PRICE_STAR, $QTY_STAR)",
            "control: 10.00×2 + 20.00×3 = 80.00",
            mapOf("$MONEY@/Shop[1]/Money" to "VALUE|80.00"), target = "../Money",
            build = ::allValidRows)

    @Test
    fun numberOfValueInFieldsPrescan() =
        agree("NumberOfValueInFields(10.00 In $PRICE_STAR)",
            "NumberOfValueInFields reaches the malformed row-2 Price",
            mapOf("$TOTAL@/Shop[1]/Total" to "CLEARED"), build = ::oneMalformedPriceRow)

    @Test
    fun numberOfValueInFieldsComputesWhenEveryCellIsValid() =
        agree("NumberOfValueInFields(10.00 In $PRICE_STAR)",
            "control: exactly one row holds 10.00",
            mapOf("$TOTAL@/Shop[1]/Total" to "VALUE|1"), build = ::allValidRows)

    @Test
    fun numberOfFilledFieldsPrescan() =
        agree("NumberOfFilledFields($PRICE_STAR)",
            "NumberOfFilledFields reaches the malformed row-2 Price",
            mapOf("$TOTAL@/Shop[1]/Total" to "CLEARED"), build = ::oneMalformedPriceRow)

    @Test
    fun numberOfFilledFieldsComputesWhenEveryCellIsValid() =
        agree("NumberOfFilledFields($PRICE_STAR)",
            "control: both rows' Price cells are filled",
            mapOf("$TOTAL@/Shop[1]/Total" to "VALUE|2"), build = ::allValidRows)
}
