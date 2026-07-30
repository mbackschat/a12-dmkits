package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * SPEC-2026-07-29-02 — a computed TIME is the exact zone-free clock `HH:mm:ss`; the kernel's internal
 * 1970-01-01 model-zone carrier is transport only. The maintained matrix reuses the ordinary computation
 * result channels and apply owners: no temporal value, representation, or differential harness is added.
 *
 * Dynamic Groovy is normative and generated Java is checked independently. The interpreter comparison uses its
 * deliberate report-all computation contract. Time's source-identical change classification is checked on both
 * kernel strategies through the five-channel owner; the existing generic computation-channel law retains the
 * ordinary unchanged control and the general source-relative application contract.
 */
class ComputedTimeTargetDiffTest {

    private data class Input(
        val hour: String? = "10",
        val minute: String? = "30",
        val second: String? = "45",
        val scheduledAt: String? = "2024-06-01T10:30:45",
        val target: String? = "09:00:00",
        val projected: String? = "09:00:00",
    ) {
        fun kernel(): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
            hour?.let { doc.field(HOUR, it, 1, 1) }
            minute?.let { doc.field(MINUTE, it, 1, 1) }
            second?.let { doc.field(SECOND, it, 1, 1) }
            scheduledAt?.let { doc.field(SCHEDULED_AT, it, 1, 1) }
            target?.let { doc.field(TARGET, it, 1, 1) }
            projected?.let { doc.field(PROJECTED, it, 1, 1) }
        }

