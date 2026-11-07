package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Differential — the gates nobody had measured on a **DATE field declared `HH:mm:ss`**, the carrier
 * [KF211](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf211)'s read-gate table does not contain.
 *
 * **Why this carrier and not another.** Every read-gate row in KF211 rides one field: a TIME declared
 * `yyyy-MM-dd`. Its model declares no clock-formatted DATE at all, so the mirror direction was inherited from
 * the classifier rule across a gate boundary rather than measured — a scope transfer. Two independent
 * a12-kernel-lean entries landed in exactly that corner; the comparison arm is
 * [KF248](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf248), and this file takes the extractor arm
 * (their `SPEC-2026-09-05-14` row, handed over so neither estate measures it twice) plus the **pre-1900 check**,
 * which through kernel 31.1.1 was a model-ADMISSION question and since 31.2 is no question at all (KF677).
 *
 * **The extractor pair is KF211's sharpest single row, mirrored.** There, `YearFromDate` is admitted and
 * `HoursFromTime` refused on a date-declared TIME. Here both axes are flipped, so the two files together show
 * the extractor gate keying on the declared format from both sides rather than on either kind.
 *
 * Own domain (maritime voyage scheduling).
 */
// One instance per CLASS: this file holds its model JSON and a kernel Java engine per rule arm as fields, and
// this Gradle task also carries clock-reading assertions (CLAUDE.md: a new test must not perturb a timing floor).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClockDeclaredDateGateDiffTest {

    private companion object {
        const val CLOCK_FORMAT = "HH:mm:ss"
        const val DATE_FORMAT = "yyyy-MM-dd"

        /** The carrier: kind DATE, declared as a clock. It carries NO additional check — see [MODEL]. */
        const val DEPARTURE_CLOCK = "/Voyage/DepartureClock"

        /** The same check on a year-bearing DATE declaration, where it fired through kernel 31.1.1. */
        const val DEPARTURE_DATE = "/Voyage/DepartureDate"

        /** An ordinary clock, so the extractor rows have a same-family counterpart. */
        const val BOARDING_CLOCK = "/Voyage/BoardingClock"

        const val ADMITTED = "ADMITTED"
        const val WRONG_FORMAT_FOR_OP = "MVK_WRONG_DATE_FORMAT_FOR_OP"

        /**
         * The legal model. `DepartureClock` deliberately carries no additional check, so the extractor rows do not
         * depend on the check's verdict; [theRetiredCheckIsAdmittedOnAClockDeclaredDateAndFiresNowhere] measures
         * that on its own model.
         */
        @Suppress("DEPRECATION") // the retired check is what one arm measures
        val MODEL: String = DocumentModelDraft.named("clock-declared-date-gates").locale("en_US")
            .rootGroup("Voyage") { v ->
                v.dateField("DepartureClock") { it.format(CLOCK_FORMAT) }
                    .dateField("DepartureDate") { it.format(DATE_FORMAT).rejectBefore1900() }
                    .timeField("BoardingClock") { it.format(CLOCK_FORMAT) }
            }.build().toJson()

        /** The same clock-declared DATE field WITH the check — refused through 31.1.1, admitted since 31.2. */
        @Suppress("DEPRECATION")
        val GUARDED_CLOCK_MODEL: String = DocumentModelDraft.named("guarded-clock-declared-date").locale("en_US")
            .rootGroup("Voyage") { v -> v.dateField("DepartureClock") { it.format(CLOCK_FORMAT).rejectBefore1900() } }
            .build().toJson()

        /** The typed surface over the legal model — `ofJson` runs the kernel's own consistency check. */
        val M: BoundModel by lazy { BoundModel.ofJson(MODEL) }

        fun render(condition: Condition): String = RuntimeLaws.render(condition)
    }

    private val java = HashMap<String, RuntimeLaws.JavaKernelEngine>()
    private val loaded = HashMap<String, io.github.mbackschat.a12.dm.interpreter.model.ModelDef>()

    /**
     * The declarations every row rests on, read out of the model bytes: a DATE-kind field carrying a clock
     * format, and no additional check on it. If a draft quietly normalized either, the rows below measure
     * something other than a cross-kind declaration.
     */
    @Test
    fun theCarrierIsADateKindFieldDeclaredAsAClockAndCarriesNoAdditionalCheck() {
        assertTrue(Regex("\"DepartureClock\"[\\s\\S]{0,400}?\"DateType\"").containsMatchIn(MODEL),
            "the carrier must be a DATE-kind declaration, or nothing here is cross-kind")
        assertTrue(Regex("\"DepartureClock\"[\\s\\S]{0,600}?\"$CLOCK_FORMAT\"").containsMatchIn(MODEL),
            "declared as a clock")
        assertTrue(Regex("youngerThan1900Check\"?\\s*:\\s*true").containsMatchIn(GUARDED_CLOCK_MODEL),
            "the guarded model must really set the check, or its admission says nothing about it")
    }

    /**
     * **KF211's extractor pair, with both axes flipped.** On one clock-declared DATE cell the TIME extractor is
     * admitted and the DATE extractor refused — the exact mirror of KF211's date-declared TIME, where
     * `YearFromDate` is admitted and `HoursFromTime` refused. The kind is DATE here and TIME there, and both
     * times the declared format decides.
     */
    @Test
    fun theTimeExtractorIsAdmittedAndTheDateExtractorRefusedOnAClockDeclaredDate() {
        assertEquals(
            mapOf(
                "HoursFromTime · clock-declared DATE" to ADMITTED,
                "YearFromDate · clock-declared DATE" to WRONG_FORMAT_FOR_OP,
                "HoursFromTime · ordinary clock" to ADMITTED,
            ),
            mapOf(
                "HoursFromTime · clock-declared DATE" to
                    verdictOf(render(Dsl.hoursFromTime(M.time(DEPARTURE_CLOCK)).eq(10)), "../DepartureClock"),
                // Raw: `M.date(DepartureClock)` is withheld by our surface (the declared shape is TIME), so the
                // condition is not authorable typed and the KERNEL is what must refuse the string.
                "YearFromDate · clock-declared DATE" to
                    verdictOf("YearFromDate($DEPARTURE_CLOCK) == 2024", "../DepartureClock"),
                "HoursFromTime · ordinary clock" to
                    verdictOf(render(Dsl.hoursFromTime(M.time(BOARDING_CLOCK)).eq(10)), "../BoardingClock"),
            ),
            "the extractor gate reads the declared format from this side too, not the kind",
        )
    }

    /** And the admitted arm is RUN — an admission verdict cannot see a silent suppression. */
    @Test
    fun theAdmittedTimeExtractorFiresOnTheClockDeclaredDateAcrossAllThreeEngines() {
        val model = ruleModel(render(Dsl.hoursFromTime(M.time(DEPARTURE_CLOCK)).eq(10)), "../DepartureClock")
        assertEquals(listOf("RR"), triCodes(model, DEPARTURE_CLOCK to "10:30:00"),
            "the hour is extracted from a DATE-kind cell and the rule fires")
        assertEquals(emptyList<String>(), triCodes(model, DEPARTURE_CLOCK to "11:30:00"),
            "and a different hour is silent, so the arm is decided by the value")
    }

    /**
     * **The pre-1900 check on a clock-declared DATE.** Through kernel 31.1.1 this was a model-ADMISSION question
     * answered in neither direction a12-kernel-lean's 2×2 offered: the clock declaration was refused outright
     * (`MVK_ADDITIONAL_CHECK_INVALID`) while a year-bearing declaration admitted the check and fired below 1900.
     * Since kernel 31.2 ([KF677](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf677)) the clock
     * declaration is admitted, the year-bearing one fires nothing, and the clock cell judges exactly as it does
     * without the check — so the guard is admitted-and-inert on every declaration.
     */
    @Test
    fun theRetiredCheckIsAdmittedOnAClockDeclaredDateAndFiresNowhere() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(GUARDED_CLOCK_MODEL),
            "the clock-declared DATE with the check is admitted")
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(MODEL), "the control model is admitted")
        assertEquals(
            mapOf(
                "year-bearing declaration · below 1900" to emptyList<String>(),
                "year-bearing declaration · at the boundary" to emptyList(),
            ),
            mapOf(
                "year-bearing declaration · below 1900" to formalCodes(DEPARTURE_DATE to "1899-12-31"),
                "year-bearing declaration · at the boundary" to formalCodes(DEPARTURE_DATE to "1900-01-01"),
            ),
            "the check on a year-bearing declaration fires on neither side of 1900",
        )
        for (clock in listOf("10:00:00", "25:00:00")) {
            val cell = DEPARTURE_CLOCK to clock
            assertEquals(triCodes(MODEL, cell), triCodes(GUARDED_CLOCK_MODEL, cell), "the stored clock $clock")
        }
    }

    /**
     * **And the other half of that 2×2 names no model at all, which makes it a SCHEMA fact.**
     * `youngerThan1900Check` is a property of the **`DateType` declaration object**; the kernel's `TimeType` and
     * `DateTimeType` carry `format` (and annotations) and nothing else — read from kernel source, the primary oracle
     * for a signature question. So "a TIME field declared `yyyy-MM-dd` with the guard set" is unrepresentable rather
     * than refused.
     *
     * Source reading leaves one link open, and this attacks it: does the DM-JSON path carry the key into a `TimeType`
     * anyway, making it declarable-but-inert? On kernel 30.8.1 the deserializer refused the property. Kernel 31.1.1
     * reads past a member a type does not declare ([KF654](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf654)),
     * so the injected model is admitted and judged exactly as the model without the key, on all three engines: the
     * key never reaches a gate.
     */
    @Test
    fun aGuardInjectedIntoATimeFieldIsReadPast() {
        val timeOnly = DocumentModelDraft.named("guard-on-time").locale("en_US")
            .rootGroup("Voyage") { v -> v.timeField("BoardingClock") { it.format(CLOCK_FORMAT) } }
            .build().toJson()
        val injected = timeOnly.replace(
            Regex("(\"TimeType\"\\s*:\\s*\\{)"), "$1\"youngerThan1900Check\":true,")
        assertFalse(injected == timeOnly, "the injection must have applied, or this measures nothing")

        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(injected))
        for (clock in listOf("10:00:00", "25:00:00")) {
            val cell = "/Voyage/BoardingClock" to clock
            assertEquals(triCodes(timeOnly, cell), triCodes(injected, cell), "the stored clock $clock")
        }
    }

    // ---- the instruments ----

    private fun ruleModel(condition: String, errorRel: String): String =
        RuntimeLaws.withRule(MODEL, "/Voyage", "RR", errorRel, "RR", condition)

    /** [ADMITTED], or the distinct MVK codes the kernel's consistency oracle reported for [condition]. */
    private fun verdictOf(condition: String, errorRel: String): String {
        val messages = RuntimeLaws.consistencyMessages(ruleModel(condition, errorRel))
        if (messages.isEmpty()) return ADMITTED
        val codes = messages.mapNotNull { Regex("(MVK_[A-Z_0-9]+)").find(it)?.groupValues?.get(1) }
            .distinct().sorted()
        return codes.joinToString(", ").ifEmpty { messages.joinToString(", ") }
    }

    /** The formal codes a stored value draws with no rule in the model, agreed by all three engines. */
    private fun formalCodes(vararg cells: Pair<String, String>): List<String> = triCodes(MODEL, *cells)

    private fun triCodes(model: String, vararg cells: Pair<String, String>): List<String> {
        val groovy = RuntimeLaws.messages(model, kernelDoc(*cells)).map { it.code() }.sorted()
        val engine = java.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
        assertEquals(groovy, engine.firedCodesSorted(engine.instance(kernelDoc(*cells))),
            "generated-Java vs normative dynamic-Groovy")
        val def = loaded.getOrPut(model) { ModelLoader.load(model) }
        val doc = def.newDocument().group("/Voyage", 1)
        for ((path, value) in cells) doc.field(path, value, 1, 1)
        assertEquals(groovy, DmInterpreter(def).validateFull(doc).map { it.code }.sorted(),
            "interpreter vs normative dynamic-Groovy")
        return groovy
    }

    private fun kernelDoc(vararg cells: Pair<String, String>): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Voyage", 1).also { d ->
            for ((path, value) in cells) d.field(path, value, 1, 1)
        }
}
