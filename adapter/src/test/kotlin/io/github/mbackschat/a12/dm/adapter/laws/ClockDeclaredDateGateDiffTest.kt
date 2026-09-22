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
import org.junit.jupiter.api.Assertions.assertThrows
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
 * (their `SPEC-2026-09-05-14` row, handed over so neither estate measures it twice) plus the **opt-in pre-1900
 * policy**, which turned out to be a model-ADMISSION question rather than a value one.
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

        /** The guard's positive control — the same opt-in check on a year-bearing DATE declaration. */
        const val DEPARTURE_DATE = "/Voyage/DepartureDate"

        /** An ordinary clock, so the extractor rows have a same-family counterpart. */
        const val BOARDING_CLOCK = "/Voyage/BoardingClock"

        const val ADMITTED = "ADMITTED"
        const val WRONG_FORMAT_FOR_OP = "MVK_WRONG_DATE_FORMAT_FOR_OP"

        /** The kernel's refusal for an additional check on a year-free date declaration. */
        const val ADDITIONAL_CHECK_INVALID = "MVK_ADDITIONAL_CHECK_INVALID"

        /** The kernel's value-policy cause, as KF211's own table spells it. */
        const val DATE_INVALID = "datumFalsch"

        /**
         * The legal model. `DepartureClock` deliberately carries no additional check: the kernel refuses that
         * combination outright, which is what [theOptInGuardIsRefusedOnAYearFreeDateDeclaration] measures on its
         * own model. Everything else here would be unreachable if this model were illegal.
         */
        val MODEL: String = DocumentModelDraft.named("clock-declared-date-gates").locale("en_US")
            .rootGroup("Voyage") { v ->
                v.dateField("DepartureClock") { it.format(CLOCK_FORMAT) }
                    .dateField("DepartureDate") { it.format(DATE_FORMAT).rejectBefore1900() }
                    .timeField("BoardingClock") { it.format(CLOCK_FORMAT) }
            }.build().toJson()

        /** The same clock-declared DATE field WITH the opt-in check — measured to be refused. */
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
            "the guarded model must really set the opt-in check, or its refusal is about something else")
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
     * **The opt-in pre-1900 guard is a model-ADMISSION question, and the kernel keys it on the declared
     * format's components.** A DATE field declared `HH:mm:ss` carrying the check is refused outright —
     * `MVK_ADDITIONAL_CHECK_INVALID`, "For date fields without a year, no 'additional check' may be specified"
     * — while the identical check on a year-bearing DATE declaration is admitted and fires below 1900.
     *
     * This answers a question a12-kernel-lean raised about six sites of theirs, and it answers it in neither of
     * the two directions their 2×2 offered: the guard is not admitted-and-inert on a clock declaration, it is
     * not declarable there at all. A consumer policy that admits the opt-in check for the DATE *kind* is
     * therefore over-permissive on exactly this declaration.
     */
    @Test
    fun theOptInGuardIsRefusedOnAYearFreeDateDeclaration() {
        val refusal = RuntimeLaws.consistencyMessages(GUARDED_CLOCK_MODEL)
        assertEquals(1, refusal.size, "one refusal expected — got $refusal")
        assertTrue(refusal.single().contains(ADDITIONAL_CHECK_INVALID),
            "the year-free declaration must draw $ADDITIONAL_CHECK_INVALID — got ${refusal.single()}")

        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(MODEL),
            "the control model must be admitted, or the refusal above is not about the additional check")
        assertEquals(
            mapOf(
                "year-bearing declaration · below 1900" to listOf(DATE_INVALID),
                "year-bearing declaration · at the boundary" to emptyList(),
            ),
            mapOf(
                "year-bearing declaration · below 1900" to formalCodes(DEPARTURE_DATE to "1899-12-31"),
                "year-bearing declaration · at the boundary" to formalCodes(DEPARTURE_DATE to "1900-01-01"),
            ),
            "the same check on a year-bearing declaration is live, so it is genuinely switched on",
        )
    }

    /**
     * **And the other half of that 2×2 names no model at all, which makes it a SCHEMA fact.**
     * `youngerThan1900Check` is a property of the **`DateType` declaration object**; the kernel's `TimeType` and
     * `DateTimeType` carry `format` (and annotations) and nothing else — read from kernel source at 30.8.1, the
     * primary oracle for a signature question. So "a TIME field declared `yyyy-MM-dd` with the guard set" is
     * unrepresentable rather than refused.
     *
     * Source reading leaves one link open, and this attacks it: would the DM-JSON path carry the key into a
     * `TimeType` anyway, making it declarable-but-inert? It would not, and the refusal is one layer earlier than
     * a consistency message — the kernel's own DESERIALIZER rejects the property, naming `TimeType` and the key
     * in its reference chain. So the shape does not reach a gate that could be inert.
     */
    @Test
    fun theGuardIsNotDeclarableOnATimeField() {
        val timeOnly = DocumentModelDraft.named("guard-on-time").locale("en_US")
            .rootGroup("Voyage") { v -> v.timeField("BoardingClock") { it.format(CLOCK_FORMAT) } }
            .build().toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(timeOnly),
            "the un-injected TIME model must be admitted, or the injection below proves nothing")

        val injected = timeOnly.replace(
            Regex("(\"TimeType\"\\s*:\\s*\\{)"), "$1\"youngerThan1900Check\":true,")
        assertFalse(injected == timeOnly, "the injection must have applied, or this measures nothing")
        assertTrue(injected.contains("youngerThan1900Check"), "and must be present in the bytes")

        // Asserted on the message CONTENT rather than on an exception class: the thrower is a kernel-internal
        // deserialization detail, while "it names this property on this type" is the claim.
        val refusal = assertThrows(Exception::class.java) { RuntimeLaws.consistencyMessages(injected) }
        val reported = generateSequence<Throwable>(refusal) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(reported.contains("youngerThan1900Check") && reported.contains("TimeType"),
            "the kernel must not silently accept a DateType-only property inside a TimeType — if this ever "
                + "stops naming both, the guard may have become declarable-but-inert on a TIME field and the "
                + "schema claim above needs restating as a behavioural one. Got: $reported")
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
