package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — what a computation's category read `[Plan -> Tier]` of a MALFORMED enum cell does in compute
 * mode, beside the plain value read `[Plan]` of the same cell (IF85's poison on read).
 *
 * Both computations try the plan's guard first and fall back to one that always holds (A12 refuses an
 * unconditional alternative beside a guarded one, `MVK_ERROR_EMPTY_PRECONDITION`), so the fallback is the
 * discriminator: a read that POISONS aborts the instance and skips the fallback (CLEARED, the target being
 * seeded), while a read that is merely unknown leaves the guard not TRUE and selects the fallback (`1`). The
 * valid, other-value and absent documents are the controls that each guard reads its cell at all, and the
 * value read `[Plan]` is the carrier IF85 already settled, holding the malformed cell fixed.
 */
class EnumCategoryComputePoisonDiffTest {

    private val model = run {
        val base = BoundModel.ofJson(
            DocumentModelDraft.named("policy-tiers").locale("en_US")
                .rootGroup("Policy") { policy ->
                    policy.stringField("Holder") { }
                        .enumField("Plan") { it.value("BASIC").value("PREMIUM").category("Tier", "LOW", "HIGH") }
                        .numberField("TierSurcharge") { it.fractionDigits(0) }
                        .numberField("PlanSurcharge") { it.fractionDigits(0) }
                        .numberField("FirstFilledSurcharge") { it.fractionDigits(0) }
                        .numberField("ListedSurcharge") { it.fractionDigits(0) }
                }
                .build().toJson(),
        )
        val plan = base.enumOf(PLAN)
        val holder = base.str(HOLDER)
        val tier = plan.category("Tier")
        /** The plan's [guard] first, then the fallback every document satisfies. */
        fun surcharge(target: String, guard: Condition) =
            base.computation(base.num(target)).name("${target.substringAfterLast('/')}Computation")
                .`when`(guard).calc(NumberLiteral.of("5"))
                .`when`(holder.filled()).calc(NumberLiteral.of("1")).message(Dsl.en("Surcharge.")).build()
        base.addAll(
            emptyList(),
            listOf(
                surcharge(TIER_SURCHARGE, tier.`is`("HIGH")),
                surcharge(PLAN_SURCHARGE, plan.`is`("PREMIUM")),
                surcharge(FIRST_FILLED_SURCHARGE,
                    Dsl.firstFilledString(tier.firstFilledOperand(), holder.field().firstFilledOperand()).eq("HIGH")),
                surcharge(LISTED_SURCHARGE, Dsl.atLeastOneFieldValueIncludedInValueList(listOf("HIGH"), tier)),
            ),
        ).toJson()
    }
    private val definition = ModelLoader.load(model)
    private val javaEngine = RuntimeLaws.kernelJavaEngine(model)

    /**
     * `TierSurcharge/PlanSurcharge/FirstFilledSurcharge/ListedSurcharge` per document. On `GOLD` every carrier is
     * poisoned, the category reads exactly like the value read beside them: the comparison, `FirstFilledValue`
     * reaching the cell, and the value-list quantifier over the category alike.
     */
    @Test
    fun aCategoryReadOfAMalformedEnumCellPoisonsTheComputationLikeAValueRead() {
        val expected = linkedMapOf(
            "PREMIUM, the HIGH tier" to "5/5/5/5",
            "BASIC, the LOW tier" to "1/1/1/1",
            "GOLD, outside the value list" to "CLEARED/CLEARED/CLEARED/CLEARED",
            "no plan" to "1/1/1/1",
        )
        val plans = mapOf("PREMIUM, the HIGH tier" to "PREMIUM", "BASIC, the LOW tier" to "BASIC",
            "GOLD, outside the value list" to "GOLD", "no plan" to null)
        val readings = expected.keys.associateWith { readings(plans.getValue(it)) }
        val dynamic = readings.mapValues { it.value.first }
        assertEquals(expected, dynamic, "dynamic Groovy")
        assertEquals(dynamic, readings.mapValues { it.value.second }, "static Java")
        assertEquals(dynamic, readings.mapValues { it.value.third }, "interpreter")
    }

    /** The [TARGETS]' outcomes under dynamic Groovy, static Java and the interpreter. */
    private fun readings(plan: String?): Triple<String, String, String> {
        fun build(b: Both) {
            b.group(ROOT, 1).field(HOLDER, "Ada", 1, 1)
            TARGETS.forEach { b.field(it, "7", 1, 1) }
            if (plan != null) b.field(PLAN, plan, 1, 1)
        }
        val dynamicDoc = Both(RuntimeLaws.doc(), definition.newDocument()).also(::build)
        val staticDoc = Both(RuntimeLaws.doc(), definition.newDocument()).also(::build).kDoc
        val dynamic = RuntimeLaws.compute(model, dynamicDoc.kDoc).associate { computed ->
            computed.path() to when {
                computed.cleared() -> "CLEARED"
                computed.errored() -> "ERRORED"
                else -> computed.value().orElse("")
            }
        }
        val static = javaEngine.computeOutcomesSorted(javaEngine.instance(staticDoc)).associate {
            it.substringBefore('|') to when (val v = it.substringAfter('|')) {
                "ERR" -> "ERRORED"
                else -> v
            }
        }
        val interpreter = DmInterpreter(definition).compute(dynamicDoc.iDoc).associate { computed ->
            computed.path to when (computed.outcome) {
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> computed.value.orEmpty()
            }
        }
        return Triple(signature(dynamic), signature(static), signature(interpreter))
    }

    private fun signature(outcomes: Map<String, String>) = TARGETS.joinToString("/") { outcomes[it].toString() }

    private companion object {
        const val ROOT = "/Policy"
        const val HOLDER = "$ROOT/Holder"
        const val PLAN = "$ROOT/Plan"
        const val TIER_SURCHARGE = "$ROOT/TierSurcharge"
        const val PLAN_SURCHARGE = "$ROOT/PlanSurcharge"
        const val FIRST_FILLED_SURCHARGE = "$ROOT/FirstFilledSurcharge"
        const val LISTED_SURCHARGE = "$ROOT/ListedSurcharge"
        val TARGETS = listOf(TIER_SURCHARGE, PLAN_SURCHARGE, FIRST_FILLED_SURCHARGE, LISTED_SURCHARGE)
    }
}
