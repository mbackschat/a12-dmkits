package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — a downstream computation's
 * **`Having` filter reads a COMPUTED dependency through the cascade** (the IG56 E2 drill: the first
 * value-string minimal — own-domain re-expression of a workspace "sum the currently-active members' shares"
 * cascade).
 *
 * An upstream per-row computation targets a boolean flag; a downstream `Sum(share Having [flag] == True)`
 * filters on it. The kernel's calc input strips EVERY calculated field's stored value (IF77 — "calculations
 * always produce a new value anyways"), so the filter sees the flag as the CASCADE left it: EMPTY when the
 * upstream cleared (a false precondition), a POISON when the upstream was invalid-marked (IF85). The
 * interpreter's `OverlayContext` handled direct refs and the scanned star path itself, but `rowContexts` —
 * the path every `Having` filter and its kept-row reads ride ([Interpreter.starOperandVals],
 * `filledFieldsValue`, the fill quantifiers) — delegated to the RAW row contexts, so a downstream filter read
 * the STALE seeded flag: a dropped row was kept (`Sum` too big — the kind-equal VALUE-vs-VALUE sweep class)
 * and a poisoned flag didn't poison the reader.
 */
class HavingOverComputedDependencyDiffTest {

    private companion object {
        const val END = "/Shop/Affiliates/EndDate"
        const val COMMISSION = "/Shop/Affiliates/Commission"
        const val WEIGHT = "/Shop/Affiliates/Weight"
        const val FLAG = "/Shop/Affiliates/ActiveFlag"
        const val TOTAL = "/Shop/TotalCommission"
        const val AFF = "/Shop/Affiliates"
    }

    private val base = DocumentModelDraft.named("affiliates").locale("en_US")
        .rootGroup("Shop") { g ->
            g.numberField("TotalCommission") { it.fractionDigits(0, 2) }
            g.group("Affiliates") { a ->
                a.repeatable(10)
                    .dateField("EndDate") { }
                    .numberField("Commission") { it.fractionDigits(0, 2) }
                    .numberField("Weight") { it.fractionDigits(0, 2) }
                    .field("ActiveFlag", FieldKind.BOOLEAN)
            }
        }.build().toJson()

    /** Upstream: the flag computes True while the affiliate has no end date ([activeCond] overridable for the
     *  poison arm); downstream: sum the active affiliates' commissions. */
    private fun model(activeCond: String = "FieldNotFilled(EndDate)"): String {
        val withFlag = RuntimeLaws.withTieredComputation(base, AFF, "ActiveComp", "../ActiveFlag", null,
            listOf(RuntimeLaws.CompAlt(activeCond, "True")))
        return RuntimeLaws.withComputation(withFlag, "/Shop", "TotalComp", "../TotalCommission",
            "Sum(/Shop/Affiliates*/Commission Having [/Shop/Affiliates/ActiveFlag] == True)")
    }

    /** One affiliate row: a commission, a STALE seeded flag, an optional end date, an optional weight. */
    private fun Both.affiliate(r: Int, commission: String, staleFlag: String = "true",
                               endDate: String? = null, weight: String? = null): Both {
        group(AFF, 1, r).field(COMMISSION, commission, 1, r, 1).field(FLAG, staleFlag, 1, r, 1)
        endDate?.let { field(END, it, 1, r, 1) }
        weight?.let { field(WEIGHT, it, 1, r, 1) }
        return this
    }

    private fun agree(kModel: String, msg: String, expect: Map<String, String>, build: (Both) -> Unit) {
        val def = ModelLoader.load(kModel)
        val b = Both(RuntimeLaws.doc(), def.newDocument())
        build(b)
        val groovy = RuntimeLaws.compute(kModel, b.kDoc).associate { "${it.pointer()}" to kSig(it) }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc()
        build(Both(kDoc2, def.newDocument()))
        val javaStatic = engine.compute(engine.instance(kDoc2)).associate { "${it.pointer()}" to kSig(it) }
        val interp = DmInterpreter(def).compute(b.iDoc).associate {
            formatPointer(it.path, it.indices) to iSig(it)
        }
        assertEquals(expect, groovy, "$msg — the kernel groovy-dynamic outcome")
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp.filterKeys { it in groovy.keys }, "$msg — kernel vs interpreter (kernel-reported cells)")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun flagAt(r: Int) = "/Shop[1]/Affiliates[$r]/ActiveFlag"
    private val total = "/Shop[1]/TotalCommission"

    // ── the found instance: a CLEARED upstream flag reads EMPTY in the downstream Having filter ─────────────

    @Test fun clearedFlagDropsItsRowFromTheFilteredSum() {
        // the sole affiliate has ENDED (upstream precondition false → flag CLEARED); its stale seeded flag
        // must NOT keep the row — the kernel sums nothing (0.00)
        agree(model(), "ended affiliate → flag cleared → row dropped → Sum 0", mapOf(
            flagAt(1) to "CLEARED",
            total to "VALUE|0",
        )) { b -> b.group("/Shop", 1).field(TOTAL, "999.99", 1, 1).affiliate(1, "100.00", endDate = "2024-03-05") }
    }

    @Test fun mixedRowsSumOnlyTheActiveOne() {
        // affiliate 1 active (no end date → flag True), affiliate 2 ended (flag cleared, stale seed "true")
        agree(model(), "one active + one ended → Sum = the active commission only", mapOf(
            flagAt(1) to "VALUE|true",
            flagAt(2) to "CLEARED",
            total to "VALUE|40",
        )) { b ->
            b.group("/Shop", 1).field(TOTAL, "999.99", 1, 1)
            b.affiliate(1, "40.00", staleFlag = "false")
            b.affiliate(2, "100.00", endDate = "2024-03-05")
        }
    }

    // ── the predicted second instance: a POISONED upstream flag poisons the downstream filter read ──────────

    @Test fun poisonedFlagPoisonsTheFilteredSum() {
        // the upstream precondition reads a formally-INVALID Weight (3 fractional digits into a max-2 field)
        // → the flag is invalid-marked (IF85); the downstream filter's read of it poisons TotalComp → CLEARED
        agree(model(activeCond = "[Weight] >= 0"), "invalid weight → flag poisoned → filtered sum poisoned", mapOf(
            flagAt(1) to "CLEARED",
            total to "CLEARED",
        )) { b -> b.group("/Shop", 1).field(TOTAL, "999.99", 1, 1).affiliate(1, "100.00", weight = "5.123") }
    }

    // ── the third path: the same stale read in a compute PRECONDITION's fill quantifier ─────────────────────

    @Test fun preconditionQuantifierHavingReadsTheCascadeFlag() {
        // TotalComp guarded by AtLeastOneFieldFilled(... Having [/Shop/Affiliates/ActiveFlag] == True): the sole affiliate is
        // ended → its flag cleared → the filter keeps nothing → the guard is FALSE → TotalComp CLEARED
        val withFlag = RuntimeLaws.withTieredComputation(base, AFF, "ActiveComp", "../ActiveFlag", null,
            listOf(RuntimeLaws.CompAlt("FieldNotFilled(EndDate)", "True")))
        val kModel = RuntimeLaws.withTieredComputation(withFlag, "/Shop", "TotalComp", "../TotalCommission", null,
            listOf(RuntimeLaws.CompAlt("AtLeastOneFieldFilled(/Shop/Affiliates*/Commission Having [/Shop/Affiliates/ActiveFlag] == True)",
                "Sum(/Shop/Affiliates*/Commission Having [/Shop/Affiliates/ActiveFlag] == True)")))
        agree(kModel, "guard quantifier reads the cleared flag as empty → CLEARED", mapOf(
            flagAt(1) to "CLEARED",
            total to "CLEARED",
        )) { b -> b.group("/Shop", 1).field(TOTAL, "999.99", 1, 1).affiliate(1, "100.00", endDate = "2024-03-05") }
    }
}
