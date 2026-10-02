package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.admitted
import io.github.mbackschat.a12.dm.adapter.laws.ThreeEngineRuns.signatures
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Tri-engine differential — which once rules A12 evaluates on an instance without content.
 *
 * A12 files a rule under its error field's root group, where it runs only inside a root instance holding
 * content, unless the rule is GLOBAL. A global rule runs once, at the all-ones row, whatever the document holds.
 * Three of the reasons for that are measured here, each beside a root rule on the same model that stays gated:
 *  - a condition naming a second root (`CROSS_*`, beside the root rule `SUB_LOW`);
 *  - a count on the small side of a comparison (`COUNT_*`, a12-kernel-lean's SPEC-2026-10-02-05, beside the
 *    plain `[Withheld] < 5` and a count on the large side);
 *  - an error field in a repeatable root that the rule does not iterate (`REPEAT_SUM`, which fires ONCE where
 *    a per-row reading fires per row).
 *
 * [SiblingRootContentGateDiffTest] holds the root-rule side: content in another root does not open the gate.
 *
 * Own domain (an insurance claim, its insured party, and a policy's withholding).
 */
// One instance per class: the models are fields, and this task carries timing floors (see FilteredStarCapacityExtentDiffTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleBucketGateDiffTest {
    private fun rule(m: BoundModel, group: String, errorField: String, code: String, condition: Condition): Rule =
        m.rule(m.group(group)).name("${code}Rule").errorField(m.field(errorField)).`when`(condition)
            .code(code).severity(Severity.ERROR).message(Dsl.en(code)).build()

    private val claimBase = DocumentModelDraft.named("claim-rule-bucket").locale("en_US")
        .rootGroup("Insured") { it.stringField("Name") { }.numberField("Amount") { f -> f.fractionDigits(0) }
            .group("Extra") { g -> g.stringField("Note") { } } }
        .rootGroup("Claimant") { it.stringField("Note") { }
            .group("Sub") { g -> g.numberField("Excess") { f -> f.fractionDigits(0) } } }
        .build().toJson()

    private val claim = BoundModel.ofJson(claimBase).let { m ->
        val note = m.field(NOTE)
        val excess = m.num(EXCESS)
        admitted(listOf(
            rule(m, "/Claimant", NOTE, "CROSS_LOW", m.num("/Insured/Amount").asNumber().lt(5L).and(note.notFilled())),
            rule(m, "/Claimant", NOTE, "CROSS_GROUP", m.group("/Insured/Extra").filled().and(note.notFilled())),
            rule(m, "/Claimant/Sub", EXCESS, "SUB_LOW", excess.asNumber().lt(5L)),
        ).fold(claimBase) { json, r -> TypedRules.inject(json, r) })
    }

    @Test
    fun aRuleNamingASecondRootIsEvaluatedWithoutContent() {
        val crossLow = "CROSS_LOW|OMISSION_ERROR|/Claimant[1]/Note"
        val crossGroup = "CROSS_GROUP|OMISSION_ERROR|/Claimant[1]/Note"
        val rows = linkedMapOf(
            """{}""" to listOf(crossLow),
            """{"Claimant":{}}""" to listOf(crossLow),
            """{"Insured":{"Amount":1}}""" to listOf(crossLow),
            """{"Insured":{"Amount":1},"Claimant":{}}""" to listOf(crossLow),
            """{"Insured":{"Extra":{"Note":"n"}}}""" to listOf(crossGroup, crossLow),
            """{"Insured":{"Name":"Ada"},"Claimant":{"Sub":{}}}""" to listOf(crossLow),
            """{"Insured":{"Name":"Ada"},"Claimant":{"Note":"late"}}""" to listOf("SUB_LOW|OMISSION_ERROR|/Claimant[1]/Sub[1]/Excess"),
            """{"Claimant":{"Sub":{"Excess":3}}}""" to listOf(crossLow, "SUB_LOW|VALUE_ERROR|/Claimant[1]/Sub[1]/Excess"),
        )
        assertEquals(rows.toList(), rows.keys.map { it to signatures(claim, it) })
    }

    private val policyBase = DocumentModelDraft.named("policy-rule-bucket").locale("en_US")
        .rootGroup("Policy") { it.numberField("Withheld") { f -> f.fractionDigits(0) }
            .group("Home") { g -> g.stringField("Street") { } }.group("Car") { g -> g.stringField("Plate") { } } }
        .rootGroup("Renewal") { it.repeatable(3).numberField("Premium") { f -> f.fractionDigits(0) } }
        .build().toJson()

    private val policy = BoundModel.ofJson(policyBase).let { m ->
        val withheld = m.num(WITHHELD)
        val empty = m.field(WITHHELD).notFilled()
        val groups = Dsl.numberOfFilledGroups(m.group("/Policy/Home"), m.group("/Policy/Car"))
        val fields = listOf(m.field("/Policy/Home/Street"), m.field("/Policy/Car/Plate"))
        admitted(listOf(
            rule(m, "/Policy", WITHHELD, "COUNT_BELOW", groups.lt(2L).and(empty)),
            rule(m, "/Policy", WITHHELD, "COUNT_NONE", groups.eq(0L).and(empty)),
            rule(m, "/Policy", WITHHELD, "COUNT_PLUS", groups.plus(withheld.asNumber()).lt(2L)),
            rule(m, "/Policy", WITHHELD, "PLAIN", withheld.asNumber().lt(5L)),
            rule(m, "/Policy", WITHHELD, "COUNT_IN_ABS", Dsl.abs(groups).lt(2L).and(empty)),
            rule(m, "/Policy", WITHHELD, "COUNT_REVERSED", Dsl.number(2L).gt(groups).and(empty)),
            rule(m, "/Policy", WITHHELD, "COUNT_LARGE_SIDE", groups.gt(5L).or(withheld.asNumber().lt(5L))),
            rule(m, "/Policy", WITHHELD, "FIELDS_BELOW", Dsl.numberOfFilledFields(fields).lt(1L).and(empty)),
            rule(m, "/Policy", WITHHELD, "DISTINCT_BELOW", Dsl.numberOfDifferentValues(fields[0], fields[1]).lt(1L).and(empty)),
            rule(m, "/Policy", WITHHELD, "ROOT_COUNT", Dsl.numberOfFilledRootGroups(m.group("/Renewal")).ge(0L)
                .or(withheld.asNumber().lt(5L))),
            rule(m, "/Renewal", "/Renewal/Premium", "REPEAT_SUM", Dsl.sum(m.flatten("/Renewal*/Premium")).lt(5L)),
        ).fold(policyBase) { json, r -> TypedRules.inject(json, r) })
    }

    @Test
    fun aSmallSideCountAndAnUniteratedRepeatableRootAreEvaluatedWithoutContent() {
        fun at(vararg codes: String) = codes.map { "$it|OMISSION_ERROR|/Policy[1]/Withheld" }
        val repeatSum = "REPEAT_SUM|OMISSION_ERROR|/Renewal[1]/Premium"
        val rootCount = "ROOT_COUNT|VALUE_ERROR|/Policy[1]/Withheld"
        val withoutContent = (at("COUNT_BELOW", "COUNT_NONE", "COUNT_PLUS", "COUNT_IN_ABS", "COUNT_REVERSED", "FIELDS_BELOW",
            "DISTINCT_BELOW") + repeatSum + rootCount).sorted()
        val rows = linkedMapOf(
            """{}""" to withoutContent,
            """{"Policy":{}}""" to withoutContent,
            """{"Policy":{"Withheld":null}}""" to withoutContent,
            """{"Renewal":[{"Premium":1},{"Premium":2}]}""" to withoutContent,
            """{"Renewal":[{}]}""" to withoutContent,
            """{"Policy":{"Withheld":1}}""" to (at("COUNT_PLUS") + listOf("COUNT_LARGE_SIDE|VALUE_ERROR|/Policy[1]/Withheld",
                "PLAIN|VALUE_ERROR|/Policy[1]/Withheld", rootCount, repeatSum)).sorted(),
        )
        assertEquals(rows.toList(), rows.keys.map { it to signatures(policy, it) })
    }

    private companion object {
        const val NOTE = "/Claimant/Note"
        const val EXCESS = "/Claimant/Sub/Excess"
        const val WITHHELD = "/Policy/Withheld"
    }
}
