package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — `FirstFilledValue` over a list of two or three DIRECT nonrepeatable DATE_RANGE fields, computed
 * into a fixed DATE_RANGE target of the same declaration profile (SPEC-2026-08-19-04).
 *
 * The fact worth locking is that the list has **two different traversal disciplines at once**. Selection is
 * authored-order LAZY: an empty source continues to the next, and the first source that is present — or that is
 * formally malformed — settles the computation, so nothing after it can change the result. The formal-input
 * inventory is EAGER and independent: it reports a malformed later source even when selection terminated
 * before reaching it. A test that only watched the target outcome would see the two as one.
 *
 * The model declares no Base Year, so the yearless `MM` list is measured where nothing can complete a year.
 *
 * Own domain (insurance claim windows).
 */
class DirectRangeFirstFilledListDiffTest {

    private val base = DocumentModelDraft.named("claim-windows").locale("en_US")
        .rootGroup("Claim") { claim ->
            claim.dateRangeField("PrimaryWindow") { it.format("MM").separator("/") }
            claim.dateRangeField("SecondaryWindow") { it.format("MM").separator("/") }
            claim.dateRangeField("TertiaryWindow") { it.format("MM").separator("/") }
            claim.dateRangeField("SelectedWindow") { it.format("MM").separator("/") }
            claim.dateRangeField("ExactPrimary") { it.format("yyyy-MM-dd").separator("/") }
            claim.dateRangeField("ExactSecondary") { it.format("yyyy-MM-dd").separator("/") }
            claim.dateRangeField("ExactSelected") { it.format("yyyy-MM-dd").separator("/") }
        }.build().toJson()

    private val m = BoundModel.ofJson(base)

    private val threeSourceModel = RuntimeLaws.withComputation(base, ROOT, "SelectedWindowComp", "../SelectedWindow",
        RuntimeLaws.render(ROOT, Dsl.firstFilledDateRange(
            m.dateRange("$ROOT/PrimaryWindow").field().firstFilledOperand(),
            m.dateRange("$ROOT/SecondaryWindow").field().firstFilledOperand(),
            m.dateRange("$ROOT/TertiaryWindow").field().firstFilledOperand()).expression()))

    private val twoSourceModel = RuntimeLaws.withComputation(base, ROOT, "ExactSelectedComp", "../ExactSelected",
        RuntimeLaws.render(ROOT, Dsl.firstFilledDateRange(
            m.dateRange("$ROOT/ExactPrimary").field().firstFilledOperand(),
            m.dateRange("$ROOT/ExactSecondary").field().firstFilledOperand()).expression()))

    /** `outcome|value` for [target] on all three engines, each pinned to [expected]. */
    private fun agree(modelJson: String, target: String, expected: String, vararg cells: Pair<String, String>) {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
        cells.forEach { (path, value) -> kernelDocument.field(path, value, 1, 1) }
        val dynamic = signature(RuntimeLaws.compute(modelJson, kernelDocument).firstOrNull { it.path() == target })
        val engine = RuntimeLaws.kernelJavaEngine(modelJson)
        val generated = signature(engine.compute(engine.instance(kernelDocument)).firstOrNull { it.path() == target })

        val definition = ModelLoader.load(modelJson)
        val document = definition.newDocument().group(ROOT, 1)
        cells.forEach { (path, value) -> document.field(path, value, 1, 1) }
        val computed = DmInterpreter(definition).compute(document).firstOrNull { it.path == target }
        val interpreted = when (computed?.outcome) {
            null -> "NONE"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED|${computed.value}"
            ComputeOutcome.VALUE -> "VALUE|${computed.value}"
        }

        assertEquals(expected, dynamic, "normative dynamic Groovy")
        assertEquals(dynamic, generated, "generated Java")
        assertEquals(dynamic, interpreted, "interpreter")
    }

    private fun signature(c: RuntimeLaws.Computed?): String = when {
        c == null -> "NONE"
        c.cleared() -> "CLEARED"
        c.errored() -> "ERRORED|${c.value().orElse("")}"
        else -> "VALUE|${c.value().orElse("")}"
    }

    @Test
    fun anEmptyPrefixContinuesToTheLastSource() =
        agree(threeSourceModel, SELECTED, "VALUE|05/08", TERTIARY to "05/08")

    @Test
    fun theFirstPresentSourceSettlesTheResult() =
        agree(threeSourceModel, SELECTED, "VALUE|06/09", SECONDARY to "06/09", TERTIARY to "01/12")

    /** The lazy half: a malformed source terminates selection, so the later filled source cannot be chosen. */
    @Test
    fun aMalformedSourceTerminatesBeforeALaterFilledOne() =
        agree(threeSourceModel, SELECTED, "CLEARED",
            SECONDARY to "xx/09", TERTIARY to "01/12", SELECTED to "01/02")

    @Test
    fun anAllEmptyListClearsASeededTarget() =
        agree(threeSourceModel, SELECTED, "CLEARED", SELECTED to "01/02")

    @Test
    fun aTwoSourceExactListRetainsItsEndpoints() =
        agree(twoSourceModel, EXACT_SELECTED, "VALUE|2024-06-01/2024-06-30",
            EXACT_SECONDARY to "2024-06-01/2024-06-30")

    /**
     * The eager half, and the reason this is one test and not two: selection stops at the first present source,
     * yet the malformed THIRD source — which selection never reads — is still reported as a formal input.
     */
    @Test
    fun aMalformedUnreadSuffixIsStillReportedAsAFormalInput() {
        val kernelDocument = RuntimeLaws.doc().group(ROOT, 1)
            .field(PRIMARY, "02/03", 1, 1)
            .field(TERTIARY, "xx/09", 1, 1)
        val channels = RuntimeLaws.resultChannels(threeSourceModel, kernelDocument)
        assertTrue(channels.formalErrorsInOperands().any { it.field().contains("TertiaryWindow") },
            "the kernel reports the unread malformed suffix: ${channels.formalErrorsInOperands()}")

        val prepared = PreparedModel.loadExpandedJson(threeSourceModel)
        val snapshot = prepared.documents.readDocument(
            RuntimeLaws.canonicalDocumentJson(threeSourceModel, kernelDocument))
        val report = prepared.createInterpreter().compute(snapshot)
        assertTrue(report.formalErrorsInOperands.any { it.errorPath.toString().contains("TertiaryWindow") },
            "the interpreter reports the same unread malformed suffix: ${report.formalErrorsInOperands}")

        agree(threeSourceModel, SELECTED, "VALUE|02/03", PRIMARY to "02/03", TERTIARY to "xx/09")
    }

    private companion object {
        const val ROOT = "/Claim"
        const val PRIMARY = "/Claim/PrimaryWindow"
        const val SECONDARY = "/Claim/SecondaryWindow"
        const val TERTIARY = "/Claim/TertiaryWindow"
        const val SELECTED = "/Claim/SelectedWindow"
        const val EXACT_SECONDARY = "/Claim/ExactSecondary"
        const val EXACT_SELECTED = "/Claim/ExactSelected"
    }
}
