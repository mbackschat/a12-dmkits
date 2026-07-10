package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF113 — the polarity of a fired string comparison over a CONCATENATION
 * carrying a NOT-GIVEN part (an empty field read, a no-match indexed cell, a not-given `FieldValueAsString`):
 * the concat stays fillable, so the fired mismatch is OMISSION ("filling could change it"); a fully-given
 * concat mismatch is VALUE. Kernel-verified by the tri-check differential
 * `adapter.laws.ConcatNotGivenPolarityDiffTest`.
 */
class ConcatNotGivenPolarityTest {

    // ── shape 1: an explicit rule `[Label] != [Code] + "-suffix"` ─────────────────────────────────────────

    private val productDef = ModelDef(
        fields = listOf(FieldDef("/Product/Code", Kind.STRING), FieldDef("/Product/Label", Kind.STRING)),
        rules = listOf(RuleDef("/Product/LabelRule", "/Product", "[Label] != [Code] + \"-suffix\"",
            "LBL", "../Label")),
        computations = emptyList(),
    )

    private fun ruleSigs(code: String?, label: String?): List<String> {
        val doc = productDef.newDocument().group("/Product", 1)
        code?.let { doc.field("/Product/Code", it, 1, 1) }
        label?.let { doc.field("/Product/Label", it, 1, 1) }
        return DmInterpreter(productDef).validateFull(doc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "O" else "V"}" }.sorted()
    }

    @Test fun aFiredMismatchOverAnEmptyConcatPartIsOmission() =
        assertEquals(listOf("LBL|O"), ruleSigs(code = null, label = "X"))

    @Test fun aFiredMismatchOverAFullyGivenConcatIsValue() =
        assertEquals(listOf("LBL|V"), ruleSigs(code = "AB", label = "X"))

    @Test fun aMatchingLabelIsSilent() =
        assertEquals(emptyList(), ruleSigs(code = "AB", label = "AB-suffix"))

    // ── shape 2: the implicit computation-validation of an indexed concat (the corpus instance) ───────────

    private val shopDef = ModelDef(
        fields = listOf(
            FieldDef("/Shop/LookupKey", Kind.NUMBER, scale = 0), FieldDef("/Shop/Info", Kind.STRING),
            FieldDef("/Shop/Items/ItemNo", Kind.NUMBER, scale = 0), FieldDef("/Shop/Items/Label", Kind.STRING),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef("/Shop/Info", "/Shop",
            listOf(ComputationAlternative(null, "\"P \" + [Items/Label For LookupKey] + \" Q\"")),
            name = "InfoComp")),
        repeatableGroups = setOf("/Shop/Items"),
        indexFields = setOf("/Shop/Items/ItemNo"),
    )

    private fun implicitSigs(key: String): List<String> {
        val doc = shopDef.newDocument().group("/Shop", 1)
            .field("/Shop/LookupKey", key, 1, 1).field("/Shop/Info", "WRONG", 1, 1)
        doc.group("/Shop/Items", 1, 1).field("/Shop/Items/ItemNo", "12", 1, 1, 1)
            .field("/Shop/Items/Label", "AB", 1, 1, 1)
        return DmInterpreter(shopDef).validateFull(doc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "O" else "V"}" }.sorted()
    }

    @Test fun aNoMatchIndexedConcatMismatchIsOmission() =
        assertEquals(listOf("InfoComp|O"), implicitSigs("274"))

    @Test fun aMatchedIndexedConcatMismatchIsValue() =
        assertEquals(listOf("InfoComp|V"), implicitSigs("12"))
}
