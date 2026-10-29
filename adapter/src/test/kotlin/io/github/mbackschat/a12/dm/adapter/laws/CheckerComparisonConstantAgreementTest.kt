package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A comparison with a written-out constant on one side, with A12's class written out on every row and the
 * portable checker required to name the same one.
 *
 * `CheckKonstanteImpl` types each constant before the comparison walk sees it (source-read): `True` and `False`
 * as the two boolean constants, `Today` as a date of `dd.MM.yyyy` and `Now` as a stamp, a number by its scale,
 * and a quoted literal as a DATE of the format its length names wherever `DateUtils.isStringAValidDate`
 * accepts it, and as text otherwise. So a date-shaped literal opposite a text or a number draws the date
 * class, and a literal that is not a date, `"29.02.2023"` among them, draws what text draws. The two-constant
 * gate ahead of the walk exempts a pair only where its LEFT side is a date and exactly one side is `Today` or
 * `Now`, which is why `Today == 5` reaches the date arm and `5 == Today` does not.
 *
 * Own domain (an order desk); no Base Year is declared except where a row says so.
 */
class CheckerComparisonConstantAgreementTest {

    private fun order(baseYear: Int? = null) = DocumentModelDraft.named("order-constants").locale("en_US")
        .let { if (baseYear == null) it else it.baseYear(baseYear) }
        .rootGroup("Order") { o -> o
            .stringField("Label") { }
            .numberField("Total") { n -> n.fractionDigits(0) }
            .enumField("Tier") { e -> e.value("GOLD").value("SILVER") }
            .booleanField("Paid") { }
            .dateField("Shipped") { d -> d.format("yyyy-MM-dd") }
            .dateField("Birthday") { d -> d.format("MM-dd") }
            .timeField("Cutoff") { t -> t.format("HH:mm:ss") }
            .dateTimeField("Stamped") { t -> t.format("yyyy-MM-dd'T'HH:mm:ss") }
            .dateFragmentField("FiscalYear") { f -> f.fragmentFormat("yyyy") }
            .dateFragmentField("Season") { f -> f.fragmentFormat("MM") }
            .dateRangeField("Window") { r -> r.format("dd.MM.yyyy").separator("-") } }
        .build().toJson()

    private val plain = order()

    private val validator = RuleValidator()

