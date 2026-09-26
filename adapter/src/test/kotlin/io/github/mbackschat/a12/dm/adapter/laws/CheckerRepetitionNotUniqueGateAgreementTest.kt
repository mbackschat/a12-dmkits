package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `RepetitionNotUnique`'s gates ([KERNEL-FINDINGS KF355]), with A12's class written out on every row and the
 * portable checker required to name the same one.
 *
 * The key list is read first, each gate over the whole list in turn — category, group, wildcard, semantic index,
 * a field without value validation — and every key-list class answers ahead of every scope and placement gate.
 * Then one of two branches runs, chosen by whether `@From` is present. The two rows that put two key defects on
 * one list separate "each gate over the whole list" from "each key through every gate", which KF355's own grid
 * never paired. The list's duplicate gate answers ahead of all five, measured on a starred key beside the group
 * holding it.
 *
 * Own domain (insurance claims), the carrier of `validate.laws.RepetitionNotUniqueGateOrderLawsTest`: `Payments`
 * is keyed on the same index field as `Claims`, so a read of one from a rule about the other does not iterate
 * past the target.
 */
class CheckerRepetitionNotUniqueGateAgreementTest {

    private val policy = DocumentModelDraft.named("policy-uniqueness").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Reference") { }
            .enumField("Tier") { e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH") }
            .group("Insured") { i -> i.stringField("InsuredName") { } }
            .group("Claims") { g -> g.repeatable(20).indexField("ClaimRef")
                .stringField("ClaimRef") { }
                .numberField("Amount") { n -> n.fractionDigits(2) }
                .stringField("RawNote") { s -> s.lineBreaks().noValueValidation() }
                .group("Items") { i -> i.repeatable(5).stringField("ItemRef") { } } }
            .group("Payments") { g -> g.repeatable(20).indexField("ClaimRef")
                .stringField("ClaimRef") { }
                .stringField("PayRef") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun theKeyListIsJudgedFirstEachGateOverTheWholeList() {
        val predicted = linkedMapOf(
            "a well-formed key — control" to (atRoot("RepetitionNotUnique(Claims/ClaimRef)") to ADMITTED),
            "a key carrying a category" to (atRoot("RepetitionNotUnique(Tier -> Band)") to setOf("MVK_CATEGORY_NOT_ALLOWED")),
            "a key naming a group" to (atRoot("RepetitionNotUnique(Claims)") to GROUP),
            // This carrier asks no root-group gate, so the root is one more group to the key list.
            "a key naming the root group" to (atRoot("RepetitionNotUnique(/Policy)") to GROUP),
            "a starred key" to (atRoot("RepetitionNotUnique(Claims*/ClaimRef)") to WILDCARD),
            "a key carrying a semantic index" to
                (atRoot("RepetitionNotUnique(Claims/Amount For \"C-1\")") to setOf("MVK_SEMANTIC_INDEX_NOT_ALLOWED")),
            "a key without value validation" to (atRoot("RepetitionNotUnique(Claims/RawNote)") to RAW),
            // Two key defects on one list: the earlier GATE answers, whichever key comes first. The keys must not
            // nest, or the list's duplicate gate answers ahead of both, as the row after shows.
            "a starred key, then a group key" to (atRoot("RepetitionNotUnique(Claims*/ClaimRef, Insured)") to GROUP),
            "a starred key beside the group that holds it" to
                (atRoot("RepetitionNotUnique(Claims*/ClaimRef, Claims)") to setOf("MVK_DUPLICATE_PARAM2")),
            "a key without value validation, then a category" to
                (atRoot("RepetitionNotUnique(Claims/RawNote, Tier -> Band)") to setOf("MVK_CATEGORY_NOT_ALLOWED")),
        )
        assertAgreement(predicted)
    }

    @Test
    fun theScopeOrPlacementBranchRunsAfterTheKeyList() {
        val predicted = linkedMapOf(
            "a scope naming a field" to
                (atRoot("RepetitionNotUnique(Claims/ClaimRef @From Reference)") to setOf("MVK_NO_FIELDS_ALLOWED")),
            "a scope that does not repeat" to
                (atRoot("RepetitionNotUnique(Claims/ClaimRef @From Insured)") to setOf("MVK_GROUP_NOT_REPEATABLE")),
            "a key outside the scope" to (atRoot("RepetitionNotUnique(Claims/ClaimRef @From Payments)") to NOT_IN_GROUP),
            "keys on parallel paths, no scope" to
                (atRoot("RepetitionNotUnique(Claims/ClaimRef, Payments/PayRef)") to setOf("MVK_NOT_IN_SAME_PATH")),
            "no repeatable group between key and rule group" to
                (atRoot("RepetitionNotUnique(Reference)") to setOf("MVK_REPEATABLE_GROUP_MISSING")),
            "a key outside the rule group, no scope" to
                (inClaims("RepetitionNotUnique(/Policy/Payments/PayRef)") to setOf("MVK_NOT_IN_RULEGROUP")),
            // The outsider's repetition CONTAINS the rule group, so the rule's own row reads it.
            "a key in the repetition above the rule group" to
                (inItems("RepetitionNotUnique(/Policy/Claims/ClaimRef)") to setOf("MVK_NOT_IN_RULEGROUP")),
            // Every key-list class answers ahead of the scope; with a scope the shared-path check never runs.
            "a group key beside a scope that does not repeat" to (atRoot("RepetitionNotUnique(Claims @From Insured)") to GROUP),
            "a key without value validation, outside the scope" to
                (atRoot("RepetitionNotUnique(Claims/RawNote @From Payments)") to RAW),
            "a starred key beside a field scope" to
                (atRoot("RepetitionNotUnique(Claims*/ClaimRef @From Reference)") to WILDCARD),
            "keys on parallel paths, with a scope" to
                (atRoot("RepetitionNotUnique(Claims/ClaimRef, Payments/PayRef @From Claims)") to NOT_IN_GROUP),
        )
        assertAgreement(predicted)
    }

    private fun assertAgreement(predicted: Map<String, Pair<DraftRule, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, cell) ->
            val (rule, a12) = cell
            val observed = compare(rule)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun atRoot(condition: String) = rule("/Policy", "../Claims/ClaimRef", condition)

    /** One level down, where a key can sit outside the rule's own group. */
    private fun inClaims(condition: String) = rule("/Policy/Claims", "../ClaimRef", condition)

    /** Two levels down, inside a repetition of the rule's own repetition. */
    private fun inItems(condition: String) = rule("/Policy/Claims/Items", "../ItemRef", condition)

    private fun rule(group: String, errorField: String, condition: String) = DraftRule.builder()
        .groupPath(group).name("UniqueClaims").errorEntityRelPath(errorField).errorCondition(condition)
        .errorCode("PROBE").severity("ERROR").build()

    private fun compare(rule: DraftRule): Pair<Set<String>, Set<String>> {
        val result = validator.check(policy, rule)
        val a12 = if (result.ok()) emptySet() else
            result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        val ours = check(ModelSource(validator.inject(policy, rule))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val GROUP = setOf("MVK_NO_GROUPS_ALLOWED")
        val WILDCARD = setOf("MVK_NO_WILDCARDS_ALLOWED")
        val RAW = setOf("MVK_INVALID_RAW_TYPE")
        val NOT_IN_GROUP = setOf("MVK_NOT_IN_GROUP")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
