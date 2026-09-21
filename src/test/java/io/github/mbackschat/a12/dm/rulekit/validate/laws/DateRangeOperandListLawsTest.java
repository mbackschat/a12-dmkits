package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.RangeYearInterpretation;

/**
 * KERNEL-FINDINGS §6 — <b>what the two range-overlap operators demand of the ranges they compare</b>.
 *
 * <p>{@code CheckEntityListenUtils.checkDateRangeWithoutSpecificInterpretationOfYear} and
 * {@code checkDateRangeConsistentRegardingYear} are called back to back by both carriers, so the same two
 * questions are asked of {@code DateRangesOverlap}'s plain list and of {@code AtLeastOneDateRangeOverlaps}'
 * subject-plus-values list. The first walks each expanded field and asks two things of it; the second walks
 * the whole list once more and asks whether they AGREE about carrying a year.
 *
 * <p>The year-consistency half has a model-level escape the format half does not: a declared BASE YEAR
 * returns from it immediately, which is what the two base-year rows below separate.
 *
 * <p>Own domain (an insurance policy's coverage windows).
 */
class DateRangeOperandListLawsTest {

    private static final String GROUP = "/Policy";
    private static final String ERROR_REL = "../Reference";

    private static final String NOT_A_RANGE = "MVK_NO_DATE_RANGE";
    private static final String BAD_RANGE_FORMAT = "MVK_INVALID_DATE_RANGE_FORMAT";
    private static final String MIXED_YEAR = "MVK_DATE_WITH_AND_WITHOUT_YEAR";

    private static final String POLICY = policy(null);
    private static final String POLICY_WITH_BASE_YEAR = policy(2024);

    private static String policy(Integer baseYear) {
        var draft = DocumentModelDraft.named("policy-coverage-windows").locale("en_US");
        if (baseYear != null) {
            draft = draft.baseYear(baseYear);
        }
        return draft
                .rootGroup("Policy", policy -> policy
                        .stringField("Reference", f -> { })
                        .stringField("Holder", f -> { })
                        .numberField("Premium", f -> f.fractionDigits(2))
                        .dateField("IssuedOn", f -> f.format("dd.MM.yyyy"))
                        .dateRangeField("Season", f -> f.format("dd.MM.yyyy").separator("-"))
                        .dateRangeField("Window", f -> f.format("dd.MM.yyyy").separator("-"))
                        .dateRangeField("Yearless", f -> f.format("dd.MM").separator("-"))
                        .dateRangeField("AlsoYearless", f -> f.format("dd.MM").separator("-"))
                        .dateRangeField("RollsForward", f -> f.format("dd.MM").separator("-")
                                .interpretationOfYear(RangeYearInterpretation.FROM))
                        .dateRangeField("RollsBack", f -> f.format("dd.MM").separator("-")
                                .interpretationOfYear(RangeYearInterpretation.TO))
                        .group("Broker", g -> g.stringField("BrokerName", f -> { }))
                        .group("Claims", g -> g.repeatable(20)
                                .dateRangeField("ClaimWindow", f -> f.format("dd.MM.yyyy").separator("-")))
                        .group("Terms", g -> g
                                .dateRangeField("TermOne", f -> f.format("dd.MM.yyyy").separator("-"))
                                .dateRangeField("TermTwo", f -> f.format("dd.MM.yyyy").separator("-")))
                        .group("Renewals", g -> g
                                .dateRangeField("RenewalOne", f -> f.format("dd.MM.yyyy").separator("-"))
                                .dateRangeField("RenewalTwo", f -> f.format("yyyy-MM-dd").separator("/"))))
                .build().toJson();
    }

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(POLICY, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
    }

    private static String withBaseYear(String condition) {
        return KernelLaws.verdictModel(POLICY_WITH_BASE_YEAR, GROUP, ERROR_REL,
                "FieldFilled(Reference) And " + condition);
    }

