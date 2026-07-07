package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF65 (graduated from IG39): a computation's implicit validation is generated for a **guarded / tiered** computation,
 * not only the degenerate single-unconditional one. The kernel auto-generates one validation rule per computation
 * (md-model `ComputationAlternativesJoiner`): `FieldFilled(F) And [(common) And] <alternatives>` where a single
 * alternative is `[(precondition) And] [F] != op` and >1 alternatives are the `Or` of each so-gated comparison. The
 * interpreter's earlier IF32 synthesized only the single-unconditional form (`FieldFilled(F) And [F] != op`) and
 * skipped every guarded/tiered computation — a kernel-only miss (`validateFull`) the WI-7 sweep surfaced on real
 * subtype models. The fix mirrors the kernel's exact condition, so it rides the same rule machinery and matches
 * bit-for-bit; `compute` was already at parity (IF21) — this test locks BOTH faces together.
 *
 * Own-domain e-commerce: `BackorderQuantity` computed from `Quantity` on `/Order`, loaded into BOTH engines via
 * [ModelLoader]. Signatures filtered to the computation's name so unrelated formal errors never confuse them.
 */
class ComputationGuardedValidationDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    /** Inject computation [NAME] into `/Order` computing `../BackorderQuantity` from [common] + [alts]
     *  (`(precondition?, operation)`), then place `Quantity`=[quantity] and a stored `BackorderQuantity`=[stored]
     *  (null = absent). */
    private fun scenario(common: String?, alts: List<Pair<String?, String>>, quantity: String?, stored: String?): Scenario {
        val kModel = RuntimeLaws.withTieredComputation(order, "/Order", NAME, "../BackorderQuantity", common,
            alts.map { RuntimeLaws.CompAlt(it.first, it.second) })
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument().group("/Order", 1)
        if (quantity != null) { kDoc.field(QTY, quantity, 1, 1); iDoc.field(QTY, quantity, 1, 1) }
        if (stored != null) { kDoc.field(BACK, stored, 1, 1); iDoc.field(BACK, stored, 1, 1) }
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun kValidate(s: Scenario) = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == NAME }
        .map { "${it.field()}|${it.type().name}" }.sorted()
    private fun iValidate(s: Scenario) = s.interp.validateFull(s.iDoc).filter { it.code == NAME }
        .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

    private fun kCompute(s: Scenario) = RuntimeLaws.compute(s.kModel, s.kDoc).filter { it.path == BACK }
        .map { if (it.cleared) "CLEARED" else if (it.errored) "ERRORED" else "VALUE:${it.value.orElse("")}" }.sorted()
    private fun iCompute(s: Scenario) = s.interp.compute(s.iDoc).filter { it.path == BACK && it.outcome != ComputeOutcome.CLEARED }
        .map { if (it.outcome == ComputeOutcome.ERRORED) "ERRORED" else "VALUE:${it.value ?: ""}" }.sorted()

    /** Assert both faces agree with the kernel. [iCompute] filters CLEARED so an already-empty field's cosmetic
     *  CLEARED entry (the interpreter reports it, the kernel does not when there was nothing to clear) does not
     *  spuriously fail — the sweep never hits it (it seeds the computed field). */
    private fun agree(s: Scenario, msg: String) {
        assertEquals(kValidate(s), iValidate(s), "$msg [validate]\n  kernel=${kValidate(s)}\n  interp=${iValidate(s)}")
        val kc = kCompute(s).filterNot { it == "CLEARED" }
        assertEquals(kc, iCompute(s), "$msg [compute]\n  kernel=$kc\n  interp=${iCompute(s)}")
    }

    // ── commonPrecondition guard: FieldFilled(Quantity) gating BackorderQuantity = [Quantity] ──────────────
    private fun common(quantity: String?, stored: String?) =
        scenario("FieldFilled(Quantity)", listOf(null to "[Quantity]"), quantity, stored)

    @Test fun commonGuardTrueDisagreeFires() =
        agree(common("5", "99"), "common guard TRUE, stored disagrees → fires (kernel generates the implicit validation)")
    @Test fun commonGuardTrueAgreeNoFire() =
        agree(common("5", "5"), "common guard TRUE, stored agrees → no fire")
    @Test fun commonGuardFalseStoredNoFire() =
        agree(common(null, "99"), "common guard FALSE (Quantity empty) → cleared, implicit validation suppressed")

    // ── precondition on the sole alternative (no commonPrecondition) ───────────────────────────────────────
    private fun alt(quantity: String?, stored: String?) =
        scenario(null, listOf("FieldFilled(Quantity)" to "[Quantity]"), quantity, stored)

    @Test fun altGuardTrueDisagreeFires() =
        agree(alt("5", "99"), "precondition TRUE, stored disagrees → fires")
    @Test fun altGuardFalseStoredNoFire() =
        agree(alt(null, "99"), "precondition FALSE → cleared, no fire")

    // ── multi-alternative TIERED: the Or-of-gated-comparisons form ─────────────────────────────────────────
    // alt1: Quantity < 100 → 10 ; alt2: Quantity >= 100 → 20. The implicit validation is
    // ( (Quantity<100 And [F] != 10) Or (Quantity>=100 And [F] != 20) ).
    private fun tiered(quantity: String?, stored: String?) =
        scenario(null, listOf("[Quantity] < 100" to "10", "[Quantity] >= 100" to "20"), quantity, stored)

    @Test fun tieredLowTierDisagreeFires() =
        agree(tiered("5", "99"), "tiered: low tier selected (Quantity<100 → 10), stored 99 ≠ 10 → fires")
    @Test fun tieredLowTierAgreeNoFire() =
        agree(tiered("5", "10"), "tiered: low tier, stored 10 == 10 → no fire")
    @Test fun tieredHighTierDisagreeFires() =
        agree(tiered("150", "99"), "tiered: high tier selected (Quantity>=100 → 20), stored 99 ≠ 20 → fires")
    @Test fun tieredHighTierAgreeNoFire() =
        agree(tiered("150", "20"), "tiered: high tier, stored 20 == 20 → no fire")

    // ── a GUARDED computation in a REPEATABLE group: per-row implicit validation (pointer carries the row index) ──
    // StockQty = [Count] guarded by FieldFilled(Count), computed per Items row. Compares the FULL signature
    // (type + per-row pointer), so a per-row pointer/type divergence is caught (the workspaces hit this shape).
    private class RepScenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    private fun repScenario(rows: List<Pair<String?, String?>>): RepScenario {
        val kModel = RuntimeLaws.withTieredComputation(order, "/Order/Items", "StockComp", "../StockQty",
            "FieldFilled(Count)", listOf(RuntimeLaws.CompAlt(null, "[Count]")))
        val def = ModelLoader.load(kModel)
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument().group("/Order", 1)
        rows.forEachIndexed { i, (count, stock) ->
            val r = i + 1
            kDoc.group("/Order/Items", 1, r).field("/Order/Items/Sku", "K$r", 1, r, 1)
            iDoc.group("/Order/Items", 1, r).field("/Order/Items/Sku", "K$r", 1, r, 1)
            if (count != null) { kDoc.field("/Order/Items/Count", count, 1, r, 1); iDoc.field("/Order/Items/Count", count, 1, r, 1) }
            if (stock != null) { kDoc.field("/Order/Items/StockQty", stock, 1, r, 1); iDoc.field("/Order/Items/StockQty", stock, 1, r, 1) }
        }
        return RepScenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun repAgree(s: RepScenario, msg: String) {
        val k = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == "StockComp" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        val i = s.interp.validateFull(s.iDoc).filter { it.code == "StockComp" }
            .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()
        assertEquals(k, i, "$msg\n  kernel=$k\n  interp=$i")
    }

    @Test fun repRowGuardTrueDisagreeFiresPerRow() =
        repAgree(repScenario(listOf("2" to "99", "3" to "3")),
            "per-row: row1 Count=2 stored 99≠2 fires; row2 Count=3 stored 3==3 no fire — pointer carries the row")
    @Test fun repRowGuardFalseNoFire() =
        repAgree(repScenario(listOf(null to "99", "3" to "3")),
            "per-row: row1 Count empty → guard FALSE → cleared, no fire; row2 agrees")

    // ── both strategies: the implicit validation is a normal generated rule; static-JAVA codegen must agree ──
    @Test fun codegenGeneratesGuardedValidationIdentically() {
        val s = common("5", "99")
        val groovy = kValidate(s)
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).filter { it.code() == NAME }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(groovy, iValidate(s), "codegen (interp≠groovy)\n  groovy=$groovy\n  interp=${iValidate(s)}")
        assertEquals(groovy, javaStatic, "codegen (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
    }
}

private const val NAME = "BackComp"
private const val QTY = "/Order/Quantity"
private const val BACK = "/Order/BackorderQuantity"
