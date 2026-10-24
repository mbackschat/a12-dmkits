package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A field typed through a TYPE DEFINITION, against its plain twin, on every operand-kind gate of the families that
 * ask a field's type (KERNEL-FINDINGS KF602).
 *
 * Each probe is written twice on one model, differing only in which of the twins it names, so the pair is a
 * within-model control: A12 draws the same class on both, row by row, which is what says it types a type-defined
 * field by the type its definition declares, and a rival reading that held the definition opaque would answer the
 * twin differently on every refusing row.
 *
 * Own domain: an insurance claim's payout, its settlement flags and the attachments filed with it.
 */
class CheckerTypeDefinedOperandKindAgreementTest {

    private val claim = DocumentModelDraft.named("claim-typed-kinds").locale("en_US")
        .typeDef("Money") { it.number { n -> n.fractionDigits(2) } }
        .typeDef("Settled") { it.bool { } }
        .typeDef("Signed") { it.confirm { } }
        .typeDef("Code") { it.string { } }
        .typeDef("Cover") { it.dateRange { d -> d.format("dd.MM.yyyy").separator("-") } }
        .typeDef("Day") { it.date { d -> d.format("dd.MM.yyyy") } }
        .rootGroup("Claim") { c -> c
            .numberField("Payout") { n -> n.fractionDigits(2) }
            .typedefField("PayoutDef", "Money")
            .booleanField("Paid") { }
            .typedefField("PaidDef", "Settled")
            .confirmField("Ack") { }
            .typedefField("AckDef", "Signed")
            .stringField("Ref") { }
            .typedefField("RefDef", "Code")
            .stringField("Other") { }
            .group("Attachments") { a -> a.repeatable(5)
                .stringField("Name") { }
                .numberField("Size") { n -> n.fractionDigits(0) }
                .typedefField("SizeDef", "Money")
                .dateRangeField("Span") { d -> d.format("dd.MM.yyyy").separator("-") }
                .typedefField("SpanDef", "Cover")
                .dateField("On") { d -> d.format("dd.MM.yyyy") }
                .typedefField("OnDef", "Day") } }
        .build().toJson()

    private val twins = TypeDefinedTwins(claim, "/Claim", "Other", listOf("Payout", "Paid", "Ack", "Ref", "Size", "Span", "On"))

    private val verdicts by lazy {
        PROBES.mapValues { (_, probe) -> twins.judge(probe.condition) to twins.judge(twins.twin(probe.condition)) }
    }

    @Test
    fun a12AnswersEachTwinAsItsPlainField() {
        val direct = verdicts.mapValues { it.value.first.first }
        assertEquals(PROBES.mapValues { it.value.a12 }, direct)
        assertEquals(direct, verdicts.mapValues { it.value.second.first }, "the type-defined twin")
    }

    @Test
    fun theCheckerNamesA12sClassOnThePlainField() {
        assertEquals(verdicts.mapValues { it.value.first.first }, verdicts.mapValues { it.value.first.second })
    }

    @Test
    fun theCheckerNamesA12sClassOnTheTypeDefinedTwin() {
        assertEquals(verdicts.mapValues { it.value.second.first }, verdicts.mapValues { it.value.second.second })
    }

    private class Probe(val condition: String, vararg codes: String) {
        val a12 = codes.toList()
    }

