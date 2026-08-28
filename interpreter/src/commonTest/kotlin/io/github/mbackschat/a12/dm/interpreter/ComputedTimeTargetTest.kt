package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.StoredCellState
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM + Node peer of `ComputedTimeTargetDiffTest`: computed clocks remain exact `HH:mm:ss` values, never
 * transport instants. Missing/impossible construction has no value, a formally invalid component poisons the
 * result, and applying VALUE/CLEARED outcomes writes or empties the ordinary TIME cell.
 */
class ComputedTimeTargetTest {

    private fun model(zone: ModelTimeZone) = ModelDef(
        fields = listOf(
            FieldDef(HOUR, Kind.NUMBER, minValue = "0", numberMinLength = 2, numberMaxLength = 2, maxIntegerDigits = 2),
            FieldDef(MINUTE, Kind.NUMBER, minValue = "0", numberMinLength = 2, numberMaxLength = 2, maxIntegerDigits = 2),
            FieldDef(SECOND, Kind.NUMBER, minValue = "0", numberMinLength = 2, numberMaxLength = 2, maxIntegerDigits = 2),
            FieldDef(SCHEDULED_AT, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss"),
            FieldDef(TARGET, Kind.TIME, format = "HH:mm:ss"),
            FieldDef(PROJECTED, Kind.TIME, format = "HH:mm:ss"),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(TARGET, ROOT, "Time(Hour, Minute, Second)"),
            ComputationDef(PROJECTED, ROOT, "TimeFromDateTime(ScheduledAt)"),
        ),
        timeZone = zone,
    )

    @Test
    fun constructedAndProjectedClocksAreZoneFree() {
        val byZone = listOf(ModelTimeZone.UTC, ModelTimeZone.EUROPE_BERLIN).associateWith { zone ->
            val model = model(zone)
            val results = DmInterpreter(model).compute(validDocument(model))
                .associate { it.path to "${it.outcome}|${it.value}" }
            assertEquals("VALUE|10:30:45", results.getValue(TARGET))
            assertEquals("VALUE|10:30:45", results.getValue(PROJECTED))
            results
        }
        assertEquals(byZone.getValue(ModelTimeZone.UTC), byZone.getValue(ModelTimeZone.EUROPE_BERLIN))
    }

    @Test
    fun missingAndImpossibleConstructionHaveNoValueAndClearOnApplication() {
        val model = model(ModelTimeZone.EUROPE_BERLIN)
        listOf(null, "24").forEach { hour ->
            val document = model.newDocument().group(ROOT, 1)
                .field(MINUTE, "30", 1, 1)
                .field(SECOND, "45", 1, 1)
                .field(SCHEDULED_AT, "2024-06-01T10:30:45", 1, 1)
                .field(TARGET, "09:00:00", 1, 1)
                .also { hour?.let { value -> it.field(HOUR, value, 1, 1) } }
            val interpreter = DmInterpreter(model)
            assertEquals(ComputeOutcome.CLEARED,
                interpreter.compute(document).single { it.path == TARGET }.outcome)
            assertEquals(StoredCellState.PresentEmpty,
                interpreter.applyComputations(document).cellStateAt(TARGET, CELL))
        }
    }

    @Test
    fun aFormallyInvalidComponentPoisonsTheConstruction() {
        val model = model(ModelTimeZone.EUROPE_BERLIN)
        val document = validDocument(model, hour = "1")
        val interpreter = DmInterpreter(model)

        assertTrue(interpreter.validateFull(document).any { it.errorPath.contains("Hour") })
        assertEquals(ComputeOutcome.CLEARED,
            interpreter.compute(document).single { it.path == TARGET }.outcome)
        assertEquals(StoredCellState.PresentEmpty,
            interpreter.applyComputations(document).cellStateAt(TARGET, CELL))
    }

    @Test
    fun exactClockApplicationUsesTheOrdinaryTimeCell() {
        val model = model(ModelTimeZone.EUROPE_BERLIN)
        val applied = DmInterpreter(model).applyComputations(validDocument(model))
        assertEquals(StoredCellState.PresentValue("10:30:45"), applied.cellStateAt(TARGET, CELL))
        assertEquals(StoredCellState.PresentValue("10:30:45"), applied.cellStateAt(PROJECTED, CELL))
    }

    @Test
    fun evenASourceEqualClockRemainsARetainedValueAction() {
        val model = model(ModelTimeZone.EUROPE_BERLIN)
        val source = validDocument(model, target = "10:30:45", projected = "10:30:45")
        val destination = validDocument(model, target = "08:15:00", projected = "08:15:00")

        val retained = DmInterpreter(model).retainedComputations(source)
        val applied = retained.applyTo(destination)

        assertTrue(
            retained.applications.filter { it.path == TARGET || it.path == PROJECTED }
                .all { it.outcome == ComputeOutcome.VALUE },
            "a SEEDED source-equal clock is a retained action — matching the kernel on a seeded document; "
                + "the re-apply case is where the two part company (IG142)",
        )
        assertEquals(StoredCellState.PresentValue("10:30:45"), applied.cellStateAt(TARGET, CELL))
        assertEquals(StoredCellState.PresentValue("10:30:45"), applied.cellStateAt(PROJECTED, CELL))
    }

    /**
     * **Pins the IG142 divergence, not the contract.** Compute over a document whose Time cell IS this
     * computation's own previous output. Both kernel strategies report NO change there
     * (`adapter.laws.RepeatableTimeConstructionChangedDiffTest`, and `ComputedTimeTargetDiffTest`'s
     * discriminator, whose successful channel confirms the second pass ran); the interpreter still emits a
     * `VALUE` action, because `Document.computedValueEquals` answers `Kind.TIME -> false` unconditionally.
     *
     * The expectation below is therefore the WRONG answer, recorded so the row cannot rot: when IG142 is fixed
     * this test fails and points at it. Do not "restore" it — change it to the kernel's empty action set.
     */
    @Test
    fun aReapplyOverTheInterpretersOwnAppliedClockIsMeasured() {
        val model = model(ModelTimeZone.EUROPE_BERLIN)
        val interpreter = DmInterpreter(model)
        val applied = interpreter.applyComputations(validDocument(model))
        val secondPass = interpreter.retainedComputations(applied).applications
            .filter { it.path == TARGET || it.path == PROJECTED }
            .map { "${it.path}|${it.outcome}" }
            .sorted()
        assertEquals(
            listOf("$PROJECTED|VALUE", "$TARGET|VALUE"),
            secondPass,
            "IG142: the interpreter re-applies a Time cell the kernel leaves alone",
        )
    }

    private fun validDocument(
        model: ModelDef,
        hour: String = "10",
        target: String = "09:00:00",
        projected: String = "09:00:00",
    ) = model.newDocument().group(ROOT, 1)
        .field(HOUR, hour, 1, 1)
        .field(MINUTE, "30", 1, 1)
        .field(SECOND, "45", 1, 1)
        .field(SCHEDULED_AT, "2024-06-01T10:30:45", 1, 1)
        .field(TARGET, target, 1, 1)
        .field(PROJECTED, projected, 1, 1)

    private companion object {
        const val ROOT = "/Clock"
        const val HOUR = "$ROOT/Hour"
        const val MINUTE = "$ROOT/Minute"
        const val SECOND = "$ROOT/Second"
        const val SCHEDULED_AT = "$ROOT/ScheduledAt"
        const val TARGET = "$ROOT/Target"
        const val PROJECTED = "$ROOT/Projected"
        val CELL = listOf(1, 1)
    }
}