    /** What the plain-list carrier demands of each member, and in which order. */
    @Test
    void whatTheRangeListDemandsOfEachMember() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two standard ranges — the control", verdict("DateRangesOverlap(Season, Window)"));
        actual.put("a text field", verdict("DateRangesOverlap(Holder, Window)"));
        actual.put("a number field", verdict("DateRangesOverlap(Premium, Window)"));
        actual.put("a plain DATE field", verdict("DateRangesOverlap(IssuedOn, Window)"));
        actual.put("the non-range in the SECOND position", verdict("DateRangesOverlap(Season, Holder)"));
        actual.put("a group", verdict("DateRangesOverlap(Broker, Window)"));
        actual.put("a FROM-interpreting range", verdict("DateRangesOverlap(RollsForward, Window)"));
        actual.put("a TO-interpreting range", verdict("DateRangesOverlap(RollsBack, Window)"));
        actual.put("a year-interpreting range in the SECOND position",
                verdict("DateRangesOverlap(Season, RollsBack)"));
        actual.put("a starred range, which this arm never asks about",
                verdict("DateRangesOverlap(Claims*/ClaimWindow, Window)"));
        actual.put("one operand only", verdict("DateRangesOverlap(Season)"));
        actual.put("one operand, and a GROUP", verdict("DateRangesOverlap(Broker)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two standard ranges — the control", KernelLaws.ADMITTED);
        expected.put("a text field", NOT_A_RANGE);
        expected.put("a number field", NOT_A_RANGE);
        // A date is not a range, which is the row the code's name alone would leave open.
        expected.put("a plain DATE field", NOT_A_RANGE);
        expected.put("the non-range in the SECOND position", NOT_A_RANGE);
        // `checkOnlyFields` runs ahead of both date-range questions and answers a group itself.
        expected.put("a group", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a FROM-interpreting range", BAD_RANGE_FORMAT);
        expected.put("a TO-interpreting range", BAD_RANGE_FORMAT);
        expected.put("a year-interpreting range in the SECOND position", BAD_RANGE_FORMAT);
        // This arm calls neither `checkNoWildCards` nor a star-validity gate, and the walk EXPANDS the star.
        expected.put("a starred range, which this arm never asks about", KernelLaws.ADMITTED);
        expected.put("one operand only", "MVK_PARAMSIZE_INVALIDFN");
        // The discriminator for the order of the two pre-gates: a lone group fails both, and the group one
        // answers, so a reader asking about arity first would name the wrong class on it.
        expected.put("one operand, and a GROUP", "MVK_NO_GROUPS_ALLOWED");
        assertEquals(expected, actual, "what the range list demands of each member");
    }

    /** The agreement question, its gate order against the per-member one, and the base-year escape. */
    @Test
    void whenTheListMustAgreeAboutCarryingAYear() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two year-less ranges", verdict("DateRangesOverlap(Yearless, AlsoYearless)"));
        actual.put("a dated range and a year-less one", verdict("DateRangesOverlap(Season, Yearless)"));
        actual.put("the year-less one first", verdict("DateRangesOverlap(Yearless, Season)"));
        actual.put("three ranges, the disagreement in the last",
                verdict("DateRangesOverlap(Season, Window, Yearless)"));
        actual.put("a year mismatch AND a non-range together",
                verdict("DateRangesOverlap(Yearless, Holder)"));
        actual.put("a year mismatch AND a year-interpreting range together",
                verdict("DateRangesOverlap(Season, RollsForward)"));
        actual.put("the same mismatch under a declared BASE YEAR",
                withBaseYear("DateRangesOverlap(Season, Yearless)"));
        actual.put("a year-interpreting range under a declared BASE YEAR",
                withBaseYear("DateRangesOverlap(RollsForward, Window)"));
        actual.put("the control under a declared BASE YEAR",
                withBaseYear("DateRangesOverlap(Season, Window)"));
        // Distinct dated SPELLINGS across two references rather than inside one expansion — the boundary the
        // group row below establishes nothing about, because the walk asks the two carriers different
        // questions: `distinct()` runs on one reference's FORMAT STRINGS, while the cross-reference test
        // compares year-bearing BOOLEANS. Same two declarations as `DateRangesOverlap(Renewals)`, with only
        // the reference count moved.
        actual.put("two explicit FIELDS, two dated spellings",
                verdict("DateRangesOverlap(Season, Renewals/RenewalTwo)"));
        actual.put("the Renewals pair named as two fields rather than as their group",
                verdict("DateRangesOverlap(Renewals/RenewalOne, Renewals/RenewalTwo)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two year-less ranges", KernelLaws.ADMITTED);
        expected.put("a dated range and a year-less one", MIXED_YEAR);
        expected.put("the year-less one first", MIXED_YEAR);
        expected.put("three ranges, the disagreement in the last", MIXED_YEAR);
        // The per-member sweep completes before the agreement walk begins, so a non-range anywhere answers
        // first even when the list also disagrees about the year.
        expected.put("a year mismatch AND a non-range together", NOT_A_RANGE);
        expected.put("a year mismatch AND a year-interpreting range together", BAD_RANGE_FORMAT);
        // Only the agreement half returns early on a base year: with one declared, a year-less range takes
        // it and there is nothing left to disagree about. The format half has no such escape.
        expected.put("the same mismatch under a declared BASE YEAR", KernelLaws.ADMITTED);
        expected.put("a year-interpreting range under a declared BASE YEAR", BAD_RANGE_FORMAT);
        expected.put("the control under a declared BASE YEAR", KernelLaws.ADMITTED);
        // The spelling refusal does NOT cross a reference boundary: both are year-bearing, so the boolean
        // comparison admits the pair that the same two declarations under ONE group reference refuse.
        expected.put("two explicit FIELDS, two dated spellings", KernelLaws.ADMITTED);
        expected.put("the Renewals pair named as two fields rather than as their group", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "when the list must agree about carrying a year");
    }

    /**
     * The SECOND carrier asks the same two questions of subject and values together.
     *
     * <p>Its pre-gates differ and run first — the subject must be exactly one plain field, and the values
     * must match the subject's base type — so the rows here are as much about what PRE-EMPTS these two
     * questions as about the questions themselves.
     */
    @Test
    void theValueListCarrierAsksTheSameTwoQuestions() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a range against a range — the control",
                verdict("AtLeastOneDateRangeOverlaps(Season In Window)"));
        actual.put("a text subject", verdict("AtLeastOneDateRangeOverlaps(Holder In Window)"));
        actual.put("a text value", verdict("AtLeastOneDateRangeOverlaps(Season In Holder)"));
        actual.put("two texts, which AGREE and so reach the range walk",
                verdict("AtLeastOneDateRangeOverlaps(Holder In Reference)"));
        actual.put("a DATE value", verdict("AtLeastOneDateRangeOverlaps(Season In IssuedOn)"));
        actual.put("a year-interpreting SUBJECT",
                verdict("AtLeastOneDateRangeOverlaps(RollsForward In Window)"));
        actual.put("a year-interpreting VALUE", verdict("AtLeastOneDateRangeOverlaps(Season In RollsBack)"));
        actual.put("a year-less value against a dated subject",
                verdict("AtLeastOneDateRangeOverlaps(Season In Yearless)"));
        actual.put("two year-less ranges", verdict("AtLeastOneDateRangeOverlaps(Yearless In AlsoYearless)"));
        actual.put("a GROUP of two ranges sharing one format",
                verdict("AtLeastOneDateRangeOverlaps(Season In Terms)"));
        actual.put("a GROUP of two DATED ranges spelled differently",
                verdict("AtLeastOneDateRangeOverlaps(Season In Renewals)"));
        actual.put("the mismatch under a declared BASE YEAR",
                withBaseYear("AtLeastOneDateRangeOverlaps(Season In Yearless)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a range against a range — the control", KernelLaws.ADMITTED);
        // `matchBaseType` runs BEFORE either range question and types the VALUES against the subject, so a
        // wrong kind on EITHER side answers as a base-type disagreement and never reaches the range walk.
        // The next row is what keeps that from reading as "this carrier cannot report MVK_NO_DATE_RANGE":
        // two fields that agree do reach it, and neither being a range is exactly what it then reports.
        expected.put("a text subject", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a text value", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("two texts, which AGREE and so reach the range walk", NOT_A_RANGE);
        expected.put("a DATE value", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a year-interpreting SUBJECT", BAD_RANGE_FORMAT);
        expected.put("a year-interpreting VALUE", BAD_RANGE_FORMAT);
        expected.put("a year-less value against a dated subject", MIXED_YEAR);
        expected.put("two year-less ranges", KernelLaws.ADMITTED);
        expected.put("a GROUP of two ranges sharing one format", KernelLaws.ADMITTED);
        // The agreement walk takes each REFERENCE's distinct FORMATS and only then asks which carry a year,
        // so a reference expanding to two differently-spelled formats fails it even when both carry one —
        // a list this operator's own law would admit, refused on the spelling. Measured, not reasoned.
        expected.put("a GROUP of two DATED ranges spelled differently", MIXED_YEAR);
        expected.put("the mismatch under a declared BASE YEAR", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the value-list carrier asks the same two questions");
    }
}
