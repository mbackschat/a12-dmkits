package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Operand gates the checker left unjudged on shapes a12-kernel-lean's K22 checkpoint reached (SPEC-2026-10-24-04
 * and -05, KERNEL-FINDINGS KF612): a filtered operand's kind, a product sum's text twin, a rounding of a marked
 * read inside a filter, a `@From` scope its key does not sit in, a root group beside its own categorized field, a
 * keyed group list, and a boolean constant in a value list.
 *
 * [ROWS] pins A12's answer on every row, and the checker must name exactly the same classes. Own domain: a claims
 * book with repeatable rows, keyed zones, and a second root holding an annex.
 */
class CheckerLedgerOperandGateAgreementTest {

    private val book = DocumentModelDraft.named("claims-book-operand-gates").locale("en_US")
        .rootGroup("Book") { b -> b
            .stringField("Title") { }
            .numberField("Amount") { n -> n.fractionDigits(2) }
            .booleanField("Flag") { }
            .group("Rows") { r -> r.repeatable(9)
                .stringField("RLabel") { }
                .numberField("RQty") { n -> n.fractionDigits(0) }
                .numberField("RCost") { n -> n.fractionDigits(2) } }
            .group("Items") { i -> i.repeatable(5)
                .stringField("ICode") { }
                .numberField("ICount") { n -> n.fractionDigits(0) } }
            .group("Zone") { z -> z.repeatable(3).indexField("ZKey")
                .stringField("ZKey") { }
                .group("Plain") { p -> p.repeatable(4).numberField("PV") { n -> n.fractionDigits(0) } } }
            .group("Box") { x -> x.stringField("BoxNote") { } } }
        .rootGroup("Annex") { a -> a
            .enumField("AKey") { e -> e.value("A").value("B").category("Band", "Hi", "Lo") }
            .stringField("ANote") { } }
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
        val draft = DraftRule("/Book", "GateProbe", "../${row.errorField}", row.condition, "PROBE", "ERROR")
        val result = validator.check(book, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(book, draft))).findings.filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    private data class Row(val errorField: String, val condition: String, val a12: List<String>)

    private companion object {
        const val NO_NUMBER = "MVK_NO_NUMBER"
        const val ROOT = "MVK_ROOT_GROUP_REFERENCED"
        const val INDEX_NOT_ALLOWED = "MVK_SEMANTIC_INDEX_NOT_ALLOWED"
        const val UNEXPECTED = "MVK_UNEXPECTED_TOKEN"

        private fun title(condition: String, a12: List<String>) = Row("Title", "FieldFilled(Title) And $condition", a12)

        val ROWS = linkedMapOf(
            "filtered extremum of text" to title("MaxValue(Rows*/RLabel Having [Rows/RQty] > 0) > \"a\"", listOf("MVK_NOT_SORTABLE")),
            "filtered distinct text beside a boolean" to title("NumberOfDifferentValues(Rows*/RLabel Having [Rows/RQty] > 0, Flag) > 1", listOf("MVK_STRING_ENUM_AND_NON_STRING_ENUM")),
            "filtered sum of text" to title("Sum(Rows*/RLabel Having [Rows/RQty] > 0) > 0", listOf(NO_NUMBER)),
            // The body is read before the operator's kind walk, so its own defect answers alone.
            "filtered extremum with a body defect" to title("MaxValue(Rows*/RLabel Having [Rows/Missing] > 0) > \"a\"", listOf("MVK_INVALID_ENTITY")),
            "filtered extremum of numbers" to title("MaxValue(Rows*/RQty Having [Rows/RQty] > 0) > 1", emptyList()),
            "product sum, text first" to title("SumOfProducts(Rows*/RLabel, Rows*/RQty) > 0", listOf(NO_NUMBER)),
            "product sum, text second" to title("SumOfProducts(Rows*/RQty, Rows*/RLabel) > 0", listOf(NO_NUMBER)),
            "product sum of numbers" to title("SumOfProducts(Rows*/RQty, Rows*/RCost) > 0", emptyList()),
            "rounded marked text in a filter" to Row("Items/ICount", "FieldFilled(Items/ICount) And " +
                "AtLeastOneFieldFilled(Items*/ICount Having [Items/ICount] > 0 And RoundUp([\$Items/ICode], 2) > 0)", listOf(NO_NUMBER)),
            "rounded marked number in a filter" to Row("Items/ICount", "FieldFilled(Items/ICount) And " +
                "AtLeastOneFieldFilled(Items*/ICount Having [Items/ICount] > 0 And RoundUp([\$Items/ICount], 2) > 0)", emptyList()),
            "scope its key is not in" to Row("Rows/RLabel", "FieldFilled(Rows/RLabel) And RepetitionNotUnique(Title @From /Book/Rows)", listOf("MVK_NOT_IN_GROUP")),
            "scope its key is in" to Row("Rows/RLabel", "FieldFilled(Rows/RLabel) And RepetitionNotUnique(Rows/RLabel @From /Book/Rows)", emptyList()),
            "first filled, root then its category" to title("FirstFilledValue(/Annex, /Annex/AKey -> Band) == \"Hi\"", listOf(ROOT)),
            "first filled, category then its root" to title("FirstFilledValue(/Annex/AKey -> Band, /Annex) == \"Hi\"", listOf(ROOT)),
            // The plain field nests under the root, so the duplicate gate answers; a category keys a separate entity.
            "first filled, root then its field" to title("FirstFilledValue(/Annex, /Annex/AKey) == \"A\"", listOf("MVK_DUPLICATE_PARAM2")),
            "sum, root then its category" to title("Sum(/Annex, /Annex/AKey -> Band) > 0", listOf(ROOT)),
            "highest, root then its category" to title("MaxValue(/Annex, /Annex/AKey -> Band) > 0", listOf(ROOT)),
            "keyed starred group list" to title("NumberOfFilledGroups(Zone/Plain* For \"z1\") > 0", listOf(INDEX_NOT_ALLOWED)),
            "keyed group beside another" to title("NumberOfFilledGroups(Zone For \"z1\", Box) > 0", listOf(INDEX_NOT_ALLOWED)),
            "keyed starred group beside another" to title("NumberOfFilledGroups(Zone/Plain* For \"z1\", Box) > 0", listOf(INDEX_NOT_ALLOWED)),
            "boolean true in a value list" to title("FieldValueIncludedInValueList(Flag, True)", listOf(UNEXPECTED)),
            "boolean false in a value list" to title("FieldValueIncludedInValueList(Flag, False)", listOf(UNEXPECTED)),
            "boolean among text values" to title("FieldValueIncludedInValueList(Title, \"a\", True)", listOf(UNEXPECTED)),
        )
    }
}
