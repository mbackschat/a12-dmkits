package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * IG55 — the QUARANTINED WI-T reproducer (recorded 2026-07-08, found by the WI-5a facet probe lane):
 * `NumberOfDifferentValues` over an INDEX field with an empty (or absent) cell — the KERNEL suppresses
 * the referencing rule entirely (even `>= 0` does not fire: the mandatory-empty index cell is
 * not-check-relevant, so the count is non-evaluable), while the INTERPRETER drops the empty cell and
 * counts the filled ones → it FIRES `== 1`.
 *
 * <p>Mechanism hypothesis for the drill: the interpreter's index-field not-check-relevant
 * classification exists only in the VLQ path ({@code Interpreter.vlqSide} / {@code classifyVlqCell},
 * IF60) — the aggregate path ({@code starOperandVals} → {@code EvalContext.star}) drops empties with
 * no index-field awareness. The drill must sweep the SIBLING aggregate sites over an index field
 * (`Sum`/`MinValue`/`MaxValue`/`NumberOfFilledFields`) and confirm from BOTH kernel strategies'
 * sources before fixing at the shared root. Enable this test when the fix lands.
 */
@Disabled("IG55 — kernel suppresses NDV over an index field's empty cell; the interpreter fires (WI-T)")
class NdvIndexFieldEmptyCellDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    private fun agree(cond: String, emptyCellInsteadOfAbsent: Boolean) {
        val model = RuntimeLaws.withRule(order, "/Order", "NDV", "../CustomerName", "NDV",
            "FieldFilled(CustomerName) And ($cond)")
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
            .group("/Order/Items", 1, 1).group("/Order/Items", 1, 2)
            .field("/Order/Items/Sku", "A", 1, 2, 1)
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1)
            .group("/Order/Items", 1, 1).group("/Order/Items", 1, 2)
            .field("/Order/Items/Sku", "A", 1, 2, 1)
        if (emptyCellInsteadOfAbsent) {
            kDoc.emptyField("/Order/Items/Sku", 1, 1, 1)
            iDoc.emptyField("/Order/Items/Sku", 1, 1, 1)
        }
        val kernel = RuntimeLaws.firedCodes(model, kDoc).contains("NDV")
        val interp = DmInterpreter(def).validate(iDoc).messages.any { it.code == "NDV" }
        assertEquals(kernel, interp, "cond=`$cond` emptyCell=$emptyCellInsteadOfAbsent")
    }

    @Test fun ndvOverIndexFieldWithEmptyCell() {
        agree("NumberOfDifferentValues(Items*/Sku) == 1", emptyCellInsteadOfAbsent = true)   // kernel=false interp=true
        agree("NumberOfDifferentValues(Items*/Sku) == 1", emptyCellInsteadOfAbsent = false)  // kernel=false interp=true
        agree("NumberOfDifferentValues(Items*/Sku) >= 0", emptyCellInsteadOfAbsent = true)   // kernel=false interp=true
    }
}
