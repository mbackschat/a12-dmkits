package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform lock for IF326: a DATE_TIME constant naming a wall time the model's DECLARED zone has no
 * instant for computes NO value and reports `berechnungsWertFehler` at the target, rather than storing the
 * canonicalized attempt and flagging it.
 *
 * **The zone is varied, and that is the point of this file.** `adapter.laws.DateTimeConstantZoneSplitDiffTest`
 * is the kernel oracle, but it holds the declared zone fixed at `Europe/Berlin` — KF231 states outright that
 * the separating case, a second model declaring a zone in which the same wall time is ordinary, was not built.
 * Here it is: the SAME literal under a UTC declaration is an ordinary instant and stores. So this locks that
 * the condition is "no instant in THIS model's zone" and not "this label is bad", which is what keeps the fix
 * off every legal DateTime constant. It is a lock on OUR classification only — no kernel row is claimed for
 * the UTC arm, because the kernel was not asked.
 *
 * Own domain (long-distance rail reservations).
 */
class UnresolvableDateTimeConstantComputationTest {

    private companion object {
        const val ROOT = "/Booking"
        const val BOARDS_AT = "/Booking/BoardsAt"

        /** A wall time Europe/Berlin skips: its 2024 transition moves 02:00 straight to 03:00. */
        const val GAP_LABEL = "31.03.2024T02:30:00"

        /** The same calendar label, canonicalized — what the target used to store and flag. */
        const val GAP_CANONICAL = "2024-03-31T02:30:00"

        const val ORDINARY_LABEL = "05.03.2024T12:30:00"
        const val ORDINARY_CANONICAL = "2024-03-05T12:30:00"

        const val COMPUTED_VALUE_ERROR = "berechnungsWertFehler"
    }

    /** One model per (zone, literal) — identical but for those two, so neither can be confounded. */
    private fun model(zone: ModelTimeZone, literal: String) = ModelDef(
        fields = listOf(FieldDef(BOARDS_AT, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss")),
        rules = emptyList(),
        computations = listOf(ComputationDef(BOARDS_AT, ROOT, "\"$literal\"")),
        groupPaths = setOf(ROOT),
        timeZone = zone,
    )

    private fun outcomeOf(zone: ModelTimeZone, literal: String): String {
        val def = model(zone, literal)
        val computed = DmInterpreter(def).compute(def.newDocument().group(ROOT, 1)).single()
        return "${computed.outcome.name}|${computed.value ?: "<none>"}"
    }

    private fun operandErrorsOf(zone: ModelTimeZone, literal: String): List<String> {
        val def = model(zone, literal)
        return DmInterpreter(def)
            .retainedComputations(def.newDocument().group(ROOT, 1))
            .formalErrorsInOperands
            .map { "${it.code}@${it.errorPath}" }
    }

    @Test
    fun anUnresolvableWallLabelComputesNoValueUnderTheDeclaringZone() {
        assertEquals("${ComputeOutcome.CLEARED.name}|<none>", outcomeOf(ModelTimeZone.EUROPE_BERLIN, GAP_LABEL),
            "a label with no instant has nothing to store — never the canonicalized attempt")
    }

    @Test
    fun theRefusalRidesTheOperandChannelAtTheTarget() {
        assertEquals(listOf("$COMPUTED_VALUE_ERROR@/Booking[1]/BoardsAt"),
            operandErrorsOf(ModelTimeZone.EUROPE_BERLIN, GAP_LABEL),
            "the cell's invalidity is reported, and on the channel the kernel uses for it")
    }

    /**
     * The zone control. Same literal, one builder argument different — and under UTC the wall time exists, so
     * the constant stores exactly as any other. Without this row the fix would be indistinguishable from
     * refusing the spelling.
     */
    @Test
    fun theSameLabelStoresWhenTheDeclaredZoneHasAnInstantForIt() {
        assertEquals("${ComputeOutcome.VALUE.name}|$GAP_CANONICAL", outcomeOf(ModelTimeZone.UTC, GAP_LABEL),
            "the condition is the declared zone's gap, not the label")
        assertEquals(emptyList(), operandErrorsOf(ModelTimeZone.UTC, GAP_LABEL),
            "and it carries no operand finding, or the Berlin row's finding says nothing")
    }

    /** The liveness control: an ordinary label still computes its instant under the SAME Berlin declaration. */
    @Test
    fun anOrdinaryLabelStillComputesUnderTheDeclaringZone() {
        assertEquals("${ComputeOutcome.VALUE.name}|$ORDINARY_CANONICAL",
            outcomeOf(ModelTimeZone.EUROPE_BERLIN, ORDINARY_LABEL),
            "the Berlin declaration must not refuse every constant")
        assertEquals(emptyList(), operandErrorsOf(ModelTimeZone.EUROPE_BERLIN, ORDINARY_LABEL),
            "nor report one")
    }
}
