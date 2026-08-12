package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.eval.CascadeOverlay
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayContext
import io.github.mbackschat.a12.dm.interpreter.eval.OverlayViews
import io.github.mbackschat.a12.dm.interpreter.eval.EvalConfig
import io.github.mbackschat.a12.dm.interpreter.eval.Interpreter
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldTypeDeclaration
import io.github.mbackschat.a12.dm.interpreter.model.CustomTypeRejection
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform lock for the RV04 bare-group computation scan: field-major declared order, the complete formal
 * oracle, cascade overlay state, and contained-target dependency closure all ride one lazy identity-preserving
 * scan. Kernel strategy differentials live in `adapter.laws.BareGroupComputeExactnessDiffTest`.
 */
class BareGroupComputeExactnessTest {

    @Test
    fun declaredMissingRowStopsBeforeMalformedLaterField() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(FLAG, Kind.NUMBER, scale = 0),
                FieldDef(PROBE, Kind.NUMBER, scale = 0),
                FieldDef(FIRST, Kind.NUMBER, scale = 0),
                FieldDef(LATER, Kind.NUMBER, scale = 0),
            ),
            rules = emptyList(),
            computations = listOf(
                computation(FLAG, "AllFieldsFilled(Details)", "1"),
                computation(PROBE, "FieldNotFilled(Flag)", "1"),
            ),
            repeatableGroups = setOf(ENTRIES),
            declaredReps = mapOf(ENTRIES to 2),
        )
        val doc = model.newDocument()
            .field(FLAG, "9", 1, 1)
            .field(PROBE, "9", 1, 1)
            .field(FIRST, "1", 1, 1, 1, 1)
            .field(LATER, "7.5", 1, 1, 1, 1)

        assertEquals("VALUE|1", outcome(DmInterpreter(model).compute(doc), PROBE))
    }

    @Test
    fun formallyValidDayOptionalValueIsFilledNotPoisoned() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(FLAG, Kind.NUMBER, scale = 0),
                FieldDef(DATE, Kind.DATE, format = "dd.MM.yyyy", datePrecision = DatePrecision.DAY_OPTIONAL),
            ),
            rules = emptyList(),
            computations = listOf(computation(FLAG, "AtLeastOneFieldFilled(Details)", "1")),
        )
        val doc = model.newDocument().field(DATE, "00.06.2024", 1, 1, 1)

        assertEquals("VALUE|1", outcome(DmInterpreter(model).compute(doc), FLAG))
    }

    @Test
    fun customTypeRejectionPoisonsTheBareGroupRead() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(FLAG, Kind.NUMBER, scale = 0),
                FieldDef(CODE, Kind.STRING, customType = CustomFieldTypeDeclaration("ProjectCode")),
            ),
            rules = emptyList(),
            computations = listOf(computation(FLAG, "AtLeastOneFieldFilled(Details)", "1")),
        )
        val doc = model.newDocument().field(CODE, "bad", 1, 1, 1)
        val validator = CustomFieldType { _, _ -> CustomTypeRejection("PROJECT_CODE_INVALID") }

        assertEquals(
            "CLEARED",
            outcome(DmInterpreter(model, customFieldTypes = mapOf("ProjectCode" to validator)).compute(doc), FLAG),
        )
    }

    @Test
    fun containedProducerIsOrderedAndIncludedByComputeField() {
        val model = cascadeModel(
            producer = ComputationDef(SOURCE, DETAILS, "5"),
            consumerCondition = "AtLeastOneFieldFilled(Details)",
        )
        val interpreter = DmInterpreter(model)
        val doc = model.newDocument().field(FLAG, "9", 1, 1)

        val all = interpreter.compute(doc)
        assertEquals("VALUE|1", outcome(all, FLAG), all.toString())
        val one = interpreter.computeField(FLAG, doc)
        assertEquals("VALUE|1", outcome(one, FLAG), one.toString())
    }

    /**
     * **A path the cascade never wrote must not even resolve its coordinates** — the property [IF286] claims and
     * the one nothing was watching. `entryAt` asks `rowsAt(path)` first and only then `repsOf(path)`, so an
     * untouched column exits on a cached `String` hash; `repsOf` is itself a map lookup, so reordering those two
     * lines would quietly restore the cost the change removed while every other test stayed green.
     */
    @Test
    fun anUntouchedColumnIsResolvedWithoutAskingForItsCoordinates() {
        val model = cascadeModel(
            producer = ComputationDef(SOURCE, DETAILS, "5"),
            consumerCondition = "AtLeastOneFieldFilled(Details)",
        )
        val ctx = model.newDocument().field(FLAG, "9", 1, 1).computeContexts(ROOT).single()
        val counting = CountingRepsContext(ctx)
        val cascade = CascadeOverlay().apply {
            overlay(SOURCE, listOf(1, 1, 1), io.github.mbackschat.a12.dm.interpreter.ast.Val.Num(dec(5)))
        }
        val overlay = OverlayContext(
            counting,
            cascade,
            { io.github.mbackschat.a12.dm.interpreter.ast.emptyValue(model.kinds.getValue(it)) },
            { 0 },
            OverlayViews(),
            FLAG,
        )

        overlay.field(FLAG)
        overlay.filled(FLAG)
        overlay.cellState(FLAG)
        assertEquals(0, counting.repsCalls,
            "FLAG is not in the cascade, so none of the three reads may resolve its repetition coordinates")

        overlay.field(SOURCE)
        assertEquals(1, counting.repsCalls, "a path the cascade DID write must resolve its coordinates exactly once")
    }

    /** Counts only what [OverlayContext] asks of its delegate; the real context's internal calls are its own. */
    private class CountingRepsContext(
        private val d: io.github.mbackschat.a12.dm.interpreter.eval.EvalContext,
    ) : io.github.mbackschat.a12.dm.interpreter.eval.EvalContext by d {
        var repsCalls = 0
        override fun repsOf(path: String): List<Int> {
            repsCalls++
            return d.repsOf(path)
        }
    }

    @Test
    fun containedScanRetainsOverlayCellIdentity() {
        val model = cascadeModel(
            producer = ComputationDef(SOURCE, DETAILS, "5"),
            consumerCondition = "AtLeastOneFieldFilled(Details)",
        )
        val ctx = model.newDocument().field(FLAG, "9", 1, 1).computeContexts(ROOT).single()
        val cascade = CascadeOverlay().apply {
            overlay(SOURCE, listOf(1, 1, 1), io.github.mbackschat.a12.dm.interpreter.ast.Val.Num(dec(5)))
        }
        val overlay = OverlayContext(
            ctx,
            cascade,
            { io.github.mbackschat.a12.dm.interpreter.ast.emptyValue(model.kinds.getValue(it)) },
            { 0 },
            OverlayViews(),
            FLAG,
        )

        assertEquals(
            listOf(SOURCE to "5", GATE to null),
            overlay.containedFieldCells(DETAILS, declaredRange = false).map { it.path to it.raw }.toList(),
        )
        assertEquals(
            Tri.TRUE,
            Interpreter(overlay, EvalConfig(computeMode = true))
                .eval(parseCondition("AtLeastOneFieldFilled(Details)", ROOT)),
        )
    }

    @Test
    fun bareGroupReadsClearedAndErroredOverlayStates() {
        val cleared = cascadeModel(
            producer = ComputationDef(
                SOURCE,
                DETAILS,
                listOf(ComputationAlternative("FieldFilled(Gate)", "5")),
            ),
            consumerCondition = "NoFieldFilled(Details)",
        )
        assertEquals(
            "VALUE|1",
            outcome(DmInterpreter(cleared).compute(cleared.newDocument().field(FLAG, "9", 1, 1)), FLAG),
        )

        val errored = cascadeModel(
            producer = ComputationDef(SOURCE, DETAILS, "50"),
            consumerCondition = "AtLeastOneFieldFilled(Details)",
            sourceMax = "10",
        )
        val outcomes = DmInterpreter(errored).compute(errored.newDocument().field(FLAG, "9", 1, 1))
        assertEquals("ERRORED", outcome(outcomes, SOURCE))
        assertEquals("CLEARED", outcome(outcomes, FLAG))
    }

    private fun computation(target: String, precondition: String, operation: String) =
        ComputationDef(target, ROOT, listOf(ComputationAlternative(precondition, operation)))

    private fun cascadeModel(
        producer: ComputationDef,
        consumerCondition: String,
        sourceMax: String? = null,
    ) = ModelDef(
        fields = listOf(
            FieldDef(FLAG, Kind.NUMBER, scale = 0),
            FieldDef(SOURCE, Kind.NUMBER, scale = 0, maxValue = sourceMax),
            FieldDef(GATE, Kind.NUMBER, scale = 0),
        ),
        rules = emptyList(),
        computations = listOf(
            computation(FLAG, consumerCondition, "1"),
            producer,
        ),
    )

    private fun outcome(outcomes: List<io.github.mbackschat.a12.dm.interpreter.model.Computed>, path: String): String {
        val result = outcomes.single { it.path == path }
        return when (result.outcome) {
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
            ComputeOutcome.VALUE -> "VALUE|${result.value}"
        }
    }

    private companion object {
        const val ROOT = "/Policy"
        const val DETAILS = "/Policy/Details"
        const val ENTRIES = "/Policy/Details/Entries"
        const val FLAG = "/Policy/Flag"
        const val PROBE = "/Policy/Probe"
        const val FIRST = "/Policy/Details/Entries/First"
        const val LATER = "/Policy/Details/Entries/Later"
        const val DATE = "/Policy/Details/Date"
        const val CODE = "/Policy/Details/Code"
        const val SOURCE = "/Policy/Details/Source"
        const val GATE = "/Policy/Details/Gate"
    }
}
