package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.LegalCharset
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A consumer-supplied charset governs a rule's and a computation's reads exactly as a model-declared one does (KERNEL-FINDINGS
 * KF555, measured against the kernel for the declared slot by `adapter.laws.SupportedCharactersComputeReadDiffTest`).
 * The model below declares none, so only the interpreter's charset can make `§` invalid; the document is built from
 * the model and so starts under the unrestricted default, which is the mismatch every call must resolve.
 */
class ConsumerCharsetReadTest {

    private val model = ModelDef(
        fields = listOf(FieldDef(COUNT, Kind.NUMBER, scale = 0), FieldDef(TAG, Kind.STRING)),
        rules = listOf(RuleDef("/Survey/Tagged", ROOT, "NumberOfFilledFields(Items*/Tag) > 1", "TAGGED", "../Count")),
        computations = listOf(
            ComputationDef(COUNT, ROOT, listOf(ComputationAlternative(null, "NumberOfFilledFields($TAG_STAR)"))),
        ),
        repeatableGroups = setOf(ITEMS),
    )

    private val ascii = DmInterpreter(model, legalCharset = LegalCharset.ofRanges(0x20..0x7e))

    @Test
    fun anUnfilteredStarReadsAValueOutsideTheConsumerCharsetAsInvalid() {
        assertEquals("CLEARED", outcome(ascii.compute(document("A", "§"))))
        assertEquals("CLEARED", outcome(ascii.computeField(COUNT, document("A", "§"))))
    }

    @Test
    fun anAuthoredRuleReadingItIsSilent() {
        assertEquals(listOf("ZeichenNichtImZeichensatz"), ascii.validate(document("A", "§")).messages.map { it.code })
        assertEquals(listOf("TAGGED"), DmInterpreter(model).validate(document("A", "§")).messages.map { it.code })
    }

    @Test
    fun theDefaultCharsetStillCountsIt() {
        assertEquals("VALUE|2", outcome(DmInterpreter(model).compute(document("A", "§"))))
        assertEquals("VALUE|2", outcome(ascii.compute(document("A", "B"))))
    }

    private fun document(vararg tags: String): Document {
        val doc = model.newDocument()
        tags.forEachIndexed { index, tag -> doc.group(ITEMS, 1, index + 1).field(TAG, tag, 1, index + 1, 1) }
        return doc
    }

    private fun outcome(results: List<Computed>): String {
        val result = results.single { it.path == COUNT }
        return when (result.outcome) {
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${result.value}"
        }
    }

    private companion object {
        const val ROOT = "/Survey"
        const val ITEMS = "/Survey/Items"
        const val COUNT = "/Survey/Count"
        const val TAG = "/Survey/Items/Tag"
        const val TAG_STAR = "/Survey/Items*/Tag"
    }
}
