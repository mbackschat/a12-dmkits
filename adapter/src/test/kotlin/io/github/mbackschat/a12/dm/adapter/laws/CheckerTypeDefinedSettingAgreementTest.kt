package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A field typed through a TYPE DEFINITION, against its plain twin, on every gate that reads a SETTING of the type as
 * well as the type itself: an enumeration's domain, labels and categories, a date's format and precision, a range's
 * format and year interpretation, a number's scale, a string's length and pattern (KERNEL-FINDINGS KF602).
 *
 * The within-model control of [CheckerTypeDefinedOperandKindAgreementTest], one row per reader: A12 answers each
 * twin as its plain field, so it reads a definition's settings as the field's own, and a reader that held the
 * definition's body opaque would answer the twin of every refusing row with silence.
 *
 * Own domain: an insurance claim's cover, its tiers and the dates it is filed, noticed and settled on.
 */
class CheckerTypeDefinedSettingAgreementTest {

    private val claim = DocumentModelDraft.named("claim-typed-settings").locale("en_US")
        .typeDef("TierType") { it.enumeration { e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH") } }
        .typeDef("LabelledType") { it.enumeration { e -> e
            .value("BASIC") { l -> l.label("en_US", "Basic cover") }.value("PLUS") { l -> l.label("en_US", "Plus cover") } } }
        .typeDef("WiderType") { it.enumeration { e -> e.value("BASIC").value("PLUS").value("VOID") } }
        .typeDef("NumericType") { it.enumeration { e -> e.value("1").value("2") } }
        .typeDef("LetteredType") { it.enumeration { e -> e.value("A").value("B") } }
        .typeDef("SoleType") { it.enumeration { e -> e.value("APPROVED").sValue("APPROVED") } }
        .typeDef("PartialType") { it.date { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) } }
        .typeDef("ExactType") { it.date { d -> d.format("yyyy-MM-dd") } }
        .typeDef("ClockType") { it.time { t -> t.format("HH:mm:ss") } }
        .typeDef("CoverType") { it.dateRange { r -> r.format("dd.MM.yyyy").separator("-") } }
        .typeDef("YearlessType") { it.dateRange { r -> r.format("dd.MM").separator("-") } }
        .typeDef("RollingType") { it.dateRange { r -> r.format("dd.MM").separator("-").interpretationOfYear(RangeYearInterpretation.FROM) } }
        .typeDef("CountType") { it.number { n -> n.fractionDigits(0) } }
        .typeDef("CodeType") { it.string { } }
        .rootGroup("Claim") { c -> c
            .stringField("Reference") { }
            .stringField("Note") { }
            .enumField("Tier") { e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH") }
            .typedefField("TierDef", "TierType")
            .enumField("Labelled") { e -> e
                .value("BASIC") { l -> l.label("en_US", "Basic cover") }.value("PLUS") { l -> l.label("en_US", "Plus cover") } }
            .typedefField("LabelledDef", "LabelledType")
            .enumField("Wider") { e -> e.value("BASIC").value("PLUS").value("VOID") }
            .typedefField("WiderDef", "WiderType")
            .enumField("Numeric") { e -> e.value("1").value("2") }
            .typedefField("NumericDef", "NumericType")
            .enumField("Lettered") { e -> e.value("A").value("B") }
            .typedefField("LetteredDef", "LetteredType")
            .dateField("Partial") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
            .typedefField("PartialDef", "PartialType")
            .dateField("Exact") { d -> d.format("yyyy-MM-dd") }
            .typedefField("ExactDef", "ExactType")
            .dateField("Noted") { d -> d.format("dd.MM.yyyy") }
            .timeField("Clock") { t -> t.format("HH:mm:ss") }
            .typedefField("ClockDef", "ClockType")
            .dateRangeField("Cover") { r -> r.format("dd.MM.yyyy").separator("-") }
            .typedefField("CoverDef", "CoverType")
            .dateRangeField("Yearless") { r -> r.format("dd.MM").separator("-") }
            .typedefField("YearlessDef", "YearlessType")
            .dateRangeField("Rolling") { r -> r.format("dd.MM").separator("-").interpretationOfYear(RangeYearInterpretation.FROM) }
            .typedefField("RollingDef", "RollingType")
            .numberField("Count") { n -> n.fractionDigits(0) }
            .typedefField("CountDef", "CountType")
            .group("Approval") { a -> a.indexField("Sole")
                .enumField("Sole") { e -> e.value("APPROVED").sValue("APPROVED") }
                .stringField("Remark") { } }
            .group("ApprovalDef") { a -> a.indexField("Sole")
                .typedefField("Sole", "SoleType")
                .stringField("Remark") { } }
            .group("Endorsements") { g -> g.repeatable(5).indexField("Key")
                .enumField("Key") { e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH") }
                .numberField("Amount") { n -> n.fractionDigits(2) } }
            .group("EndorsementsDef") { g -> g.repeatable(5).indexField("Key")
                .typedefField("Key", "TierType")
                .numberField("Amount") { n -> n.fractionDigits(2) } }
            .group("Visits") { g -> g.repeatable(5).indexField("Key")
                .dateField("Key") { d -> d.format("yyyy-MM-dd") }
                .numberField("Amount") { n -> n.fractionDigits(2) } }
            .group("VisitsDef") { g -> g.repeatable(5).indexField("Key")
                .typedefField("Key", "ExactType")
                .numberField("Amount") { n -> n.fractionDigits(2) } }
            .group("Items") { g -> g.repeatable(5)
                .dateField("Exact") { d -> d.format("yyyy-MM-dd") }
                .typedefField("ExactDef", "ExactType")
                .dateField("Noted") { d -> d.format("dd.MM.yyyy") } } }
        .build().toJson()

    private val twins = TypeDefinedTwins(claim, "/Claim", "Reference", listOf("Tier", "Labelled", "Wider", "Numeric",
        "Lettered", "Partial", "Exact", "Clock", "Cover", "Yearless", "Rolling", "Count", "Approval", "Endorsements", "Visits"))

    private fun Probe.judge(twinned: Boolean): Pair<List<String>, List<String>> {
        fun t(text: String) = if (twinned) twins.twin(text) else text
        return when {
            target != null -> twins.judgeComputation(t(target), t(condition))
            else -> twins.judge(t(condition), on?.let(::t) ?: "Reference", message?.let(::t) ?: "Check the entry.")
        }
    }

    private val verdicts by lazy { PROBES.mapValues { (_, probe) -> probe.judge(twinned = false) to probe.judge(twinned = true) } }

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

    /** A probe: a condition, or with [target] a computation's operation, and what A12 draws on the plain field. */
    private class Probe(
        val condition: String,
        vararg codes: String,
        val target: String? = null,
        val on: String? = null,
        val message: String? = null,
    ) {
        val a12 = codes.toList()
    }

    private companion object {
        const val INVALID_DATE_TYPE = "MVK_INVALID_DATE_TYPE"
        const val WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP"
        const val INDEX_VALUE_INVALID = "MVK_INDEX_VALUE_INVALID"
        const val CONSTANT_FOR_ENUMERATION = "MVK_INVALID_STRING_CONSTANT_FOR_ENUMERATION"

        val PROBES: Map<String, Probe> = linkedMapOf(
            // An enumeration's domain, labels and categories.
            "comparison, a constant outside the domain" to
                Probe("[Tier] == \"NOPE\"", "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON"),
            "comparison, display labels against a text" to Probe("[Note] == [Labelled]", "MVK_INVALID_COMPARE_ENUM_TO_STRING"),
            "addition, display labels" to Probe("[Labelled] + [Note] == \"x\"", "MVK_NO_STRING_OR_NUMBER_FOR_ADD"),
            "addition, no display labels" to Probe("[Tier] + [Note] == \"x\""),
            "value in fields, a constant outside the domain" to
                Probe("NumberOfValueInFields(\"NOPE\" In Tier, Note) > 0", CONSTANT_FOR_ENUMERATION),
            "scalar list, a constant outside the domain" to
                Probe("FieldValueIncludedInValueList(Tier, \"BASIC\", \"NOPE\")", CONSTANT_FOR_ENUMERATION),
            "scalar list, a constant outside the category" to
                Probe("FieldValueIncludedInValueList(Tier -> Band, \"LOW\", \"NOPE\")", "${CONSTANT_FOR_ENUMERATION}_CATEGORY"),
            "value list, a constant outside the domain" to
                Probe("AtLeastOneFieldValueIncludedInValueList(Tier, Wider In \"NOPE\")", "${CONSTANT_FOR_ENUMERATION}_OR_CATEGORY"),
            "category read, an unknown category" to Probe("[Tier -> NoSuchBand] == \"LOW\"", "MVK_CATEGORY_UNKOWN"),
            "category read, a number" to Probe("[Count -> Band] == \"LOW\"", "MVK_NO_ENUMERATION"),
            "category read, declared" to Probe("[Tier -> Band] == \"LOW\""),
            "entity list, a category" to Probe("AtLeastOneFieldFilled(Tier -> Band, Note)", "MVK_CATEGORY_NOT_ALLOWED"),
            "conversion, lettered values" to Probe("FieldValueAsNumber(Lettered) > 0", "MVK_NO_CONVERT_IN_NUMBER"),
            "conversion, numeric values" to Probe("FieldValueAsNumber(Numeric) > 0"),
            "index key, a constant outside the domain" to Probe("Sum(Endorsements/Amount For \"PENDING\") > 0", INDEX_VALUE_INVALID),
            "message, a declared category" to Probe("FieldFilled(Tier)", message = "Check \$Tier -> Band\$."),
            "message, an unknown category" to Probe("FieldFilled(Tier)", "INVALID_CATEGORY", message = "Check \$Tier -> NoSuch\$."),
            "message, a category of a number" to Probe("FieldFilled(Count)", "FIELD_NO_ENUM", message = "Check \$Count -> Band\$."),
            "message, a single-valued index" to
                Probe("FieldFilled(Approval/Sole)", on = "Approval/Remark", message = "Approval \$index(Sole).value\$ needs a note."),
            "computation, a wider source" to Probe("[Wider]", "MVK_ERROR_INVALID_ENUM_CALCULATION", target = "Tier"),
            "computation, a labelled source" to Probe("[Labelled]", "MVK_INVALID_COMPARE_ENUM_TO_STRING", target = "Tier"),
            // A date's format and precision.
            "read, a partial date" to Probe("[Partial] == [Exact]", INVALID_DATE_TYPE),
            "shift, a partial date" to Probe("[Exact] > AddDays(Partial, 1)", INVALID_DATE_TYPE),
            "shift, hours on a date" to Probe("[Exact] > AddHours(Exact, 1)", WRONG_FORMAT),
            "shift, hours on a time" to Probe("[Clock] > AddHours(Clock, 1)", "MVK_INVALID_TIME"),
            "shift, an enumeration amount" to Probe("[Exact] > AddDays(Exact, [Tier])", "MVK_NON_NUMERIC"),
            "value as date, a full date" to Probe("ValueAsDate(Exact, FirstDay) > [Noted]", "MVK_INVALID_DATE_TYPE_FOR_OP"),
            "value as date, a partial date" to Probe("ValueAsDate(Partial, FirstDay) > [Exact]"),
            "minimum, a partial date" to Probe("MinValue(Partial, Exact) == [Exact]", "MVK_PARTIAL_DATE_NOT_ALLOWED"),
            "comparison, a date against a number" to Probe("[Exact] == 5", "MVK_INVALID_COMPARE_TO_DATE"),
            "extractor, hours of a partial date" to Probe("HoursFromTime(Partial) == 1", INVALID_DATE_TYPE),
            "extractor, date of a date" to Probe("DateFromDateTime(Exact) == [Exact]", WRONG_FORMAT),
            "uniqueness, two date formats" to
                Probe("FieldValuesNotUnique(Items*/Exact, Items*/Noted)", "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED"),
            "index key, a constant in another format" to Probe("Sum(Visits/Amount For \"31.01.2020\") > 0", INDEX_VALUE_INVALID),
            "computation, a partial source" to Probe("[Partial]", INVALID_DATE_TYPE, target = "Exact"),
            // A range's format and year interpretation.
            "comparison, a range against a number" to Probe("[Cover] == 5", "MVK_INVALID_COMPARE_TO_DATE_RANGE"),
            "overlap, a number" to Probe("DateRangesOverlap(Count, Cover)", "MVK_NO_DATE_RANGE"),
            "overlap, a rolling year" to Probe("DateRangesOverlap(Rolling, Cover)", "MVK_INVALID_DATE_RANGE_FORMAT"),
            "overlap, with and without a year" to Probe("DateRangesOverlap(Cover, Yearless)", "MVK_DATE_WITH_AND_WITHOUT_YEAR"),
            "extractor, the start of a yearless range" to Probe("YearFromDate(StartOfDateRange(Yearless)) == 2024", WRONG_FORMAT),
            // The kind gates the same pass resolves.
            "rounding, an enumeration" to Probe("0 < RoundUpValue(Tier, 2)", "MVK_NO_NUMBER"),
            "date slot, an enumeration" to Probe("DifferenceInDays(Tier, Exact) > 0", "MVK_NO_DATE"),
            "validity, a number" to Probe("Valid(Count, \"EMail\")", "MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM"),
            "substring, a number" to Probe("RangeAsString(Count, 1, 3) == \"ABC\"", "MVK_INVALID_TYPE"),
        )
    }
}
