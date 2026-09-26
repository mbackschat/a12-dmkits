package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The entity lists that refuse a category, each operator on the four A12-model carriers that call
 * `CheckEntityListenUtils.checkNoCategories`, with A12's class written out and the portable checker required to
 * name the same one ([KERNEL-FINDINGS KF473]).
 *
 * Only an Enumeration's own category reaches a list gate. The spec's category gate runs first
 * (`CheckEntitySpecImpl.checkCategory`, source-read): it refuses a field declaring no enumeration with
 * `MVK_NO_ENUMERATION` and a group with `MVK_NO_GROUPS_ALLOWED`, and the group rows below measure the second on
 * every group carrier. What separates the carriers is WHERE the list gate sits against the duplicate walk
 * `getRefList` runs: `CheckFeldListenAnzahlOpImpl` asks it first, the other three after the list is built
 * (source-read), so a list carrying a category and a duplicate draws a different class on each side of that line.
 *
 * Own domain (an order with a billing block and a contact block).
 */
class CheckerEntityListCategoryAgreementTest {

    private val order = DocumentModelDraft.named("order-categories").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Reference") { }
            .stringField("Label") { }
            .enumField("Tier") { e -> e.value("GOLD").value("SILVER").category("Level", "HI", "LO") }
            .dateRangeField("Window") { r -> r.format("dd.MM.yyyy").separator("-") }
            .group("Billing") { b -> b.numberField("Net") { n -> n.fractionDigits(0) } }
            .group("Contact") { c -> c.stringField("Email") { } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun everyListThatRefusesACategoryRefusesItInItsOwnPlace() {
        val predicted = linkedMapOf(
            // `CheckFeldListenBedingungImpl`: after the duplicate walk and the root gate.
            "AtLeastOneFieldFilled" to ("AtLeastOneFieldFilled(Tier -> Level, Label)" to CATEGORY),
            "NoFieldFilled" to ("NoFieldFilled(Tier -> Level, Label)" to CATEGORY),
            "FieldFilled" to ("FieldFilled(Tier -> Level)" to CATEGORY),
            "FieldNotFilled" to ("FieldNotFilled(Tier -> Level)" to CATEGORY),
            "NotAllFieldsFilled" to ("NotAllFieldsFilled(Tier -> Level, Label)" to CATEGORY),
            "AllFieldsFilled" to ("AllFieldsFilled(Tier -> Level, Label)" to CATEGORY),
            "FieldsNotCollectivelyFilled" to ("FieldsNotCollectivelyFilled(Tier -> Level, Label)" to CATEGORY),
            "NotExactlyOneFieldFilled" to ("NotExactlyOneFieldFilled(Tier -> Level, Label)" to CATEGORY),
            "MoreThanOneFieldFilled" to ("MoreThanOneFieldFilled(Tier -> Level, Label)" to CATEGORY),
            "DateRangesOverlap" to ("DateRangesOverlap(Window, Tier -> Level)" to CATEGORY),
            "FieldValuesNotUnique" to ("FieldValuesNotUnique(Tier -> Level, Label)" to CATEGORY),
            "AllFieldsFilled, beside a duplicate" to ("AllFieldsFilled(Tier -> Level, Label, Label)" to DUPLICATE),
            // The category is part of an operand's identity, so the field beside its own category repeats nothing.
            "AllFieldsFilled, beside its own field uncategorized" to ("AllFieldsFilled(Tier -> Level, Tier)" to CATEGORY),
            "FieldValuesNotUnique, beside a duplicate" to ("FieldValuesNotUnique(Tier -> Level, Label, Label)" to DUPLICATE),
            // `CheckFeldListenAnzahlOpImpl`: first, ahead of the duplicate walk.
            "NumberOfFilledFields" to ("NumberOfFilledFields(Tier -> Level, Label) > 0" to CATEGORY),
            "NumberOfDifferentValues" to ("NumberOfDifferentValues(Tier -> Level, Label) > 0" to CATEGORY),
            "NumberOfFilledGroups" to ("NumberOfFilledGroups(Tier -> Level, Contact) > 0" to CATEGORY),
            "NumberOfFilledFields, beside a duplicate" to ("NumberOfFilledFields(Tier -> Level, Label, Label) > 0" to CATEGORY),
            "NumberOfDifferentValues, beside a duplicate" to
                ("NumberOfDifferentValues(Tier -> Level, Label, Label) > 0" to CATEGORY),
            "NumberOfFilledGroups, beside a duplicate" to ("NumberOfFilledGroups(Tier -> Level, Contact, Contact) > 0" to CATEGORY),
            // `CheckKontextListenBedingungImpl`: after the duplicate walk. The five group-list quantifiers take a
            // field, so an Enumeration's category reaches the gate there.
            "AtLeastOneGroupFilled" to ("AtLeastOneGroupFilled(Tier -> Level, Contact)" to CATEGORY),
            "NoGroupFilled" to ("NoGroupFilled(Tier -> Level, Contact)" to CATEGORY),
            "AllGroupsFilled" to ("AllGroupsFilled(Tier -> Level, Contact)" to CATEGORY),
            "NotAllGroupsFilled" to ("NotAllGroupsFilled(Tier -> Level, Contact)" to CATEGORY),
            "GroupsNotCollectivelyFilled" to ("GroupsNotCollectivelyFilled(Tier -> Level, Contact)" to CATEGORY),
            "AtLeastOneGroupFilled, beside a duplicate" to
                ("AtLeastOneGroupFilled(Tier -> Level, Contact, Contact)" to DUPLICATE),
            // `CheckWiederholungBedingungImpl`: after the duplicate walk.
            "RepetitionNotUnique" to ("RepetitionNotUnique(Tier -> Level, Label)" to CATEGORY),
            "RepetitionNotUnique, beside a duplicate" to ("RepetitionNotUnique(Tier -> Level, Label, Label)" to DUPLICATE),
            // A category behind a GROUP never reaches a list: the spec's own category gate asks `checkIsField`
            // first (`CheckEntitySpecImpl.checkCategory`), so the group class answers on every carrier, ahead of
            // the duplicate walk too.
            "NumberOfFilledGroups, a group's category" to ("NumberOfFilledGroups(Billing -> Level, Contact) > 0" to GROUP),
            "AtLeastOneGroupFilled, a group's category" to ("AtLeastOneGroupFilled(Billing -> Level, Contact)" to GROUP),
            "AllGroupsFilled, a group's category" to ("AllGroupsFilled(Billing -> Level, Contact)" to GROUP),
            "GroupFilled, a group's category" to ("GroupFilled(Billing -> Level)" to GROUP),
            "GroupNotFilled, a group's category" to ("GroupNotFilled(Billing -> Level)" to GROUP),
            "AtLeastOneGroupFilled, a group's category beside a duplicate" to
                ("AtLeastOneGroupFilled(Billing -> Level, Contact, Contact)" to GROUP),
            "AllFieldsFilled, a group's category" to ("AllFieldsFilled(Billing -> Level, Label)" to GROUP),
            // The controls: the same lists without the category.
            "AllFieldsFilled, the control" to ("AllFieldsFilled(Tier, Label)" to ADMITTED),
            "NumberOfFilledGroups, the control" to ("NumberOfFilledGroups(Billing, Contact) > 0" to ADMITTED),
            // Without the category the carrier's own later class answers: a repetition key list needs a
            // repeatable group, which this root rule lacks, and the gate order puts that behind the category.
            "RepetitionNotUnique, the control" to ("RepetitionNotUnique(Tier, Label)" to setOf("MVK_REPEATABLE_GROUP_MISSING")),
        )
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val observed = compare(condition)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(probe: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Order", "ProbeRule", "../Reference", "FieldFilled(Reference) And $probe", "PROBE",
            "ERROR")
        val a12 = validator.check(order, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        }
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val CATEGORY = setOf("MVK_CATEGORY_NOT_ALLOWED")
        val DUPLICATE = setOf("MVK_DUPLICATE_PARAM1")
        val GROUP = setOf("MVK_NO_GROUPS_ALLOWED")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
