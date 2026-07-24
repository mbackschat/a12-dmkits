package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A fired message's `$field.value$` interpolation over a STAGED enum-index cell, on the PURE engine, JVM AND
 * JS/Node — the cross-platform twin of `adapter.laws.StagedIndexDefaultInterpolationDiffTest` (whose
 * expectations are the real kernel's, dual-strategy; IF221). The staged S-value decides the CONDITION (the rule
 * fires), but `$field.value$` reads the ORIGINAL caller cell — empty for a staged (absent / present-empty) index
 * — so it interpolates as the enum's empty default. A genuinely present cell interpolates its own value.
 * E-commerce domain (own fixtures).
 */
class StagedIndexDefaultInterpolationMultiplatformTest {

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val SERVICE_LEVEL = "/Order/Lines/ServiceLevel"
        const val QTY = "/Order/Lines/Qty"
        const val TEMPLATE = "service level [\$ServiceLevel.value\$]"
    }

    private val model = ModelDef(
        listOf(
            FieldDef(SERVICE_LEVEL, Kind.STRING, enumValues = listOf("STD", "EXPRESS"), enumSValue = "STD"),
            FieldDef(QTY, Kind.NUMBER),
        ),
        listOf(
            RuleDef("$LINES/SlStd", LINES, "[Qty] >= 0 And [ServiceLevel] == \"STD\"", "SL_STD", "../Qty",
                messages = mapOf("en_US" to TEMPLATE)),
            RuleDef("$LINES/SlExp", LINES, "[Qty] >= 0 And [ServiceLevel] == \"EXPRESS\"", "SL_EXP", "../Qty",
                messages = mapOf("en_US" to TEMPLATE)),
        ),
        repeatableGroups = setOf(LINES),
        declaredReps = mapOf(LINES to 10),
        indexFields = setOf(SERVICE_LEVEL),
    )

    private fun textOf(code: String, doc: io.github.mbackschat.a12.dm.interpreter.eval.Document): String =
        DmInterpreter(model).validateFull(doc, "en_US").single { it.code == code }.text

    @Test
    fun stagedAbsentIndexInterpolatesTheOriginalEmptyCell() {
        val doc = model.newDocument().group(ORDER, 1).field(QTY, "5", 1, 1, 1)
        assertEquals("service level []", textOf("SL_STD", doc),
            "the staged token fired the rule but is not the display value")
    }

    @Test
    fun stagedPresentEmptyIndexInterpolatesTheOriginalEmptyCell() {
        val doc = model.newDocument().group(ORDER, 1).field(QTY, "5", 1, 1, 1).emptyField(SERVICE_LEVEL, 1, 1, 1)
        assertEquals("service level []", textOf("SL_STD", doc))
    }

    @Test
    fun explicitIndexValueInterpolatesTheGivenValue() {
        val doc = model.newDocument().group(ORDER, 1).field(QTY, "5", 1, 1, 1).field(SERVICE_LEVEL, "EXPRESS", 1, 1, 1)
        assertEquals("service level [EXPRESS]", textOf("SL_EXP", doc))
    }
}
