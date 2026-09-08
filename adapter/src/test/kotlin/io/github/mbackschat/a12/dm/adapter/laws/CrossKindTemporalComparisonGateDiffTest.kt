package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Differential — **the comparison-admission gate partitions temporal operands by their declared FORMAT, and a
 * cross-KIND pair is admitted whenever the formats agree.** Reproduces a12-kernel-lean's `SPEC-2026-09-05-13`
 * on independently authored carriers, and independently rather than on their report: an inbound measurement is
 * their observation, and a fact this repository's own artifacts rest on gets measured here.
 *
 * **Why this is not covered by what we already hold.** [TemporalDeclaredFormatGateDiffTest] establishes the
 * same rule for the CLASSIFIER — which formal check and which value decoder a declaration selects — on the
 * complete 8-format × 3-kind grid ([KF211](../../../../../../../../../docs/KERNEL-FINDINGS.md)), and it does
 * measure the comparison gate. But it measures it on ONE arm: a date-declared TIME against a date-declared
 * DATE, plus the ordinary clock-versus-date refusal as its control. Its model declares no clock-formatted DATE
 * at all. So "the format decides the comparison partition too" was established for one direction and, for the
 * mirror, inherited from the classifier rule — a different gate. Inheriting a proved mechanism across a gate is
 * a scope transfer, so this measures the mirror instead of assuming it.
 *
 * **The grid is a within-model 2×2 and every cell shares one model**, so nothing unnoticed can differ between
 * the arms: each CARRIER is a temporal field whose kind and declared format disagree, and each is compared
 * against both an ordinary clock and an ordinary date. Each kind therefore appears on both sides of both
 * outcomes, which is what rules out "DATE is the permissive kind" or "TIME is" and leaves the format — one
 * direction alone would not have. The two ordinary fields double as the controls a12-kernel-lean's entry
 * carries: comparing them to each other is refused.
 *
 * Own domain (long-distance rail reservations).
 */