    @Test
    fun aRuleComparisonAgainstAConstantDrawsA12sClass() {
        val predicted = linkedMapOf(
            // A date opposite each constant kind that is not a date.
            "a date against a whole number" to ("[Shipped] == 5" to DATE),
            "a date ordered against a whole number" to ("[Shipped] > 5" to DATE),
            "a whole number ordered against a date" to ("5 < [Shipped]" to DATE),
            "a date against a decimal" to ("[Shipped] == 1.5" to DATE),
            "a date against True" to ("[Shipped] == True" to DATE),
            "a date ordered against True" to ("[Shipped] > True" to ORDERING),
            "a date against False" to ("[Shipped] != False" to DATE),
            "a date against a text" to ("[Shipped] == \"abc\"" to DATE),
            "a date ordered against a text" to ("[Shipped] > \"abc\"" to ORDERING),
            "a date against a day the calendar lacks" to ("[Shipped] == \"29.02.2023\"" to DATE),
            "a date ordered against a day the calendar lacks" to ("[Shipped] > \"29.02.2023\"" to ORDERING),
            // A date opposite a date-shaped constant: the components decide.
            "a date against a dated literal" to ("[Shipped] == \"05.03.2024\"" to ADMITTED),
            "a date ordered against a dated literal" to ("[Shipped] > \"05.03.2024\"" to ADMITTED),
            "a dated literal ordered against a date" to ("\"05.03.2024\" < [Shipped]" to ADMITTED),
            "a date against a yearless literal" to ("[Shipped] == \"05.03.\"" to DATE),
            "a date against a time literal" to ("[Shipped] == \"12:30:00\"" to DATE),
            "a date against a stamp literal" to ("[Shipped] == \"05.03.2024T12:30:00\"" to DATE),
            "a date ordered against a stamp literal" to ("[Shipped] > \"05.03.2024T12:30:00\"" to ADMITTED),
            "a date against Today" to ("[Shipped] == Today" to ADMITTED),
            "a date against Now" to ("[Shipped] == Now" to DATE),
            "a date ordered against Now" to ("[Shipped] > Now" to ADMITTED),
            // The other temporal declarations.
            "a yearless date against a yearless literal" to ("[Birthday] == \"05.03.\"" to ADMITTED),
            "a yearless date against a dated literal" to ("[Birthday] == \"05.03.2024\"" to DATE),
            "a yearless date against Today" to ("[Birthday] == Today" to DATE),
            "a time against a time literal" to ("[Cutoff] == \"12:30:00\"" to ADMITTED),
            "a time ordered against a time literal" to ("[Cutoff] > \"12:30:00\"" to ADMITTED),
            "a time against a whole number" to ("[Cutoff] == 5" to DATE),
            "a time against a dated literal" to ("[Cutoff] == \"05.03.2024\"" to DATE),
            "a time against Now" to ("[Cutoff] == Now" to DATE),
            "a stamp against a stamp literal" to ("[Stamped] == \"05.03.2024T12:30:00\"" to ADMITTED),
            "a stamp against a dated literal" to ("[Stamped] == \"05.03.2024\"" to DATE),
            "a stamp ordered against a dated literal" to ("[Stamped] > \"05.03.2024\"" to ADMITTED),
            "a stamp against Now" to ("[Stamped] == Now" to ADMITTED),
            "a stamp against Today" to ("[Stamped] == Today" to DATE),
            "a year fragment against a dated literal" to ("[FiscalYear] == \"05.03.2024\"" to ADMITTED),
            "a year fragment against a yearless literal" to ("[FiscalYear] == \"05.03.\"" to DATE),
            "a year fragment against a whole number" to ("[FiscalYear] == 2024" to DATE),
            "a month fragment against a yearless literal" to ("[Season] == \"05.03.\"" to ADMITTED),
            "a month fragment against a dated literal" to ("[Season] == \"05.03.2024\"" to DATE),
            // A date range: the date arm answers a date-typed constant, the range arm every other.
            "a range against a whole number" to ("[Window] == 5" to RANGE),
            "a range ordered against a whole number" to ("[Window] > 5" to ORDERING),
            "a range against True" to ("[Window] == True" to RANGE),
            "a range against a text" to ("[Window] == \"abc\"" to RANGE),
            "a range against a dated literal" to ("[Window] == \"05.03.2024\"" to DATE),
            "a range against Today" to ("[Window] == Today" to DATE),
            "a range ordered against Today" to ("[Window] > Today" to ORDERING),
            "a range ordered against a dated literal" to ("[Window] > \"05.03.2024\"" to ORDERING),
            // A date-typed constant opposite a declaration that is no date.
            "a text against a dated literal" to ("[Label] == \"05.03.2024\"" to DATE),
            "a text against a time literal" to ("[Label] == \"12:30:00\"" to DATE),
            "a text against a day the calendar lacks" to ("[Label] == \"29.02.2023\"" to ADMITTED),
            "a text against Today" to ("[Label] == Today" to DATE),
            "a text ordered against Today" to ("[Label] > Today" to ORDERING),
            "an enumeration against a dated literal" to ("[Tier] == \"05.03.2024\"" to DATE),
            "an enumeration against Now" to ("[Tier] == Now" to DATE),
            "a number against a dated literal" to ("[Total] == \"05.03.2024\"" to DATE),
            "a number ordered against a dated literal" to ("[Total] > \"05.03.2024\"" to DATE),
            "a number against Today" to ("[Total] == Today" to DATE),
            "a number ordered against Today" to ("[Total] > Today" to DATE),
            "a boolean against a dated literal" to ("[Paid] == \"05.03.2024\"" to YES_NO),
            // Two constants: exempt only where the left one is a date and exactly one is Today or Now.
            "Today against a whole number" to ("Today == 5" to DATE),
            "a whole number against Today" to ("5 == Today" to CONST),
            "a dated literal against Today" to ("\"05.03.2024\" == Today" to ADMITTED),
            "Today against Now" to ("Today == Now" to CONST),
            "two dated literals" to ("\"05.03.2024\" == \"06.03.2024\"" to CONST),
            "Today against a text" to ("Today == \"abc\"" to DATE),
            "a text literal against Today" to ("\"abc\" == Today" to CONST),
            "Today ordered against a yearless literal" to ("Today > \"05.03.\"" to DATE),
            "Now against a stamp literal" to ("Now == \"05.03.2024T12:30:00\"" to ADMITTED),
            "Today against True" to ("Today == True" to DATE),
            "True against Today" to ("True == Today" to CONST),
            // A pattern must be TEXT, so a date-typed, boolean or number constant fails the type question.
            "a text matched against a time literal" to ("[Label] PatternMatched \"12:30:00\"" to PATTERN_KIND),
            "a text matched against a dated literal" to ("[Label] PatternMatched \"05.03.2024\"" to PATTERN_KIND),
            "a text matched against a literal that is no date" to ("[Label] PatternMatched \"12:30\"" to ADMITTED),
            "a text matched against True" to ("[Label] PatternMatched True" to PATTERN_KIND),
            "a text matched against Today" to ("[Label] PatternMatched Today" to PATTERN_KIND),
            "Today matched against a pattern" to
                ("Today PatternMatched \"a+\"" to setOf("MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON")),
            "a pattern matched against Today" to ("\"a+\" PatternMatched Today" to CONST),
        )
        assertAgreement(predicted.mapValues { (_, row) -> rule(plain, row.first) to row.second })
    }

