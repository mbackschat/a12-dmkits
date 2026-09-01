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

    /**
     * The application surface is SOURCE-relative, so a clock the source already holds leaves the destination's
     * own value alone. The kernel's String-kind analogue is `ComputeResultChannelsLawsTest`, whose unchanged
     * `DisplaySku` keeps the destination sentinel; Time behaves the same way once it stops being special-cased.
     */
    @Test
    fun aSourceEqualClockRetainsNoActionAndLeavesTheDestinationAlone() {
        val model = model(ModelTimeZone.EUROPE_BERLIN)
        val source = validDocument(model, target = "10:30:45", projected = "10:30:45")
        val destination = validDocument(model, target = "08:15:00", projected = "08:15:00")

        val retained = DmInterpreter(model).retainedComputations(source)
        val applied = retained.applyTo(destination)

        assertEquals(
            emptyList<String>(),
            retained.applications.filter { it.path == TARGET || it.path == PROJECTED }
                .map { "${it.path}|${it.outcome}" }.sorted(),
            "a source-equal clock mints no action",
        )
        // The destination keeps its OWN clock: nothing was applied over it.
        assertEquals(StoredCellState.PresentValue("08:15:00"), applied.cellStateAt(TARGET, CELL))
        assertEquals(StoredCellState.PresentValue("08:15:00"), applied.cellStateAt(PROJECTED, CELL))
    }

    /**
     * A re-apply retains NOTHING: the stored Time cell IS this computation's own previous output, so it is
     * source-identical and no action is minted. Both kernel strategies agree on the typed route this
     * interpreter's public transport corresponds to — see `adapter.laws.SourceIdentityIngressRouteDiffTest`,
     * which measures every kind on both of A12's ingress routes, and `ComputedTimeTargetDiffTest`'s
     * discriminator, whose successful channel confirms the second pass actually ran.
     *
     * This is the assertion the retired IG142 inverted. The old `Kind.TIME -> false` was calibrated against
     * the kernel's V1-STRING seed route, where a typed cell holds an unconverted String and therefore never
     * compares equal — a route with no public counterpart here.
     */
    @Test
    fun aReapplyOverTheInterpretersOwnAppliedClockRetainsNothing() {
        val model = model(ModelTimeZone.EUROPE_BERLIN)
        val interpreter = DmInterpreter(model)
        val applied = interpreter.applyComputations(validDocument(model))
        val secondPass = interpreter.retainedComputations(applied).applications
            .filter { it.path == TARGET || it.path == PROJECTED }
            .map { "${it.path}|${it.outcome}" }
            .sorted()
        assertEquals(
            emptyList<String>(),
            secondPass,
            "a source-identical Time cell is not re-applied — the kernel's typed-route answer",
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