// One instance per CLASS: this file holds its model JSON and a kernel Java engine per rule arm as fields, and
// this Gradle task also carries clock-reading assertions. Per-method lifecycle would redo codegen and model
// loading for every case (CLAUDE.md: a new test must not perturb a timing floor sharing its task).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossKindTemporalComparisonGateDiffTest {

    private companion object {
        const val CLOCK_FORMAT = "HH:mm:ss"
        const val DATE_FORMAT = "yyyy-MM-dd"
        const val STAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"

        /** The two CARRIERS — a temporal field whose declared format contradicts its kind. */
        const val DEPARTS_AS_CLOCK = "/Trip/DepartsAsClock"   // kind DATE, declared HH:mm:ss
        const val WINDOW_AS_DATE = "/Trip/WindowAsDate"       // kind TIME, declared yyyy-MM-dd

        /** The two ORDINARY counterparts, each declared in its own kind's natural format. */
        const val BOARDING_CLOCK = "/Trip/BoardingClock"      // kind TIME, declared HH:mm:ss
        const val SERVICE_DATE = "/Trip/ServiceDate"          // kind DATE, declared yyyy-MM-dd

        /** The DATE_TIME family's two carriers and its ordinary counterpart — see [theDateTimeFamilyObeysTheSameRule]. */
        const val ARRIVES_AS_STAMP = "/Trip/ArrivesAsStamp"   // kind DATE, declared yyyy-MM-dd'T'HH:mm:ss
        const val LOG_AS_DATE = "/Trip/LogAsDate"             // kind DATE_TIME, declared yyyy-MM-dd
        const val SERVICE_STAMP = "/Trip/ServiceStamp"        // kind DATE_TIME, declared yyyy-MM-dd'T'HH:mm:ss

        const val FAMILY_MISMATCH = "MVK_INVALID_COMPARE_TO_DATE"
        const val ADMITTED = "ADMITTED"

        val MODEL: String = DocumentModelDraft.named("cross-kind-temporal-comparison").locale("en_US")
            .rootGroup("Trip") { t ->
                t.dateField("DepartsAsClock") { it.format(CLOCK_FORMAT) }
                    .timeField("WindowAsDate") { it.format(DATE_FORMAT) }
                    .timeField("BoardingClock") { it.format(CLOCK_FORMAT) }
                    .dateField("ServiceDate") { it.format(DATE_FORMAT) }
                    .dateField("ArrivesAsStamp") { it.format(STAMP_FORMAT) }
                    .dateTimeField("LogAsDate") { it.format(DATE_FORMAT) }
                    .dateTimeField("ServiceStamp") { it.format(STAMP_FORMAT) }
            }.build().toJson()

        /** The typed authoring surface over the same model — `ofJson` runs the kernel's own consistency check. */
        val M: BoundModel by lazy { BoundModel.ofJson(MODEL) }

        fun render(condition: Condition): String = DslRenderer().render(condition)
    }

    private val java = HashMap<String, RuntimeLaws.JavaKernelEngine>()
    private val loaded = HashMap<String, io.github.mbackschat.a12.dm.interpreter.model.ModelDef>()

    /**
     * The declarations this whole file is about, read out of the model bytes rather than assumed from the
     * builder call. A model that quietly normalized a contradicting format to its kind's own would make every
     * row below a measurement of two ordinary fields.
     */
    @Test
    fun bothCarriersReallyDeclareAFormatTheirKindDoesNotOwn() {
        assertTrue(Regex("\"DepartsAsClock\"[\\s\\S]{0,400}?\"$CLOCK_FORMAT\"").containsMatchIn(MODEL),
            "the DATE-kind carrier must declare the clock format, or its rows measure an ordinary date")
        assertTrue(Regex("\"WindowAsDate\"[\\s\\S]{0,400}?\"$DATE_FORMAT\"").containsMatchIn(MODEL),
            "the TIME-kind carrier must declare the date format, or its rows measure an ordinary clock")
        assertTrue(Regex("\"ArrivesAsStamp\"[\\s\\S]{0,400}?\"DateType\"").containsMatchIn(MODEL),
            "the stamp-declared carrier must be DATE-kind, or the DateTime rows are not cross-kind")
        assertTrue(Regex("\"LogAsDate\"[\\s\\S]{0,400}?\"DateTimeType\"").containsMatchIn(MODEL),
            "and the date-declared carrier must be DATE_TIME-kind")
    }

    /**
     * **The whole claim, as one table.** A clock-declared DATE compares with a clock and not with a date; a
     * date-declared TIME compares with a date and not with a clock. Both kinds sit on both sides of both
     * outcomes, so the partition cannot be read as a property of either kind.
     */
    @Test
    fun theComparisonPartitionFollowsTheDeclaredFormatAcrossBothKinds() {
        assertEquals(
            mapOf(
                "clock-declared DATE vs clock" to ADMITTED,
                "clock-declared DATE vs date" to FAMILY_MISMATCH,
                "date-declared TIME vs date" to ADMITTED,
                "date-declared TIME vs clock" to FAMILY_MISMATCH,
            ),
            mapOf(
                "clock-declared DATE vs clock" to
                    verdictOf(render(M.time(DEPARTS_AS_CLOCK).eq(M.time(BOARDING_CLOCK))), "../DepartsAsClock"),
                "clock-declared DATE vs date" to
                    verdictOf("[$DEPARTS_AS_CLOCK] == [$SERVICE_DATE]", "../DepartsAsClock"),
                "date-declared TIME vs date" to
                    verdictOf(render(M.date(WINDOW_AS_DATE).eq(M.date(SERVICE_DATE))), "../WindowAsDate"),
                "date-declared TIME vs clock" to
                    verdictOf("[$WINDOW_AS_DATE] == [$BOARDING_CLOCK]", "../WindowAsDate"),
            ),
            "the declared format decides the operand family at the comparison gate, on both kinds",
        )
    }

    /**
     * **The DATE_TIME family, which both estates had left explicitly open.** a12-kernel-lean's
     * `SPEC-2026-09-05-13` limited itself to the DATE/TIME pair under equality and recorded the DateTime
     * family's cross-kind comparison as an untested gap; so did we, four times across that exchange. Neither
     * side's grid reached it: [KF211](../../../../../../../../../docs/KERNEL-FINDINGS.md#kf211)'s 12 × 3
     * vocabulary grid covers a DATE_TIME *declaration* for the CLASSIFIER, which is a different gate.
     *
     * The same 2×2, one family over: a stamp-declared DATE compares with a stamp and not with a date, and a
     * date-declared DATE_TIME does the reverse. Both kinds again sit on both sides of both outcomes.
     */
    @Test
    fun theDateTimeFamilyObeysTheSameRule() {
        assertEquals(
            mapOf(
                "stamp-declared DATE vs stamp" to ADMITTED,
                "stamp-declared DATE vs date" to FAMILY_MISMATCH,
                "date-declared DATE_TIME vs date" to ADMITTED,
                "date-declared DATE_TIME vs stamp" to FAMILY_MISMATCH,
                "ordinary stamp vs ordinary date" to FAMILY_MISMATCH,
            ),
            mapOf(
                "stamp-declared DATE vs stamp" to
                    verdictOf(render(M.dateTime(ARRIVES_AS_STAMP).eq(M.dateTime(SERVICE_STAMP))),
                        "../ArrivesAsStamp"),
                "stamp-declared DATE vs date" to
                    verdictOf("[$ARRIVES_AS_STAMP] == [$SERVICE_DATE]", "../ArrivesAsStamp"),
                "date-declared DATE_TIME vs date" to
                    verdictOf(render(M.date(LOG_AS_DATE).eq(M.date(SERVICE_DATE))), "../LogAsDate"),
                "date-declared DATE_TIME vs stamp" to
                    verdictOf("[$LOG_AS_DATE] == [$SERVICE_STAMP]", "../LogAsDate"),
                "ordinary stamp vs ordinary date" to
                    verdictOf("[$SERVICE_STAMP] == [$SERVICE_DATE]", "../ServiceStamp"),
            ),
            "the declared format decides the operand family for the DateTime family too",
        )
    }

    /** And both admitted DateTime arms are RUN, for the reason the DATE/TIME arms are. */
    @Test
    fun bothAdmittedDateTimeArmsAgreeOnAllThreeEnginesWhenTheyFire() {
        val stampArm = ruleModel(render(M.dateTime(ARRIVES_AS_STAMP).eq(M.dateTime(SERVICE_STAMP))),
            "../ArrivesAsStamp")
        assertEquals(listOf("RR"), triCodes(stampArm,
            ARRIVES_AS_STAMP to "2024-06-15T10:30:00", SERVICE_STAMP to "2024-06-15T10:30:00"),
            "equal stamps fire across the kind boundary")
        assertEquals(emptyList<String>(), triCodes(stampArm,
            ARRIVES_AS_STAMP to "2024-06-15T10:30:00", SERVICE_STAMP to "2024-06-15T11:45:00"),
            "and unequal ones do not")

        val dateArm = ruleModel(render(M.date(LOG_AS_DATE).eq(M.date(SERVICE_DATE))), "../LogAsDate")
        assertEquals(listOf("RR"), triCodes(dateArm,
            LOG_AS_DATE to "2024-06-15", SERVICE_DATE to "2024-06-15"),
            "a date-declared DATE_TIME compares as the date its declaration carries")
        assertEquals(emptyList<String>(), triCodes(dateArm,
            LOG_AS_DATE to "2024-06-15", SERVICE_DATE to "2024-07-01"),
            "and unequal ones do not")
    }

    /**
     * The controls the grid needs: the two ORDINARY fields compared to each other are refused, and each
     * compared to its own family's carrier is admitted. Without the refusal the table above is consistent with
     * "everything is admitted"; without the admissions it is consistent with "any cross-kind pair is refused".
     */
    @Test
    fun theOrdinaryPairIsRefusedAndEachFamilyAdmitsItsOwn() {
        assertEquals(FAMILY_MISMATCH, verdictOf("[$BOARDING_CLOCK] == [$SERVICE_DATE]", "../BoardingClock"),
            "an ordinary clock against an ordinary date is the refusal every other row is measured against")
        assertEquals(ADMITTED, verdictOf(render(M.date(SERVICE_DATE).eq(M.date(WINDOW_AS_DATE))), "../ServiceDate"),
            "the date family admits its own, authored order reversed")
        assertEquals(ADMITTED,
            verdictOf(render(M.time(BOARDING_CLOCK).eq(M.time(DEPARTS_AS_CLOCK))), "../BoardingClock"),
            "and the clock family likewise")
    }

    /**
     * An admission verdict is not a runtime one, so the two admitted cross-kind arms are also RUN — all three
     * engines on the same document. This is what separates "the kernel accepts the rule" from "the kernel
     * evaluates it the way we do": the earlier defect in this area (IF314) was a silent suppression, which an
     * admission-only reading cannot see.
     */
    @Test
    fun bothAdmittedCrossKindArmsAgreeOnAllThreeEnginesWhenTheyFire() {
        val clockArm = ruleModel(render(M.time(DEPARTS_AS_CLOCK).eq(M.time(BOARDING_CLOCK))), "../DepartsAsClock")
        assertEquals(listOf("RR"),
            triCodes(clockArm, DEPARTS_AS_CLOCK to "10:30:00", BOARDING_CLOCK to "10:30:00"),
            "equal clocks fire across the kind boundary")
        assertEquals(emptyList<String>(),
            triCodes(clockArm, DEPARTS_AS_CLOCK to "10:30:00", BOARDING_CLOCK to "11:45:00"),
            "and unequal ones do not — the arm is decided by the values, so nothing here is constant")

        val dateArm = ruleModel(render(M.date(WINDOW_AS_DATE).eq(M.date(SERVICE_DATE))), "../WindowAsDate")
        assertEquals(listOf("RR"),
            triCodes(dateArm, WINDOW_AS_DATE to "2024-06-15", SERVICE_DATE to "2024-06-15"),
            "equal dates fire across the kind boundary")
        assertEquals(emptyList<String>(),
            triCodes(dateArm, WINDOW_AS_DATE to "2024-06-15", SERVICE_DATE to "2024-07-01"),
            "and unequal ones do not")
    }

    // ---- the instruments ----

    private fun ruleModel(condition: String, errorRel: String): String =
        RuntimeLaws.withRule(MODEL, "/Trip", "RR", errorRel, "RR", condition)

    /** [ADMITTED], or the distinct MVK codes the kernel's consistency oracle reported for [condition]. */
    private fun verdictOf(condition: String, errorRel: String): String {
        val messages = RuntimeLaws.consistencyMessages(ruleModel(condition, errorRel))
        if (messages.isEmpty()) return ADMITTED
        val codes = messages.mapNotNull { Regex("(MVK_[A-Z_]+)").find(it)?.groupValues?.get(1) }.distinct().sorted()
        return codes.joinToString(", ").ifEmpty { messages.joinToString(", ") }
    }

    /**
     * The fired codes agreed by dynamic Groovy, generated Java and the interpreter over one document. A fresh
     * [RuntimeLaws.Doc] per engine, because an instance is built from it rather than shared.
     */
    private fun triCodes(model: String, vararg cells: Pair<String, String>): List<String> {
        val groovy = RuntimeLaws.messages(model, kernelDoc(*cells)).map { it.code() }.sorted()
        val engine = java.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
        assertEquals(groovy, engine.firedCodesSorted(engine.instance(kernelDoc(*cells))),
            "generated-Java vs normative dynamic-Groovy")
        val def = loaded.getOrPut(model) { ModelLoader.load(model) }
        val iDoc = def.newDocument().group("/Trip", 1)
        for ((path, value) in cells) iDoc.field(path, value, 1, 1)
        assertEquals(groovy, DmInterpreter(def).validateFull(iDoc).map { it.code }.sorted(),
            "interpreter vs normative dynamic-Groovy")
        return groovy
    }

    private fun kernelDoc(vararg cells: Pair<String, String>): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Trip", 1).also { d ->
            for ((path, value) in cells) d.field(path, value, 1, 1)
        }
}
