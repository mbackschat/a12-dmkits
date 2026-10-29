package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The order in which the three list carriers that compare VALUES ask their gates, settled on inputs that break
 * two gates at once (a12-kernel-lean SPEC-2026-10-09-01). [KF390] fixed `FieldValuesNotUnique`'s four own gates and
 * [KF401] the two range walks; what lies ahead of them, and where the value list's member gates sit against the
 * duplicate across both lists, is what these rows add. A12's class is written out per row and the portable checker
 * must name the same one.
 *
 * The sequences, read from Kernel 30.8.1 source and measured here:
 * - `CheckFeldListenBedingungImpl`: every operand read, then direct and indirect duplicates (`getRefList`), the root,
 *   categories, and only then the operator's arm. `FieldValuesNotUnique` asks multiplicity, the kind and format scan,
 *   the common base type and raw-String validation; `DateRangesOverlap` asks field-only shape, multiplicity
 *   (`MVK_PARAMSIZE_INVALIDFN`), then the range walks.
 * - `CheckFeldListeUndWerteListenBedingungImpl` (`AtLeastOneDateRangeOverlaps`): the subject list's duplicates, root
 *   and common base type, its one-plain-field slot, a written-out value list, then the member list's own duplicates,
 *   root and base type against the subject, the duplicate ACROSS both lists, and the range walks over the union.
 *   It asks no category gate.
 *
 * A duplicate is identity-sensitive: a semantic index or a category is part of an operand, so the same field under
 * two keys repeats nothing, and a field under a category is not inside its own group's uncategorized reference.
 *
 * Own domain (an insurance policy's coverage windows and claims).
 */
class CheckerListAdmissionOrderAgreementTest {

    private val policy = DocumentModelDraft.named("policy-list-order").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Reference") { }
            .stringField("Holder") { }
            .stringField("Raw") { s -> s.lineBreaks().noValueValidation() }
            .numberField("Premium") { n -> n.fractionDigits(2) }
            .numberField("Count") { n -> n.fractionDigits(0) }
            .booleanField("Active") { }
            .dateField("IssuedOn") { d -> d.format("dd.MM.yyyy") }
            .dateField("ExpiresOn") { d -> d.format("dd.MM.yyyy") }
            .dateField("RenewedOn") { d -> d.format("yyyy-MM-dd") }
            .enumField("Tier") { e -> e.value("GOLD").value("SILVER").category("Level", "HI", "LO") }
            .dateRangeField("Window") { r -> r.format("dd.MM.yyyy").separator("-") }
            .dateRangeField("Season") { r -> r.format("dd.MM.yyyy").separator("-") }
            .dateRangeField("Yearless") { r -> r.format("dd.MM").separator("-") }
            .dateRangeField("RollsForward") { r ->
                r.format("dd.MM").separator("-").interpretationOfYear(RangeYearInterpretation.FROM) }
            .group("Broker") { g -> g.stringField("BrokerName") { } }
            .group("Renewals") { g -> g
                .dateRangeField("RenewalOne") { r -> r.format("dd.MM.yyyy").separator("-") }
                .dateRangeField("RenewalTwo") { r -> r.format("yyyy-MM-dd").separator("/") } }
            .group("Claims") { g -> g.repeatable(20).indexField("ClaimRef")
                .stringField("ClaimRef") { }
                .stringField("Note") { }
                .dateRangeField("Span") { r -> r.format("dd.MM.yyyy").separator("-") } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun uniquenessAsksReadsDuplicatesRootAndCategoryAheadOfItsOwnGates() {
        val predicted = linkedMapOf(
            // Every read finishes first, so an unknown name answers even behind a duplicate.
            "an unknown name ahead of a duplicate" to ("FieldValuesNotUnique(Nope, Holder, Holder)" to ENTITY),
            "an unknown name behind a duplicate" to ("FieldValuesNotUnique(Holder, Holder, Nope)" to ENTITY),
            "a duplicate beside a Boolean" to ("FieldValuesNotUnique(Holder, Holder, Active)" to DUPLICATE),
            "a categorized duplicate" to ("FieldValuesNotUnique(Tier -> Level, Tier -> Level)" to DUPLICATE),
            // The root contains Tier, so the group repeats it before the root is asked about.
            "a field inside the root beside the root" to ("FieldValuesNotUnique(Tier, /Policy)" to CONTAINED),
            // Tier under its category is not inside the uncategorized root, so the root class answers.
            "a categorized field beside the root" to ("FieldValuesNotUnique(Tier -> Level, /Policy)" to ROOT),
            "a sole categorized field" to ("FieldValuesNotUnique(Tier -> Level)" to CATEGORY),
            "a sole Boolean" to ("FieldValuesNotUnique(Active)" to setOf("MVK_PARAMSIZE_INVALIDN")),
            // The format scan precedes the base type and remembers a date's format past a non-temporal field.
            "a Number and two differently formatted Dates" to
                ("FieldValuesNotUnique(Premium, IssuedOn, RenewedOn)" to KIND),
            "a Number between two differently formatted Dates" to
                ("FieldValuesNotUnique(IssuedOn, Premium, RenewedOn)" to KIND),
            "a Number between two equally formatted Dates" to
                ("FieldValuesNotUnique(IssuedOn, Premium, ExpiresOn)" to VARYING),
            "a Number and one Date" to ("FieldValuesNotUnique(Premium, IssuedOn)" to VARYING),
            "a raw String beside a Number" to ("FieldValuesNotUnique(Raw, Premium)" to VARYING),
            "a raw String beside a String" to ("FieldValuesNotUnique(Raw, Holder)" to RAW),
            // The semantic index is part of an operand's identity.
            "one field under two keys" to ("FieldValuesNotUnique(Claims/Note For \"A\", Claims/Note For \"B\")" to ADMITTED),
            "one field under one key twice" to
                ("FieldValuesNotUnique(Claims/Note For \"A\", Claims/Note For \"A\")" to DUPLICATE),
            "a keyed group beside its field under that key" to
                ("FieldValuesNotUnique(Claims For \"A\", Claims/Note For \"A\")" to CONTAINED),
            "a keyed group beside its field under another key" to
                ("FieldValuesNotUnique(Claims/ClaimRef For \"A\", Claims/Note For \"B\")" to ADMITTED),
        )
        assertAgreement(predicted)
    }

    @Test
    fun theRangeListAsksItsShapeAndArityBeforeEitherWalk() {
        val predicted = linkedMapOf(
            "a sole range" to ("DateRangesOverlap(Window)" to FIELD_ARITY),
            "a sole Number" to ("DateRangesOverlap(Premium)" to FIELD_ARITY),
            "a sole starred range" to ("DateRangesOverlap(Claims*/Span)" to ADMITTED),
            "a sole group" to ("DateRangesOverlap(Broker)" to GROUP),
            "a sole root" to ("DateRangesOverlap(/Policy)" to ROOT),
            "a sole categorized field" to ("DateRangesOverlap(Tier -> Level)" to CATEGORY),
            "a categorized field beside a group" to ("DateRangesOverlap(Tier -> Level, Broker)" to CATEGORY),
            "a range beside the root containing it" to ("DateRangesOverlap(Window, /Policy)" to CONTAINED),
            "one range twice" to ("DateRangesOverlap(Window, Window)" to DUPLICATE),
            "one starred range twice" to ("DateRangesOverlap(Claims*/Span, Claims*/Span)" to ADMITTED),
            // The first walk asks both of its questions of one field before it moves to the next.
            "an interpreted range ahead of a Number" to ("DateRangesOverlap(RollsForward, Premium)" to RANGE_FORMAT),
            "a Number ahead of an interpreted range" to ("DateRangesOverlap(Premium, RollsForward)" to NOT_A_RANGE),
            "one keyed range under two keys" to ("DateRangesOverlap(Claims/Span For \"A\", Claims/Span For \"B\")" to ADMITTED),
            "one keyed range under one key twice" to
                ("DateRangesOverlap(Claims/Span For \"A\", Claims/Span For \"A\")" to DUPLICATE),
        )
        assertAgreement(predicted)
    }

    @Test
    fun theValueListJudgesItsMembersBeforeTheDuplicateAcrossBothLists() {
        val predicted = linkedMapOf(
            "the subject repeated in the members" to ("AtLeastOneDateRangeOverlaps(Window In Window)" to DUPLICATE),
            // The members' base type is asked against the subject before the repeat across the lists.
            "a repeated subject beside a Number member" to
                ("AtLeastOneDateRangeOverlaps(Window In Window, Premium)" to VARYING),
            "a Number member" to ("AtLeastOneDateRangeOverlaps(Window In Season, Premium)" to VARYING),
            "a member repeated in the members" to ("AtLeastOneDateRangeOverlaps(Window In Season, Season)" to DUPLICATE),
            // The root containing the subject is a repeat across the lists, but the member root is asked first.
            "the root as the member list" to ("AtLeastOneDateRangeOverlaps(Window In /Policy)" to ROOT),
            "a written-out member list" to ("AtLeastOneDateRangeOverlaps(Window In \"01.01.2024-31.01.2024\")" to NO_CONST),
            "two subjects" to ("AtLeastOneDateRangeOverlaps(Window, Season In Yearless)" to SLOT),
            "a Number beside a subject" to ("AtLeastOneDateRangeOverlaps(Window, Premium In Season)" to VARYING),
            "a subject repeated in the subjects" to ("AtLeastOneDateRangeOverlaps(Window, Window In Season)" to DUPLICATE),
            "two Numbers" to ("AtLeastOneDateRangeOverlaps(Premium In Count)" to NOT_A_RANGE),
            "an interpreted subject" to ("AtLeastOneDateRangeOverlaps(RollsForward In Yearless)" to RANGE_FORMAT),
            "a yearless member" to ("AtLeastOneDateRangeOverlaps(Window In Yearless)" to MIXED_YEAR),
            // One reference expanding to two spellings fails the year walk; the same two fields written out do not.
            "a group of two spellings" to ("AtLeastOneDateRangeOverlaps(Window In Renewals)" to MIXED_YEAR),
            "two spellings written out" to
                ("AtLeastOneDateRangeOverlaps(Window In Renewals/RenewalOne, Renewals/RenewalTwo)" to ADMITTED),
            "a categorized subject" to ("AtLeastOneDateRangeOverlaps(Tier -> Level In Window)" to VARYING),
        )
        assertAgreement(predicted)
    }

    /** The three other value-list conditions build the same two lists, so each repeat answers at the same place. */
    @Test
    fun theValueListQuantifiersAskTheSamePositions() {
        val predicted = linkedMapOf(
            "a repeat in the field list" to ("AtLeastOneFieldValueIncludedInValueList(Holder, Holder In \"x\")" to DUPLICATE),
            "a repeat in the value list" to ("AtLeastOneFieldValueIncludedInValueList(Holder In Count, Count)" to DUPLICATE),
            "a repeat across the lists" to ("AtLeastOneFieldValueIncludedInValueList(Holder In Holder, Raw)" to DUPLICATE),
            "a field across the lists from its group" to
                ("AtLeastOneFieldValueIncludedInValueList(Broker In Broker/BrokerName)" to CONTAINED),
            "the root as the field list" to ("NoFieldValueIncludedInValueList(/Policy In Holder)" to ROOT),
            "the root as the value list" to ("NotAllFieldValuesIncludedInValueList(Holder In /Policy)" to ROOT),
            // The field list's own gates come first: a Number beside a text there outranks the value list's root.
            "a mixed field list against the root" to
                ("AtLeastOneFieldValueIncludedInValueList(Holder, Premium In /Policy)" to VARYING),
            "a categorized field beside its own field" to
                ("AtLeastOneFieldValueIncludedInValueList(Tier -> Level, Tier In \"HI\")" to ADMITTED),
        )
        assertAgreement(predicted)
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val observed = compare(condition)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(probe: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Policy", "ProbeRule", "../Reference", "FieldFilled(Reference) And $probe", "PROBE",
            "ERROR")
        val a12 = validator.check(policy, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.codeOrSummary() }.toSet()
        }
        val ours = rejectingCodes(validator.inject(policy, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val ENTITY = setOf("MVK_INVALID_ENTITY")
        val DUPLICATE = setOf("MVK_DUPLICATE_PARAM1")
        val CONTAINED = setOf("MVK_DUPLICATE_PARAM2")
        val ROOT = setOf("MVK_ROOT_GROUP_REFERENCED")
        val CATEGORY = setOf("MVK_CATEGORY_NOT_ALLOWED")
        val GROUP = setOf("MVK_NO_GROUPS_ALLOWED")
        val KIND = setOf("MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED")
        val VARYING = setOf("MVK_VARYING_TYPES_NOT_ALLOWED")
        val RAW = setOf("MVK_INVALID_RAW_TYPE")
        val FIELD_ARITY = setOf("MVK_PARAMSIZE_INVALIDFN")
        val NOT_A_RANGE = setOf("MVK_NO_DATE_RANGE")
        val RANGE_FORMAT = setOf("MVK_INVALID_DATE_RANGE_FORMAT")
        val MIXED_YEAR = setOf("MVK_DATE_WITH_AND_WITHOUT_YEAR")
        val NO_CONST = setOf("MVK_NO_CONST")
        val SLOT = setOf("MVK_INVALID_PARAMETER_FOR_DATE_RANGE_COMPARISON")

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
