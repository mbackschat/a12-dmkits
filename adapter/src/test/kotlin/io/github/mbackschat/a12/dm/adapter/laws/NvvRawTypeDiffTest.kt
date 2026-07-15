package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) for the **nvv (raw-type) read
 * semantics** (IG71 → IF125). A `noValueValidation` STRING field's value never enters the kernel's eval
 * caches (`CheckCommand` interns `ValidationCache.PSEUDO_VALUE`), and the model tier closes every value
 * window (`MVK_INVALID_RAW_TYPE` / `MVK_INVALID_LENGTH_OF_RAW_TYPE` / `INVALID_NO_VALUE_VALIDATION` — the
 * accept/reject laws are `validate.laws.NvvRawTypeLawsTest`) except ONE: a rule whose whole condition is
 * `Length(nvv) > constant` (or mirrored `constant < Length(nvv)`), which the codegen ELIMINATES — it never
 * becomes runtime code; its (field, c) only tightens the generated meta-model's max length
 * (`ValidationCodeGenerator.handleLengthOfAllowedValueValidationRule`). So at runtime the rule NEVER fires,
 * in either strategy, in full AND partial validation — while presence semantics over the same nvv cell
 * (`FieldFilled`, quantifiers, group-content counts) are ordinary. Multiplatform twin:
 * `interpreter … NvvEliminatedLengthRuleTest`.
 */
class NvvRawTypeDiffTest {

    private companion object {
        const val NOTE = "/Claim/IncidentNote"
        const val OTHER = "/Claim/Other"
        const val REF = "/Claim/Ref"
        const val BLOB = "/Claim/Attachments/Blob"

        val json: String = DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", Consumer { g ->
                g.stringField("IncidentNote") { n -> n.lineBreaks().noValueValidation() }
                    .stringField("Other") {}
                    .stringField("Ref") {}
                    .group("Attachments") { a ->
                        a.repeatable(5)
                            .stringField("Blob") { b -> b.lineBreaks().noValueValidation() }
                            .stringField("Name") {}
                    }
            })
            .build().toJson()
    }

    /** Fired codes for the rule model under (fill, empty), per engine — (groovy, java-static, interpreter). */
    private fun fired(cond: String, errRel: String, fill: Map<String, List<Int>?>): Triple<List<String>, List<String>, List<String>> {
        val model = RuntimeLaws.withRule(json, "/Claim", "R_X", errRel, "X", cond)
        val kDoc = RuntimeLaws.doc().group("/Claim", 1)
        fill.forEach { (p, reps) -> kDoc.field(p, "v-${p.substringAfterLast('/')}", *(reps ?: listOf(1, 1)).toIntArray()) }
        val k = RuntimeLaws.messages(model, kDoc).map { it.code() }.sorted()
        val engine = RuntimeLaws.kernelJavaEngine(model)
        val j = engine.messages(engine.instance(kDoc)).map { it.code() }.sorted()
        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Claim", 1)
        fill.forEach { (p, reps) -> iDoc.field(p, "v-${p.substringAfterLast('/')}", *(reps ?: listOf(1, 1)).toIntArray()) }
        val i = DmInterpreter(def).validateFull(iDoc).map { it.code }.sorted()
        return Triple(k, j, i)
    }

    private fun triAgree(cond: String, errRel: String, expected: List<String>, fill: Map<String, List<Int>?>, label: String) {
        val (k, j, i) = fired(cond, errRel, fill)
        assertEquals(expected, k, "$label: kernel (groovy-dynamic)")
        assertEquals(k, j, "$label: groovy-dynamic vs java-static")
        assertEquals(k, i, "$label: kernel vs interpreter")
    }

    // The eliminated meta-length shape: never fires, any value, any bound — both directions of the mirror.
    @Test fun eliminatedLengthRuleNeverFires() =
        triAgree("Length(IncidentNote) > 5", "../IncidentNote", emptyList(),
            mapOf(NOTE to null), "Length(nvv) > 5 over a 6-unit value")

    @Test fun eliminatedLengthRuleNeverFiresAtAlwaysTrueBound() =
        triAgree("Length(IncidentNote) > -1", "../IncidentNote", emptyList(),
            mapOf(NOTE to null), "Length(nvv) > -1 over a filled value")

    @Test fun mirroredEliminatedFormNeverFires() =
        triAgree("-1 < Length(IncidentNote)", "../IncidentNote", emptyList(),
            mapOf(NOTE to null), "-1 < Length(nvv) over a filled value")

    // Presence semantics over the SAME nvv cell are ordinary — the pseudo-interned cell is content.
    @Test fun presenceOverNvvFires() =
        triAgree("FieldFilled(IncidentNote)", "../IncidentNote", listOf("X"),
            mapOf(NOTE to null), "FieldFilled(nvv) filled")

    @Test fun quantifierOverNvvFires() =
        triAgree("AllFieldsFilled(IncidentNote, Other)", "../Other", listOf("X"),
            mapOf(NOTE to null, OTHER to null), "AllFieldsFilled(nvv, other) both filled")

    @Test fun negativeQuantifierOverNvvFires() =
        triAgree("NoFieldFilled(IncidentNote, Other)", "../Other", listOf("X"),
            mapOf(REF to null), "NoFieldFilled(nvv, other) on a content-bearing row")

    @Test fun aggregateCountsNvvContent() =
        triAgree("FieldFilled(Ref) And NumberOfFilledFields(Attachments*/Blob) > 0", "../Ref", listOf("X"),
            mapOf(REF to null, BLOB to listOf(1, 1, 1)), "count over nvv cells in a repeatable group")

    // Partial validation: the elimination is mode-independent (the rule does not exist in the generated
    // code), presence stays ordinary.
    @Test fun partialValidationSameElimination() {
        val mLen = RuntimeLaws.withRule(json, "/Claim", "R_L", "../IncidentNote", "L", "Length(IncidentNote) > 5")
        val mPres = RuntimeLaws.withRule(json, "/Claim", "R_P", "../IncidentNote", "P", "FieldFilled(IncidentNote)")
        val rel = setOf(RuntimeLaws.Relevant(NOTE, listOf(1, 1)))
        val iRel = setOf(RelevantEntity(NOTE, listOf(1, 1)))
        for ((label, model, expected) in listOf(
            Triple("partial Length(nvv) > 5", mLen, emptyList()),
            Triple("partial FieldFilled(nvv)", mPres, listOf("P")),
        )) {
            val kDoc = RuntimeLaws.doc().group("/Claim", 1).field(NOTE, "ABCDEF", 1, 1)
            val k = RuntimeLaws.messagesPart(model, kDoc, rel).map { it.code() }.sorted()
            val engine = RuntimeLaws.kernelJavaEngine(model)
            val j = engine.messagesPart(kDoc, rel).map { it.code() }.sorted()
            val def = ModelLoader.load(model)
            val iDoc = def.newDocument().group("/Claim", 1).field(NOTE, "ABCDEF", 1, 1)
            val i = DmInterpreter(def).validatePart(iDoc, iRel).messages.map { it.code }.sorted()
            assertEquals(expected, k, "$label: kernel (groovy-dynamic)")
            assertEquals(k, j, "$label: groovy-dynamic vs java-static")
            assertEquals(k, i, "$label: kernel vs interpreter")
        }
    }
}
