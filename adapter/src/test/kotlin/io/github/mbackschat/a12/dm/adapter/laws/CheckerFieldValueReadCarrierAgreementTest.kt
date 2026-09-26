package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The field-value read's gates on the carriers [KERNEL-FINDINGS KF473] left out, with A12's class written out on
 * every row and the portable checker required to name the same one.
 *
 * Each row is chosen so that a gate owned by the construct around the read would predict another class: an
 * arithmetic operator's `MVK_NO_NUMBER_FOR_CALC`, a pattern comparison's own type class, a membership subject's
 * kind gate, `MVK_CATEGORY_UNKOWN` for an undeclared category, the value-list quantifier's base-type gate, and a
 * short-name fallback that would admit a bare name its declaring group declares as a group. The rows re-measure
 * a12-kernel-lean's report (their `SPEC-2026-09-25-09`, `-10` and `SPEC-2026-09-26-01`) on our own carrier.
 *
 * Own domain (an order with a billing block, a contact block and repeatable lines).
 */
class CheckerFieldValueReadCarrierAgreementTest {

    private val order = DocumentModelDraft.named("order-reads").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .numberField("Total") { n -> n.fractionDigits(0) }
            .stringField("Remark") { s -> s.lineBreaks().noValueValidation() }
            .dateField("Promised") { d -> d.format("yyyy-MM-dd").datePrecision(DatePrecision.DAY_OPTIONAL) }
            .dateField("Shipped") { d -> d.format("yyyy-MM-dd") }
            .stringField("Channel") { }
            .stringField("Label") { }
            .enumField("Tier") { e -> e.value("GOLD").value("SILVER").category("Level", "HI", "LO") }
            .enumField("TierCopy") { e -> e.value("GOLD").value("SILVER") }
            .timeField("Cutoff") { t -> t.format("HH:mm:ss") }
            .timeField("CutoffCopy") { t -> t.format("HH:mm:ss") }
            .dateRangeField("Window") { r -> r.format("dd.MM.yyyy").separator("-") }
            .dateRangeField("Window2") { r -> r.format("dd.MM.yyyy").separator("-") }
            .group("Billing") { b -> b.numberField("Net") { n -> n.fractionDigits(0) } }
            .group("Contact") { c -> c.stringField("Email") { }.dateField("Since") { d -> d.format("yyyy-MM-dd") } }
            .group("Lines") { l -> l.repeatable(5)
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .stringField("Sku") { }
                .enumField("Size") { e -> e.value("S").value("M") } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aReadInARuleAnswersAheadOfTheConstructAroundIt() {
        val predicted = linkedMapOf(
            "arithmetic, a group" to ("[Total] == {[Billing] + 1}" to GROUP),
            "arithmetic, a group times two" to ("[Total] == {[Billing] * 2}" to GROUP),
            "Abs of a group" to ("[Total] == Abs([Billing])" to GROUP),
            "Min of a group" to ("[Total] == Min([Billing], [Total])" to GROUP),
            "a sum side, a group" to ("[Total] + [Billing] > 0" to GROUP),
            "arithmetic, a raw String" to ("[Total] == {[Remark] + 1}" to RAW),
            "arithmetic, a partial Date" to ("[Total] == {[Promised] - 1}" to PARTIAL),
            "arithmetic, a Date: the operator's class" to ("[Total] == {[Shipped] - 1}" to setOf("MVK_NO_NUMBER_FOR_CALC")),
            "arithmetic, a String: the operator's class" to ("[Total] == {[Channel] * 2}" to setOf("MVK_NO_NUMBER_FOR_CALC")),
            "arithmetic, a name resolving to nothing" to ("[Total] == {[NoSuch] + 1}" to UNRESOLVED),
            "arithmetic, the control" to ("[Total] == {[Total] + 1}" to ADMITTED),
            "a membership member, a group's category" to ("FieldValueIncludedInValueList(Channel, [Billing -> Level])" to GROUP),
            "a comparison side, a group's category" to ("[Billing -> Level] == [Channel]" to GROUP),
            "a category read, the control" to ("[Tier -> Level] == \"HI\"" to ADMITTED),
            "a pattern comparison, a group" to ("[/Order/Billing] PatternMatched \"a\"" to GROUP),
            "a pattern comparison, a partial Date" to ("[/Order/Promised] PatternMatched \"a\"" to PARTIAL),
            "a pattern comparison, a raw String" to ("[/Order/Remark] PatternMatched \"a\"" to RAW),
            "a pattern comparison, a Date: the comparison's class" to
                ("[/Order/Shipped] PatternMatched \"a\"" to setOf("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON")),
            "a pattern comparison, the control" to ("[/Order/Channel] PatternMatched \"a\"" to ADMITTED),
            "a DateRange comparison, a group on the left" to ("[/Order/Billing] == [/Order/Window]" to GROUP),
            "a DateRange comparison, a group on the right" to ("[/Order/Window] == [/Order/Billing]" to GROUP),
            "a DateRange comparison, a raw String on the right" to ("[/Order/Window] == [/Order/Remark]" to RAW),
            "a DateRange comparison, the control" to ("[/Order/Window] == [/Order/Window2]" to ADMITTED),
            "Length of a group" to ("Length(/Order/Billing) > 0" to GROUP),
            "Length of an unstarred repeatable group" to ("Length(/Order/Lines) > 0" to UNSTARRED),
            "Length of a raw String" to ("Length(Remark) > 0" to setOf("MVK_INVALID_LENGTH_OF_RAW_TYPE")),
            "Length of a partial Date" to ("Length(Promised) > 0" to setOf("MVK_NO_STRING")),
        )
        assertAgrees("in a root rule", predicted.mapValues { (_, row) -> compareRule(AT_ROOT, row.first) to row.second })
    }

    @Test
    fun aMembershipSubjectIsAskedInItsOwnOrder() {
        val predicted = linkedMapOf(
            "a group subject, a raw member" to ("FieldValueIncludedInValueList(/Order/Billing, [/Order/Remark])" to RAW),
            "a group subject, a String member" to ("FieldValueIncludedInValueList(/Order/Billing, [/Order/Channel])" to GROUP),
            "a group's category subject, a raw member" to
                ("FieldValueIncludedInValueList(/Order/Billing -> Level, [/Order/Remark])" to GROUP),
            "a group subject, literals" to ("FieldValueIncludedInValueList(/Order/Billing, \"a\", \"b\")" to GROUP),
            "a bare group subject, literals" to ("FieldValueIncludedInValueList(Billing, \"a\", \"b\")" to GROUP),
            "the root subject, literals" to ("FieldValueIncludedInValueList(/Order, \"a\", \"b\")" to GROUP),
            "a group subject, numbers" to ("FieldValueIncludedInValueList(/Order/Billing, 1, 2)" to GROUP),
            "an unstarred repeatable subject, literals" to ("FieldValueIncludedInValueList(/Order/Lines, \"a\", \"b\")" to UNSTARRED),
            "an unstarred repeatable subject, numbers" to ("FieldValueIncludedInValueList(/Order/Lines, 1, 2)" to UNSTARRED),
            "the root subject, a raw member" to ("FieldValueIncludedInValueList(/Order, [/Order/Remark])" to RAW),
            "an unstarred repeatable subject, a raw member" to
                ("FieldValueIncludedInValueList(/Order/Lines, [/Order/Remark])" to UNSTARRED),
            "a subject resolving to nothing, a raw member" to
                ("FieldValueIncludedInValueList(/Order/NoSuch, [/Order/Remark])" to UNRESOLVED),
            "a partial Date subject, literals" to ("FieldValueIncludedInValueList(Promised, \"a\", \"b\")" to KIND),
            "a partial Date subject, a String member" to ("FieldValueIncludedInValueList(Promised, [Channel])" to KIND),
            "a Date subject, literals" to ("FieldValueIncludedInValueList(Shipped, \"a\", \"b\")" to KIND),
            "a raw subject, literals" to ("FieldValueIncludedInValueList(Remark, \"a\", \"b\")" to RAW),
            "a raw subject, a String member" to ("FieldValueIncludedInValueList(Remark, [Channel])" to RAW),
            "a raw subject, numbers" to ("FieldValueIncludedInValueList(Remark, 1, 2)" to RAW),
            "a literal membership, the control" to ("FieldValueIncludedInValueList(/Order/Channel, \"a\", \"b\")" to ADMITTED),
        )
        assertAgrees("a membership", predicted.mapValues { (_, row) -> compareRule(AT_ROOT, row.first) to row.second })
    }

    @Test
    fun aCategoryOfAFieldDeclaringNoEnumerationDrawsItsOwnClass() {
        val predicted = linkedMapOf(
            "of a raw String" to ("[Remark -> Level] == \"x\"" to NOT_ENUM),
            "of a String" to ("[Channel -> Level] == \"x\"" to NOT_ENUM),
            "of a partial Date" to ("[Promised -> Level] == \"x\"" to NOT_ENUM),
            "of a Date" to ("[Shipped -> Level] == \"x\"" to NOT_ENUM),
            "an undeclared category of a String" to ("[Channel -> Nope] == \"x\"" to NOT_ENUM),
            "of an unstarred repeatable group" to ("[Lines -> Level] == \"x\"" to UNSTARRED),
            "of an unstarred repeatable field" to ("[Lines/Sku -> Level] == \"x\"" to UNSTARRED),
            "a membership member" to ("FieldValueIncludedInValueList(Channel, [Remark -> Level])" to NOT_ENUM),
            "a membership subject" to ("FieldValueIncludedInValueList(Channel -> Level, \"a\")" to NOT_ENUM),
            "a value-list quantifier's field" to ("AtLeastOneFieldValueIncludedInValueList(Channel -> Level In \"a\")" to NOT_ENUM),
            "a value-list quantifier's starred field" to
                ("AtLeastOneFieldValueIncludedInValueList(Lines*/Sku -> Level In \"a\")" to NOT_ENUM),
            "a NumberOfFilledFields member" to ("NumberOfFilledFields(Channel -> Level, Label) > 0" to NOT_ENUM),
            "a Sum member" to ("Sum(Total, Channel -> Level) > 0" to NOT_ENUM),
            "a DateRangesOverlap member" to ("DateRangesOverlap(Window, Channel -> Level)" to NOT_ENUM),
            "an AtLeastOneDateRangeOverlaps field" to ("AtLeastOneDateRangeOverlaps(Channel -> Level IN Window, Window2)" to NOT_ENUM),
            "a FieldValuesNotUnique member" to ("FieldValuesNotUnique(Channel -> Level, Label)" to NOT_ENUM),
            "an Enumeration's category in NumberOfFilledFields" to
                ("NumberOfFilledFields(Tier -> Level, Label) > 0" to setOf("MVK_CATEGORY_NOT_ALLOWED")),
            "an Enumeration's category in FieldValuesNotUnique" to
                ("FieldValuesNotUnique(Tier -> Level, Label)" to setOf("MVK_CATEGORY_NOT_ALLOWED")),
        )
        assertAgrees("a category", predicted.mapValues { (_, row) -> compareRule(AT_ROOT, row.first) to row.second })
    }

    @Test
    fun aValueListQuantifierAndAFilterAskTheReadInTheirOwnOrder() {
        val predicted = linkedMapOf(
            "a group's expansion on the right" to ("AtLeastOneFieldValueIncludedInValueList(Reference In /Order/Contact)" to VARYING),
            "a group's expansion on the left" to ("AtLeastOneFieldValueIncludedInValueList(/Order/Contact In Reference)" to VARYING),
            "a Number group opposite a String" to ("AtLeastOneFieldValueIncludedInValueList(Reference In /Order/Billing)" to VARYING),
            "a bare group beside a String, literals" to ("AtLeastOneFieldValueIncludedInValueList(Billing, Channel In \"a\")" to VARYING),
            "an Enumeration's category opposite a String" to
                ("AtLeastOneFieldValueIncludedInValueList(Tier -> Level In Reference)" to VARYING),
            "a name resolving to nothing, a raw String opposite" to
                ("AtLeastOneFieldValueIncludedInValueList(NoSuch In Remark)" to UNRESOLVED),
            "a raw String, a name resolving to nothing opposite" to
                ("AtLeastOneFieldValueIncludedInValueList(Remark In NoSuch)" to UNRESOLVED),
            "a filter, a constant leaf beside a leaf of its level" to ("Sum(Lines*/Qty Having [Lines/Qty] > [Total]) > 0" to ADMITTED),
            "a filter, a constant leaf alone" to ("Sum(Lines*/Qty Having [Total] > 0) > 0" to setOf("MVK_NO_ITERATION_FOR_WILDCARD")),
            "a filter, a raw String" to ("Sum(Lines*/Qty Having [Remark] == [Lines/Sku]) > 0" to RAW),
            "a filter, a partial Date" to ("Sum(Lines*/Qty Having [Promised] > [Shipped]) > 0" to PARTIAL),
        )
        assertAgrees("a quantifier or filter", predicted.mapValues { (_, row) -> compareRule(AT_ROOT, row.first) to row.second })
    }

    @Test
    fun aRuleDeclaredInARepeatableGroupReadsAsARootRuleDoes() {
        val predicted = linkedMapOf(
            "arithmetic, a group" to ("{[/Order/Billing] + 1} > 0" to GROUP),
            "arithmetic, a raw String" to ("{[/Order/Remark] + 1} > 0" to RAW),
            "Length of a group" to ("Length(/Order/Billing) > 0" to GROUP),
            "arithmetic, the control" to ("{[/Order/Total] + 1} > 0" to ADMITTED),
        )
        assertAgrees("in Lines", predicted.mapValues { (_, row) -> compareRule(IN_LINES, row.first) to row.second })
    }

    @Test
    fun aComputationsOperandAsksTheReadOnEveryTargetKind() {
        val predicted = linkedMapOf(
            "a String from a group" to (("../Label" to "[Billing]") to GROUP),
            "an Enumeration from a group" to (("../TierCopy" to "[Billing]") to GROUP),
            "a Time from a group" to (("../CutoffCopy" to "[Billing]") to GROUP),
            "a Number from a group plus one" to (("../Total" to "[Billing] + 1") to GROUP),
            "a Number from a raw String plus one" to (("../Total" to "[Remark] + 1") to RAW),
            "an Enumeration from a raw String" to (("../TierCopy" to "[Remark]") to RAW),
            "a Time from a raw String" to (("../CutoffCopy" to "[Remark]") to RAW),
            "a Number from a partial Date minus one" to (("../Total" to "[Promised] - 1") to PARTIAL),
            "a Number from an unstarred repeatable group plus one" to (("../Total" to "[Lines] + 1") to UNSTARRED),
            "a String from an unstarred repeatable group" to (("../Label" to "[Lines]") to UNSTARRED),
            "an Enumeration from a group's category" to (("../TierCopy" to "[Billing -> Level]") to GROUP),
            "an Enumeration from a String's category" to (("../TierCopy" to "[Channel -> Level]") to NOT_ENUM),
            "an Enumeration from a Number" to (("../TierCopy" to "[Total]") to TO_ENUM),
            "a String, the control" to (("../Label" to "[Channel]") to ADMITTED),
            "an Enumeration, the control" to (("../TierCopy" to "[Tier]") to ADMITTED),
            "a Time, the control" to (("../CutoffCopy" to "[Cutoff]") to ADMITTED),
            "a Number, the control" to (("../Total" to "[Billing/Net] + 1") to ADMITTED),
        )
        assertAgrees("a root target", predicted.mapValues { (_, row) -> compareComputation("/Order", row.first) to row.second })
    }

    @Test
    fun aRepeatableTargetReadsAsANonrepeatableOneDoes() {
        val predicted = linkedMapOf(
            "a Number from a group" to (("../Lines/Qty" to "[Billing]") to GROUP),
            "a Number from a raw String" to (("../Lines/Qty" to "[Remark]") to RAW),
            "a Number from a partial Date" to (("../Lines/Qty" to "[Promised]") to PARTIAL),
            "a Number from a group plus one" to (("../Lines/Qty" to "[Billing] + 1") to GROUP),
            "a Number from Abs of a raw String" to (("../Lines/Qty" to "Abs([Remark])") to RAW),
            "a Number from a sum with a group on its right" to (("../Lines/Qty" to "[Total] + [Billing]") to GROUP),
            "an Enumeration from a group" to (("../Lines/Size" to "[Billing]") to GROUP),
            "a String from a raw String" to (("../Lines/Sku" to "[Remark]") to RAW),
            "an Enumeration from a String's category" to (("../Lines/Size" to "[Channel -> Level]") to NOT_ENUM),
            "a Number from the target's own group" to (("../Lines/Qty" to "[Lines]") to GROUP),
            "a String from the target's own group" to (("../Lines/Sku" to "[Lines]") to GROUP),
            "an Enumeration from the target's own group" to (("../Lines/Size" to "[Lines]") to GROUP),
            "a Number, the control" to (("../Lines/Qty" to "[Total]") to ADMITTED),
            "a String, the control" to (("../Lines/Sku" to "[Channel]") to ADMITTED),
        )
        assertAgrees("a Lines target", predicted.mapValues { (_, row) -> compareComputation("/Order", row.first) to row.second })
    }

    /**
     * A computation resolves a relative name from the group that declares it, not from its target's. The target
     * is `Billing/Rank` in both rows, so only the declaring group differs: from the root `[Grade]` is the root's
     * Enumeration, from `Billing` it is `Billing`'s Number, which an Enumeration target refuses.
     */
    @Test
    fun aComputationResolvesARelativeNameFromItsDeclaringGroup() {
        val model = DocumentModelDraft.named("order-origin").locale("en_US")
            .rootGroup("Order") { o -> o
                .enumField("Grade") { e -> e.value("GOLD").value("SILVER") }
                .group("Billing") { b -> b.numberField("Grade") { n -> n.fractionDigits(0) }
                    .enumField("Rank") { e -> e.value("GOLD").value("SILVER") } } }
            .build().toJson()
        val predicted = linkedMapOf(
            "declared at the root" to (compareComputation("/Order", "../Billing/Rank" to "[Grade]", model) to ADMITTED),
            "declared in the target's group" to
                (compareComputation("/Order/Billing", "../Rank" to "[Grade]", model) to TO_ENUM),
        )
        assertAgrees("the origin", predicted)
    }

    /**
     * A bare name its declaring group declares as a group is that group, even where the short-name tier would
     * find a field of that name elsewhere: a fallback would admit the first model's rows, and an ambiguous one
     * draw `MVK_FIELDNAME_NOT_UNIQUE` in the second. The control is a bare name the declaring group does not
     * declare, which the tier does resolve.
     */
    @Test
    fun aBareNameNamingALocalGroupTakesNoShortNameFallback() {
        fun model(archives: Int) = DocumentModelDraft.named("order-bare").locale("en_US")
            .rootGroup("Order") { o ->
                var root = o.stringField("Reference") { }
                    .group("Billing") { b -> b.numberField("Net") { n -> n.fractionDigits(0) } }
                (1..archives).forEach { i ->
                    root = root.group("Archive$i") { a -> a.numberField("Billing") { n -> n.fractionDigits(0) } }
                }
                root
            }
            .build().toJson()
        val one = model(1)
        val two = model(2)
        val predicted = linkedMapOf(
            "a read, one field elsewhere" to (compareRule(AT_ROOT, "[Billing] > 0", one) to GROUP),
            "FieldFilled, one field elsewhere" to (compareRule(AT_ROOT, "FieldFilled(Billing)", one) to GROUP),
            "a read, two fields elsewhere" to (compareRule(AT_ROOT, "[Billing] > 0", two) to GROUP),
            "FieldFilled, two fields elsewhere" to (compareRule(AT_ROOT, "FieldFilled(Billing)", two) to GROUP),
            "a bare name the root does not declare, the control" to (compareRule(AT_ROOT, "[Net] > 0", one) to ADMITTED),
        )
        assertAgrees("a bare name", predicted)
    }

    /**
     * Each row as (A12, ours) against A12's predicted class, ours defaulting to it; the message names only the
     * rows that disagree, since a whole-map diff over this many rows is unreadable.
     */
    private fun assertAgrees(scope: String, rows: Map<String, Pair<Pair<Set<String>, Set<String>>, Set<String>>>) {
        val wrong = rows.mapNotNull { (row, observed) ->
            val (answers, a12) = observed
            val expected = a12 to (OURS["$scope | $row"] ?: a12)
            if (answers == expected) null else "$scope | $row: expected (A12, ours) $expected, observed $answers"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compareRule(at: Pair<String, String>, probe: String, model: String = order): Pair<Set<String>, Set<String>> {
        val (group, errorLeaf) = at
        val draft = DraftRule(group, "ProbeRule", "../$errorLeaf", "FieldFilled($errorLeaf) And $probe", "PROBE",
            "ERROR")
        val a12 = validator.check(model, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(model, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private fun compareComputation(
        group: String,
        written: Pair<String, String>,
        model: String = order,
    ): Pair<Set<String>, Set<String>> {
        val (target, operation) = written
        val draft = DraftComputation(group, "Derived", target, null,
            listOf(DraftComputation.Alternative(null, operation)))
        val a12 = validator.check(model, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(model, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val AT_ROOT = "/Order" to "Reference"
        val IN_LINES = "/Order/Lines" to "Sku"

        val ADMITTED = emptySet<String>()
        val GROUP = setOf("MVK_NO_GROUPS_ALLOWED")
        val RAW = setOf("MVK_INVALID_RAW_TYPE")
        val PARTIAL = setOf("MVK_INVALID_DATE_TYPE")
        val UNSTARRED = setOf("MVK_NO_WILDCARD")
        val UNRESOLVED = setOf("MVK_INVALID_ENTITY")
        val NOT_ENUM = setOf("MVK_NO_ENUMERATION")
        val KIND = setOf("MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED")
        val VARYING = setOf("MVK_VARYING_TYPES_NOT_ALLOWED")
        val TO_ENUM = setOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")

        /** The rows where the checker answers otherwise, keyed `scope | row`, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
