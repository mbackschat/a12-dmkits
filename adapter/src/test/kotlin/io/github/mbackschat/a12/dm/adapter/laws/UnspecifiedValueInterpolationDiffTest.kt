package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.eval.SingleRowDoc
import io.github.mbackschat.a12.dm.interpreter.eval.renderMessage
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential (IF121; kernel contract §13) — interpolating an UNSPECIFIED value: `$field.value$` of a not-given field yields
 * `0` for a NUMBER and an empty string for other kinds (KERNEL-SEMANTICS §13). The interpolated field must be
 * referenced in the rule condition (the kernel's `INVALID_FIELD` — an interpolated field not in the condition
 * is rejected at codegen), so each rule fires on a comparison over the SAME (unspecified, non-required) field:
 * the number substitutes `0` (`0 < 100` fires), the string substitutes empty (`"" != "ABC"` fires). The
 * interpreter's [renderMessage] must match the kernel's `getErrorText`.
 */
class UnspecifiedValueInterpolationDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun render(c: io.github.mbackschat.a12.dm.rulekit.dsl.Condition) = DslRenderer().render(c)

    private fun kernelText(errorRel: String, cond: String, template: String, doc: RuntimeLaws.Doc): String? {
        val model = RuntimeLaws.withRuleMessages(order, "/Order", "R", errorRel, "R_CODE", cond, template, template)
        return RuntimeLaws.errorTextOf(model, "R_CODE", doc, Locale.US).orElse(null)
    }

    @Test
    fun unspecifiedNumberInterpolatesAsZeroLikeTheKernel() {
        val cond = render(m.num(Q).notFilled())   // fires BECAUSE Quantity is unspecified; references it
        val template = "Qty is \$Quantity.value\$."
        val doc = RuntimeLaws.doc().group("/Order", 1)   // Quantity NOT filled
        val ctx = SingleRowDoc("/Order", emptyMap(), mapOf(Q to Kind.NUMBER))
        assertEquals(kernelText("../Quantity", cond, template, doc), renderMessage(template, "/Order", ctx),
            "an unspecified NUMBER interpolates as the kernel renders it (§13: 0)")
    }

    @Test
    fun unspecifiedStringInterpolatesAsEmptyLikeTheKernel() {
        val cond = render(m.str(CN).notFilled())   // fires BECAUSE CustomerName is unspecified; references it
        val template = "Name [\$CustomerName.value\$]."
        val doc = RuntimeLaws.doc().group("/Order", 1)   // CustomerName NOT filled
        val ctx = SingleRowDoc("/Order", emptyMap(), mapOf(CN to Kind.STRING))
        assertEquals(kernelText("../CustomerName", cond, template, doc), renderMessage(template, "/Order", ctx),
            "an unspecified STRING interpolates empty (§13)")
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val CN = "/Order/CustomerName"
    }
}
