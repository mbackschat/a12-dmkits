package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM + JS outcome lock for SPEC-2026-07-26-04. The poison overlay retains computed-cell identity only:
 * distinct producer failures collapse to the same dependent CLEARED outcome, while a clean no-selection
 * result remains readable as empty and its numeric dependent computes zero.
 */
class ComputedDependencyCauseErasureTest {

    @Test
    fun invalidProducerCausesAreNotReconstructedAcrossTheDependencyEdge() {
        val model = ModelDef(
            fields = listOf(
                FieldDef(MALFORMED, Kind.NUMBER, scale = 0),
                FieldDef(SELECTION_GATE, Kind.NUMBER, scale = 0),
                FieldDef(REJECTED, Kind.NUMBER, scale = 0, maxValue = "9"),
                FieldDef(POISONED, Kind.NUMBER, scale = 0),
                FieldDef(CLEAN_EMPTY, Kind.NUMBER, scale = 0),
                FieldDef(FROM_REJECTED, Kind.NUMBER, scale = 0),
                FieldDef(FROM_POISONED, Kind.NUMBER, scale = 0),
                FieldDef(FROM_CLEAN_EMPTY, Kind.NUMBER, scale = 0),
            ),
            rules = emptyList(),
            computations = listOf(
                computation(REJECTED, "10"),
                computation(POISONED, "[MalformedInput]"),
                ComputationDef(
                    CLEAN_EMPTY,
                    ROOT,
                    listOf(ComputationAlternative(null, "[MalformedInput]")),
                    "FieldFilled(SelectionGate)",
                ),
                computation(FROM_REJECTED, "[RejectedProjection]"),
                computation(FROM_POISONED, "[PoisonedProjection]"),
                computation(FROM_CLEAN_EMPTY, "[CleanEmptyProjection]"),
            ),
        )
        val document = model.newDocument().group(ROOT, 1).field(MALFORMED, "1.5", 1, 1).apply {
            TARGETS.forEach { field(it, "7", 1, 1) }
        }

        val outcomes = DmInterpreter(model).compute(document).associate { computed ->
            computed.path to when (computed.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${computed.value}"
            }
        }

        assertEquals(
            mapOf(
                REJECTED to "ERRORED",
                POISONED to "CLEARED",
                CLEAN_EMPTY to "CLEARED",
                FROM_REJECTED to "CLEARED",
                FROM_POISONED to "CLEARED",
                FROM_CLEAN_EMPTY to "VALUE|0",
            ),
            outcomes,
        )
    }

    private fun computation(target: String, operation: String) =
        ComputationDef(target, ROOT, listOf(ComputationAlternative(null, operation)))

    private companion object {
        const val ROOT = "/Claim"
        const val MALFORMED = "$ROOT/MalformedInput"
        const val SELECTION_GATE = "$ROOT/SelectionGate"
        const val REJECTED = "$ROOT/RejectedProjection"
        const val POISONED = "$ROOT/PoisonedProjection"
        const val CLEAN_EMPTY = "$ROOT/CleanEmptyProjection"
        const val FROM_REJECTED = "$ROOT/FromRejected"
        const val FROM_POISONED = "$ROOT/FromPoisoned"
        const val FROM_CLEAN_EMPTY = "$ROOT/FromCleanEmpty"
        val TARGETS = listOf(REJECTED, POISONED, CLEAN_EMPTY, FROM_REJECTED, FROM_POISONED, FROM_CLEAN_EMPTY)
    }
}
