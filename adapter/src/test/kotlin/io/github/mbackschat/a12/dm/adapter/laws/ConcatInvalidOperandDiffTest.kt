package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for STRING CONCATENATION `[Code] + "-"
 * + [Suffix]` over a FORMALLY-INVALID operand — IF81 (was IG51; split from the IF78 operand-invalidity
 * investigation).
 *
 * The concat branch of `Interpreter.evalArith` maps every non-`Val.Txt` operand to `""` (so an EMPTY string
 * field concatenates as `""` — kernel-probed `"" + " " + "Smith"` = `" Smith"`). But it could not distinguish
 * "Unknown because EMPTY" (→ `""`, correct) from "Unknown because FORMALLY INVALID" (a too-long value etc. → the
 * whole concatenation is non-evaluable → the computed field is CLEARED). The kernel CLEARS a `[S] + "x"` over a
 * `stringZuLang` operand — differential-confirmed below. The fix returns `Val.Unknown` when a concat operand
 * (possibly nested) is a FILLED-but-invalid field, while an EMPTY operand still concatenates as `""`.
 *
 * Own-domain (e-commerce catalog): `Code` (`maxLength` 5) — a too-long value is the filled-invalid trigger. The
 * target `Label` is seeded so a clear is a reported CHANGE (comparable to the kernel's change-only compute report).
 */
class ConcatInvalidOperandDiffTest {

    private val CODE = "/Product/Code"
    private val SUFFIX = "/Product/Suffix"
    private val LABEL = "/Product/Label"
    private val SEED = "SEED"

    /** `[Code] + "-" + [Suffix]`, rendered relative to /Product through the typed DSL. */
    private fun operation(m: BoundModel): String {
        val c: Calculation = m.str(CODE).plus("-").plus(m.str(SUFFIX).asString()).expression()
        return RuntimeLaws.render("/Product", c)
    }

    /** The model JSON with a `Label` computation, [required] toggling whether `Code` is mandatory. */
    private fun model(required: Boolean): String {
        val base = DocumentModelDraft.named("catalog").locale("en_US")
            .rootGroup("Product") { p ->
                p.stringField("Code") { c -> if (required) c.maxLength(5).required() else c.maxLength(5) }
                    .stringField("Suffix") { c -> c.maxLength(5) }
                    .stringField("Label") {}
            }.build().toJson()
        val op = operation(BoundModel.ofJson(base))
        return RuntimeLaws.withComputation(base, "/Product", "LabelComp", "../Label", op)
    }

    private fun kernelSig(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED"
        else -> "VALUE|${c.value().orElse("")}"
    }

    /** groovy-dynamic: the md-runtime service's change-only compute report for Label. */
    private fun kOutcome(model: String, values: Map<String, String>): String {
        val doc = RuntimeLaws.doc().group("/Product", 1)
        doc.field(LABEL, SEED, 1, 1)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        return kernelSig(RuntimeLaws.compute(model, doc).firstOrNull { it.path() == LABEL })
    }

    /** java-static: the code-generated engine's compute outcome for Label. */
    private fun jOutcome(model: String, values: Map<String, String>): String {
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val doc = RuntimeLaws.doc().group("/Product", 1)
        doc.field(LABEL, SEED, 1, 1)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        val raw = engine.computeOutcomesSorted(engine.instance(doc)).firstOrNull { it.startsWith("$LABEL|") }
            ?.substringAfter("$LABEL|") ?: return "NONE"
        return when (raw) { "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$raw" }
    }

    private fun iOutcome(model: String, values: Map<String, String>): String {
        val def = ModelLoader.load(model)
        val doc = def.newDocument().group("/Product", 1)
        doc.field(LABEL, SEED, 1, 1)
        values.forEach { (p, v) -> doc.field(p, v, 1, 1) }
        val c = DmInterpreter(def).compute(doc).firstOrNull { it.path == LABEL }
        return when (c?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
        }
    }

    /** groovy-dynamic == java-static == interpreter on the Label compute outcome. */
    private fun tri(required: Boolean, values: Map<String, String>, msg: String) {
        val model = model(required)
        val groovy = kOutcome(model, values)
        val javaStatic = jOutcome(model, values)
        val interp = iOutcome(model, values)
        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter")
    }

    // ── control: both operands valid → the concatenation computes ────────────────────────────────────────
    @Test fun bothValidComputes() =
        tri(false, mapOf(CODE to "ABC", SUFFIX to "XY"), "Code=ABC (≤5) valid → VALUE ABC-XY")

    // ── the IF81 core: a FILLED-but-INVALID concat operand → the kernel CLEARS ─────────────────────────────
    @Test fun tooLongLeftOperandClears() =
        tri(false, mapOf(CODE to "ABCDEF", SUFFIX to "XY"), "Code=ABCDEF (len 6 > max 5, stringZuLang) → kernel CLEARS")

    // ── an EMPTY (optional) operand still concatenates as "" (must NOT be over-suppressed by the invalid gate) ─
    @Test fun optionalEmptyLeftOperandConcatsAsBlank() =
        tri(false, mapOf(SUFFIX to "XY"), "Code empty + OPTIONAL → concat as \"\" → VALUE -XY (not over-suppressed)")

    // ── a REQUIRED-empty operand: validation treats it as not-check-relevant (Unknown); this pins whether the
    //    CONCAT agrees — the kernel is the oracle, the tri-check asserts all three match whichever way. ────────
    @Test fun requiredEmptyLeftOperand() =
        tri(true, mapOf(SUFFIX to "XY"), "Code empty + REQUIRED → kernel/interp must agree")

    // ── the invalid operand on the RIGHT of the concat clears too (propagation through the concat tree) ──────
    @Test fun tooLongRightOperandClears() =
        tri(false, mapOf(CODE to "ABC", SUFFIX to "TOOLONGSUFFIX"), "Suffix len 13 > max 5 on the RIGHT → CLEARS")
}
