package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.Requiredness
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — **the form-engine validation flow** `compute → apply → validate` (KERNEL-FINDINGS
 * "validateFull does NOT run computations"). The kernel keeps validation and computation separate: a bare
 * `validateFull` sees computed fields as whatever the instance stores (empty if absent), so a
 * *required-but-computed* field false-fires `mandatoryField`, and a rule over a computed field misses the
 * value. The realistic answer applies the computation first (`compute().applyTo(doc)`), then validates — the
 * interpreter's [DmInterpreter.applyComputations] mirrors that apply-computed-back step, and `model eval`
 * defaults to it.
 *
 * Each case asserts the interpreter agrees with the kernel in BOTH modes — raw (`validate`) and applied
 * (`applyComputations` then `validate`) — by comparing the full `code@pointer` set, AND that the two modes
 * genuinely DIFFER (the ordering matters). `BackorderQuantity := Quantity * 2` (scale-0 counts on the
 * non-repeatable `/Order`).
 */
class ComputeApplyDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val m = BoundModel.ofJson(order)
    private fun renderOp(c: Calculation): String = DslRenderer(ConditionLanguage.EN, "/Order").render(c)
    private fun render(c: Condition): String = DslRenderer().render(c)

    // BackorderQuantity := Quantity * 2 (the proven scale-0 op shape from ComputeCascadeDiffTest).
    private val op = renderOp(m.num(Q).asNumber().times(NumberExpr(NumberLiteral.of(2))).expression())

    private fun kDoc() = RuntimeLaws.doc().group("/Order", 1).field(Q, "5", 1, 1).emptyField(BO, 1, 1)
    private fun iDoc(def: ModelDef) =
        Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig, def.dateFormats,
            def.scales, def.dateTimeFormats, def.datePrecisions).group("/Order", 1).field(Q, "5", 1, 1).emptyField(BO, 1, 1)

    private fun kernelRaw(kModel: String) =
        RuntimeLaws.messages(kModel, kDoc()).map { "${it.code}@${it.field}" }.filter { !it.startsWith("MVK_") }.toSortedSet()
    private fun kernelApplied(kModel: String) =
        RuntimeLaws.messagesAfterCompute(kModel, kDoc(), Locale.US).map { "${it.code}@${it.field}" }
            .filter { !it.startsWith("MVK_") }.toSortedSet()
    private fun interpRaw(def: ModelDef) =
        DmInterpreter(def).validate(iDoc(def)).messages.map { "${it.code}@${it.errorPath}" }.toSortedSet()
    private fun interpApplied(def: ModelDef) =
        DmInterpreter(def).let { it.validate(it.applyComputations(iDoc(def))) }.messages
            .map { "${it.code}@${it.errorPath}" }.toSortedSet()

    @Test
    fun requiredComputedFieldFalseFiresMandatoryRawButComputeThenValidateClearsIt() {
        // BackorderQuantity is required AND computed. Empty in the instance, with Quantity=5 present.
        val kModel = require(RuntimeLaws.withComputation(order, "/Order", "BackorderComp", "../$BO_REL", op),
            BO, "absoluteOrRelativeToNextRepAncestor")
        val def = ModelDef(
            listOf(FieldDef(Q, Kind.NUMBER), FieldDef(BO, Kind.NUMBER, required = Requiredness.ABSOLUTE)),
            emptyList(),
            computations = listOf(ComputationDef(BO, "/Order", op)),
        )

        // RAW (bare validateFull): the empty required field false-fires mandatoryField — interp == kernel.
        assertEquals(kernelRaw(kModel), interpRaw(def), "raw validate: interp == kernel")
        assertTrue(interpRaw(def).any { it.startsWith("mandatoryField@") },
            "a required-but-computed empty field false-fires mandatoryField under raw validation")

        // APPLIED (compute → apply → validate): the computation fills it (10), so mandatoryField is gone —
        // interp == kernel, and the two modes genuinely differ (the ordering matters).
        assertEquals(kernelApplied(kModel), interpApplied(def), "compute-then-validate: interp == kernel")
        assertTrue(interpApplied(def).none { it.startsWith("mandatoryField@") },
            "applying the computation fills the field, so no mandatoryField — the form-engine answer")
        assertTrue(interpRaw(def) != interpApplied(def), "raw and applied must differ — compute-then-validate is not a no-op")
    }

    @Test
    fun ruleOverAComputedFieldSeesTheValueOnlyAfterApply() {
        // A rule [BackorderQuantity] > 8 fires only once the computation (10) is applied; raw sees empty→0.
        val cond = render(m.num(BO).gt(8))
        val kModel = RuntimeLaws.withRule(RuntimeLaws.withComputation(order, "/Order", "BackorderComp", "../$BO_REL", op),
            "/Order", "BackorderHigh", "../$BO_REL", "BACKORDER_HIGH", cond)
        val def = ModelDef(
            listOf(FieldDef(Q, Kind.NUMBER), FieldDef(BO, Kind.NUMBER)),
            listOf(RuleDef("/Order/BackorderHigh", "/Order", cond, "BACKORDER_HIGH", "../$BO_REL")),
            computations = listOf(ComputationDef(BO, "/Order", op)),
        )

        // RAW: empty number → 0, 0 > 8 is false → the rule does NOT fire (interp == kernel).
        assertEquals(kernelRaw(kModel), interpRaw(def), "raw validate: interp == kernel")
        assertTrue(interpRaw(def).none { it.startsWith("BACKORDER_HIGH@") }, "raw sees empty→0, 0 > 8 false → no fire")

        // APPLIED: BackorderQuantity = 10, 10 > 8 → the rule fires (interp == kernel).
        assertEquals(kernelApplied(kModel), interpApplied(def), "compute-then-validate: interp == kernel")
        assertTrue(interpApplied(def).any { it.startsWith("BACKORDER_HIGH@") },
            "after apply, the rule sees the computed 10 → fires")
    }

    /** Inject a `requirednessConfig` for [fieldPath] into the model JSON (same surgery as RequirednessDiffTest). */
    private fun require(modelJson: String, fieldPath: String, mode: String): String {
        val id = modelJson.indexOf("\"id\": \"$fieldPath\"")
        require(id >= 0) { "field not found: $fieldPath" }
        val brace = modelJson.indexOf('{', modelJson.indexOf("\"Field\":", id))
        return modelJson.substring(0, brace + 1) +
            "\n\"requirednessConfig\": {\"mode\": \"$mode\"}," + modelJson.substring(brace + 1)
    }

    private companion object {
        const val Q = "/Order/Quantity"
        const val BO = "/Order/BackorderQuantity"
        const val BO_REL = "BackorderQuantity"
    }
}
