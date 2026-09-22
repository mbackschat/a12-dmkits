package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentConfig
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the kernel's **directional, sign-aware** VALUE-vs-OMISSION typing. A fired comparison over
 * an EMPTY number is OMISSION only if filling it *in the breaking direction* could clear it: an empty number
 * can always grow (`kannGroesserWerden`) but can shrink only if **signed** (`kannKleinerWerden = hatVorzeichen`,
 * kernel `RuntimeController.feldWertAlsZahl` / `BedingungsOperatorHelper.vergleiche`). So `[Unsigned] >= 0`
 * (empty) fires as a **VALUE** error (filling can't break it) while `[Signed] >= 0` is **OMISSION**; both
 * `<= 100` are OMISSION (filling bigger breaks them). `Quantity` is signed (default); `StockOnHand` is
 * unsigned (`NumberType.positivesOnly: true` — the kernel's `hatVorzeichen == false`). Driven from parsed
 * text; the interpreter reads the sign from the Document.
 */
class DirectionalPolarityDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(FieldDef(Q, Kind.NUMBER), FieldDef(SOH, Kind.NUMBER), FieldDef(CN, Kind.STRING))
    private val kinds = mapOf(Q to Kind.NUMBER, SOH to Kind.NUMBER, CN to Kind.STRING)
    private val unsigned = setOf(SOH)

    // Conditions are authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to BOTH
    // engines (kernel injection + the interpreter) — no free-text condition literals.
    private val m = BoundModel.ofJson(order)
    private fun render(c: Condition): String = RuntimeLaws.render(c)

    /** `cond` fires on a row where [emptyField] is empty (substituted 0) and CustomerName is filled. */
    private fun agree(cond: String, emptyField: String, errorRel: String, msg: String) {
        val code = "DP"
        val model = RuntimeLaws.withRule(order, "/Order", "R", errorRel, code, cond)
        val kernel = RuntimeLaws.typeOf(model, code, setOf("/Order"), setOf(emptyField), mapOf(CN to "Acme"))
            .map { it.name }.orElse(null)
        val doc = Document(DocumentConfig(kinds, unsignedFields = unsigned)).field(CN, "Acme", 1, 1).emptyField(emptyField, 1, 1)
        val interp = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Order/R", "/Order", cond, code, errorRel))))
            .validate(doc).messages.firstOrNull { it.code == code }?.type
            ?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(kernel, interp, "$msg  [kernel=$kernel interp=$interp]")
    }

    @Test fun unsignedEmptyGreaterEqualZeroIsValueNotOmission() {
        // The divergence: an unsigned empty can't shrink below 0, so `>= 0` can't be broken by filling → VALUE.
        agree(render(m.num(SOH).ge(0)), SOH, "../StockOnHand", "unsigned [StockOnHand] >= 0 (empty) is a VALUE error")
    }

    @Test fun signedEmptyGreaterEqualZeroIsOmission() {
        // Control: a signed empty CAN shrink (fill negative) → `>= 0` is fillable-to-fix → OMISSION.
        agree(render(m.num(Q).ge(0)), Q, "../Quantity", "signed [Quantity] >= 0 (empty) is an OMISSION error")
    }

    @Test fun emptyLessEqualIsOmissionRegardlessOfSign() {
        // Either sign: an empty can grow, breaking `<= 100` → fillable → OMISSION.
        agree(render(m.num(SOH).le(100)), SOH, "../StockOnHand", "unsigned [StockOnHand] <= 100 (empty) is OMISSION")
        agree(render(m.num(Q).le(100)), Q, "../Quantity", "signed [Quantity] <= 100 (empty) is OMISSION")
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val SOH = "/Order/StockOnHand"
        const val CN = "/Order/CustomerName"
    }
}
