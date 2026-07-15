package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the ELIMINATED nvv meta-length rule (IG71 → IF125): a rule whose WHOLE
 * condition is `Length(nvvField) > constant` (or the mirrored `constant < Length(nvvField)`) is the kernel's
 * disguised max-length declaration — the codegen REMOVES it (`handleLengthOfAllowedValueValidationRule`,
 * both strategies) and only tightens the generated meta-model's max length, so at runtime it NEVER fires,
 * for any value or constant. The interpreter mirrors the elimination at the rule level; presence semantics
 * over the same nvv field are untouched. The kernel differential (both strategies) is
 * `adapter.laws.NvvRawTypeDiffTest`; this pins the interpreter's outcomes on both platforms.
 */
class NvvEliminatedLengthRuleTest {

    private companion object {
        const val NOTE = "/Claim/IncidentNote"   // nvv (raw-type) string
        const val REF = "/Claim/Ref"             // plain string control
    }

    private fun model(cond: String) = ModelDef(
        fields = listOf(
            FieldDef(NOTE, Kind.STRING, noValueValidation = true, lineBreaksPermitted = true),
            FieldDef(REF, Kind.STRING),
        ),
        rules = listOf(RuleDef("/Claim/R", "/Claim", cond, "X", "../IncidentNote")),
    )

    private fun fired(cond: String, vararg fill: Pair<String, String>): List<String> {
        val m = model(cond)
        val doc = m.newDocument().group("/Claim", 1)
        for ((p, v) in fill) doc.field(p, v, 1, 1)
        return DmInterpreter(m).validateFull(doc).map { it.code }
    }

    // The eliminated shape never fires — not on a long value, not at an always-true bound.
    @Test fun eliminatedShapeNeverFires() =
        assertEquals(emptyList(), fired("Length(IncidentNote) > 5", NOTE to "ABCDEF"))

    @Test fun eliminatedShapeNeverFiresAtAlwaysTrueBound() =
        assertEquals(emptyList(), fired("Length(IncidentNote) > -1", NOTE to "ABCDEF"))

    // The mirrored allowed form `constant < Length(nvv)` is the same declaration — eliminated too.
    @Test fun mirroredShapeIsEliminated() =
        assertEquals(emptyList(), fired("-1 < Length(IncidentNote)", NOTE to "ABCDEF"))

    // The gate is nvv-scoped and shape-scoped: a PLAIN field's Length rule still fires…
    @Test fun plainFieldLengthRuleStillFires() =
        assertEquals(listOf("X"), fired("Length(Ref) > 5", REF to "ABCDEF"))

    // …and presence over the SAME nvv field is untouched.
    @Test fun presenceOverNvvStillFires() =
        assertEquals(listOf("X"), fired("FieldFilled(IncidentNote)", NOTE to "ABCDEF"))

    // testRule reports the eliminated rule PASSED (never fires — no message, no suppression), kernel-parity
    // with a rule that does not exist in the generated validation.
    @Test fun testRuleReportsEliminatedRulePassed() {
        val m = model("Length(IncidentNote) > 5")
        val doc = m.newDocument().group("/Claim", 1).field(NOTE, "ABCDEF", 1, 1)
        assertEquals(Verdict.PASSED, DmInterpreter(m).testRule("/Claim/R", doc).verdict)
    }
}
