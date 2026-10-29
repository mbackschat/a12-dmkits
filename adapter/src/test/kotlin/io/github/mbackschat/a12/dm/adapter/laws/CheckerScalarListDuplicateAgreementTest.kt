package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A scalar value list of `[field]` entries names each field once, the subject included: A12 walks the subject with
 * the entries (`checkParamsReferencedOnlyOnce`) and refuses a repeat as a duplicate parameter, ahead of the entries'
 * base types (KERNEL-FINDINGS KF614).
 *
 * [ROWS] pins A12's answer on every row, and the checker must name exactly the same classes. Own domain: an
 * insurance policy with text and number fields.
 */
class CheckerScalarListDuplicateAgreementTest {

    private val policy = DocumentModelDraft.named("policy-scalar-list-duplicate").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Title") { }
            .stringField("Holder") { }
            .stringField("Agent") { }
            .stringField("Broker") { }
            .numberField("Amount") { n -> n.fractionDigits(0) }
            .numberField("Total") { n -> n.fractionDigits(0) } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS, ROWS.mapValues { judge(it.key).first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(ROWS.mapValues { judge(it.key).first }, ROWS.mapValues { judge(it.key).second })
    }

    private fun judge(condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Policy", "ListProbe", "../Title", "FieldFilled(Title) And $condition", "PROBE", "ERROR")
        val result = validator.check(policy, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = rejectingCodes(validator.inject(policy, draft))
        return a12.sorted() to ours.sorted()
    }

    private companion object {
        val DUPLICATE = listOf("MVK_DUPLICATE_PARAM1")

        val ROWS: Map<String, List<String>> = linkedMapOf(
            "FieldValueIncludedInValueList(Holder, [Holder])" to DUPLICATE,
            "FieldValueIncludedInValueList(Holder, [Agent], [Agent])" to DUPLICATE,
            "FieldValueIncludedInValueList(Holder, [Agent], [Holder])" to DUPLICATE,
            "FieldValueNotIncludedInValueList(Amount, [Total], [Amount])" to DUPLICATE,
            // The duplicate walk runs before the base types are matched.
            "FieldValueIncludedInValueList(Amount, [Holder], [Amount])" to DUPLICATE,
            "FieldValueIncludedInValueList(Amount, [Holder])" to listOf("MVK_VARYING_TYPES_NOT_ALLOWED"),
            "FieldValueIncludedInValueList(Holder, [Agent])" to emptyList(),
            "FieldValueIncludedInValueList(Holder, [Agent], [Broker])" to emptyList(),
        )
    }
}
