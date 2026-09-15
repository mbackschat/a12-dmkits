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
 * Differential + tri-check — the **three operator paths that never read `computeMode`**, [IG136] item (b).
 *
 * **Why a mode-blind path is the nastiest member of this defect class.** [IF287](../../../../../../../../../../../docs/INTERPRETER-FINDINGS.md#if287)'s
 * seventh instance was a validation/compute split, and the lesson it left is that the operator is
 * *demonstrably right* wherever the estate pairs it and wrong on the path nobody paired. IG136 names three such
 * paths and, crucially, why they were invisible: **zero** compute-touching test files in the estate contain a
 * value-list quantifier or either date-range predicate.
 *
 * On the compute path a formally-INVALID cell must **poison** — the kernel's `CalculationCache` marks the cell
 * invalid and any read of it throws, so a computation that touches one produces no value and is CLEARED when the
 * target was seeded ([IF85]). On the validation path the same cell is merely UNKNOWN, which suppresses a message
 * instead. A path that classifies rather than throws therefore computes a value the kernel refuses to compute.
 *
 * The three loci, each exercised here as a computation **precondition** over one malformed cell:
 *
 * 1. `ValueListQuantEval` — no `computeMode` read anywhere; a malformed cell becomes `Val.Unknown`.
 * 2. `filteredStarCells` — likewise, and it feeds both date-range predicates AND `FieldValuesNotUnique`.
 * 3. `config.groupPresence` is null on the compute path, so `GroupFilled`/`GroupNotFilled`/`NumberOfFilledGroups`
 *    silently fall back to structural presence instead of the formal-aware state validation uses.
 *
 * Every target is seeded, so a kernel CLEAR is observable rather than indistinguishable from "never computed".
 */
class ComputeModeBlindOperatorDiffTest {

    private val PRICE = "/Shop/Pricing/Price"

    /** The starred spelling: the `*` marks the REPEATABLE LEVEL, so it rides `Pricing`, not the leaf. */
    private val PRICE_STAR = "/Shop/Pricing*/Price"
    private val BUNDLE_PRICE = "/Shop/Bundle/BundlePrice"
    private val FLAG = "/Shop/Flag"

    /** The value list for locus 1, as a FIELD: a bare number literal after `In` draws MVK_UNEXPECTED_TOKEN. */
    private val WATCH = "/Shop/Watch"

    /**
     * `Price` and `BundlePrice` declare `fractionDigits(2, 2)`, so a value of `5.5` is FILLED but formally
     * INVALID — the same malformed-cell device `ComputeStarAggregatePoisonDiffTest` uses, and the only kind of
     * malformation a String field cannot express, which is why both carriers here are Numbers.
     */
    private val base = DocumentModelDraft.named("shop").locale("en_US")
        .rootGroup("Shop", Consumer { g: GroupBuilder ->
            g.numberField("Flag", Consumer { })
                .numberField("Watch", Consumer { it.fractionDigits(2, 2) })
                .group("Pricing", Consumer { p ->
                    p.repeatable(9).numberField("Price", Consumer { it.fractionDigits(2, 2) })
                })
                .group("Bundle", Consumer { b ->
                    b.numberField("BundlePrice", Consumer { it.fractionDigits(2, 2) })
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

    /** One root computation of `Flag` gated on [precondition]. */
    private fun model(precondition: String): String =
        RuntimeLaws.withTieredComputation(base, "/Shop", "GatedComp", "../Flag", null,
            listOf(RuntimeLaws.CompAlt(precondition, "1")))

    private fun agree(precondition: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val kModel = model(precondition)
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

    /** Row 1 valid, row 2 FILLED-but-malformed; the seeded `Flag` makes a kernel clear observable. */
    private fun oneMalformedPriceRow(b: Both) {
        b.group("/Shop", 1).field(FLAG, "9", 1, 1).field(WATCH, "10.00", 1, 1)
            .group("/Shop/Pricing", 1, 1).field(PRICE, "10.00", 1, 1, 1)
            .group("/Shop/Pricing", 1, 2).field(PRICE, "5.5", 1, 2, 1)
    }

    /**
     * **LOCUS 1 — the value-list quantifier, and the kernel does NOT poison.** The prediction was that its scan
     * reaches the malformed cell and must therefore clear the computation, leaving the `Val.Unknown`
     * classification as a divergence. MEASURED: the kernel COMPUTES — the quantifier answers from row 1's valid
     * `10.00` and the malformed row-2 cell is skipped rather than poisoning. So the classification is CORRECT and
     * the missing `computeMode` read is not an omission here.
     */
    @Test
    fun aValueListQuantifierPrecondition() =
        agree("AtLeastOneFieldValueIncludedInValueList($PRICE_STAR In $WATCH)",
            "the value-list scan reaches the malformed row-2 Price and still computes",
            mapOf("$FLAG@/Shop[1]/Flag" to "VALUE|1"),
            build = ::oneMalformedPriceRow)

    /**
     * **LOCUS 2 — `filteredStarCells`**, reached through `FieldValuesNotUnique`; the shared path both date-range
     * predicates also use. Here the kernel DOES clear, and the interpreter agrees unchanged — so this path's
     * poison already works, by whatever mechanism, and the missing `computeMode` read is again not the defect the
     * source reading suggested.
     */
    @Test
    fun aFieldValuesNotUniquePrecondition() =
        agree("FieldValuesNotUnique($PRICE_STAR)",
            "the uniqueness scan reaches the malformed row-2 Price",
            mapOf("$FLAG@/Shop[1]/Flag" to "CLEARED"),
            build = ::oneMalformedPriceRow)

    /**
     * **LOCUS 3 — group presence, and the kernel's answer is the opposite of what the source reading predicted.**
     * IG136 flagged that `config.groupPresence` is null on the compute path, so `GroupFilled` falls back to
     * structural presence instead of validation's formal-aware state, and treated that as a probable divergence.
     * MEASURED: the kernel COMPUTES here — a group whose only cell is filled-but-malformed is `GroupFilled` on the
     * compute path, so the structural fallback is the CORRECT behaviour and the mode difference is intentional
     * rather than an omission. Kept as a locked row precisely because it is a negative: the next reader would
     * otherwise re-derive the same wrong prediction from the same correct source reading.
     */
    @Test
    fun aGroupFilledPrecondition() =
        agree("GroupFilled(/Shop/Bundle)",
            "the group's only cell is filled-but-malformed",
            mapOf("$FLAG@/Shop[1]/Flag" to "VALUE|1")) { b ->
            b.group("/Shop", 1).field(FLAG, "9", 1, 1).field(WATCH, "10.00", 1, 1)
                .group("/Shop/Bundle", 1, 1).field(BUNDLE_PRICE, "5.5", 1, 1, 1)
        }

    /**
     * The positive control, which after the measurements above carries MORE weight rather than less: two of the
     * three loci compute a value on the malformed document too, so without a row proving the precondition can also
     * be FALSE, "computes" would be indistinguishable from "this precondition is always satisfied". Locus 2's
     * CLEARED row is the other half of that separation.
     */
    @Test
    fun theSamePreconditionsComputeWhenNothingIsMalformed() {
        val allValid: (Both) -> Unit = { b ->
            b.group("/Shop", 1).field(FLAG, "9", 1, 1).field(WATCH, "10.00", 1, 1)
                .group("/Shop/Pricing", 1, 1).field(PRICE, "10.00", 1, 1, 1)
                .group("/Shop/Pricing", 1, 2).field(PRICE, "20.00", 1, 2, 1)
                .group("/Shop/Bundle", 1, 1).field(BUNDLE_PRICE, "30.00", 1, 1, 1)
        }
        agree("AtLeastOneFieldValueIncludedInValueList($PRICE_STAR In $WATCH)",
            "control/value-list: 10.00 is in the list and every cell is valid",
            mapOf("$FLAG@/Shop[1]/Flag" to "VALUE|1"), build = allValid)
        agree("GroupFilled(/Shop/Bundle)",
            "control/group presence: the bundle's cell is valid and filled",
            mapOf("$FLAG@/Shop[1]/Flag" to "VALUE|1"), build = allValid)
    }
}
