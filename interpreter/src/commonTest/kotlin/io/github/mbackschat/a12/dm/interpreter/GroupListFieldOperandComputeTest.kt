package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.Computed
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS/Node) lock for IF219 → the FIXED group-list quantifiers used as a COMPUTATION
 * PRECONDITION over the common field-or-group entity list. Two behaviors the kernel differential
 * (`adapter.laws.GroupListEntityOperandComputeDiffTest`) pins and this replays on both platforms:
 *
 *  - a FIELD operand is READ in the kernel's list order and POISONS on a formally-invalid value, but the scan
 *    STOPS at the deciding operand — a decisive (filled/empty) operand BEFORE a malformed one clears the read,
 *    so no poison (the read-order fix; the eager tally used to poison unconditionally);
 *  - a GROUP operand is structural presence (a malformed-only group is FILLED, never poison), and a STARRED
 *    group scope may be MIXED with other operands (one FILLED per instantiated row).
 *
 * The discriminator is the dependent `Audit = [Payout]`: a plain precondition clear leaves Payout empty →
 * Audit reads empty-as-0 (`VALUE|0.00`); a poison leaves Payout poisoned → Audit's read poisons → `CLEARED`.
 * Own insurance domain; both targets seeded so a clear is a reported change.
 */
class GroupListFieldOperandComputeTest {

    private val PAYOUT = "/Claim/Payout"
    private val AUDIT = "/Claim/Audit"

    private fun model(precondition: String) = ModelDef(
        fields = listOf(
            FieldDef("/Claim/PolicyNumber", Kind.STRING),
            FieldDef("/Claim/DeductibleRate", Kind.NUMBER, scale = 1, minFractionalDigits = 1),
            FieldDef(PAYOUT, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(AUDIT, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef("/Claim/Vehicle/Vin", Kind.STRING),
            FieldDef("/Claim/Vehicle/AxleCount", Kind.NUMBER, scale = 1, minFractionalDigits = 1),
            FieldDef("/Claim/Coverages/Kind", Kind.STRING),
        ),
        rules = emptyList(),
        computations = listOf(
            ComputationDef(PAYOUT, "/Claim", listOf(ComputationAlternative(null, "100.00")), commonPrecondition = precondition),
            ComputationDef(AUDIT, "/Claim", "[Payout]"),
        ),
        declaredReps = mapOf("/Claim/Coverages" to 3),
    )

    /** A path → "OUTCOME|value" map for the two computed targets. */
    private fun outcomes(precondition: String, build: Document.() -> Unit): Map<String, String> {
        val m = model(precondition)
        val doc = m.newDocument().group("/Claim", 1)
            .field(PAYOUT, "0.01", 1, 1).field(AUDIT, "0.01", 1, 1)
        doc.build()
        return DmInterpreter(m).compute(doc).filter { it.path == PAYOUT || it.path == AUDIT }
            .associate { it.path to sig(it) }
    }

    private fun sig(c: Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private val computes = mapOf(PAYOUT to "VALUE|100.00", AUDIT to "VALUE|100.00")
    private val plainClear = mapOf(PAYOUT to "CLEARED", AUDIT to "VALUE|0.00")
    private val poison = mapOf(PAYOUT to "CLEARED", AUDIT to "CLEARED")

    // --- FIELD operand: AtLeastOneGroupFilled(DeductibleRate) ----------------------------------------------

    @Test fun fieldValidFilledComputes() =
        assertEquals(computes, outcomes("AtLeastOneGroupFilled(DeductibleRate)") { field("/Claim/DeductibleRate", "1.5", 1, 1) })

    @Test fun fieldAbsentPlainClears() =
        assertEquals(plainClear, outcomes("AtLeastOneGroupFilled(DeductibleRate)") { })

    @Test fun fieldMalformedPoisons() =
        assertEquals(poison, outcomes("AtLeastOneGroupFilled(DeductibleRate)") { field("/Claim/DeductibleRate", "7", 1, 1) })

    // --- GROUP operand: structural, never poison -----------------------------------------------------------

    @Test fun groupFilledComputes() =
        assertEquals(computes, outcomes("AtLeastOneGroupFilled(Vehicle)") { group("/Claim/Vehicle", 1, 1).field("/Claim/Vehicle/Vin", "V1", 1, 1, 1) })

    @Test fun groupMalformedOnlyIsStructurallyFilled() =
        assertEquals(computes, outcomes("AtLeastOneGroupFilled(Vehicle)") { group("/Claim/Vehicle", 1, 1).field("/Claim/Vehicle/AxleCount", "3", 1, 1, 1) })

    @Test fun noGroupFilledOverMalformedOnlyGroupPlainClears() =
        assertEquals(plainClear, outcomes("NoGroupFilled(Vehicle)") { group("/Claim/Vehicle", 1, 1).field("/Claim/Vehicle/AxleCount", "3", 1, 1, 1) })

    @Test fun noGroupFilledOverMalformedFieldPoisons() =
        assertEquals(poison, outcomes("NoGroupFilled(DeductibleRate)") { field("/Claim/DeductibleRate", "7", 1, 1) })

    // --- read ORDER: a decisive operand before a malformed one stops the scan (no poison) ------------------

    @Test fun decisiveFieldBeforeMalformedFieldStops() =
        assertEquals(computes, outcomes("AtLeastOneGroupFilled(PolicyNumber, DeductibleRate)") {
            field("/Claim/PolicyNumber", "P-100", 1, 1); field("/Claim/DeductibleRate", "7", 1, 1)
        })

    @Test fun decisiveGroupBeforeMalformedFieldStops() =
        assertEquals(computes, outcomes("AtLeastOneGroupFilled(Vehicle, DeductibleRate)") {
            group("/Claim/Vehicle", 1, 1).field("/Claim/Vehicle/Vin", "V1", 1, 1, 1); field("/Claim/DeductibleRate", "7", 1, 1)
        })

    @Test fun malformedFieldBeforeDecisiveGroupPoisons() =
        assertEquals(poison, outcomes("AtLeastOneGroupFilled(DeductibleRate, Vehicle)") {
            field("/Claim/DeductibleRate", "7", 1, 1); group("/Claim/Vehicle", 1, 1).field("/Claim/Vehicle/Vin", "V1", 1, 1, 1)
        })

    // --- mixed STARRED group scope beside a plain operand --------------------------------------------------

    @Test fun starredScopeInstantiatedRowDecidesAtLeastOne() =
        assertEquals(computes, outcomes("AtLeastOneGroupFilled(PolicyNumber, Coverages*)") {
            group("/Claim/Coverages", 1, 1).field("/Claim/Coverages/Kind", "collision", 1, 1, 1)
        })

    @Test fun starredScopeNoRowsPlainClears() =
        assertEquals(plainClear, outcomes("AtLeastOneGroupFilled(PolicyNumber, Coverages*)") { })

    @Test fun starredScopeRowBlocksNoGroupFilled() =
        assertEquals(plainClear, outcomes("NoGroupFilled(PolicyNumber, Coverages*)") {
            group("/Claim/Coverages", 1, 1).field("/Claim/Coverages/Kind", "collision", 1, 1, 1)
        })

    @Test fun starredScopeNoRowsSatisfiesNoGroupFilled() =
        assertEquals(computes, outcomes("NoGroupFilled(PolicyNumber, Coverages*)") { })
}