    @Test
    fun aBaseYearSupplementsAConstantsYearAsItDoesADeclarations() {
        val dated = order(2024)
        val predicted = linkedMapOf(
            "a yearless date against a dated literal" to (rule(dated, "[Birthday] == \"05.03.2024\"") to ADMITTED),
            "a date against a yearless literal" to (rule(dated, "[Shipped] == \"05.03.\"") to ADMITTED),
            "a time against a dated literal" to (rule(dated, "[Cutoff] == \"05.03.2024\"") to DATE),
        )
        assertAgreement(predicted)
    }

    @Test
    fun aComputationsConstantIsJudgedAsTheComparisonItIsLoweredTo() {
        val predicted = linkedMapOf(
            "a text from a dated literal" to (computation("../Label", "\"05.03.2024\"") to DATE),
            "a date from a dated literal" to (computation("../Shipped", "\"05.03.2024\"") to ADMITTED),
            "a date from a whole number" to (computation("../Shipped", "5") to DATE),
            "a date from True" to (computation("../Shipped", "True") to DATE),
            "a range from a whole number" to (computation("../Window", "5") to RANGE),
            "a time from Now" to (computation("../Cutoff", "Now") to DATE),
            "a stamp from Now" to (computation("../Stamped", "Now") to ADMITTED),
            "a number from Today" to (computation("../Total", "Today") to DATE),
            "a precondition ordering a date against a whole number" to
                (computation("../Total", "[Total]", precondition = "[Shipped] > 1") to DATE),
        )
        assertAgreement(predicted)
    }

    private fun rule(model: String, probe: String) =
        model to DraftRule("/Order", "ProbeRule", "../Label", "FieldFilled(Label) And $probe", "PROBE", "ERROR")

    private fun computation(target: String, operation: String, precondition: String? = null) =
        plain to DraftComputation("/Order", "Derived", target, null,
            listOf(DraftComputation.Alternative.builder(operation).precondition(precondition).build()))

    private fun assertAgreement(predicted: Map<String, Pair<Pair<String, Any>, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (carrier, a12) = probe
            val observed = compare(carrier.first, carrier.second)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(model: String, draft: Any): Pair<Set<String>, Set<String>> {
        val (result, injected) = when (draft) {
            is DraftRule -> validator.check(model, draft) to validator.inject(model, draft)
            is DraftComputation -> validator.check(model, draft) to validator.inject(model, draft)
            else -> error("no carrier $draft")
        }
        val a12 = if (result.ok()) emptySet() else
            result.diagnostics().map { it.codeOrSummary() }.toSet()
        val ours = rejectingCodes(injected).toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val DATE = setOf("MVK_INVALID_COMPARE_TO_DATE")
        val RANGE = setOf("MVK_INVALID_COMPARE_TO_DATE_RANGE")
        val ORDERING = setOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        val CONST = setOf("MVK_INVALID_COMPARE_TO_CONST")
        val YES_NO = setOf("MVK_INVALID_COMPARE_TO_YESNO")
        val PATTERN_KIND = setOf("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }

    @Test
    fun stringComparisonAdmissionDoesNotInferTheIndexKeysLengthGate() {
        val host = DocumentModelDraft.named("dispatch-literal-comparison-bounds").locale("en_US")
            .rootGroup("Dispatch") { dispatch ->
                dispatch.stringField("Unbounded") { }
                    .stringField("MinimumFour") { it.minLength(4) }
                    .stringField("MaximumThree") { it.maxLength(3) }
                    .stringField("MaximumFour") { it.maxLength(4) }
            }.build().toJson()
        val edit = EditSession.read(host)
        val dispatch = edit.group("/Dispatch")
        val literals = listOf(
            "plain" to "AB", "lone-cr" to "A\rB", "lf" to "A\nB", "crlf" to "A\r\nB",
            "repeated-cr" to "A\r\r\nB",
        )
        val rows = listOf("Unbounded", "MinimumFour", "MaximumThree", "MaximumFour").flatMap { name ->
            val field = dispatch.str(name)
            literals.flatMap { (id, literal) ->
                listOf(false, true).map { unequal ->
                    val condition = if (unequal) field.ne(literal) else field.eq(literal)
                    val rule = dispatch.rule { it.name("LiteralProbe").errorField(field).`when`(condition)
                        .code("LITERAL_PROBE").message(Dsl.en("Check the dispatch reference.")).build() }
                    ConditionCase("$name-$id-$unequal", validator.inject(host, rule.draft()), null)
                }
            }
        }
        assertEquals(40, rows.size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val expected = rows.map { it.id to emptySet<String>() }
        assertEquals(expected, rows.map { it.id to kernelModelClasses(it.model) }, "kernel comparison admission control")
        assertEquals(expected, rows.map { row -> row.id to rejectingCodes(row.model).toSet() }, "portable comparison admission control")
    }
}
