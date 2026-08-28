package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Differential — **a temporal field's DECLARED FORMAT decides what it is at every gate; its KIND is never
 * read** (KF211), on dynamic Groovy, generated Java and the interpreter. [KF206] established this for the
 * DECLARATION (which format strings are admitted) and stated that storage, rendering and operand admission were
 * separate questions; these are those, and the answer is the same rule one tier down.
 *
 * **The discriminators.** A `Time` cell was documented — by us and by a12-kernel-lean's `spec/` — to report only
 * the format cause and never the date cause `datumFalsch`, with the reason given as the KIND: a clock has no
 * position in time. Once a TIME field may legally declare `yyyy-MM-dd`, that reason is testable and it is wrong.
 * On one such cell the kernel carries the Gregorian floor, admits a direct comparison against a DATE, answers
 * `YearFromDate`, and refuses `HoursFromTime` — its own kind's extractor.
 *
 * **Controls, all within one model so nothing unnoticed can differ.** [aDateDeclaredCellReportsTheDateErrorWhateverItsKind]
 * holds the declaration fixed and moves the kind across all three date-bearing kinds;
 * [aTimeDeclaredCellNeverReportsTheDateError] holds the kind fixed and moves the format. On the read side each
 * admitted arm is paired with a refused one differing in exactly one thing: the comparison against a `HH:mm:ss`
 * sibling ([theComparisonGateReadsTheFormatNotTheKind]) and the time extractor on the cell whose date extractor
 * is admitted ([theExtractorGateReadsTheFormatNotTheKind]).
 *
 * **Both engines had this wrong before 2026-08-28**, in opposite directions and for the same reason: the
 * interpreter keyed its formal check and its value canonicalization on `Kind`, so a date-declared TIME cell was
 * malformed on every value (every referencing rule silently suppressed where the kernel evaluates one — the gap
 * that was IG144) and a clock-declared DATE cell was refused a clock (the mirror, unreported until the grid was
 * measured). The complete 8-format × 3-kind grid is locked multiplatform in
 * `interpreter … TemporalDeclaredFormatFormalTest`; this class is its kernel oracle plus the read gates.
 *
 * **The four admitted conditions are authored through the typed rulekit surface**, which is what closed the
 * `DG40` gap this class opened: `m.date(path)` now yields a date handle for any temporal field whose DECLARED
 * FORMAT is date-bearing, so the kernel-legal condition on a date-declared TIME cell is expressible instead of
 * merely refused-by-omission. The two REFUSED arms stay raw strings on purpose, and they divide differently:
 * the cross-kind comparison is not expressible typed at all (the surface withholds cross-kind equality, KF82,
 * so here it agrees with the kernel's gate for free), while `HoursFromTime` over a TIME-kind field IS
 * expressible — a client-side surface is not a substitute for the kernel's authoring gate, which is what
 * `RuleDiagnostics.diagnose` is for.
 */
class TemporalDeclaredFormatGateDiffTest {

    private companion object {
        const val ISO = "yyyy-MM-dd"
        const val DOTTED = "dd.MM.yyyy"
        const val TIME_AS_DATE = "/Probe/TimeAsDate"
        const val DATE_AS_DATE = "/Probe/DateAsDate"
        const val TIME_AS_TIME = "/Probe/TimeAsTime"
        const val TIME_AS_DOTTED = "/Probe/TimeAsDotted"
        const val DATE_AS_DOTTED = "/Probe/DateAsDotted"

        val STORED = listOf(
            "2024-06-15",   // an ordinary date
            "1583-10-16",   // the Gregorian floor itself
            "1583-10-15",   // one day below it
            "0000-06-15",   // year zero: an unreal component, not a position below the floor
            "2024-02-31",   // pattern-valid, calendrically unreal
            "10:30:00",     // a clock: the TIME kind's own natural spelling
            "24:00:00",     // no end-of-day spelling on any declaration
            "",             // empty is present and value-free
        )

        /** One model: three date-format declarations across three kinds, the clock control, and a dotted pair. */
        val MODEL: String = DocumentModelDraft.named("declared-format-gate").locale("en_US")
            .rootGroup("Probe") { p ->
                p.timeField("TimeAsDate") { it.format(ISO) }
                    .dateField("DateAsDate") { it.format(ISO) }
                    .dateTimeField("StampAsDate") { it.format(ISO) }
                    .timeField("TimeAsTime") { it.format("HH:mm:ss") }
                    .timeField("TimeAsDotted") { it.format(DOTTED) }
                    .dateField("DateAsDotted") { it.format(DOTTED) }
            }.build().toJson()

        /** Kernel codegen and model load are per model, so each rule arm's engines are built once per class. */
        private val JAVA = HashMap<String, RuntimeLaws.JavaKernelEngine>()
        private val LOADED = HashMap<String, io.github.mbackschat.a12.dm.interpreter.model.ModelDef>()

        fun javaEngine(model: String): RuntimeLaws.JavaKernelEngine =
            JAVA.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }

        fun loaded(model: String) = LOADED.getOrPut(model) { ModelLoader.load(model) }

        /** The typed authoring surface over the same model — `ofJson` runs the kernel's own consistency check. */
        val M: BoundModel by lazy { BoundModel.ofJson(MODEL) }

        fun render(condition: Condition): String = DslRenderer().render(condition)
    }

    // ── the formal channel: which cause a stored value carries ───────────────────────────────────────────────

    /** Every stored value's formal codes at [field], asserted equal on both kernel strategies and the interpreter. */
    private fun formalCodes(field: String): Map<String, List<String>> {
        val groovy = STORED.associateWith { RuntimeLaws.messages(MODEL, oneCell(field, it)).map { m -> m.code() }.sorted() }
        assertEquals(groovy, STORED.associateWith { staticCodes(MODEL, oneCell(field, it)) },
            "$field — groovy-dynamic vs generated-java on the formal channel")
        assertEquals(groovy, STORED.associateWith { interpreterCodes(MODEL, field, it) },
            "$field — kernel vs interpreter on the formal channel")
        return groovy
    }

    private fun oneCell(field: String, value: String): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Probe", 1).also { if (value.isNotEmpty()) it.field(field, value, 1, 1) }

    private fun staticCodes(model: String, doc: RuntimeLaws.Doc): List<String> =
        javaEngine(model).let { it.firedCodesSorted(it.instance(doc)) }

    private fun interpreterCodes(model: String, field: String, value: String): List<String> {
        val def = loaded(model)
        val doc = def.newDocument().group("/Probe", 1)
        if (value.isNotEmpty()) doc.field(field, value, 1, 1)
        return DmInterpreter(def).validateFull(doc).map { it.code }.sorted()
    }

    @Test fun aDateDeclaredCellReportsTheDateErrorWhateverItsKind() {
        val expected = mapOf(
            "2024-06-15" to emptyList<String>(),
            "1583-10-16" to emptyList(),
            "1583-10-15" to listOf("datumFalsch"),
            // Reality before position in time: year zero is unreal, so it takes the FORMAT cause while
            // 1583-10-15 — a real date — takes the date one.
            "0000-06-15" to listOf("datumFormatFalsch"),
            "2024-02-31" to listOf("datumFormatFalsch"),
            "10:30:00" to listOf("datumFormatFalsch"),
            "24:00:00" to listOf("datumFormatFalsch"),
            "" to emptyList(),
        )
        assertEquals(expected, formalCodes(TIME_AS_DATE),
            "a TIME field declared $ISO carries the Gregorian floor, so the date error is reachable on a clock kind")
        assertEquals(expected, formalCodes(DATE_AS_DATE), "the DATE reference row for the same declaration")
        assertEquals(expected, formalCodes("/Probe/StampAsDate"),
            "DATE_TIME declared $ISO agrees too, so this is not a TIME peculiarity")
    }

    @Test fun aTimeDeclaredCellNeverReportsTheDateError() {
        val f = listOf("datumFormatFalsch")
        assertEquals(
            mapOf("2024-06-15" to f, "1583-10-16" to f, "1583-10-15" to f, "0000-06-15" to f, "2024-02-31" to f,
                "10:30:00" to emptyList(), "24:00:00" to f, "" to emptyList<String>()),
            formalCodes(TIME_AS_TIME),
            "the polarity control — with a time format declared, the published never-the-date-error claim holds",
        )
    }

    // ── the read gates: comparison, extraction, difference ───────────────────────────────────────────────────

    /** The six operand pairs every read arm runs, named by what separates them. */
    private enum class Pair2(val time: String?, val date: String) {
        EQUAL("2024-06-15", "2024-06-15"),
        TIME_LATER("2024-06-16", "2024-06-15"),
        TIME_EARLIER("2024-06-14", "2024-06-15"),
        TIME_BELOW_FLOOR("1583-10-15", "2024-06-15"),
        TIME_IS_A_CLOCK("10:30:00", "2024-06-15"),
        TIME_EMPTY(null, "2024-06-15"),
    }

    /** Author [condition] as a rule reporting at [errorRel], assert the kernel ADMITS it, return its firing. */
    private fun admittedRule(condition: String, errorRel: String): Map<Pair2, List<String>> {
        val timePath = TIME_AS_DATE
        val datePath = DATE_AS_DATE
        val model = RuntimeLaws.withRule(MODEL, "/Probe", "RR", errorRel, "RR", condition)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model),
            "the kernel must admit this condition for its firing rows to mean anything: $condition")
        val groovy = Pair2.entries.associateWith {
            RuntimeLaws.messages(model, pairDoc(it, timePath, datePath)).map { m -> m.code() }.sorted()
        }
        assertEquals(groovy, Pair2.entries.associateWith { staticCodes(model, pairDoc(it, timePath, datePath)) },
            "groovy-dynamic vs generated-java on: $condition")
        assertEquals(groovy, Pair2.entries.associateWith { interpreterPairCodes(model, it, timePath, datePath) },
            "kernel vs interpreter on: $condition")
        return groovy
    }

    /** Assert the kernel REFUSES [condition] with exactly [code] — the control half of each read gate. */
    private fun refusedRule(condition: String, errorRel: String, code: String) {
        val messages = RuntimeLaws.consistencyMessages(
            RuntimeLaws.withRule(MODEL, "/Probe", "RR", errorRel, "RR", condition))
        assertEquals(1, messages.size, "one refusal expected for: $condition — got $messages")
        assertTrue(messages.single().contains(code), "$condition must be refused $code — got ${messages.single()}")
    }

    private fun pairDoc(p: Pair2, timePath: String, datePath: String): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Probe", 1)
            .also { d -> p.time?.let { d.field(timePath, reformat(it, timePath), 1, 1) } }
            .also { it.field(datePath, reformat(p.date, datePath), 1, 1) }

    private fun interpreterPairCodes(
        model: String, p: Pair2, timePath: String, datePath: String,
    ): List<String> {
        val def = loaded(model)
        val doc = def.newDocument().group("/Probe", 1)
        p.time?.let { doc.field(timePath, reformat(it, timePath), 1, 1) }
        doc.field(datePath, reformat(p.date, datePath), 1, 1)
        return DmInterpreter(def).validateFull(doc).map { it.code }.sorted()
    }

    /** The pairs above are written ISO; a dotted-declared cell needs the same date in its own spelling. */
    private fun reformat(isoOrClock: String, path: String): String = when {
        path != TIME_AS_DOTTED && path != DATE_AS_DOTTED -> isoOrClock
        isoOrClock.length == 10 && isoOrClock[4] == '-' ->
            isoOrClock.substring(8, 10) + "." + isoOrClock.substring(5, 7) + "." + isoOrClock.substring(0, 4)
        else -> isoOrClock
    }

    @Test fun theComparisonGateReadsTheFormatNotTheKind() {
        val fires = listOf("RR")
        assertEquals(
            mapOf(Pair2.EQUAL to fires, Pair2.TIME_LATER to emptyList(), Pair2.TIME_EARLIER to emptyList(),
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to emptyList<String>()),
            admittedRule(render(M.date(TIME_AS_DATE).eq(M.date(DATE_AS_DATE))), "../TimeAsDate"),
            "a date-declared TIME cell compares directly with a DATE on the decoded date, and a formally invalid "
                + "one suppresses the rule exactly like any other malformed cell",
        )
        assertEquals(
            mapOf(Pair2.EQUAL to emptyList(), Pair2.TIME_LATER to fires, Pair2.TIME_EARLIER to emptyList(),
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to emptyList<String>()),
            admittedRule(render(M.date(TIME_AS_DATE).after(M.date(DATE_AS_DATE))), "../TimeAsDate"),
            "ordering too, and strictly",
        )
        // Raw, because the typed surface cannot express it: a clock-declared field yields a TimeRef and there
        // is no cross-kind equality — the one refusal it agrees with the kernel about by construction.
        refusedRule("[$TIME_AS_TIME] == [$DATE_AS_DATE]", "../TimeAsTime", "MVK_INVALID_COMPARE_TO_DATE")
    }

    /** One explicit operand pair through all three engines, for a case the shared [Pair2] set cannot express. */
    private fun triCodes(model: String, timeValue: String, dateValue: String): List<String> {
        val doc = RuntimeLaws.doc().group("/Probe", 1)
            .field(TIME_AS_DOTTED, timeValue, 1, 1).field(DATE_AS_DOTTED, dateValue, 1, 1)
        val groovy = RuntimeLaws.messages(model, doc).map { it.code() }.sorted()
        assertEquals(groovy, staticCodes(model, RuntimeLaws.doc().group("/Probe", 1)
            .field(TIME_AS_DOTTED, timeValue, 1, 1).field(DATE_AS_DOTTED, dateValue, 1, 1)),
            "groovy-dynamic vs generated-java on '$timeValue' vs '$dateValue'")
        val def = loaded(model)
        val iDoc = def.newDocument().group("/Probe", 1)
            .field(TIME_AS_DOTTED, timeValue, 1, 1).field(DATE_AS_DOTTED, dateValue, 1, 1)
        assertEquals(groovy, DmInterpreter(def).validateFull(iDoc).map { it.code }.sorted(),
            "kernel vs interpreter on '$timeValue' vs '$dateValue'")
        return groovy
    }

    /**
     * The DOTTED declaration is where a lexical reading and a decoded-date reading come apart, and the pairs the
     * other arms use cannot tell them apart: `16.06.2024` sorts after `15.06.2024` under BOTH readings. The
     * discriminating pair is a later date with a smaller leading digit — `01.07.2024` is later than `15.06.2024`
     * as a date and EARLIER as text — so a cell passed through as raw text is silent exactly where a decoded one
     * fires. This is the arm that proves the interpreter decodes a date-declared TIME cell by its own format.
     */
    @Test fun aDottedTimeDeclarationIsComparedAsADateNotAsText() {
        val model = RuntimeLaws.withRule(MODEL, "/Probe", "RR", "../TimeAsDotted", "RR",
            render(M.date(TIME_AS_DOTTED).after(M.date(DATE_AS_DOTTED))))
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model), "the condition must be admitted")
        assertEquals(listOf("RR"), triCodes(model, "01.07.2024", "15.06.2024"),
            "July 1 is later than June 15 as a date, and earlier as text — the decoded reading fires")
        assertEquals(emptyList<String>(), triCodes(model, "15.06.2024", "01.07.2024"),
            "the reverse pair, which a text reading would report as later")
        assertEquals(emptyList<String>(), triCodes(model, "15.06.2024", "15.06.2024"), "equality control")
    }

    @Test fun theExtractorGateReadsTheFormatNotTheKind() {
        val fires = listOf("RR")
        assertEquals(
            mapOf(Pair2.EQUAL to fires, Pair2.TIME_LATER to fires, Pair2.TIME_EARLIER to fires,
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to emptyList<String>()),
            admittedRule(render(Dsl.yearFromDate(M.date(TIME_AS_DATE)).eq(2024)), "../TimeAsDate"),
            "the DATE component extractor accepts a TIME field whose declared format carries the year",
        )
        // Raw for the opposite reason: `M.time(TIME_AS_DATE)` IS obtainable (a TIME field keeps its time handle
        // whatever it declares), so this condition is authorable and the KERNEL is what refuses it. Rendering it
        // typed would assert the same string through more machinery.
        refusedRule("HoursFromTime($TIME_AS_DATE) == 0", "../TimeAsDate", "MVK_WRONG_DATE_FORMAT_FOR_OP")
    }

    @Test fun differenceInDaysAcceptsItAndTreatsAnEmptyOperandAsZero() {
        val fires = listOf("RR")
        assertEquals(
            mapOf(Pair2.EQUAL to fires, Pair2.TIME_LATER to emptyList(), Pair2.TIME_EARLIER to emptyList(),
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to fires),
            admittedRule(
                render(Dsl.differenceInDays(M.date(TIME_AS_DATE), M.date(DATE_AS_DATE)).eq(0)), "../TimeAsDate"),
            "the difference reads it as a date; an empty operand maps to the symmetric zero the family documents, "
                + "so the == 0 comparison fires there rather than staying silent",
        )
    }
}
