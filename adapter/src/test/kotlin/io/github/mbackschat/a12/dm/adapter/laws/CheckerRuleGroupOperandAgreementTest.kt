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
 * spelled out (KERNEL-FINDINGS KF611). So do the operand gates of every other carrier measured: each row of
 * [SPELLED] draws the class its spelled twin draws.
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

    /** A holder with a field named like the keyword, which a quoted spelling reaches. */
    private val namedLikeTheKeyword = DocumentModelDraft.named("policy-rule-group-field").locale("en_US")
        .rootGroup("Policy") { p -> p
            .group("Holder") { h -> h
                .stringField("Name") { }
                .stringField("RuleGroup") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun a12ReadsTheKeywordAsTheSpelledGroup() {
        assertEquals(SPELLED.mapValues { ROWS.getValue(it.key).a12 },
            SPELLED.mapValues { (name, spelled) -> judge(ROWS.getValue(name).copy(condition = spelled)).first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(ROWS.mapValues { judge(it.value).first }, ROWS.mapValues { judge(it.value).second })
    }

    /** Only the unquoted keyword is the group: quoted, it names the field, which a field operand may read. */
    @Test
    fun aQuotedKeywordNamesTheFieldOfThatName() {
        val rows = mapOf("FieldFilled(Name) And FieldFilled('RuleGroup')" to emptyList(),
            "FieldFilled(Name) And FieldFilled(RuleGroup)" to listOf("MVK_NO_GROUPS_ALLOWED"))
        val judged = rows.mapValues { (condition, _) -> judge(Row("/Policy/Holder", "Name", condition, emptyList()), namedLikeTheKeyword) }
        assertEquals(rows, judged.mapValues { it.value.first })
        assertEquals(rows, judged.mapValues { it.value.second })
    }

    private fun judge(row: Row, model: String = policy): Pair<List<String>, List<String>> {
        val draft = DraftRule(row.group, "GroupProbe", "../${row.errorField}", row.condition, "PROBE", "ERROR")
        val result = validator.check(model, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(model, draft))).findings.filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    private data class Row(val group: String, val errorField: String, val condition: String, val a12: List<String>)

    private companion object {
        const val NEG = "MVK_NEG_CONDITION_IN_ITERATION"
        const val ROOT = "MVK_ROOT_GROUP_REFERENCED"

        private fun claims(condition: String, a12: List<String>) = Row("/Policy/Claims", "Amount", condition, a12)

        private fun holder(condition: String, a12: List<String>) = Row("/Policy/Holder", "Name", condition, a12)

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
            // A number beside two strings: each carrier's own type gate answers.
            "values not unique" to claims("FieldValuesNotUnique(RuleGroup)", listOf("MVK_VARYING_TYPES_NOT_ALLOWED")),
            "largest value" to claims("MaxValue(RuleGroup) > 0", listOf("MVK_NOT_SORTABLE")),
            "sum" to claims("Sum(RuleGroup) > 0", listOf("MVK_NO_NUMBER")),
            "distinct values" to claims("NumberOfDifferentValues(RuleGroup) > 0", listOf("MVK_NUMBER_AND_NON_NUMBER")),
            "first filled value" to claims("FirstFilledValue(RuleGroup) == 'a'", listOf("MVK_VARYING_TYPES_NOT_ALLOWED")),
            "absolute value" to claims("AbsValue(RuleGroup) > 0", listOf("MVK_NO_GROUPS_ALLOWED")),
            // Two strings: the single-field carriers refuse the group, and strings neither sort nor sum.
            "strings, largest value" to holder("MaxValue(RuleGroup) == 'a'", listOf("MVK_NOT_SORTABLE")),
            "strings, smallest value" to holder("MinValue(RuleGroup) == 'a'", listOf("MVK_NOT_SORTABLE")),
            "strings, sum" to holder("Sum(RuleGroup) > 0", listOf("MVK_NO_NUMBER")),
            "length" to holder("Length(RuleGroup) > 0", listOf("MVK_NO_GROUPS_ALLOWED")),
            "value as number" to holder("FieldValueAsNumber(RuleGroup) > 0", listOf("MVK_NO_GROUPS_ALLOWED")),
            "field filled" to holder("FieldFilled(RuleGroup)", listOf("MVK_NO_GROUPS_ALLOWED")),
        )

        /** The operand-gate rows with the declaring group spelled out instead of the keyword. */
        val SPELLED = ROWS.filterKeys { it in ROWS.keys.drop(ROWS.keys.indexOf("values not unique")) }
            .mapValues { (_, row) -> row.condition.replace("RuleGroup", row.group) }
    }
}
