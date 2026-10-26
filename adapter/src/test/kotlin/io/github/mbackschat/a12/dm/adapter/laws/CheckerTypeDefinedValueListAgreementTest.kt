package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A date-range value list whose subject and values mix plain and type-defined fields. A12 compares the two sides by
 * their EFFECTIVE base types, so a type-defined range matches a plain one and two type-defined non-ranges of
 * different kinds do not (KERNEL-FINDINGS KF602). [CheckerTypeDefinedSettingAgreementTest] twins every field of a
 * row at once and so cannot reach these mixes.
 *
 * Own domain: an insurance claim's cover periods and counts.
 */
class CheckerTypeDefinedValueListAgreementTest {

    private val claim = DocumentModelDraft.named("claim-typed-value-list").locale("en_US")
        .typeDef("CoverType") { it.dateRange { r -> r.format("dd.MM.yyyy").separator("-") } }
        .typeDef("RollingType") { it.dateRange { r -> r.format("dd.MM").separator("-").interpretationOfYear(RangeYearInterpretation.FROM) } }
        .typeDef("CountType") { it.number { n -> n.fractionDigits(0) } }
        .rootGroup("Claim") { c -> c
            .stringField("Reference") { }
            .dateRangeField("Cover") { r -> r.format("dd.MM.yyyy").separator("-") }
            .typedefField("CoverDef", "CoverType")
            .dateRangeField("Yearless") { r -> r.format("dd.MM").separator("-") }
            .dateRangeField("Rolling") { r -> r.format("dd.MM").separator("-").interpretationOfYear(RangeYearInterpretation.FROM) }
            .typedefField("RollingDef", "RollingType")
            .numberField("Count") { n -> n.fractionDigits(0) }
            .typedefField("CountDef", "CountType") }
        .build().toJson()

    private val twins = TypeDefinedTwins(claim, "/Claim", "Reference", emptyList())

    private val verdicts by lazy {
        ROWS.mapValues { (_, row) -> twins.judge("FieldFilled(Reference) And AtLeastOneDateRangeOverlaps(${row.first})") }
    }

    @Test
    fun a12AnswersEveryMixAsPinned() {
        assertEquals(ROWS.mapValues { it.value.second }, verdicts.mapValues { it.value.first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private companion object {
        val ROWS = linkedMapOf(
            "a defined subject, a rolling value" to ("CoverDef In Rolling" to listOf("MVK_INVALID_DATE_RANGE_FORMAT")),
            "a plain subject, a defined rolling value" to ("Cover In RollingDef" to listOf("MVK_INVALID_DATE_RANGE_FORMAT")),
            "a defined subject, a yearless value" to ("CoverDef In Yearless" to listOf("MVK_DATE_WITH_AND_WITHOUT_YEAR")),
            "two defined fields of different kinds" to ("CountDef In CoverDef" to listOf("MVK_VARYING_TYPES_NOT_ALLOWED")),
            "a defined number, a plain number" to ("CountDef In Count" to listOf("MVK_NO_DATE_RANGE")),
            "a plain number, a defined number" to ("Count In CountDef" to listOf("MVK_NO_DATE_RANGE")),
        )
    }
}
