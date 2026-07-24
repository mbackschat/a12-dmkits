package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * IF219 lock — the FIXED group-list quantifiers (`AllGroupsFilled` / `NoGroupFilled` /
 * `AtLeastOneGroupFilled` / `NotAllGroupsFilled` / `GroupsNotCollectivelyFilled`) used as a COMPUTATION
 * precondition, over FIELD and GROUP operands in the clean / invalid / absent states. IF216 locked them in
 * VALIDATION; the compute phase is uncharacterized (the field operands are newly representable there, and the
 * group-only lists were never compute-locked either).
 *
 * The discriminator is a DEPENDENT computation `Audit = [Payout]`: when the precondition merely fails
 * (FALSE/UNKNOWN → CLEARED, a plain clear) the dependent reads Payout empty-as-0 → `VALUE|0.00`; when the
 * precondition POISONS (an invalid-operand read → CLEARED + poisoned) the dependent's read poisons too →
 * `CLEARED`. So the (Payout, Audit) pair separates VALUE vs plain-CLEAR vs POISON. Both targets are SEEDED so a
 * kernel clear is a reported change and the outcome maps compare directly. Tri-check: groovy-dynamic (normative)
 * == java-static == interpreter. Own insurance domain.
 */
class GroupListEntityOperandComputeDiffTest {

    private val base = DocumentModelDraft.named("claimintake").locale("en_US")
        .rootGroup("Claim") { claim ->
            claim.stringField("PolicyNumber") { }
            claim.numberField("DeductibleRate") { it.fractionDigits(1, 1) }
            claim.numberField("Payout") { it.fractionDigits(2, 2) }
            claim.numberField("Audit") { it.fractionDigits(2, 2) }
            claim.group("Vehicle") { vehicle ->
                vehicle.stringField("Vin") { }
                vehicle.numberField("AxleCount") { it.fractionDigits(1, 1) }
            }
            claim.group("Coverages") { cov ->
                cov.repeatable(3)
                cov.stringField("Kind") { }
            }
        }
        .build()
        .toJson()

    private val m = BoundModel.ofJson(base)
    private fun render(c: Condition): String = DslRenderer(ConditionLanguage.EN, "/Claim").render(c)

    /** Inject `Payout` (gated by [precondition]) + the dependent `Audit = [Payout]`, then compute over a doc
     *  seeding both targets + the given operand [values]; assert groovy == java-static == interpreter. */
    private fun agree(precondition: Condition, values: Map<String, String>, msg: String) {
        var model = RuntimeLaws.withTieredComputation(
            base, "/Claim", "PayoutComp", "../Payout", render(precondition),
            listOf(RuntimeLaws.CompAlt(null, "100.00")),
        )
        model = RuntimeLaws.withTieredComputation(
            model, "/Claim", "AuditComp", "../Audit", null,
            listOf(RuntimeLaws.CompAlt(null, "[Payout]")),
        )

        val kDoc = RuntimeLaws.doc().group("/Claim", 1)
            .field(PAYOUT, "0.01", 1, 1).field(AUDIT, "0.01", 1, 1)
        values.forEach { (p, v) -> kDoc.field(p, v, *repsOf(p)) }
        val groovy = RuntimeLaws.compute(model, kDoc).associate { it.path() to kSig(it) }

        val engine = RuntimeLaws.kernelJavaEngine(model)
        val kDoc2 = RuntimeLaws.doc().group("/Claim", 1)
            .field(PAYOUT, "0.01", 1, 1).field(AUDIT, "0.01", 1, 1)
        values.forEach { (p, v) -> kDoc2.field(p, v, *repsOf(p)) }
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).associate {
            it.substringBefore('|') to when (val v = it.substringAfter('|')) {
                "ERR" -> "ERRORED"; "CLEARED" -> "CLEARED"; else -> "VALUE|$v"
            }
        }

        val def = ModelLoader.load(model)
        val iDoc = def.newDocument().group("/Claim", 1)
            .field(PAYOUT, "0.01", 1, 1).field(AUDIT, "0.01", 1, 1)
        values.forEach { (p, v) -> iDoc.field(p, v, *repsOf(p)) }
        val interp = DmInterpreter(def).compute(iDoc).associate { it.path to iSig(it) }

