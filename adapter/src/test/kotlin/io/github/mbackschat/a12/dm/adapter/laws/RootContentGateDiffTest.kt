package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Test

/**
 * Differential — full validation gates a rule on its ROOT instance's content, never on the row it judges
 * (INTERPRETER-FINDINGS IF435). `RulesInRootGroup_method.st` runs a root's rules inside
 * `mindestensEinVordruckAngegeben(root, l)`, and the per-rule template iterates its declared domain with no further
 * content question, so once a root instance holds anything, a comparison fires as empty-as-0 on every declared slot
 * below it, the unused ones included.
 *
 * Plain comparisons only, none of them global, so the root gate is the one gate in play. The repeatable `Fleet`
 * root is the control where the root instance is the row's own prefix. Own domain: an insurance policy with
 * claims and their parts, and a repeatable fleet of vehicles.
 */
class RootContentGateDiffTest {

    private val model = DocumentModelDraft.named("policy-root-content-gate").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Email") { }
            .group("Agent") { a -> a.stringField("Name") { } }
            .group("Claims") { c -> c.repeatable(2)
                .stringField("ClaimNo") { }
                .numberField("Amount") { n -> n.fractionDigits(0) }
                .group("Parts") { r -> r.repeatable(2).numberField("Qty") { n -> n.fractionDigits(0) } } } }
        .rootGroup("Fleet") { f -> f.repeatable(2)
            .stringField("Name") { }
            .group("Units") { u -> u.repeatable(2).numberField("Km") { n -> n.fractionDigits(0) } } }
        .build().toJson()
        .let { RuntimeLaws.withRule(it, "/Policy", "claim", "../Claims/Amount", "claim", "[Claims/Amount] < 5") }
        .let { RuntimeLaws.withRule(it, "/Policy/Claims", "part", "../Parts/Qty", "part", "[Parts/Qty] < 5") }
        .let { RuntimeLaws.withRule(it, "/Fleet", "unit", "../Units/Km", "unit", "[Units/Km] < 5") }

    @Test
    fun aRootHoldingContentJudgesEveryDeclaredSlot() = DocumentDifferential.assertAgree(model, mapOf(
        "a keyed first claim" to """{"Policy":{"Claims":[{"ClaimNo":"a"}]}}""",
        "a blank claim object" to """{"Policy":{"Claims":[{}]}}""",
        "a stored second claim" to """{"Policy":{"Claims":[{},{"ClaimNo":"b"}]}}""",
        "an agent alone" to """{"Policy":{"Agent":{"Name":"x"}}}""",
        "one part" to """{"Policy":{"Claims":[{"Parts":[{"Qty":9}]}]}}""",
        "an empty policy" to """{"Policy":{}}""",
        "a blank agent object" to """{"Policy":{"Agent":{}}}""",
        "one vehicle" to """{"Fleet":[{"Units":[{"Km":9}]}]}""",
        "a blank vehicle before a used one" to """{"Fleet":[{},{"Units":[{"Km":9}]}]}""",
    ))
}
