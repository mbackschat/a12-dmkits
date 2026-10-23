package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `$`-marked operand on the carriers [CheckerOuterMarkedOperandAgreementTest] does not reach: the list
 * families with a value list or a date range, the typed aggregates, a raw-type `Length`, a category access, a
 * bracketed operand, a computation, a keyed or jointly parallel group read, and the parallel levels the
 * reference reader declines (KERNEL-FINDINGS KF599).
 *
 * Own domain: a shop customer, the items of an order, and the parcels and returns that ship it.
 */
class CheckerOuterMarkedResidueAgreementTest {

    private val shop = DocumentModelDraft.named("shop-marked-residue").locale("en_US")
        .rootGroup("Customer") { c -> c
            .numberField("BirthMonth") { n -> n.fractionDigits(0).min("1").max("12") }
            .numberField("BirthYear") { n -> n.fractionDigits(0).min("1800").max("2200") }
            .numberField("Total") { n -> n.fractionDigits(0) }
            .group("Items") { i -> i.repeatable(20)
                .numberField("Count") { n -> n.fractionDigits(0) }
                .numberField("UnitPrice") { n -> n.fractionDigits(2) }
                .stringField("Code") { }
                .stringField("RawCode") { s -> s.noValueValidation().lineBreaks() }
                .enumField("Tier") { e -> e.value("BRONZE").value("GOLD").category("Band", "LOW", "HIGH") }
                .dateRangeField("Window") { it.format("yyyy-MM-dd").separator("/") }
                .dateField("ShipOn") { d -> d.format("yyyy-MM-dd") }
                .numberField("Day") { n -> n.fractionDigits(0).min("1").max("31") } }
            .group("Parcels") { p -> p.repeatable(5).indexField("Label")
                .stringField("Label") { }
                .numberField("Weight") { n -> n.fractionDigits(1) } }
            .group("Returns") { r -> r.repeatable(5).indexField("Label")
                .stringField("Label") { }
                .stringField("Reason") { } } }
        .build().toJson()

    /** Items and notes keyed by a type-defined code, which the parallel walk resolves to the type it defines (KF602). */
    private val typedKeys = DocumentModelDraft.named("shop-typed-keys").locale("en_US")
        .typeDef("LineCode") { it.string { } }
        .typeDef("TicketNo") { it.number { n -> n.fractionDigits(0) } }
        .typeDef("RawLabel") { it.string { s -> s.noValueValidation().lineBreaks() } }
        .rootGroup("Customer") { c -> c
            .group("Items") { i -> i.repeatable(20).indexField("Code")
                .typedefField("Code", "LineCode")
                .typedefField("RawRef", "RawLabel")
                .numberField("Count") { n -> n.fractionDigits(0) } }
            .group("Notes") { n -> n.repeatable(20).indexField("Code")
                .typedefField("Code", "LineCode")
                .stringField("Text") { } }
            .group("Memos") { m -> m.repeatable(20).indexField("Code")
                .stringField("Code") { }
                .stringField("Text") { } }
            .group("Tickets") { t -> t.repeatable(20).indexField("Code")
                .typedefField("Code", "TicketNo")
                .stringField("Text") { } } }
        .build().toJson()

    /** Items and notes keyed alike, the notes holding keyed pages: two keyed groups on one path. */
    private val nestedKeys = DocumentModelDraft.named("shop-nested-keys").locale("en_US")
        .rootGroup("Customer") { c -> c
            .group("Items") { i -> i.repeatable(20).indexField("Code")
                .stringField("Code") { }
                .numberField("Count") { n -> n.fractionDigits(0) } }
            .group("Notes") { n -> n.repeatable(20).indexField("Code")
                .stringField("Code") { }
                .group("Pages") { p -> p.repeatable(5).indexField("PageNo")
                    .stringField("PageNo") { }
                    .stringField("Text") { } } } }
        .build().toJson()

    private val models = mapOf("shop" to shop, "typed" to typedKeys, "nested" to nestedKeys)

    private val validator = RuleValidator()