        assertEquals(groovy, javaStatic, "$msg — groovy vs java-static (tri-check)  kernel=$groovy java=$javaStatic")
        assertEquals(groovy, interp, "$msg — kernel vs interpreter  kernel=$groovy interp=$interp")
    }

    private fun kSig(c: RuntimeLaws.Computed): String =
        if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}"

    private fun iSig(c: io.github.mbackschat.a12.dm.interpreter.model.Computed): String = when (c.outcome) {
        ComputeOutcome.CLEARED -> "CLEARED"
        ComputeOutcome.ERRORED -> "ERRORED"
        ComputeOutcome.VALUE -> "VALUE|${c.value}"
    }

    private fun repsOf(path: String): IntArray = IntArray(path.trim('/').split('/').size) { 1 }

    // --- FIELD operand: AtLeastOneGroupFilled(DeductibleRate) ------------------------------------------------

    private fun oneField() = Dsl.atLeastOneGroupFilled(m.field(DEDUCTIBLE_RATE))

    @Test fun fieldOperandValidFilledComputes() =
        agree(oneField(), mapOf(DEDUCTIBLE_RATE to "1.5"), "valid field filled → precondition TRUE → Payout computes")

    @Test fun fieldOperandAbsentClears() =
        agree(oneField(), emptyMap(), "field absent → precondition FALSE → plain clear (Audit reads empty-as-0)")

    @Test fun fieldOperandInvalidReadInPrecondition() =
        agree(oneField(), mapOf(DEDUCTIBLE_RATE to "7"), "malformed field (0 frac < min 1) read by the precondition")

    // --- GROUP operand: AtLeastOneGroupFilled(Vehicle) -----------------------------------------------------

    private fun oneGroup() = Dsl.atLeastOneGroupFilled(m.flattenGroup(VEHICLE))

    @Test fun groupOperandFilledComputes() =
        agree(oneGroup(), mapOf(VIN to "WAU12345"), "group filled → precondition TRUE → Payout computes")

    @Test fun groupOperandAbsentClears() =
        agree(oneGroup(), emptyMap(), "group absent → precondition FALSE → plain clear")

    @Test fun groupOperandInvalidOnlyReadInPrecondition() =
        agree(oneGroup(), mapOf(AXLE_COUNT to "3"), "malformed-only group (AxleCount 0 frac < min 1) read by the precondition")

    // --- NoGroupFilled — the negative (poison-sensitive) form ----------------------------------------------

    @Test fun noGroupFilledInvalidField() =
        agree(Dsl.noGroupFilled(m.field(DEDUCTIBLE_RATE)), mapOf(DEDUCTIBLE_RATE to "7"),
            "NoGroupFilled over a malformed field operand in a precondition")

    @Test fun noGroupFilledInvalidOnlyGroup() =
        agree(Dsl.noGroupFilled(m.flattenGroup(VEHICLE)), mapOf(AXLE_COUNT to "3"),
            "NoGroupFilled over a malformed-only group operand in a precondition")

    // --- mixed field+group list with a malformed field beside a filled group -------------------------------

    @Test fun mixedListMalformedFieldBesideFilledGroup() =
        agree(Dsl.atLeastOneGroupFilled(m.field(DEDUCTIBLE_RATE), m.flattenGroup(VEHICLE)),
            mapOf(DEDUCTIBLE_RATE to "7", VIN to "WAU12345"),
            "mixed list: malformed field FIRST + filled group — does the filled group decide before the field poisons?")

    // --- READ ORDER: a decisive operand BEFORE a malformed one (does the tally short-circuit?) --------------

    @Test fun decisiveGroupBeforeMalformedField() =
        agree(Dsl.atLeastOneGroupFilled(m.flattenGroup(VEHICLE), m.field(DEDUCTIBLE_RATE)),
            mapOf(VIN to "WAU12345", DEDUCTIBLE_RATE to "7"),
            "filled group FIRST (decides AtLeastOne) then malformed field — eager-read poison vs short-circuit value?")

    @Test fun decisiveFieldBeforeMalformedField() =
        agree(Dsl.atLeastOneGroupFilled(m.field(POLICY_NUMBER), m.field(DEDUCTIBLE_RATE)),
            mapOf(POLICY_NUMBER to "P-100", DEDUCTIBLE_RATE to "7"),
            "valid field FIRST (decides AtLeastOne) then malformed field — eager-read poison vs short-circuit value?")

    // --- mixed STARRED group scope beside a plain operand (representable via overScopes) --------------------

    @Test fun starredScopeMixedWithFilledFieldInstantiatedRows() =
        agree(Dsl.atLeastOneGroupFilled(m.field(POLICY_NUMBER), m.flattenGroup("$COVERAGES*")),
            mapOf(POLICY_NUMBER to "P-100", "$COVERAGES/Kind" to "collision"),
            "mixed list: filled field + starred Coverages* with one instantiated row")

    @Test fun starredScopeMixedWithMalformedField() =
        agree(Dsl.atLeastOneGroupFilled(m.field(DEDUCTIBLE_RATE), m.flattenGroup("$COVERAGES*")),
            mapOf(DEDUCTIBLE_RATE to "7", "$COVERAGES/Kind" to "collision"),
            "mixed list: malformed field + starred Coverages* with one instantiated row — poison or star decides?")

    @Test fun starredScopeMixedNoRows() =
        agree(Dsl.atLeastOneGroupFilled(m.field(POLICY_NUMBER), m.flattenGroup("$COVERAGES*")),
            emptyMap(),
            "mixed list: empty field + starred Coverages* with no rows → plain clear")

    @Test fun starredScopeDecidesAtLeastOneEmptyField() =
        agree(Dsl.atLeastOneGroupFilled(m.field(POLICY_NUMBER), m.flattenGroup("$COVERAGES*")),
            mapOf("$COVERAGES/Kind" to "collision"),
            "empty field + starred Coverages* with 1 row — star's instantiated row decides AtLeastOne")

    @Test fun starredScopeDecidesNoGroupFilledEmptyField() =
        agree(Dsl.noGroupFilled(m.field(POLICY_NUMBER), m.flattenGroup("$COVERAGES*")),
            mapOf("$COVERAGES/Kind" to "collision"),
            "empty field + starred Coverages* with 1 row — NoGroupFilled FALSE (star row is filled)")

    @Test fun starredScopeNoGroupFilledAllEmpty() =
        agree(Dsl.noGroupFilled(m.field(POLICY_NUMBER), m.flattenGroup("$COVERAGES*")),
            emptyMap(),
            "empty field + starred Coverages* with no rows — NoGroupFilled TRUE → plain clear")

    private companion object {
        const val ROOT = "/Claim"
        const val POLICY_NUMBER = "$ROOT/PolicyNumber"
        const val DEDUCTIBLE_RATE = "$ROOT/DeductibleRate"
        const val PAYOUT = "$ROOT/Payout"
        const val AUDIT = "$ROOT/Audit"
        const val VEHICLE = "$ROOT/Vehicle"
        const val VIN = "$VEHICLE/Vin"
        const val AXLE_COUNT = "$VEHICLE/AxleCount"
        const val COVERAGES = "$ROOT/Coverages"
    }
}
