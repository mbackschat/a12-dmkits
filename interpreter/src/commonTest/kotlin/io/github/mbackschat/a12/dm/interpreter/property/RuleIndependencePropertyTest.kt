package io.github.mbackschat.a12.dm.interpreter.property

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.model.Message
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Metamorphic rule independence: adding one supported rule may add that rule's own messages, but may not change
 * any pre-existing rule or automatic finding. `adapter/laws/RuleIndependenceDiffTest` anchors the relation on both
 * kernel strategies; this kernel-free sweep amplifies it across the flat, repeatable, and nested models and the
 * identical deterministic samples on JVM and JS.
 */
class RuleIndependencePropertyTest {

    private data class Case(val scenario: PropertySupport.Scenario, val added: RuleDef)

    private val cases = listOf(
        Case(
            PropertySupport.flat,
            RuleDef(
                "/T/R_PROPERTY_INDEPENDENCE",
                "/T",
                "FieldFilled(Flag)",
                "PROPERTY_ADDED_FLAT",
                "../Flag",
            ),
        ),
        Case(
            PropertySupport.singleRep,
            RuleDef(
                "/Cart/Lines/R_PROPERTY_INDEPENDENCE",
                "/Cart/Lines",
                "FieldFilled(Note)",
                "PROPERTY_ADDED_SINGLE",
                "../Note",
            ),
        ),
        Case(
            PropertySupport.nested,
            RuleDef(
                "/M/G1/G2/R_PROPERTY_INDEPENDENCE",
                "/M/G1/G2",
                "FieldFilled(/M/G1/G2/K2)",
                "PROPERTY_ADDED_NESTED",
                "../K2",
            ),
        ),
    )

    @Test
    fun addingAnUnrelatedRuleNeverChangesExistingResults() {
        for (case in cases) {
            val originalEngine = DmInterpreter(case.scenario.model)
            val augmentedModel = case.scenario.model.withRule(case.added)
            val augmentedEngine = DmInterpreter(augmentedModel)
            val originalCodes = case.scenario.model.rules.mapTo(hashSetOf()) { it.errorCode }
            var originalFirings = 0
            var addedFirings = 0

            for (seed in PropertySupport.SEEDS) {
                val placements = case.scenario.placements(seed)
                val original = originalEngine.validate(PropertySupport.documentOf(case.scenario.model, placements))
                val augmented = augmentedEngine.validate(PropertySupport.documentOf(augmentedModel, placements))
                val where = "${case.scenario.name} seed=$seed"

                assertEquals(
                    signatures(original.messages),
                    signatures(augmented.messages.filterNot { it.code == case.added.errorCode }),
                    "$where: adding ${case.added.errorCode} changed an existing message",
                )
                assertEquals(
                    original.unsupported,
                    augmented.unsupported,
                    "$where: adding ${case.added.errorCode} changed unsupported findings",
                )
                originalFirings += original.messages.count { it.code in originalCodes }
                addedFirings += augmented.messages.count { it.code == case.added.errorCode }
            }

            assertTrue(
                originalFirings > 0,
                "${case.scenario.name}: no original rule fired, so independence was vacuous",
            )
            assertTrue(
                addedFirings > 0,
                "${case.scenario.name}: ${case.added.errorCode} never fired, so the added rule was a vacuous control",
            )
        }
    }

    private fun signatures(messages: List<Message>): List<String> =
        messages.map { "${it.code}|${it.type}|${it.errorPath}" }.sorted()
}
