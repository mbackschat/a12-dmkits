package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `RuleGroup` keyword as a field-list operand: A12 reads it as the declaring group, iterating where that group
 * repeats, so the iteration guard and the entity list's root and duplicate gates meet it as they meet the group
 * spelled out (KERNEL-FINDINGS KF611).
 *
 * [ROWS] pins A12's answer on every row, and the checker must name exactly the same classes. Own domain: a policy
 * with repeatable claims and a single holder.
 */
class CheckerRuleGroupOperandAgreementTest {

    private val policy = DocumentModelDraft.named("policy-rule-group").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Title") { }
            .group("Claims") { c -> c.repeatable(9)
                .numberField("Amount") { n -> n.fractionDigits(2) }
                .stringField("Note") { }
                .stringField("Status") { } }
            .group("Holder") { h -> h
                .stringField("Name") { }
                .stringField("Phone") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(ROWS.mapValues { judge(it.value).first }, ROWS.mapValues { judge(it.value).second })
    }

    private fun judge(row: Row): Pair<List<String>, List<String>> {
        val draft = DraftRule(row.group, "GroupProbe", "../${row.errorField}", row.condition, "PROBE", "ERROR")
        val result = validator.check(policy, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(policy, draft))).findings.filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    private data class Row(val group: String, val errorField: String, val condition: String, val a12: List<String>)

    private companion object {
        const val NEG = "MVK_NEG_CONDITION_IN_ITERATION"
        const val ROOT = "MVK_ROOT_GROUP_REFERENCED"

        private fun claims(condition: String, a12: List<String>) = Row("/Policy/Claims", "Amount", condition, a12)

        val ROWS = linkedMapOf(
            "count below one" to claims("NumberOfFilledFields(RuleGroup) < 1", listOf(NEG)),
            "none filled" to claims("NoFieldFilled(RuleGroup)", listOf(NEG)),
            "count above zero" to claims("NumberOfFilledFields(RuleGroup) > 0", emptyList()),
            "one filled" to claims("AtLeastOneFieldFilled(RuleGroup)", emptyList()),
            // A count has no at-zero arm (KERNEL-FINDINGS KF595), so only its below arm needs the row.
            "count at zero" to claims("NumberOfFilledFields(RuleGroup) == 0", emptyList()),
            "more than one filled" to claims("MoreThanOneFieldFilled(RuleGroup)", emptyList()),
            "beside its own field" to claims("NumberOfFilledFields(RuleGroup, Amount) > 0", listOf("MVK_DUPLICATE_PARAM2")),
            "named twice" to claims("NumberOfFilledFields(RuleGroup, RuleGroup) > 0", listOf("MVK_DUPLICATE_PARAM1")),
            // The keyword and the spelled path are one entity, against "a keyword is no entity to compare".
            "beside the spelled group" to claims("NumberOfFilledFields(RuleGroup, /Policy/Claims) > 0", listOf("MVK_DUPLICATE_PARAM1")),
            "single group, none filled" to Row("/Policy/Holder", "Name", "NoFieldFilled(RuleGroup)", emptyList()),
            "root, counted" to Row("/Policy", "Title", "FieldFilled(Title) And NumberOfFilledFields(RuleGroup) > 0", listOf(ROOT)),
            "root, none filled" to Row("/Policy", "Title", "NoFieldFilled(RuleGroup)", listOf(ROOT)),
        )
    }
}