    private companion object {
        const val DATE_AND_NONDATE = "MVK_DATE_AND_NONDATE"
        const val INVALID_TYPES_FOR_COMPARISON = "MVK_INVALID_TYPES_FOR_COMPARISON"
        const val NOT_SORTABLE = "MVK_NOT_SORTABLE"
        const val NO_BOOLY_ALLOWED = "MVK_NO_BOOLY_ALLOWED"
        const val NO_NUMBER = "MVK_NO_NUMBER"
        const val NO_STRING_OR_ENUM = "MVK_NO_STRING_OR_ENUM"
        const val NUMBER_AND_NON_NUMBER = "MVK_NUMBER_AND_NON_NUMBER"
        const val ONLY_STRING_ENUM_NUMBER_ALLOWED = "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"
        const val ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED = "MVK_ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED"
        const val ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED"
        const val STRING_ENUM_AND_NON_STRING_ENUM = "MVK_STRING_ENUM_AND_NON_STRING_ENUM"
        const val VARYING_TYPES_NOT_ALLOWED = "MVK_VARYING_TYPES_NOT_ALLOWED"

        val PROBES: Map<String, Probe> = linkedMapOf(
            "scalar list, boolean subject" to
                Probe("FieldValueIncludedInValueList(Paid, \"a\")", ONLY_STRING_ENUM_NUMBER_ALLOWED),
            "scalar list, number subject, text list" to
                Probe("FieldValueIncludedInValueList(Payout, \"a\")", INVALID_TYPES_FOR_COMPARISON),
            "scalar list, text subject, number list" to
                Probe("FieldValueIncludedInValueList(Ref, 1, 2)", INVALID_TYPES_FOR_COMPARISON),
            "scalar list, number subject, text entry" to
                Probe("FieldValueIncludedInValueList(Payout, [Other])", VARYING_TYPES_NOT_ALLOWED),
            "scalar list, text subject, number entry" to
                Probe("FieldValueIncludedInValueList(Other, [Payout])", VARYING_TYPES_NOT_ALLOWED),
            "value list, boolean field" to
                Probe("AtLeastOneFieldValueIncludedInValueList(Paid In \"a\")", ONLY_STRING_ENUM_NUMBER_ALLOWED),
            "value list, number and text fields" to
                Probe("AtLeastOneFieldValueIncludedInValueList(Payout, Other In \"a\")", VARYING_TYPES_NOT_ALLOWED),
            "value list, number field, text list" to
                Probe("AtLeastOneFieldValueIncludedInValueList(Payout In \"a\")", INVALID_TYPES_FOR_COMPARISON),
            "value list, text field, number entity" to
                Probe("AtLeastOneFieldValueIncludedInValueList(Other In Payout)", VARYING_TYPES_NOT_ALLOWED),
            "uniqueness, text beside number" to
                Probe("FieldValuesNotUnique(Attachments*/Name, Attachments*/Size)", VARYING_TYPES_NOT_ALLOWED),
            "uniqueness, number beside text" to
                Probe("FieldValuesNotUnique(Attachments*/Size, Attachments*/Name)", VARYING_TYPES_NOT_ALLOWED),
            "value in fields, text constant in number" to
                Probe("NumberOfValueInFields(\"x\" In Payout, Other) > 0", NO_STRING_OR_ENUM),
            "value in fields, number constant in text" to
                Probe("NumberOfValueInFields(1 In Ref, Other) > 0", NO_NUMBER),
            "value in fields, text constant in boolean" to
                Probe("NumberOfValueInFields(\"x\" In Paid, Other) > 0", NO_STRING_OR_ENUM),
            "distinct, text leader beside number" to
                Probe("NumberOfDifferentValues(Ref, Payout) > 1", STRING_ENUM_AND_NON_STRING_ENUM),
            "distinct, number leader beside text" to
                Probe("NumberOfDifferentValues(Payout, Other) > 1", NUMBER_AND_NON_NUMBER),
            "distinct, boolean leader" to
                Probe("NumberOfDifferentValues(Paid, Other) > 1", ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED),
            "distinct, confirm leader" to
                Probe("NumberOfDifferentValues(Ack, Other) > 1", ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED),
            "sum, boolean beside number" to Probe("Sum(Payout, Paid) > 0", NO_NUMBER),
            "sum, text beside number" to Probe("Sum(Payout, Ref) > 0", NO_NUMBER),
            "first filled, confirm" to Probe("FirstFilledValue(Other, Ack) == \"x\"", NO_BOOLY_ALLOWED),
            "first filled, number beside text" to
                Probe("FirstFilledValue(Other, Payout) == \"x\"", VARYING_TYPES_NOT_ALLOWED),
            "first filled, text beside text" to Probe("FirstFilledValue(Other, Ref) == \"x\""),
            "max, number leader beside text" to Probe("MaxValue(Payout, Ref) > 1", NOT_SORTABLE),
            "max, number leader beside boolean" to Probe("MaxValue(Payout, Paid) > 1", NOT_SORTABLE),
            "max, text leader" to Probe("MaxValue(Ref, Payout) > 1", NOT_SORTABLE),
            // An admitted read and an admitted list: controls that the fixture itself draws nothing.
            "comparison read, number" to Probe("[Payout] == 1"),
            "uniqueness, date range" to
                Probe("FieldValuesNotUnique(Attachments*/Span, Attachments*/Name)", ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED),
            "uniqueness, date beside text" to
                Probe("FieldValuesNotUnique(Attachments*/On, Attachments*/Name)", VARYING_TYPES_NOT_ALLOWED),
            "distinct, date range leader" to
                Probe("NumberOfDifferentValues(Attachments*/Span) > 1", ONLY_STRING_ENUM_NUMBER_CMP_DATE_ALLOWED),
            "max, date leader beside number" to Probe("MaxValue(Attachments*/On, Payout) > 1", DATE_AND_NONDATE),
            "first filled, date beside text" to
                Probe("FirstFilledValue(Other, Attachments*/On) == \"x\"", VARYING_TYPES_NOT_ALLOWED),
            "value list, date range field" to
                Probe("AtLeastOneFieldValueIncludedInValueList(Attachments*/Span In \"a\")", ONLY_STRING_ENUM_NUMBER_ALLOWED),
        )
    }
}
