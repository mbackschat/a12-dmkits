package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Differential — **an entity-list extremum's operand gate reads each operand's declared FORMAT too, and the
 * three engines agree on what the resulting extremum computes.**
 *
 * The sibling [CrossKindTemporalComparisonGateDiffTest] settled this for the direct comparison. The extremum
 * was a different gate reached through a different checker arm
 * (`CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType`), and both of our own classifiers for it
 * read the KIND until 2026-09-07 — so `MaxValue(<TIME yyyy-MM-dd>, <DATE yyyy-MM-dd>)` was unauthorable
 * through the typed surface and unreadable through the adapter, on input the kernel admits
 * (`rulekit.validate.laws.TemporalAggregateComponentSetLawsTest`,
 * `adapter.CrossKindTemporalOperandReadTest`).
 *
 * **This file is the RUNTIME half of that fix, and it exists because the accept/reject half cannot stand in
 * for it.** Making a shape newly authorable puts it in front of the interpreter for the first time, and an
 * `IG` divergence there is invisible to a consistency oracle. So each admitted arm is run on all three engines
 * — dynamic Groovy (normative), generated Java, and the kernel-free interpreter — in both the fires and the
 * does-not-fire direction, over one document.
 *
 * Own domain (freight shipments).
 */
// One instance per CLASS: the model JSON, a kernel Java engine per rule arm and the loaded interpreter model
// are held as fields, and this Gradle task also carries clock-reading assertions elsewhere.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossKindTemporalExtremumDiffTest {

    private companion object {
        const val DATE_FORMAT = "yyyy-MM-dd"
        const val CLOCK_FORMAT = "HH:mm:ss"

        /** The carriers: a TIME and a DATE_TIME field each declaring a complete DATE. */
        const val CHECKED_AS_DATE = "/Shipment/CheckedAsDate"   // kind TIME, declared yyyy-MM-dd
        const val LOGGED_AS_DATE = "/Shipment/LoggedAsDate"     // kind DATE_TIME, declared yyyy-MM-dd

        /** The ordinary counterparts, each declared in its own kind's natural format. */
        const val DISPATCHED_ON = "/Shipment/DispatchedOn"      // kind DATE, declared yyyy-MM-dd
        const val CLEARED_ON = "/Shipment/ClearedOn"            // kind DATE, declared yyyy-MM-dd
        const val GATE_CLOCK = "/Shipment/GateClock"            // kind TIME, declared HH:mm:ss

        const val SET_MISMATCH = "MVK_DATEFORMATS_NOT_COMPATIBLE"
        const val ADMITTED = "ADMITTED"

        val MODEL: String = DocumentModelDraft.named("cross-kind-temporal-extremum").locale("en_US")
            .rootGroup("Shipment") { s ->
                s.timeField("CheckedAsDate") { it.format(DATE_FORMAT) }
                    .dateTimeField("LoggedAsDate") { it.format(DATE_FORMAT) }
                    .dateField("DispatchedOn") { it.format(DATE_FORMAT) }
                    .dateField("ClearedOn") { it.format(DATE_FORMAT) }
                    .timeField("GateClock") { it.format(CLOCK_FORMAT) }
            }.build().toJson()

        /** The typed authoring surface over the same model — `ofJson` runs the kernel's own consistency check. */
        val M: BoundModel by lazy { BoundModel.ofJson(MODEL) }
    }

    private val java = HashMap<String, RuntimeLaws.JavaKernelEngine>()
    private val loaded = HashMap<String, io.github.mbackschat.a12.dm.interpreter.model.ModelDef>()

    /**
     * The declarations this whole file rests on, read out of the model bytes rather than assumed from the
     * builder call. A model that normalized a contradicting format back to its kind's own would leave every
     * row below measuring two ordinary dates.
     */
    @Test
    fun bothCarriersReallyDeclareAFormatTheirKindDoesNotOwn() {
        assertTrue(Regex("\"CheckedAsDate\"[\\s\\S]{0,400}?\"TimeType\"").containsMatchIn(MODEL),
            "the date-declared carrier must be TIME-kind, or its rows are not cross-kind")
        assertTrue(Regex("\"CheckedAsDate\"[\\s\\S]{0,400}?\"$DATE_FORMAT\"").containsMatchIn(MODEL),
            "…and it must really declare the date format")
        assertTrue(Regex("\"LoggedAsDate\"[\\s\\S]{0,400}?\"DateTimeType\"").containsMatchIn(MODEL),
            "the second carrier must be DATE_TIME-kind")
        assertTrue(Regex("\"LoggedAsDate\"[\\s\\S]{0,400}?\"$DATE_FORMAT\"").containsMatchIn(MODEL),
            "…and it too must really declare the date format")
    }

    /**
     * **The admission table, and its within-model control.** Both cross-kind lists are admitted; the same TIME
     * kind carrying its own kind's clock format beside the same DATE is refused. So the gate is the
     * declaration's component set, not the kinds' classes — the reading our two classifiers had encoded.
     */
    @Test
    fun theExtremumGateFollowsTheDeclaredFormatAndNotTheKind() {
        assertEquals(
            mapOf(
                "MaxValue over a date-declared TIME beside a DATE" to ADMITTED,
                "MinValue over a date-declared DATE_TIME beside a DATE" to ADMITTED,
                "MaxValue over both carriers together" to ADMITTED,
                "MaxValue over a CLOCK-declared TIME beside a DATE — the control" to SET_MISMATCH,
            ),
            mapOf(
                "MaxValue over a date-declared TIME beside a DATE"
                    to verdictOf("MaxValue($CHECKED_AS_DATE, $DISPATCHED_ON) == [$CLEARED_ON]", "../ClearedOn"),
                "MinValue over a date-declared DATE_TIME beside a DATE"
                    to verdictOf("MinValue($LOGGED_AS_DATE, $DISPATCHED_ON) == [$CLEARED_ON]", "../ClearedOn"),
                "MaxValue over both carriers together"
                    to verdictOf("MaxValue($CHECKED_AS_DATE, $LOGGED_AS_DATE) == [$CLEARED_ON]", "../ClearedOn"),
                "MaxValue over a CLOCK-declared TIME beside a DATE — the control"
                    to verdictOf("MaxValue($GATE_CLOCK, $DISPATCHED_ON) == [$CLEARED_ON]", "../ClearedOn"),
            ),
            "the extremum's operand gate compares declared component sets",
        )
    }

    /**
     * The TIME carrier's arm on all three engines, both directions. The document gives the carrier the LATER
     * date in both rows, so the extremum is its value and only `ClearedOn` moves — which is what makes this a
     * measurement of what the extremum COMPUTES rather than of the comparison alone. The rule's condition is
     * its ERROR condition, so it fires when the equality holds.
     */
    @Test
    fun theTimeCarriersExtremumComputesTheSameOnAllThreeEngines() {
        val arm = ruleModel("MaxValue($CHECKED_AS_DATE, $DISPATCHED_ON) == [$CLEARED_ON]", "../ClearedOn")
        assertEquals(listOf("RR"),
            triCodes(arm,
                CHECKED_AS_DATE to "2024-06-15", DISPATCHED_ON to "2024-06-01", CLEARED_ON to "2024-06-15"),
            "the max is the date-declared TIME field's later date, so the equality holds and the rule fires")
        assertEquals(emptyList<String>(),
            triCodes(arm,
                CHECKED_AS_DATE to "2024-06-15", DISPATCHED_ON to "2024-06-01", CLEARED_ON to "2024-06-01"),
            "and it does not fire against the OTHER operand's value, which the max is not")
    }

    /** The DATE_TIME carrier's arm, so the agreement is not one carrier's property. */
    @Test
    fun theDateTimeCarriersExtremumComputesTheSameOnAllThreeEngines() {
        val arm = ruleModel("MinValue($LOGGED_AS_DATE, $DISPATCHED_ON) == [$CLEARED_ON]", "../ClearedOn")
        assertEquals(listOf("RR"),
            triCodes(arm,
                LOGGED_AS_DATE to "2024-05-02", DISPATCHED_ON to "2024-06-01", CLEARED_ON to "2024-05-02"),
            "the min is the date-declared DATE_TIME field's earlier date, so the rule fires")
        assertEquals(emptyList<String>(),
            triCodes(arm,
                LOGGED_AS_DATE to "2024-05-02", DISPATCHED_ON to "2024-06-01", CLEARED_ON to "2024-06-01"),
            "and it does not fire against the OTHER operand's value, which the min is not")
    }

    /**
     * The typed surface reaches both admitted arms — the authoring mirror of the table above, asserted here
     * rather than trusted, because a client-side refusal is invisible to every engine.
     */
    @Test
    fun theTypedSurfaceAuthorsBothAdmittedArms() {
        assertEquals("MaxValue($CHECKED_AS_DATE, $DISPATCHED_ON)",
            io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer().render(
                io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.latestOf(
                    M.field(CHECKED_AS_DATE), M.field(DISPATCHED_ON)).expression()))
        assertEquals("MinValue($LOGGED_AS_DATE, $DISPATCHED_ON)",
            io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer().render(
                io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.earliestOf(
                    M.field(LOGGED_AS_DATE), M.field(DISPATCHED_ON)).expression()))
    }

    // ---- the instruments ----

    private fun ruleModel(condition: String, errorRel: String): String =
        RuntimeLaws.withRule(MODEL, "/Shipment", "RR", errorRel, "RR", condition)

    /** [ADMITTED], or the distinct MVK codes the kernel's consistency oracle reported for [condition]. */
    private fun verdictOf(condition: String, errorRel: String): String {
        val messages = RuntimeLaws.consistencyMessages(ruleModel(condition, errorRel))
        if (messages.isEmpty()) return ADMITTED
        val codes = messages.mapNotNull { Regex("(MVK_[A-Z_]+)").find(it)?.groupValues?.get(1) }.distinct().sorted()
        return codes.joinToString(", ").ifEmpty { messages.joinToString(", ") }
    }

    /** The fired codes agreed by dynamic Groovy, generated Java and the interpreter over one document. */
    private fun triCodes(model: String, vararg cells: Pair<String, String>): List<String> {
        val groovy = RuntimeLaws.messages(model, kernelDoc(*cells)).map { it.code() }.sorted()
        val engine = java.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
        assertEquals(groovy, engine.firedCodesSorted(engine.instance(kernelDoc(*cells))),
            "generated-Java vs normative dynamic-Groovy")
        val def = loaded.getOrPut(model) { ModelLoader.load(model) }
        val iDoc = def.newDocument().group("/Shipment", 1)
        for ((path, value) in cells) iDoc.field(path, value, 1, 1)
        assertEquals(groovy, DmInterpreter(def).validateFull(iDoc).map { it.code }.sorted(),
            "interpreter vs normative dynamic-Groovy")
        return groovy
    }

    private fun kernelDoc(vararg cells: Pair<String, String>): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Shipment", 1).also { d ->
            for ((path, value) in cells) d.field(path, value, 1, 1)
        }
}