    private fun judge(row: Row): Pair<List<String>, List<String>> {
        val model = models.getValue(row.model)
        val (result, injected) = if (row.computed == null) {
            val draft = DraftRule("/Customer", "MarkedProbe", "../${row.errorField}", row.condition, "PROBE", "ERROR")
            validator.check(model, draft) to validator.inject(model, draft)
        } else {
            val draft = DraftComputation("/Customer", "MarkedProbe", "../${row.computed}", null,
                listOf(DraftComputation.Alternative(row.precondition, row.condition, null)), listOf())
            validator.check(model, draft) to validator.inject(model, draft)
        }
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(injected)).findings
            .filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = ROWS.mapValues { judge(it.value) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private class Row(val model: String, val errorField: String, val condition: String, val a12: List<String>,
        val computed: String? = null, val precondition: String? = null)

    private companion object {
        /** The body beside an unmarked read of the starred level, so a sound operand is admitted. */
        const val BESIDE = "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Items*/Count Having [Items/Count] > 0 And %s)"
        const val ALONE = "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Items*/Count Having %s)"

        val ROWS: Map<String, Row> = LinkedHashMap<String, Row>().apply {
            fun beside(body: String, vararg a12: String, model: String = "shop") =
                put("$body, beside", Row(model, "Items/Count", BESIDE.format(body), a12.toList()))
            fun alone(body: String, vararg a12: String) =
                put("$body, alone", Row("shop", "Items/Count", ALONE.format(body), a12.toList()))
            fun rule(errorField: String, condition: String, vararg a12: String, model: String = "shop") =
                put("$condition, on $errorField", Row(model, errorField, condition, a12.toList()))
            fun computation(computed: String, operation: String, vararg a12: String, precondition: String? = null) =
                put("computing $computed as $operation when $precondition",
                    Row("shop", "", operation, a12.toList(), computed, precondition))

            beside("RepetitionNotUnique(\$Items/Code, \$Items/Code)", "MVK_DUPLICATE_PARAM1")
            alone("RepetitionNotUnique(\$Items/Code, \$Items/Code)", "MVK_DUPLICATE_PARAM1")
            beside("RepetitionNotUnique(\$Items, Items/Code)", "MVK_DUPLICATE_PARAM2")
            alone("RepetitionNotUnique(\$Items, Items/Code)", "MVK_DUPLICATE_PARAM2")
            beside("NoFieldValueIncludedInValueList(\$Items/Code, \$Items/Code In \"a\")", "MVK_DUPLICATE_PARAM1")
            beside("NoFieldValueIncludedInValueList(Items/Code, Items/Code In \"a\")", "MVK_DUPLICATE_PARAM1")
            beside("AtLeastOneFieldValueIncludedInValueList(\$Items/Code, Items/Code In \"a\")")
            beside("DateRangesOverlap(\$Items/Window, \$Items/Window)", "MVK_DUPLICATE_PARAM1")
            beside("DateRangesOverlap(Items/Window, Items/Window)", "MVK_DUPLICATE_PARAM1")
            beside("AtLeastOneDateRangeOverlaps(\$Items/Window IN \$Items/Window)", "MVK_DUPLICATE_PARAM1")
            beside("MaxValue(\$Items*/Code) > 0", "MVK_NOT_SORTABLE")
            beside("MaxValue(\$Items*/Count) > 0")
            beside("NumberOfDifferentValues(\$Items*/Code) > 1")
            beside("MinValue(\$Items/Code, Items/Count) > 0", "MVK_NOT_SORTABLE")
            beside("Length(\$Items/RawCode) > 1", "MVK_INVALID_LENGTH_OF_RAW_TYPE")
            beside("Length(Items/RawCode) > 1", "MVK_INVALID_LENGTH_OF_RAW_TYPE")
            beside("SumOfProducts(\$Items/UnitPrice, Items/UnitPrice) > 0", "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED")
            beside("NumberOfDifferentValues(\$Items/Tier -> Band, Items/Tier -> Band) > 1", "MVK_CATEGORY_NOT_ALLOWED")
            beside("NumberOfDifferentValues(Items/Tier -> Band, Items/Tier -> Band) > 1", "MVK_CATEGORY_NOT_ALLOWED")
            beside("RoundUpValue([\$Items], 2) > 0", "MVK_UNEXPECTED_TOKEN")
            beside("RoundUpValue([\$Items/UnitPrice], 2) > 0", "MVK_UNEXPECTED_TOKEN")
            beside("DayFromDate(\$Items) > 1", "MVK_NO_GROUPS_ALLOWED")
            beside("DayFromDate(\$Items*/ShipOn) > 1", "MVK_NO_WILDCARDS_ALLOWED")
            beside("DayFromDate(Items*/ShipOn) > 1", "MVK_NO_WILDCARDS_ALLOWED")
            beside("AddDays(\$Items/ShipOn, 1) > [Items/ShipOn]")
            beside("AddDays(Items/ShipOn, 1) > [Items/ShipOn]")
            beside("AddDays(\$Items, 1) > \"2020-01-01\"", "MVK_NO_GROUPS_ALLOWED")
            beside("AddDays(\$Items*/ShipOn, 1) > \"2020-01-01\"", "MVK_NO_WILDCARDS_ALLOWED")
            beside("Valid(Date(\$Items*/Day, BirthMonth, BirthYear))", "MVK_NO_WILDCARDS_ALLOWED")
            beside("Valid(Date(Items*/Day, BirthMonth, BirthYear))", "MVK_NO_WILDCARDS_ALLOWED")
            beside("ValueNotConsistent(\$Items To \"a\")", "MVK_NO_GROUPS_ALLOWED")
            beside("ValueNotConsistent(\$Items/Tier To \"a\")", "MVK_TABLE_ENUM_EXPECTED")
            beside("AtLeastOneFieldFilled(\$/Customer)", "MVK_ROOT_GROUP_REFERENCED")
            beside("Sum(Items/Code, \$Items/Code) > 0", "MVK_NO_NUMBER")
            beside("AtLeastOneFieldFilled(Items, \$Items/Code)", "MVK_DUPLICATE_PARAM2")
            rule("BirthYear", "FieldFilled(BirthYear) And RangeAsString(Parcels For \"x\", 1, 3) == \"a\"", "MVK_NO_GROUPS_ALLOWED")
            rule("BirthYear", "FieldFilled(BirthYear) And ValueAsDate(Parcels For \"x\", FirstDay) > \"01.01.2020\"", "MVK_NO_GROUPS_ALLOWED")
            rule("Parcels/Weight", "FieldFilled(Parcels/Weight) And RangeAsString(Returns, 1, 3) == \"a\"", "MVK_NO_GROUPS_ALLOWED")
            rule("Parcels/Weight", "FieldFilled(Parcels/Weight) And ValueAsDate(Returns, FirstDay) > \"01.01.2020\"", "MVK_NO_GROUPS_ALLOWED")
            computation("Items/UnitPrice", "RoundUpValue(\$Items, 2)", "MVK_INVALID_OUTER_ITERATION")
            computation("Items/UnitPrice", "RoundUpValue(\$Items/Count, 2)", "MVK_INVALID_OUTER_ITERATION")
            computation("Items/UnitPrice", "RoundUpValue(Items, 2)", "MVK_NO_GROUPS_ALLOWED")
            computation("Items/UnitPrice", "RoundUpValue(\$Itemz/Count, 2)", "MVK_INVALID_ENTITY")
            computation("Items/UnitPrice", "RoundUpValue(Items/Count, 2)", "MVK_INVALID_OUTER_ITERATION", precondition = "FieldFilled(\$Items/Count)")
            computation("Items/UnitPrice", "RoundUpValue(Items/Count, 2)", precondition = "FieldFilled(Items/Count)")
            computation("Items/UnitPrice", "Sum(Items*/Count Having [Items/Count] > 0 And [\$Items/Count] > 0)", "MVK_INVALID_COMPARE_DEC_PLACES")
            computation("Total", "Sum(Items*/Count Having [\$Items/Count] > 0)", "MVK_NO_WILDCARD")
            computation("Total", "Sum(Items*/Count Having [Items/Count] > 0)")
            computation("Items/UnitPrice", "Sum(Items*/UnitPrice Having [\$Items/UnitPrice] > 0)", "MVK_NO_ITERATION_FOR_WILDCARD")
            computation("Total", "Sum(Items*/Count Having [\$Total] > 0)", "MVK_NO_ITERATION_FOR_WILDCARD")
            rule("Items/Count", "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Items*/Count Having [Items/Count] > 0 And " +
                "AtLeastOneFieldFilled(Parcels*/Weight Having [\$Items/Count] > 0))", "MVK_NO_ITERATION_FOR_WILDCARD")
            rule("Items/Count", "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Items*/Count Having [Items/Count] > 0 And " +
                "AtLeastOneFieldFilled(Parcels*/Weight Having [Parcels/Weight] > 0))", "MVK_INVALID_ITERATION_IN_FILTER_CONDITION")
            rule("Parcels/Weight", "FieldFilled(Returns/Reason) And AtLeastOneFieldFilled(Items*/Count Having [\$Parcels/Weight] > 0)", "MVK_NO_ITERATION_FOR_WILDCARD")
            rule("Parcels/Weight", "FieldFilled(Returns/Reason) And AtLeastOneFieldFilled(Items*/Count Having [Items/Count] > 0)", "MVK_ERROR_FIELD_NOT_REFERENCED")
            beside("RangeAsString(\$Notes, 1, 3) == \"a\"", "MVK_NO_GROUPS_ALLOWED", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And RangeAsString(Notes, 1, 3) == \"a\"", "MVK_NO_GROUPS_ALLOWED", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And RangeAsString(Memos, 1, 3) == \"a\"", "MVK_NO_GROUPS_ALLOWED", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And RangeAsString(Tickets, 1, 3) == \"a\"", "MVK_ERROR_FIELD_INVALID_INDEX_TYPE", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Notes/Text)", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Tickets/Text)", "MVK_ERROR_FIELD_INVALID_INDEX_TYPE", model = "typed")
            beside("RangeAsString(\$Notes/Pages, 1, 3) == \"a\"", "MVK_MULTIPLE_INDEX_FIELDS", model = "nested")
            rule("Items/Count", "FieldFilled(Items/Count) And RangeAsString(Notes/Pages, 1, 3) == \"a\"", "MVK_MULTIPLE_INDEX_FIELDS", model = "nested")
            rule("Items/Count", "FieldFilled(Items/Count) And GroupFilled(Notes/Pages)", "MVK_MULTIPLE_INDEX_FIELDS", model = "nested")
            rule("Items/Count", "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Notes/Pages)", "MVK_MULTIPLE_INDEX_FIELDS", model = "nested")
            rule("Items/Count", "FieldFilled(Items/Count) And RoundUpValue(Notes/Pages, 2) > 0", "MVK_MULTIPLE_INDEX_FIELDS", model = "nested")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Notes/Pages/Text)", "MVK_MULTIPLE_INDEX_FIELDS", model = "nested")
            beside("NumberOfFilledGroups(\$Returns, \$Returns) > 0", "MVK_ERROR_FIELD_NO_INDEX")
            beside("NumberOfFilledGroups(Returns, Returns) > 0", "MVK_DUPLICATE_PARAM1")
            beside("AtLeastOneFieldFilled(Returns/Reason, Returns/Reason)", "MVK_DUPLICATE_PARAM1")
            rule("Items/Count", "FieldFilled(Items/Count) And NumberOfFilledGroups(Returns, Returns) > 0", "MVK_ERROR_FIELD_NO_INDEX")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Tickets/Text For Items/Code)", "MVK_INDEX_TYPE_INCONSISTENT", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Notes/Text For Items/Code)", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Memos/Text For Items/Code)", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Tickets/Text For Items/Count)", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And FieldFilled(Memos/Text For Items/RawRef)", "MVK_INVALID_RAW_TYPE", model = "typed")
            rule("Items/Count", "FieldFilled(Items/Count) And AtLeastOneFieldFilled(Memos*/Text For Items/RawRef)",
                "MVK_INVALID_RAW_TYPE", model = "typed")
            beside("AllGroupsFilled(Returns*, Parcels)", "MVK_NO_WILDCARDS_G_ALLOWED")
        }
    }
}
