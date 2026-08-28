package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kernel law — **a temporal field's DECLARED FORMAT decides what it is at every gate; its KIND is never read**,
 * on both codegen strategies. [KF206](../../../../../../../../../docs/KERNEL-FINDINGS.md) established this for
 * the DECLARATION (which format strings are admitted) and said explicitly that storage, rendering, and operand
 * admission were separate questions. This class answers those, and the answer is the same rule.
 *
 * **Why it needs a lock rather than an inference.** Both our [KF207] entry and a12-kernel-lean's `spec/` state
 * that a Time cell never reports the date finding `datumFalsch` *because a clock has no position in time*. That
 * reason is about the kind, and once a TIME field may legally declare `yyyy-MM-dd` it is testable: such a cell
 * carries the Gregorian floor exactly as a DATE does, is directly comparable with a DATE, and answers
 * `YearFromDate` — while `HoursFromTime`, its own kind's extractor, refuses it.
 *
 * **Controls, all within one model so nothing unnoticed can differ.** [aDateDeclaredCellReportsTheDateErrorWhateverItsKind]
 * holds the declaration fixed and moves the kind across all three date-bearing kinds; [aTimeDeclaredCellNeverReportsTheDateError]
 * holds the kind fixed and moves the format. On the read side each admitted arm is paired with a refused one that
 * differs in exactly one thing: the comparison against a `HH:mm:ss` sibling ([theComparisonGateReadsTheFormatNotTheKind])
 * and the time extractor on the same cell the date extractor accepts ([theExtractorGateReadsTheFormatNotTheKind]).
 *
 * **Deliberately kernel-only: no interpreter arm.** Our interpreter rejects every value of a date-declared TIME
 * cell (`FormalError.kt`'s `Kind.TIME -> parseTimeToIso`), which is the open gap `IG144` in
 * [INTERPRETER-GAPS](../../../../../../../../../docs/INTERPRETER-GAPS.md). This class is the oracle that gap has
 * to be fixed against; a differential arm would fail by design instead of measuring the kernel.
 *
 * **Conditions are raw strings here, and that is the exception rather than the habit.** The typed rulekit surface
 * is kind-keyed — `m.time(path)` yields a `TimeRef` whose only comparison is `agreesWith(TimeRef)`, and
 * `m.date(path)` throws on a TIME field — so it cannot express a single condition below. That erasure is the
 * measured gap `DG40` in [DEV-API-GAPS](../../../../../../../../../docs/DEV-API-GAPS.md).
 */
class TemporalDeclaredFormatGateKernelTest {

    private companion object {
        const val ISO = "yyyy-MM-dd"
        const val TIME_AS_DATE = "/Probe/TimeAsDate"
        const val DATE_AS_DATE = "/Probe/DateAsDate"
        const val TIME_AS_TIME = "/Probe/TimeAsTime"

        val STORED = listOf(
            "2024-06-15",   // an ordinary date
            "1583-10-16",   // the Gregorian floor itself
            "1583-10-15",   // one day below it
            "1582-01-01",   // well below it
            "2024-02-31",   // pattern-valid, calendrically unreal
            "10:30:00",     // a clock: the TIME kind's own natural spelling
            "24:00:00",     // no end-of-day spelling on any declaration
            "",             // empty is present and value-free
        )

        /** One model, four cells: three date-format declarations across three kinds, plus the time-format control. */
        val MODEL: String = DocumentModelDraft.named("declared-format-gate").locale("en_US")
            .rootGroup("Probe") { p ->
                p.timeField("TimeAsDate") { it.format(ISO) }
                    .dateField("DateAsDate") { it.format(ISO) }
                    .dateTimeField("StampAsDate") { it.format(ISO) }
                    .timeField("TimeAsTime") { it.format("HH:mm:ss") }
            }.build().toJson()

        /** Kernel codegen is per model, so every rule arm's engine is built once for the whole class. */
        private val ENGINES = HashMap<String, RuntimeLaws.JavaKernelEngine>()

        fun javaEngine(model: String): RuntimeLaws.JavaKernelEngine =
            ENGINES.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
    }

    // ── the formal channel: which cause a stored value carries ───────────────────────────────────────────────

    /** Every stored value's formal codes at [field] of [MODEL], asserted equal on both kernel strategies. */
    private fun formalCodes(field: String): Map<String, List<String>> {
        val dynamic = STORED.associateWith { codesFor(MODEL, oneCell(field, it)) }
        assertEquals(dynamic, STORED.associateWith { staticCodesFor(MODEL, oneCell(field, it)) },
            "$field — groovy-dynamic vs generated-java on the formal channel")
        return dynamic
    }

    private fun oneCell(field: String, value: String): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Probe", 1).also { if (value.isNotEmpty()) it.field(field, value, 1, 1) }

    private fun codesFor(model: String, doc: RuntimeLaws.Doc): List<String> =
        RuntimeLaws.messages(model, doc).map { it.code() }.sorted()

    private fun staticCodesFor(model: String, doc: RuntimeLaws.Doc): List<String> =
        javaEngine(model).let { it.firedCodesSorted(it.instance(doc)) }

    @Test fun aDateDeclaredCellReportsTheDateErrorWhateverItsKind() {
        val expected = mapOf(
            "2024-06-15" to emptyList<String>(),
            "1583-10-16" to emptyList(),
            "1583-10-15" to listOf("datumFalsch"),
            "1582-01-01" to listOf("datumFalsch"),
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
            mapOf("2024-06-15" to f, "1583-10-16" to f, "1583-10-15" to f, "1582-01-01" to f, "2024-02-31" to f,
                "10:30:00" to emptyList(), "24:00:00" to f, "" to emptyList<String>()),
            formalCodes(TIME_AS_TIME),
            "the polarity control — with a time format declared, the published never-the-date-error claim holds",
        )
    }

    // ── the read gates: comparison, extraction, difference ───────────────────────────────────────────────────

    /** The six (TimeAsDate, DateAsDate) pairs every read arm runs, named by what separates them. */
    private enum class Pair2(val time: String?, val date: String) {
        EQUAL("2024-06-15", "2024-06-15"),
        TIME_LATER("2024-06-16", "2024-06-15"),
        TIME_EARLIER("2024-06-14", "2024-06-15"),
        TIME_BELOW_FLOOR("1583-10-15", "2024-06-15"),
        TIME_IS_A_CLOCK("10:30:00", "2024-06-15"),
        TIME_EMPTY(null, "2024-06-15"),
    }

    /** Author [condition] as a rule reporting at [errorRel], assert the kernel ADMITS it, and return its firing. */
    private fun admittedRule(condition: String, errorRel: String): Map<Pair2, List<String>> {
        val model = RuntimeLaws.withRule(MODEL, "/Probe", "RR", errorRel, "RR", condition)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model),
            "the kernel must admit this condition for its firing rows to mean anything: $condition")
        val dynamic = Pair2.entries.associateWith { codesFor(model, pairDoc(it)) }
        assertEquals(dynamic, Pair2.entries.associateWith { staticCodesFor(model, pairDoc(it)) },
            "groovy-dynamic vs generated-java on: $condition")
        return dynamic
    }

    /** Assert the kernel REFUSES [condition] with exactly [code] — the control half of each read gate. */
    private fun refusedRule(condition: String, errorRel: String, code: String) {
        val messages = RuntimeLaws.consistencyMessages(
            RuntimeLaws.withRule(MODEL, "/Probe", "RR", errorRel, "RR", condition))
        assertEquals(1, messages.size, "one refusal expected for: $condition — got $messages")
        assertTrue(messages.single().contains(code), "$condition must be refused $code — got ${messages.single()}")
    }

    private fun pairDoc(p: Pair2): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Probe", 1)
            .also { d -> p.time?.let { d.field(TIME_AS_DATE, it, 1, 1) } }
            .also { it.field(DATE_AS_DATE, p.date, 1, 1) }

    @Test fun theComparisonGateReadsTheFormatNotTheKind() {
        val fires = listOf("RR")
        assertEquals(
            mapOf(Pair2.EQUAL to fires, Pair2.TIME_LATER to emptyList(), Pair2.TIME_EARLIER to emptyList(),
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to emptyList<String>()),
            admittedRule("[$TIME_AS_DATE] == [$DATE_AS_DATE]", "../TimeAsDate"),
            "a date-declared TIME cell compares directly with a DATE on the decoded date, and a formally invalid "
                + "one suppresses the rule exactly like any other malformed cell",
        )
        assertEquals(
            mapOf(Pair2.EQUAL to emptyList(), Pair2.TIME_LATER to fires, Pair2.TIME_EARLIER to emptyList(),
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to emptyList<String>()),
            admittedRule("[$TIME_AS_DATE] > [$DATE_AS_DATE]", "../TimeAsDate"),
            "ordering too, and strictly",
        )
        refusedRule("[$TIME_AS_TIME] == [$DATE_AS_DATE]", "../TimeAsTime", "MVK_INVALID_COMPARE_TO_DATE")
    }

    @Test fun theExtractorGateReadsTheFormatNotTheKind() {
        val fires = listOf("RR")
        assertEquals(
            mapOf(Pair2.EQUAL to fires, Pair2.TIME_LATER to fires, Pair2.TIME_EARLIER to fires,
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to emptyList<String>()),
            admittedRule("YearFromDate($TIME_AS_DATE) == 2024", "../TimeAsDate"),
            "the DATE component extractor accepts a TIME field whose declared format carries the year",
        )
        refusedRule("HoursFromTime($TIME_AS_DATE) == 0", "../TimeAsDate", "MVK_WRONG_DATE_FORMAT_FOR_OP")
    }

    @Test fun differenceInDaysAcceptsItAndTreatsAnEmptyOperandAsZero() {
        val fires = listOf("RR")
        assertEquals(
            mapOf(Pair2.EQUAL to fires, Pair2.TIME_LATER to emptyList(), Pair2.TIME_EARLIER to emptyList(),
                Pair2.TIME_BELOW_FLOOR to listOf("datumFalsch"),
                Pair2.TIME_IS_A_CLOCK to listOf("datumFormatFalsch"), Pair2.TIME_EMPTY to fires),
            admittedRule("DifferenceInDays($TIME_AS_DATE, $DATE_AS_DATE) == 0", "../TimeAsDate"),
            "the difference reads it as a date; an empty operand maps to the symmetric zero the family documents, "
                + "so the == 0 comparison fires there rather than staying silent",
        )
    }
}
