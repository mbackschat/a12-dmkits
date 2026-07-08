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
 * Multiplatform (JVM + JS) lock for IF85 — the COMPUTE-mode **poison-on-read** semantics: a computation whose
 * evaluation READS a formally-invalid cell (an input field, an ERRORED computed dependency, or an
 * itself-poisoned one) is poisoned — no value, reported CLEARED (report-all), its dependents' reads poison
 * transitively. The poison is READ-driven, so the kernel's scan/short-circuit order decides whether an invalid
 * cell is reached at all. A precondition-false clear stays a plain clear (dependents read EMPTY — IF77). The
 * kernel differential (tri-check, both strategies) is `adapter.laws.ComputePoisonReadDiffTest`; this pins the
 * interpreter's outcomes on both platforms.
 */
class ComputePoisonTest {

    private val FLAG = "/Cart/Flag"
    private val REF = "/Cart/Ref"
    private val BAD = "/Cart/Bad"
    private val SRC = "/Cart/Src"
    private val CAP = "/Cart/Cap"
    private val PRICE = "/Cart/Lines/Price"

    private fun model(vararg comps: ComputationDef) = ModelDef(
        fields = listOf(
            FieldDef(FLAG, Kind.NUMBER, scale = 0),
            FieldDef(REF, Kind.NUMBER, scale = 0),
            FieldDef(BAD, Kind.NUMBER, scale = 0),
            FieldDef(SRC, Kind.NUMBER, scale = 0),
            FieldDef(CAP, Kind.NUMBER, scale = 0, maxValue = "999"),
            FieldDef(PRICE, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
        ),
        rules = emptyList(),
        computations = comps.toList(),
        repeatableGroups = setOf("/Cart/Lines"),
        declaredReps = mapOf("/Cart/Lines" to 99),
    )

    private fun outcomes(m: ModelDef, build: (io.github.mbackschat.a12.dm.interpreter.eval.Document) -> Unit): Map<String, String> {
        val doc = m.newDocument()
        build(doc)
        return DmInterpreter(m).compute(doc).associate {
            it.path to when (it.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"; ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
            }
        }
    }

    private fun flagQuant() = model(
        ComputationDef(FLAG, "/Cart", listOf(ComputationAlternative("AtLeastOneFieldFilled(Lines* / Price)", "1"))),
    )

    @Test
    fun quantifierPoisonIsReadOrderDependent() {
        // An invalid cell BEFORE the first filled one is read → poison → CLEARED.
        assertEquals(mapOf(FLAG to "CLEARED"), outcomes(flagQuant()) { d ->
            d.group("/Cart", 1).field(FLAG, "9", 1, 1)
            d.group("/Cart/Lines", 1, 1).field(PRICE, "5", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field(PRICE, "3.10", 1, 2, 1)
        }, "invalid row 1 read first → poisoned")
        // The scan stops at the first FILLED cell — a later invalid one is never read.
        assertEquals(mapOf(FLAG to "VALUE|1"), outcomes(flagQuant()) { d ->
            d.group("/Cart", 1).field(FLAG, "9", 1, 1)
            d.group("/Cart/Lines", 1, 1).field(PRICE, "3.10", 1, 1, 1)
            d.group("/Cart/Lines", 1, 2).field(PRICE, "5", 1, 2, 1)
        }, "filled row 1 decides — no poison")
    }

    @Test
    fun poisonedDependencyCascadesThroughAPresenceCheck() {
        val m = model(
            ComputationDef(REF, "/Cart", listOf(ComputationAlternative(null, "[Bad]"))),
            ComputationDef(FLAG, "/Cart", listOf(ComputationAlternative("FieldNotFilled(Ref)", "1"))),
        )
        assertEquals(mapOf(REF to "CLEARED", FLAG to "CLEARED"), outcomes(m) { d ->
            d.group("/Cart", 1).field(FLAG, "9", 1, 1).field(REF, "4", 1, 1).field(BAD, "7.5", 1, 1)
        }, "an operand-invalidity clear is a POISON — the dependent's FieldNotFilled read poisons too")
    }

    @Test
    fun preconditionClearedDependencyReadsEmpty() {
        val m = model(
            ComputationDef(REF, "/Cart", listOf(ComputationAlternative(null, "[Src]")), "FieldFilled(Src)"),
            ComputationDef(FLAG, "/Cart", listOf(ComputationAlternative("FieldNotFilled(Ref)", "1"))),
        )
        assertEquals(mapOf(REF to "CLEARED", FLAG to "VALUE|1"), outcomes(m) { d ->
            d.group("/Cart", 1).field(FLAG, "9", 1, 1).field(REF, "4", 1, 1)
        }, "a precondition clear cascades as EMPTY (IF77) — the dependent computes")
    }

    @Test
    fun erroredDependencyPoisonsItsReader() {
        val m = model(
            ComputationDef(CAP, "/Cart", listOf(ComputationAlternative(null, "5000"))),
            ComputationDef(FLAG, "/Cart", listOf(ComputationAlternative(null, "[Cap]"))),
        )
        assertEquals(mapOf(CAP to "ERRORED", FLAG to "CLEARED"), outcomes(m) { d ->
            d.group("/Cart", 1).field(FLAG, "9", 1, 1)
        }, "reading an ERRORED dependency poisons — reported CLEARED (report-all), even unseeded")
    }

    @Test
    fun andShortCircuitsBeforeAPoisoningRight() {
        val m = model(
            ComputationDef(FLAG, "/Cart", listOf(
                ComputationAlternative("FieldNotFilled(Src) And AtLeastOneFieldFilled(Lines* / Price)", "1"),
                ComputationAlternative("FieldFilled(Src)", "2"),
            )),
        )
        assertEquals(mapOf(FLAG to "VALUE|2"), outcomes(m) { d ->
            d.group("/Cart", 1).field(FLAG, "9", 1, 1).field(SRC, "1", 1, 1)
            d.group("/Cart/Lines", 1, 1).field(PRICE, "5", 1, 1, 1)
        }, "a FALSE left conjunct skips the poisoning right — alternative 2 fires")
    }

    @Test
    fun aPoisonSkipsTheRemainingAlternatives() {
        val m = model(
            ComputationDef(FLAG, "/Cart", listOf(
                ComputationAlternative("AtLeastOneFieldFilled(Lines* / Price) And FieldNotFilled(Src)", "1"),
                ComputationAlternative("FieldFilled(Src)", "2"),
            )),
        )
        assertEquals(mapOf(FLAG to "CLEARED"), outcomes(m) { d ->
            d.group("/Cart", 1).field(FLAG, "9", 1, 1).field(SRC, "1", 1, 1)
            d.group("/Cart/Lines", 1, 1).field(PRICE, "5", 1, 1, 1)
        }, "the poison in alternative 1's precondition aborts the instance — alternative 2 is not tried")
    }
}
