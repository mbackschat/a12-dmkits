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
                .numberField("Units") { n -> n.fractionDigits(0) }
                .numberField("Paid") { n -> n.fractionDigits(2) } }
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

    @Test
    fun anAggregateInAFilterBodyIsReadByTheBodyWalk() {
        val predicted = linkedMapOf(
            // An aggregate with no filter of its own: its operand is one more spec the body walk reads.
            "a starred aggregate alone" to ("Sum(Claims*/Paid Having Sum(Claims*/Units) > 0) > 0" to NO_ITERATION),
            // Two operands, so the arity gate that answers a lone unstarred operand first stays out of the row.
            "an unstarred aggregate of the filtered level" to
                ("Sum(Claims*/Paid Having Sum(Claims/Units, Claims/Paid) > 0) > 0" to ADMITTED),
            "a starred aggregate beside an in-scope read" to
                ("Sum(Claims*/Paid Having Sum(Claims*/Units) > 0 And [Claims/Units] > 1) > 0" to ADMITTED),
            "a starred aggregate of another repetition beside an in-scope read" to
                ("Sum(Claims*/Paid Having Sum(Parties*/Count) > 0 And [Claims/Units] > 1) > 0" to ADMITTED),
            "an unstarred aggregate of another repetition" to
                ("Sum(Claims*/Paid Having Sum(Parties/Count, Claims/Units) > 0) > 0" to ITERATION),
            // The path question is asked of a level before the below-star one.
            "an aggregate iterating below its star" to
                ("Sum(Orders*/Units Having Sum(Orders*/Lines/Qty) > 0 And [Orders/Units] > 1) > 0" to ITERATION),
            "an aggregate iterating below its star, inside the filtered path" to
                ("Sum(Orders*/Lines*/Qty Having Sum(Orders*/Lines/Qty) > 0 And [Orders/Lines/Qty] > 1) > 0" to NO_WILDCARD),
            "an aggregate starred at every level" to
                ("Sum(Orders*/Units Having Sum(Orders*/Lines*/Qty) > 0 And [Orders/Units] > 1) > 0" to ADMITTED),
            // A nested filter: the inner walk runs as the inner spec is read, the outer walk after the body, and
            // the construct gate last.
            "a nested filter over the filtered level" to
                ("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > 5) > 0) > 0" to DISALLOWED),
            "a nested filter over an unstarred operand" to
                ("Sum(Claims*/Paid Having Sum(Claims/Units Having [Claims/Units] > 5) > 0) > 0" to NO_WILDCARD),
            "a nested filter over another repetition" to
                ("Sum(Claims*/Paid Having Sum(Parties*/Count Having [Parties/Name] == \"x\") > 0) > 0" to ITERATION),
            "a nested filter reading the outer level" to
                ("Sum(Claims*/Paid Having Sum(Parties*/Count Having [Claims/Units] > 1) > 0) > 0" to ITERATION),
            "a nested filter with no in-scope read" to
                ("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Reference] == \"x\") > 0) > 0" to NO_ITERATION),
            // The filter flag is one boolean, cleared as the nested filter ends, so a read after it is the rule's.
            "an in-scope read before a nested filter" to
                ("Sum(Claims*/Paid Having [Claims/Units] > 1 And Sum(Claims*/Units Having [Claims/Units] > 5) > 0) > 0" to DISALLOWED),
            "an in-scope read after a nested filter" to
                ("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > 5) > 0 And [Claims/Units] > 1) > 0" to NO_WILDCARD),
            "a marked read after a nested filter" to
                ("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > 5) > 0 And [\$Reference] == \"x\") > 0" to
                    setOf("MVK_INVALID_OUTER_ITERATION")),
        )
        assertAgreement(predicted)
    }

    @Test
    fun aMarkedReadInAFilterBodyIsTheRulesOwnRead() {
        // A marked spec is checked with the rule's error field restored and skipped by the filter walk
        // (`CheckEntitySpecImpl.checkCommon`, `extractFilterIterationEntityRefs`, source-read). The rival account,
        // that the marker binds the read to the filter it stands in, answers otherwise on every row marked (x).
        val fromTheRoot = linkedMapOf(
            "(x) a marked read of the filtered level" to
                ("Sum(Claims*/Paid Having [Claims/Units] > [\$Claims/Units]) > 0" to NO_WILDCARD),
            "a marked read of a field no repetition holds" to
                ("Sum(Claims*/Paid Having [Claims/Units] > 1 And [\$Reference] == \"x\") > 0" to ADMITTED),
            "(x) a marked read of the filtered level inside a nested filter" to
                ("Sum(Claims*/Paid Having Sum(Claims*/Units Having [Claims/Units] > [\$Claims/Units]) > 0) > 0" to NO_WILDCARD),
            "a marked read ahead of a nested filter" to
                ("Sum(Claims*/Paid Having [\$Reference] == \"x\" And Sum(Claims*/Units Having [Claims/Units] > 5) > 0) > 0" to DISALLOWED),
            "a marked starred bracket in a body" to
                ("Sum(Claims*/Paid Having [Claims/Units] > 1 And [\$Claims*/Units] > 1) > 0" to setOf("MVK_NO_WILDCARDS_ALLOWED")),
            "a marked starred bracket outside any filter" to ("[\$Claims*/Units] > 1" to OUTER),
        )

        // The rule iterates `Claims`, so a marked read of it is at the rule's own coordinates.
        val insideAClaim = linkedMapOf(
            "(x) a marked read at the rule's coordinates" to
                ("Sum(Parties*/Count Having [Parties/Name] == [\$Claims/Notes]) > 0" to ADMITTED),
            "a marked read of the filtered level" to
                ("Sum(Parties*/Count Having [Parties/Name] == [\$Parties/Name]) > 0" to NO_WILDCARD),
            "a marked read of a keyed group the target's path does not key" to
                ("Sum(Parties*/Count Having [Parties/Name] == [\$Orders/Note]) > 0" to setOf("MVK_ERROR_FIELD_NO_INDEX")),
            "(x) a body of marked reads only" to ("Sum(Parties*/Count Having [\$Claims/Notes] == \"x\") > 0" to NO_ITERATION),
            "the same body unmarked" to ("Sum(Parties*/Count Having [Claims/Notes] == \"x\") > 0" to ITERATION),
            "the unmarked read beside the marked one" to
                ("Sum(Parties*/Count Having [Claims/Notes] == \"x\" And [\$Claims/Notes] == \"x\") > 0" to ITERATION),
            "(x) a marked read at the rule's coordinates inside a nested filter" to
                ("Sum(Parties*/Count Having Sum(Parties*/Count Having [Parties/Name] == [\$Claims/Notes]) > 0) > 0" to DISALLOWED),
        )

        // Declared in `Parties` and reporting on the root: any read of one row of `Parties` is refused where
        // A12 checks it, so the rows say which reads A12 checks as the rule's.
        val outsideTheRuleGroup = linkedMapOf(
            "(x) a marked read of one row" to
                ("Sum(/Policy/Claims*/Paid Having [/Policy/Claims/Units] > [\$/Policy/Parties/Count]) > 0" to NOT_IN_RULEGROUP),
            "the same read unmarked" to
                ("Sum(/Policy/Claims*/Paid Having [/Policy/Claims/Units] > [/Policy/Parties/Count]) > 0" to ITERATION),
            "a read of one row after a nested filter" to
                ("Sum(/Policy/Claims*/Paid Having Sum(/Policy/Claims*/Units Having [/Policy/Claims/Units] > 5) > 0 " +
                    "And [/Policy/Parties/Name] == \"x\") > 0" to NOT_IN_RULEGROUP),
            "a nested base reading one row" to
                ("Sum(/Policy/Claims*/Paid Having Sum(/Policy/Parties/Count Having [/Policy/Parties/Name] == \"x\") > 0) > 0" to
                    NOT_IN_RULEGROUP),
        )
        assertAgreement(fromTheRoot to FROM_THE_ROOT, insideAClaim to INSIDE_A_CLAIM,
            outsideTheRuleGroup to OUTSIDE_THE_RULE_GROUP)
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) =
        assertAgreement(predicted to FROM_THE_ROOT)

    /** Every grid is run before anything is asserted, so one failing probe does not hide another's rows. */
    private fun assertAgreement(vararg grids: Pair<Map<String, Pair<String, Set<String>>>, Probe>) {
        val wrong = grids.flatMap { (predicted, probe) ->
            predicted.mapNotNull { (row, cell) ->
                val (condition, a12) = cell
                val observed = compare(condition, probe)
                val expected = a12 to (OURS[row] ?: a12)
                if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(condition: String, probe: Probe): Pair<Set<String>, Set<String>> {
        val draft = DraftRule(probe.group, "ProbeRule", probe.errorField, "${probe.filled} And $condition",
            "PROBE", "ERROR")
        val result = validator.check(policy, draft)
        val a12 = if (result.ok()) emptySet() else
            result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        val ours = check(ModelSource(validator.inject(policy, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    /** Where the probe rule is declared, the field it reports on, and the conjunct that reads that field. */
    private data class Probe(val group: String, val errorField: String, val filled: String)

    private companion object {
        val FROM_THE_ROOT = Probe("/Policy", "../Reference", "FieldFilled(Reference)")
        val INSIDE_A_CLAIM = Probe("/Policy", "../Claims/Notes", "FieldFilled(Claims/Notes)")
        val OUTSIDE_THE_RULE_GROUP = Probe("/Policy/Parties", "../../Reference", "FieldFilled(/Policy/Reference)")

        val ITERATION = setOf("MVK_INVALID_ITERATION_IN_FILTER_CONDITION")
        val NO_ITERATION = setOf("MVK_NO_ITERATION_FOR_WILDCARD")
        val KEY_STAR = setOf("MVK_SEMANTIC_INDEX_WITH_WILDCARD")
        val NESTED_INDEX = setOf("MVK_SEMANTIC_INDEX_WITH_SEMANTIC_INDEX")
        val NO_WILDCARD = setOf("MVK_NO_WILDCARD")
        val DISALLOWED = setOf("MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION")
        val OUTER = setOf("MVK_INVALID_OUTER_ITERATION")
        val NOT_IN_RULEGROUP = setOf("MVK_ERROR_FIELD_NOT_IN_RULEGROUP")
        val ADMITTED = emptySet<String>()

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
