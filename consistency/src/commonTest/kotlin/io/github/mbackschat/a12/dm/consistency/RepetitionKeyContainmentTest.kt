package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of where a `RepetitionNotUnique` key may sit.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.RepetitionNotUniqueFromScopeLawsTest` and `…RepetitionNotUniqueKeyPathLawsTest`, on
 * the same depot shape. The INDEX-KEYED column is what makes the containment classes reachable at all: an
 * index-free parallel pair is refused at the wildcard gate, so a table built on one keying reads three
 * classes as unreachable and the other as containment law.
 */
class RepetitionKeyContainmentTest {

    private val notInGroup = "MVK_NOT_IN_GROUP"
    private val notInSamePath = "MVK_NOT_IN_SAME_PATH"
    private val notInRuleGroup = "MVK_NOT_IN_RULEGROUP"
    private val noWildcard = "MVK_NO_WILDCARD"
    private val repeatableMissing = "MVK_REPEATABLE_GROUP_MISSING"

    /**
     * A depot with two PARALLEL repeatable groups, a nested repeatable one, and a non-repeatable group.
     *
     * [indexed] is the one variable the reachability rows move: with it both repeatable groups declare the
     * same index field and so iterate jointly, which satisfies the wildcard gate and lets A12 reach the
     * containment question.
     */
    private fun codes(
        group: String,
        errorField: String,
        condition: String,
        indexed: Boolean = false,
    ): List<String> {
        val key = if (indexed) """"indexFieldName":"SlotRef",""" else ""
        val slot = if (indexed) {
            """{"type":"Field","id":"fS","name":"SlotRef","Field":{"fieldType":{"type":"StringType"}}},"""
        } else {
            ""
        }
        return check(
            ModelSource(
                """
            {"header":{"id":"depot","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
                {"type":"Field","id":"f0","name":"DepotRef","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Crates","Group":{"repeatability":20,$key"elements":[
                  ${slot.replace("\"fS\"", "\"fS1\"")}
                  {"type":"Field","id":"f1","name":"CrateRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Group","id":"g2","name":"Items","Group":{"repeatability":50,"elements":[
                    {"type":"Field","id":"f2","name":"Sku","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}
                  ${probe(group == CRATES, errorField, condition)}
                ]}},
                {"type":"Group","id":"g3","name":"Pallets","Group":{"repeatability":10,$key"elements":[
                  ${slot.replace("\"fS\"", "\"fS2\"")}
                  {"type":"Field","id":"f3","name":"PalletRef","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g4","name":"Office","Group":{"elements":[
                  {"type":"Field","id":"f4","name":"OfficeRef","Field":{"fieldType":{"type":"StringType"}}}
                ]}}
                ${probe(group == DEPOT, errorField, condition)}
              ]}}
             ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
    }

    private fun probe(here: Boolean, errorField: String, condition: String): String = if (!here) {
        ""
    } else {
        """,{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"$errorField","errorCode":"C1",
              "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
    }

    /**
     * The `@From` arm: a key outside the scope, on the only keying that reaches the class.
     *
     * The index-free row is the control that locates the refusal — the same violation is answered by the
     * wildcard gate there, so a table without it would read this family's silence as containment law.
     */
    @Test
    fun whatTheFromScopeHasToContain() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the key sits directly in the scope"] =
            codes(DEPOT, "../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Crates)")
        actual["the key sits in a DESCENDANT of the scope"] =
            codes(DEPOT, "../Crates/Items/Sku", "RepetitionNotUnique(Crates/Items/Sku @From Crates)")
        actual["the key sits in a PARALLEL group — index-free"] =
            codes(DEPOT, "../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Pallets)")
        actual["the key sits in a PARALLEL group — INDEX-KEYED"] =
            codes(DEPOT, "../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From Pallets)", true)
        actual["the key sits ABOVE the scope"] =
            codes(DEPOT, "../DepotRef", "RepetitionNotUnique(DepotRef @From Crates)")
        actual["the scope is the rule's own non-repeatable group"] =
            codes(DEPOT, "../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef @From /Depot)")

        val expected = mutableMapOf<String, List<String>>()
        expected["the key sits directly in the scope"] = emptyList()
        expected["the key sits in a DESCENDANT of the scope"] = emptyList()
        // Index-free the two groups do not iterate together, so A12 refuses the pairing before containment
        // with `MVK_NO_WILDCARD`, which the operand-scope walk names on the scope group.
        expected["the key sits in a PARALLEL group — index-free"] = listOf("MVK_NO_WILDCARD")
        expected["the key sits in a PARALLEL group — INDEX-KEYED"] = listOf(notInGroup)
        // The scope repeats below the rule's own group, so the walk refuses it unstarred.
        expected["the key sits ABOVE the scope"] = listOf("MVK_NO_WILDCARD")
        expected["the scope is the rule's own non-repeatable group"] = listOf("MVK_GROUP_NOT_REPEATABLE")
        assertEquals(expected, actual, "what the @From scope has to contain")
    }

    /** The no-`@From` arm, whose two gates run in this order and both sit ahead of the repetition demand. */
    @Test
    fun theTwoGatesOfTheScopelessArm() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two keys in parallel repeatable groups — index-free"] =
            codes(DEPOT, "../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef, Pallets/PalletRef)")
        actual["two keys in parallel repeatable groups — INDEX-KEYED"] =
            codes(DEPOT, "../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef, Pallets/PalletRef)", true)
        actual["a key outside the rule group, in a NON-repeatable one"] =
            codes(CRATES, "../CrateRef", "RepetitionNotUnique(CrateRef, /Depot/Office/OfficeRef)")
        actual["a key outside the rule group, in a parallel REPEATABLE one"] =
            codes(CRATES, "../CrateRef", "RepetitionNotUnique(CrateRef, /Depot/Pallets/PalletRef)")
        actual["a containment defect beside a SECOND RepetitionNotUnique — INDEX-KEYED"] =
            codes(
                DEPOT,
                "../Crates/CrateRef",
                "RepetitionNotUnique(Crates/CrateRef, Pallets/PalletRef) " +
                    "And RepetitionNotUnique(Crates/CrateRef)",
                true,
            )
        actual["TWO clean RepetitionNotUnique calls — INDEX-KEYED"] =
            codes(
                DEPOT,
                "../Crates/CrateRef",
                "RepetitionNotUnique(Crates/CrateRef) And RepetitionNotUnique(Crates/SlotRef)",
                true,
            )
        actual["a CLEAN key list beside a parallel read — INDEX-KEYED"] =
            codes(
                DEPOT,
                "../Crates/CrateRef",
                "RepetitionNotUnique(Crates/CrateRef) And FieldFilled(Pallets/PalletRef)",
                true,
            )

        val expected = mutableMapOf<String, List<String>>()
        expected["two keys in parallel repeatable groups — index-free"] = listOf(noWildcard)
        expected["two keys in parallel repeatable groups — INDEX-KEYED"] = listOf(notInSamePath)
        expected["a key outside the rule group, in a NON-repeatable one"] = listOf(notInRuleGroup)
        // The outsider's own repetition is what the wildcard gate answers about, so this class never appears
        // and the reference family names the one A12 draws.
        expected["a key outside the rule group, in a parallel REPEATABLE one"] = listOf(noWildcard)
        // The second post-parse class, and the reason the suppression is the PHASE's rather than one gate's:
        // A12 draws the containment class alone here too.
        expected["a containment defect beside a SECOND RepetitionNotUnique — INDEX-KEYED"] =
            listOf(notInSamePath)
        expected["TWO clean RepetitionNotUnique calls — INDEX-KEYED"] =
            listOf("MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE")
        // The control for the whole-rule suppression: this model parallel-iterates, and A12 says so the
        // moment the key list stops offending — so the silence in the rows above is phase order, not a
        // checker that stopped reporting the class ([KERNEL-FINDINGS KF375]).
        expected["a CLEAN key list beside a parallel read — INDEX-KEYED"] =
            listOf("MVK_PARALLEL_ITERATION_NOT_ALLOWED")
        assertEquals(expected, actual, "the two gates of the scopeless arm")
    }

    /**
     * Where this family stops and the sibling one starts — the partition A12 draws between the two gates.
     *
     * The last row is the positive control: without it the silent rows would pass on a model that never
     * parsed.
     */
    @Test
    fun whereThePlacementFamilyTakesOver() {
        val actual = mutableMapOf<String, List<String>>()
        actual["every key inside the rule group, none with a repetition between"] =
            codes(CRATES, "../CrateRef", "RepetitionNotUnique(CrateRef)")
        actual["a sole key with its repetition — admitted"] =
            codes(DEPOT, "../Crates/CrateRef", "RepetitionNotUnique(Crates/CrateRef)")
        actual["a key outside the rule group in the same model — the positive control"] =
            codes(CRATES, "../CrateRef", "RepetitionNotUnique(CrateRef, /Depot/Office/OfficeRef)")

        val expected = mutableMapOf<String, List<String>>()
        expected["every key inside the rule group, none with a repetition between"] = listOf(repeatableMissing)
        expected["a sole key with its repetition — admitted"] = emptyList()
        expected["a key outside the rule group in the same model — the positive control"] =
            listOf(notInRuleGroup)
        assertEquals(expected, actual, "where the placement family takes over")
    }

    private companion object {
        const val DEPOT = "/Depot"
        const val CRATES = "/Depot/Crates"
    }
}
