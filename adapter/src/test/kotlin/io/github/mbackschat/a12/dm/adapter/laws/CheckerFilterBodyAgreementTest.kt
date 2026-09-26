package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a `Having` body may read, with A12's class written out on every row and the portable checker required
 * to name the same one.
 *
 * `CheckEntitySpecImpl.checkFilterCondition` runs on the spec that OWNS the filter (source-read). A `Having`
 * written after an entity key belongs to the key's spec ([KERNEL-FINDINGS KF358]), so its star gate and its
 * body walk are asked of the KEY, and they run while the key is parsed, ahead of the index gates the outer
 * spec asks of the key once it is complete.
 *
 * Own domain (a claims policy); every rule is declared at the root and reports on `Reference`.
 */
class CheckerFilterBodyAgreementTest {

    private val policy = DocumentModelDraft.named("claims-policy").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Reference") { }
            .group("Claims") { c -> c.repeatable(5)
                .stringField("ClaimNo") { }
                .stringField("Notes") { }
                .numberField("Units") { n -> n.fractionDigits(0) } }
            .group("Parties") { p -> p.repeatable(4)
                .stringField("Name") { }
                .numberField("Count") { n -> n.fractionDigits(0) } }
            .group("Orders") { o -> o.repeatable(3).indexField("OrderNo")
                .stringField("OrderNo") { }
                .stringField("Note") { }
                .numberField("Units") { n -> n.fractionDigits(0) }
                .group("Lines") { l -> l.repeatable(3)
                    .stringField("Memo") { }
                    .numberField("Qty") { n -> n.fractionDigits(0) } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aFilterAnIndexKeyOwnsIsWalkedAgainstTheKey() {
        val predicted = linkedMapOf(
            // A starred key: its body is walked against the key's entity, ahead of the key's own star class.
            "a read of the keyed level" to ("Sum(Orders*/Units For Parties*/Name Having [Orders/Note] == \"x\") > 0" to ITERATION),
            "a read of the key's own level" to ("Sum(Orders*/Units For Parties*/Name Having [Parties/Name] == \"x\") > 0" to KEY_STAR),
            "a read of no repetition" to ("Sum(Orders*/Units For Parties*/Name Having [Reference] == \"x\") > 0" to NO_ITERATION),
            "a read of another repetition" to ("Sum(Orders*/Units For Parties*/Name Having [Claims/Notes] == \"x\") > 0" to ITERATION),
            "a read below the keyed level" to ("Sum(Orders*/Units For Parties*/Name Having [Orders/Lines/Memo] == \"x\") > 0" to ITERATION),
            "an in-scope read, then the keyed level" to
                ("Sum(Orders*/Units For Parties*/Name Having [Parties/Name] == \"x\" And [Orders/Note] == \"y\") > 0" to ITERATION),
            // An index in the body sits inside the key's own index, and is refused as it is parsed.
            "a semantic index in the body" to
                ("Sum(Orders*/Units For Parties*/Name Having [Orders/Note For \"A\"] == \"x\") > 0" to NESTED_INDEX),
            "a read outside the key's scope, then an index read" to
                ("Sum(Orders*/Units For Parties*/Name Having [Claims/Notes] == \"x\" And [Orders/Note For \"A\"] == \"y\") > 0" to NESTED_INDEX),
            "a refused comparison in the body" to
                ("Sum(Orders*/Units For Parties*/Name Having [Orders/Note] < [Orders/Units]) > 0" to setOf("MVK_INVALID_TYPE_FOR_COMPARISON")),
            "a key over another starred level, read in scope" to
                ("Sum(Orders*/Units For Claims*/ClaimNo Having [Claims/Notes] == \"x\") > 0" to KEY_STAR),
            "a key over another starred level, read outside it" to
                ("Sum(Orders*/Units For Claims*/ClaimNo Having [Parties/Name] == \"x\") > 0" to ITERATION),
            // An unstarred key: its own gates answer before the body is walked.
            "an unstarred repeatable key" to
                ("Sum(Orders*/Units For Parties/Name Having [Orders/Note] == \"x\") > 0" to setOf("MVK_SEMANTIC_INDEX_INVALID_ITER")),
            "an unstarred fixed key" to ("Sum(Orders*/Units For Reference Having [Orders/Note] == \"x\") > 0" to setOf("MVK_WILDCARDS_REQUIRED")),
            // The operand's own filter, the control that the walk reads the operand there.
            "the operand's own filter reading the key's level" to
                ("Sum(Orders*/Units Having [Parties/Name] == \"x\") > 0" to ITERATION),
            "the operand's own filter reading through an index" to
                ("Sum(Orders*/Units Having [Orders/Note For \"A\"] == \"x\") > 0" to setOf("MVK_SEMANTIC_INDEX_NOT_ALLOWED")),
        )
        assertAgreement(predicted)
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val observed = compare(condition)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Policy", "ProbeRule", "../Reference", "FieldFilled(Reference) And $condition",
            "PROBE", "ERROR")
        val result = validator.check(policy, draft)
        val a12 = if (result.ok()) emptySet() else
            result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        val ours = check(ModelSource(validator.inject(policy, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ITERATION = setOf("MVK_INVALID_ITERATION_IN_FILTER_CONDITION")
        val NO_ITERATION = setOf("MVK_NO_ITERATION_FOR_WILDCARD")
        val KEY_STAR = setOf("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        val NESTED_INDEX = setOf("MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
