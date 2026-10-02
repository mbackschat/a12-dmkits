package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.outcomes
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — SPEC-2026-10-02-01 on our own carrier: what a later computation reads of a computed
 * DateTime, in `Europe/Berlin`, across the 2024-10-27 repeated hour.
 *
 * The rows are a12-kernel-lean's `datetime-join-2026-10-02` documents, ids kept, on a shift schedule whose
 * `Handover = AddDays(Start, [Offset])` feeds `Finish = AddHours(Handover, 2)`, read by
 * `Hours = DifferenceInHours(Start, Finish)`. The discriminating pair is `d10` against `d07`: `00:30` plus two
 * elapsed hours is the first, daylight-time `02:30`, yet `Hours` is `3`, while from `01:30` it is `2` — a later
 * read takes the rendering `02:30` at its second, standard-time occurrence, not the instant computed. The landing
 * rows (`l…`, their second model) are the control that one operation keeps the instant: `AddHours(AddDays(Start, 1),
 * 2)` from `2024-10-26T02:30:00` lands on `03:30`. Every computed field is seeded stale so a cleared result is
 * visible. Whether the kernel re-parses the text or normalizes the instant is not separated; only values are.
 *
 * Own domain (a work-shift schedule).
 */
// One instance per class: the models are fields, and this task carries timing floors (see FilteredStarCapacityExtentDiffTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComputedDateTimeJoinDiffTest {
    private fun carrier(name: String, extra: (DocumentModelDraft.GroupBuilder) -> Unit) =
        EditSession.read(DocumentModelDraft.named(name).locale("en_US")
            .rootGroup("Shift") { shift -> shift.dateTimeField("Start") { it.format(STAMP) }; extra(shift) }
            .build().toJson()).setTimeZone("Europe/Berlin").toJson()

    private val shiftBase = carrier("shift-datetime-join") { shift ->
        for (field in listOf("Handover", "Finish", "NextDay")) shift.dateTimeField(field) { it.format(STAMP) }
        for (field in listOf("OffsetDays", "Offset", "Hours", "Days", "Check", "NoFinish")) {
            shift.numberField(field) { it.fractionDigits(0) }
        }
        shift.stringField("Note") { }
    }

    /** Authored readers first, so each later read is of a value computed earlier in the same cascade (`Days` last). */
    private val shift = BoundModel.ofJson(shiftBase).let { m ->
        val start = m.dateTime("/Shift/Start")
        val finish = m.dateTime("/Shift/Finish")
        val offsetDays = m.num("/Shift/OffsetDays").asNumber()
        fun comp(target: String) = if (target in DATE_TIMES) m.computation(m.dateTime("/Shift/$target"))
            else m.computation(m.num("/Shift/$target"))
        admitted(listOf(
            comp("Hours").name("HoursComp").calc(Dsl.differenceInHours(start, finish)),
            comp("Check").name("CheckComp").`when`(finish.filled()).calc(offsetDays),
            comp("NoFinish").name("NoFinishComp").`when`(finish.notFilled()).calc(offsetDays),
            comp("Finish").name("FinishComp").calc(Dsl.addHours(m.dateTime("/Shift/Handover"), 2).expression()),
            comp("Handover").name("HandoverComp").calc(Dsl.addDays(start, m.num("/Shift/Offset").asNumber()).expression()),
            comp("Offset").name("OffsetComp").calc(offsetDays),
            comp("NextDay").name("NextDayComp").`when`(m.str("/Shift/Note").filled())
                .calc(Dsl.addDays(start, 1).expression()),
            comp("Days").name("DaysComp").calc(Dsl.differenceInDays(start, m.dateTime("/Shift/NextDay"))),
        ).map { it.message(Dsl.en("Shift recomputed.")).build() }.fold(shiftBase) { json, comp -> TypedRules.inject(json, comp) })
    }

    private val landingBase = carrier("shift-datetime-landing") { shift ->
        shift.dateTimeField("Direct") { it.format(STAMP) }.dateTimeField("Landing") { it.format(STAMP) }
    }

    private val landing = BoundModel.ofJson(landingBase).let { m ->
        val start = m.dateTime("/Shift/Start")
        admitted(listOf<Computation>(
            m.computation(m.dateTime("/Shift/Direct")).name("DirectComp")
                .calc(Dsl.addHours(Dsl.addDays(start, 1), 2).expression()).message(Dsl.en("Direct.")).build(),
            m.computation(m.dateTime("/Shift/Landing")).name("LandingComp")
                .calc(Dsl.addDays(start, 1).expression()).message(Dsl.en("Landing.")).build(),
        ).fold(landingBase) { json, comp -> TypedRules.inject(json, comp) })
    }

    data class Row(val id: String, val cells: Map<String, Any>, val expected: List<String>)

    private fun rows(): List<Row> = buildList {
        fun row(id: String, cells: Map<String, Any>, vararg expected: String) = add(Row(id, cells, expected.toList()))
        row("d01-clean", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-03-10T10:00:00", "OffsetDays" to 2, "Note" to "x"), "Handover|VALUE|2024-03-12T10:00:00", "Finish|VALUE|2024-03-12T12:00:00", "NextDay|VALUE|2024-03-11T10:00:00", "Hours|VALUE|50", "Days|VALUE|1", "Check|VALUE|2", "NoFinish|CLEARED", "Offset|VALUE|2")
        row("d02-dt0-malformed", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "bad", "OffsetDays" to 2, "Note" to "x"), "Handover|CLEARED", "Finish|CLEARED", "NextDay|CLEARED", "Hours|CLEARED", "Days|CLEARED", "Check|CLEARED", "NoFinish|CLEARED", "Offset|VALUE|2")
        row("d03-n0-malformed", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-03-10T10:00:00", "OffsetDays" to "bad", "Note" to "x"), "Handover|CLEARED", "Finish|CLEARED", "NextDay|VALUE|2024-03-11T10:00:00", "Hours|CLEARED", "Days|VALUE|1", "Check|CLEARED", "NoFinish|CLEARED", "Offset|CLEARED")
        row("d04-dt0-absent", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "OffsetDays" to 2, "Note" to "x"), "Handover|CLEARED", "Finish|CLEARED", "NextDay|CLEARED", "Hours|VALUE|0", "Days|VALUE|0", "Check|CLEARED", "NoFinish|VALUE|2", "Offset|VALUE|2")
        row("d05-n0-absent", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-03-10T10:00:00", "Note" to "x"), "Handover|VALUE|2024-03-10T10:00:00", "Finish|VALUE|2024-03-10T12:00:00", "NextDay|VALUE|2024-03-11T10:00:00", "Hours|VALUE|2", "Days|VALUE|1", "Check|VALUE|0", "NoFinish|CLEARED", "Offset|VALUE|0")
        row("d06-g-absent", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-03-10T10:00:00", "OffsetDays" to 2), "Handover|VALUE|2024-03-12T10:00:00", "Finish|VALUE|2024-03-12T12:00:00", "NextDay|CLEARED", "Hours|VALUE|50", "Days|VALUE|0", "Check|VALUE|2", "NoFinish|CLEARED", "Offset|VALUE|2")
        row("d07-fall-back-elapsed", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-10-27T01:30:00", "OffsetDays" to 0, "Note" to "x"), "Handover|VALUE|2024-10-27T01:30:00", "Finish|VALUE|2024-10-27T02:30:00", "NextDay|VALUE|2024-10-28T01:30:00", "Hours|VALUE|2", "Days|VALUE|1", "Check|VALUE|0", "NoFinish|CLEARED", "Offset|VALUE|0")
        row("d08-fall-back-calendar", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-10-26T02:30:00", "OffsetDays" to 1, "Note" to "x"), "Handover|VALUE|2024-10-27T02:30:00", "Finish|VALUE|2024-10-27T04:30:00", "NextDay|VALUE|2024-10-27T02:30:00", "Hours|VALUE|27", "Days|VALUE|1", "Check|VALUE|1", "NoFinish|CLEARED", "Offset|VALUE|1")
        row("d09-spring-forward", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-03-31T01:30:00", "OffsetDays" to 0, "Note" to "x"), "Handover|VALUE|2024-03-31T01:30:00", "Finish|VALUE|2024-03-31T04:30:00", "NextDay|VALUE|2024-04-01T01:30:00", "Hours|VALUE|2", "Days|VALUE|1", "Check|VALUE|0", "NoFinish|CLEARED", "Offset|VALUE|0")
        // Ours, not the peer's: the spring-forward twin of d10's NextDay. Both sources sit within the offset delta
        // of midnight, which is where a day shift's re-fit must be judged against the expected date.
        row("d12-spring-forward-evening", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-03-30T23:30:00", "OffsetDays" to 0, "Note" to "x"), "Handover|VALUE|2024-03-30T23:30:00", "Finish|VALUE|2024-03-31T01:30:00", "NextDay|VALUE|2024-03-31T23:30:00", "Hours|VALUE|2", "Days|VALUE|1", "Check|VALUE|0", "NoFinish|CLEARED", "Offset|VALUE|0")
        row("d10-fall-back-first-occurrence", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-10-27T00:30:00", "OffsetDays" to 0, "Note" to "x"), "Handover|VALUE|2024-10-27T00:30:00", "Finish|VALUE|2024-10-27T02:30:00", "NextDay|VALUE|2024-10-28T00:30:00", "Hours|VALUE|3", "Days|VALUE|1", "Check|VALUE|0", "NoFinish|CLEARED", "Offset|VALUE|0")
        row("d11-fall-back-document-text", mapOf("Offset" to 99, "Hours" to 99, "Check" to 99, "NoFinish" to 99, "Days" to 99, "Handover" to "2000-01-01T00:00:00", "Finish" to "2000-01-01T00:00:00", "NextDay" to "2000-01-01T00:00:00", "Start" to "2024-10-27T02:30:00", "OffsetDays" to 0, "Note" to "x"), "Handover|VALUE|2024-10-27T02:30:00", "Finish|VALUE|2024-10-27T04:30:00", "NextDay|VALUE|2024-10-28T02:30:00", "Hours|VALUE|2", "Days|VALUE|1", "Check|VALUE|0", "NoFinish|CLEARED", "Offset|VALUE|0")
        row("l1-overlap-landing", mapOf("Start" to "2024-10-26T02:30:00", "Direct" to "2000-01-01T00:00:00", "Landing" to "2000-01-01T00:00:00"), "Direct|VALUE|2024-10-27T03:30:00", "Landing|VALUE|2024-10-27T02:30:00")
        row("l2-control", mapOf("Start" to "2024-03-10T10:00:00", "Direct" to "2000-01-01T00:00:00", "Landing" to "2000-01-01T00:00:00"), "Direct|VALUE|2024-03-11T12:00:00", "Landing|VALUE|2024-03-11T10:00:00")
    }

    @Test
    fun aLaterComputationReadsAComputedDateTimeAtItsRendering() {
        val rows = rows()
        assertEquals(14, rows.size, "a12-kernel-lean's eleven join and two landing documents, and our spring twin")
        val mapper = ObjectMapper()
        val observed = rows.map { row ->
            val model = if (row.id.startsWith("l")) landing else shift
            row.id to outcomes(model, mapper.writeValueAsString(mapOf("Shift" to row.cells)))
        }
        assertEquals(rows.map { row -> row.id to row.expected.map { "/Shift[1]/$it" }.sorted() }, observed, "computed outcomes")
    }

    private companion object {
        const val STAMP = "yyyy-MM-dd'T'HH:mm:ss"
        val DATE_TIMES = setOf("Handover", "Finish", "NextDay")
    }
}
