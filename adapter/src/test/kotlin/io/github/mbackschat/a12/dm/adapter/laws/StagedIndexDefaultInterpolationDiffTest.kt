package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tri-engine control for the ONE corner IF217's staging split left unprobed (graduated from IG104(a)): a fired
 * message's `$field.value$` interpolation over a **staged** enum-index cell. IF217 proved the S-value reaches the
 * generated checks and authored-condition evaluation (`IndexDefaultStagingDiffTest`), but the §13 interpolation
 * estate ([UnspecifiedValueInterpolationDiffTest], [MessageValueDisplayPolicyDiffTest]) never saw a staged cell.
 *
 * The kernel splits the two reads (IF221): the staged S-value goes into the transient evaluation cache
 * (`datenCache`), which `$[field]$` CONDITION reads consult (so the rule FIRES on the staged token), while a
 * `$field.value$` MESSAGE interpolation reads `validationCache.getDaten().getValueForDisplay` — the ORIGINAL
 * caller document, which never sees that cache. So a staged cell interpolates as if EMPTY (→ the enum's empty
 * format default, `""`), even though the same token decided the condition. Both kernel routes agree; the
 * interpreter previously read its staged call-local view and rendered `STD` — the divergence this locks.
 *
 * E-commerce domain (own fixtures): an order's Lines are keyed by a ServiceLevel enum whose S-value defaults the
 * only line to standard shipping. Two staged arms (absent + present-empty index) render `[]`; the non-staged
 * EXPRESS baseline proves the interpolation still reads a genuinely present cell.
 */
class StagedIndexDefaultInterpolationDiffTest {

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val SERVICE_LEVEL = "/Order/Lines/ServiceLevel"
        const val QTY = "/Order/Lines/Qty"
        const val STD_CODE = "SL_STD"
        const val EXP_CODE = "SL_EXP"
    }

    /** A repeatable Lines group keyed by an enum index carrying the S-value "STD", with two rules that fire on a
     *  row whose ServiceLevel reads STD / EXPRESS and interpolate `$ServiceLevel.value$` into the error text. Both
     *  rules reference Qty (the always-true `[Qty] >= 0` conjunct the MVK error-field gate requires) and the
     *  interpolated ServiceLevel (the codegen `INVALID_FIELD` gate requires). Authored end-to-end through the
     *  typed surface (modelkit draft → EditSession → rulekit refs). */
    private val model: String = run {
        val base = DocumentModelDraft.named("order").locale("en_US")
            .rootGroup("Order") { o ->
                o.group("Lines") { lines: GroupBuilder ->
                    lines.repeatable(10).indexField("ServiceLevel")
                        .enumField("ServiceLevel") { e -> e.value("STD").value("EXPRESS").sValue("STD") }
                        .numberField("Qty") { }
                }
            }
            .build().toJson()
        EditSession.read(base).also { edit ->
            fun addFiringRule(name: String, code: String, value: String) = edit.addRule { bound ->
                val qty = bound.num(QTY)
                bound.rule(bound.group(LINES)).name(name).code(code).errorField(qty)
                    .`when`(qty.ge(0).and(bound.enumOf(SERVICE_LEVEL).`is`(value))).severity(Severity.ERROR)
                    .message(Dsl.en("service level [\$ServiceLevel.value\$]")).build()
            }
            addFiringRule("SlStd", STD_CODE, "STD")
            addFiringRule("SlExp", EXP_CODE, "EXPRESS")
            edit.validate()
        }.toJson()
    }

    private val definition = ModelLoader.load(model)

    /** Mirror one document build across the kernel [RuntimeLaws.Doc] and the interpreter document, then assert the
     *  fully-rendered error text for [code] agrees across dynamic-Groovy (normative), static-Java, and the
     *  interpreter, and equals [expected]. */
    private inner class Build {
        val kDoc = RuntimeLaws.doc().group(ORDER, 1)
        val iDoc = definition.newDocument().group(ORDER, 1)
        fun line(i: Int) { kDoc.group(LINES, 1, i); iDoc.group(LINES, 1, i) }
        fun qty(i: Int, v: String) { line(i); kDoc.field(QTY, v, 1, i, 1); iDoc.field(QTY, v, 1, i, 1) }
        fun emptyServiceLevel(i: Int) { line(i); kDoc.emptyField(SERVICE_LEVEL, 1, i, 1); iDoc.emptyField(SERVICE_LEVEL, 1, i, 1) }
        fun serviceLevel(i: Int, v: String) { line(i); kDoc.field(SERVICE_LEVEL, v, 1, i, 1); iDoc.field(SERVICE_LEVEL, v, 1, i, 1) }
    }

    private fun agree(code: String, expected: String, build: Build.() -> Unit) {
        val b = Build().apply(build)
        val dynamic = RuntimeLaws.errorTextOf(model, code, b.kDoc, Locale.US).orElseThrow()
        val java = RuntimeLaws.kernelJavaEngine(model)
        val generated = java.errorTextOf(java.instance(b.kDoc), code, Locale.US).orElseThrow()
        val interpreted = DmInterpreter(definition).validateFull(b.iDoc, "en_US").single { it.code == code }.text
        assertEquals(expected, dynamic, "normative Groovy-dynamic")
        assertEquals(dynamic, generated, "generated Java  kernel=$dynamic java=$generated")
        assertEquals(dynamic, interpreted, "interpreter  kernel=$dynamic interp=$interpreted")
    }

    /** Absent index → the rule FIRES on the staged STD (condition read the staging cache), but `$…value$`
     *  interpolates the ORIGINAL empty cell → the enum's empty default. */
    @Test fun stagedAbsentIndexInterpolatesTheOriginalEmptyCellNotTheStagedValue() =
        agree(STD_CODE, "service level []") { qty(1, "5") }

    /** Present-empty twin: same firing, same empty interpolation. */
    @Test fun stagedPresentEmptyIndexInterpolatesTheOriginalEmptyCell() =
        agree(STD_CODE, "service level []") { qty(1, "5"); emptyServiceLevel(1) }

    /** Non-staged baseline: a genuinely present EXPRESS cell interpolates its own value. */
    @Test fun explicitIndexValueInterpolatesTheGivenValue() =
        agree(EXP_CODE, "service level [EXPRESS]") { qty(1, "5"); serviceLevel(1, "EXPRESS") }
}
