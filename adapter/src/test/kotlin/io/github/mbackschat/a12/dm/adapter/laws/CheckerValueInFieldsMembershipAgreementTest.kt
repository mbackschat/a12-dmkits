package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `NumberOfValueInFields` counting a text constant over an enumeration whose domain does or does not hold it
 * (KERNEL-FINDINGS KF409's walk, `rulekit.validate.laws.ValueInFieldsConstantKindLawsTest`).
 *
 * A12 walks the list field by field and stops at the first it refuses, so the rows put the enumeration before and
 * after a number it would refuse for the kind: which class answers says where the walk stopped. The label, the
 * lowercase token and the category read say what the domain is.
 *
 * Own domain: an order's sales channel, the quantity ordered, and a gift note.
 */
class CheckerValueInFieldsMembershipAgreementTest {

    private val order = DocumentModelDraft.named("order-channel-domain").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .stringField("GiftNote") { }
            .numberField("Quantity") { n -> n.fractionDigits(0) }
            .enumField("Channel") { e -> e
                .value("WEB") { l -> l.label("en_US", "Web shop") }.value("STORE") { l -> l.label("en_US", "In store") }
                .category("Band", "ONLINE", "OFFLINE") } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun judge(condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule.builder().groupPath("/Order").name("CountProbe").errorEntityRelPath("../Reference")
            .errorCondition("FieldFilled(Reference) And $condition").errorCode("PROBE").severity("ERROR")
            .messages(listOf(Dsl.en("Check the order."))).build()
        val result = validator.check(order, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(order, draft))).findings.filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    private val verdicts by lazy { ROWS.mapValues { (_, row) -> judge(row.first) } }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.second }, verdicts.mapValues { it.value.first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private companion object {
        const val MEMBERSHIP = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION"
        const val KIND = "MVK_NO_STRING_OR_ENUM"

        fun count(constant: String, vararg fields: String) = "NumberOfValueInFields(\"$constant\" In ${fields.joinToString()}) > 0"

        val ROWS: Map<String, Pair<String, List<String>>> = linkedMapOf(
            "a member" to (count("WEB", "Channel", "GiftNote") to listOf()),
            "a non-member" to (count("MAIL", "Channel", "GiftNote") to listOf(MEMBERSHIP)),
            "a non-member before a number" to (count("MAIL", "Channel", "Quantity") to listOf(MEMBERSHIP)),
            "a member before a number" to (count("WEB", "Channel", "Quantity") to listOf(KIND)),
            "a number before a non-member" to (count("MAIL", "Quantity", "Channel") to listOf(KIND)),
            "a label" to (count("Web shop", "Channel", "GiftNote") to listOf(MEMBERSHIP)),
            "a token in another case" to (count("web", "Channel", "GiftNote") to listOf(MEMBERSHIP)),
            "a category read" to (count("ONLINE", "Channel -> Band", "GiftNote") to listOf()),
        )
    }
}