        fun interpreted(model: ModelDef): Document = model.newDocument().group(ROOT, 1).also { doc ->
            hour?.let { doc.field(HOUR, it, 1, 1) }
            minute?.let { doc.field(MINUTE, it, 1, 1) }
            second?.let { doc.field(SECOND, it, 1, 1) }
            scheduledAt?.let { doc.field(SCHEDULED_AT, it, 1, 1) }
            target?.let { doc.field(TARGET, it, 1, 1) }
            projected?.let { doc.field(PROJECTED, it, 1, 1) }
        }
    }

    private class Fixture(zone: String) {
        private val draft = DocumentModelDraft.named("computed-clock").locale("en_US")
            .rootGroup("Clock") { group ->
                group.numberField("Hour") { it.min("0").minLength(2).maxLength(2).maxIntegerDigits(2) }
                    .numberField("Minute") { it.min("0").minLength(2).maxLength(2).maxIntegerDigits(2) }
                    .numberField("Second") { it.min("0").minLength(2).maxLength(2).maxIntegerDigits(2) }
                    .dateTimeField("ScheduledAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
                    .timeField("Target") { it.format("HH:mm:ss") }
                    .timeField("Projected") { it.format("HH:mm:ss") }
            }
            .build()
        private val edited = EditSession.read(draft.toJson()).also { it.config().timeZone(zone) }
        private val bound = BoundModel.ofJson(edited.toJson())
        private val constructed = bound.computation(bound.time(TARGET).field()).name("ConstructedClock")
            .calc(Dsl.time(bound.num(HOUR).field(), bound.num(MINUTE).field(), bound.num(SECOND).field()).expression())
            .message(Dsl.en("Construct the clock."))
            .build()
        private val wallProjection = bound.computation(bound.time(PROJECTED).field()).name("ProjectedClock")
            .calc(Dsl.timeFromDateTime(bound.dateTime(SCHEDULED_AT)).expression())
            .message(Dsl.en("Project the wall clock."))
            .build()
        private val exactClock = bound.rule(bound.group(ROOT)).name("ExactAppliedClock")
            .errorField(bound.time(TARGET).field())
            .`when`(bound.time(TARGET).eq(Dsl.timeValue("10:30:45"))
                .and(bound.time(PROJECTED).eq(Dsl.timeValue("10:30:45"))))
            .code(EXACT_CLOCK)
            .severity(Severity.ERROR)
            .message(Dsl.en("Both applied clocks retain 10:30:45."))
            .build()
        private val leakedUtcClock = bound.rule(bound.group(ROOT)).name("LeakedUtcClock")
            .errorField(bound.time(TARGET).field())
            .`when`(bound.time(TARGET).eq(Dsl.timeValue("09:30:45"))
                .or(bound.time(PROJECTED).eq(Dsl.timeValue("09:30:45"))))
            .code(LEAKED_UTC_CLOCK)
            .severity(Severity.ERROR)
            .message(Dsl.en("A transport instant leaked into clock identity."))
            .build()

        val json: String = TypedRules.inject(
            TypedRules.inject(
                TypedRules.inject(
                    TypedRules.inject(edited.toJson(), constructed),
                    wallProjection,
                ),
                exactClock,
            ),
            leakedUtcClock,
        )
        val model: ModelDef = ModelLoader.load(json)
        val java: RuntimeLaws.JavaKernelEngine = RuntimeLaws.kernelJavaEngine(json)
    }

    @Test
    fun constructedAndProjectedClocksAreExactAndZoneFree() {
        val expected = mapOf(TARGET to "VALUE|10:30:45", PROJECTED to "VALUE|10:30:45")
        val byZone = listOf("UTC", "Europe/Berlin").associateWith { zone ->
            val fixture = Fixture(zone)
            assertTri(fixture, Input(), expected, "$zone exact clocks")
            assertAppliedClock(fixture, Input(), "$zone exact application")
            dynamicSignatures(fixture, Input())
        }

        assertEquals(byZone.getValue("UTC"), byZone.getValue("Europe/Berlin"),
            "the same wall clock is independent of the model zone")
    }

    @Test
    fun impossibleAndMissingConstructionClearAStaleTarget() {
        val fixture = Fixture("Europe/Berlin")
        val cases = listOf(
            Input(hour = "24") to "impossible hour",
            Input(hour = null) to "missing hour",
        )
        cases.forEach { (input, label) ->
            assertTri(fixture, input,
                mapOf(TARGET to "CLEARED", PROJECTED to "VALUE|10:30:45"), label)
            assertClearedApplication(fixture, input, "$label application")
        }
    }

    @Test
    fun aFormallyInvalidComponentPoisonsTheConstruction() {
        val fixture = Fixture("Europe/Berlin")
        val input = Input(hour = "1")
        val dynamic = RuntimeLaws.resultChannels(fixture.json, input.kernel())
        val generated = fixture.java.resultChannels(fixture.java.instance(input.kernel()))

        listOf(dynamic, generated).forEach { channels ->
            assertTrue(channels.formalErrorsInOperands().any { it.errorField().contains("Hour") },
                "the invalid hour remains a formal operand error: ${channels.formalErrorsInOperands()}")
            assertTrue(channels.cleared().any { it.contains("Target") },
                "the poisoned Time construction clears the stale target: ${channels.cleared()}")
        }
        val interpretedDocument = input.interpreted(fixture.model)
        val interpreter = DmInterpreter(fixture.model)
        assertTrue(interpreter.validateFull(interpretedDocument).any { it.errorPath.contains("Hour") },
            "the interpreter retains the component's formal error")
        assertEquals(ComputeOutcome.CLEARED,
            interpreter.compute(interpretedDocument).single { it.path == TARGET }.outcome,
            "formal unavailability poisons the Time construction")
        assertEquals(StoredCellState.PresentEmpty,
            interpreter.applyComputations(interpretedDocument).cellStateAt(TARGET, CELL),
            "applying the poisoned result clears the stale target")
    }

    @Test
    fun evenTheSameClockIsSourceClassifiedAsChangedAndApplied() {
        val fixture = Fixture("Europe/Berlin")
        assertChangedClassification(fixture, Input(target = "09:00:00"))
        assertChangedClassification(fixture, Input(target = "10:30:45"))

        val destination = Input(
            hour = null,
            minute = null,
            second = null,
            scheduledAt = null,
            target = "12:00:00",
            projected = null,
        )
        assertEquals(
            listOf("$TARGET|presentValue|1970-01-01T09:30:45Z"),
            RuntimeLaws.appliedStatesToDestination(
                fixture.json,
                Input(target = "09:00:00", scheduledAt = null, projected = null).kernel(),
                destination.kernel(),
                listOf(RuntimeLaws.CellProbe(TARGET, CELL)),
            ),
            "a source-relative changed Time writes its exact clock to the destination",
        )
        assertEquals(
            listOf("$TARGET|presentValue|1970-01-01T09:30:45Z"),
            RuntimeLaws.appliedStatesToDestination(
                fixture.json,
                Input(target = "10:30:45", scheduledAt = null, projected = null).kernel(),
                destination.kernel(),
                listOf(RuntimeLaws.CellProbe(TARGET, CELL)),
            ),
            "kernel 30.8.1 classifies an identical source Time as changed and therefore overwrites the destination",
        )
    }

    private fun assertChangedClassification(fixture: Fixture, input: Input) {
        val dynamic = RuntimeLaws.resultChannels(fixture.json, input.kernel())
        val generated = fixture.java.resultChannels(fixture.java.instance(input.kernel()))
        listOf(dynamic, generated).forEach { channels ->
            assertTrue(channels.withoutErrors().any { it.contains("Target") },
                "every clean Time result is in the successful channel: ${channels.withoutErrors()}")
            assertTrue(channels.withChanges().any { it.contains("Target") },
                "kernel 30.8.1 classifies the Time result as changed: ${channels.withChanges()}")
            assertFalse(channels.withErrors().any { it.contains("Target") },
                "a real exact Time is not errored: ${channels.withErrors()}")
        }
    }

    private fun assertTri(
        fixture: Fixture,
        input: Input,
        expected: Map<String, String>,
        label: String,
    ) {
        val dynamic = dynamicSignatures(fixture, input)
        val generated = fixture.java.computeOutcomesSorted(fixture.java.instance(input.kernel()))
            .filter { it.substringBefore('|') in TARGETS }
            .associate { encoded ->
                val path = encoded.substringBefore('|')
                path to when (val value = encoded.substringAfter('|')) {
                    "CLEARED" -> "CLEARED"
                    "ERR" -> "ERRORED"
                    else -> "VALUE|$value"
                }
            }
        val interpreted = DmInterpreter(fixture.model).compute(input.interpreted(fixture.model))
            .filter { it.path in TARGETS }
            .associate { computed ->
                computed.path to when (computed.outcome) {
                    ComputeOutcome.VALUE -> "VALUE|${computed.value}"
                    ComputeOutcome.CLEARED -> "CLEARED"
                    ComputeOutcome.ERRORED -> "ERRORED|${computed.value}"
                }
            }

        assertEquals(expected, dynamic, "$label — dynamic Groovy")
        assertEquals(dynamic, generated, "$label — generated Java")
        assertEquals(dynamic, interpreted, "$label — interpreter")
    }

    private fun dynamicSignatures(fixture: Fixture, input: Input): Map<String, String> =
        RuntimeLaws.compute(fixture.json, input.kernel())
            .filter { it.path() in TARGETS }
            .associate { computed ->
                computed.path() to when {
                    computed.cleared() -> "CLEARED"
                    computed.errored() -> "ERRORED|${computed.value().orElse("")}"
                    else -> "VALUE|${computed.value().orElse("")}"
                }
            }

    private fun assertAppliedClock(
        fixture: Fixture,
        input: Input,
        label: String,
    ) {
        val relevant = setOf(EXACT_CLOCK, LEAKED_UTC_CLOCK)
        val dynamic = RuntimeLaws.messagesAfterCompute(fixture.json, input.kernel(), java.util.Locale.US)
            .map { it.code() }.filter { it in relevant }.sorted()
        val generated = fixture.java.messagesAfterCompute(input.kernel())
            .map { it.code() }.filter { it in relevant }.sorted()
        val interpreter = DmInterpreter(fixture.model)
        val interpreted = interpreter.validateFull(
            interpreter.applyComputations(input.interpreted(fixture.model)),
        ).map { it.code }.filter { it in relevant }.sorted()

        assertEquals(listOf(EXACT_CLOCK), dynamic, "$label — dynamic Groovy")
        assertEquals(dynamic, generated, "$label — generated Java")
        assertEquals(dynamic, interpreted, "$label — interpreter")
    }

    private fun assertClearedApplication(fixture: Fixture, input: Input, label: String) {
        val probe = listOf(RuntimeLaws.CellProbe(TARGET, CELL))
        val dynamic = RuntimeLaws.appliedStates(fixture.json, input.kernel(), probe)
        val generated = fixture.java.appliedStates(input.kernel(), probe)
        val applied = DmInterpreter(fixture.model).applyComputations(input.interpreted(fixture.model))
        val interpreted = listOf("$TARGET|${wire(applied.cellStateAt(TARGET, CELL))}")

        assertEquals(listOf("$TARGET|presentEmpty"), dynamic, "$label — dynamic Groovy")
        assertEquals(dynamic, generated, "$label — generated Java")
        assertEquals(dynamic, interpreted, "$label — interpreter")
    }

    private fun wire(state: StoredCellState): String = when (state) {
        StoredCellState.Absent -> "absent"
        StoredCellState.PresentEmpty -> "presentEmpty"
        is StoredCellState.PresentValue -> "presentValue|${state.raw}"
    }

    private companion object {
        const val ROOT = "/Clock"
        const val HOUR = "$ROOT/Hour"
        const val MINUTE = "$ROOT/Minute"
        const val SECOND = "$ROOT/Second"
        const val SCHEDULED_AT = "$ROOT/ScheduledAt"
        const val TARGET = "$ROOT/Target"
        const val PROJECTED = "$ROOT/Projected"
        const val EXACT_CLOCK = "EXACT_APPLIED_CLOCK"
        const val LEAKED_UTC_CLOCK = "LEAKED_UTC_CLOCK"
        val TARGETS = listOf(TARGET, PROJECTED)
        val CELL = listOf(1, 1)
    }
}
