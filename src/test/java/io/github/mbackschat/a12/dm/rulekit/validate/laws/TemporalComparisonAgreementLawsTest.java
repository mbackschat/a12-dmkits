package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * What a DATE or DATE-RANGE side of a comparison demands of its partner.
 *
 * <p>{@code CheckVergleichsBedingungImpl.checkDateType} and {@code checkDateRangeType} run back to back,
 * ahead of the text, enumeration and number arms, and each answers with a class of its own. The date arm
 * asks three questions of the two FORMATS — do they agree about the year, about carrying any date component
 * at all, and (under equality only) about carrying any time component — and never compares the format
 * strings themselves.
 *
 * <p>Both arms are also where a partner of a wholly different kind lands: a date against a number, a text or
 * a date range is this arm's refusal, not the arm that would otherwise own that partner.
 */
class TemporalComparisonAgreementLawsTest {

    private static final String GROUP = "/Policy";

    private static final String POLICY_JSON = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy", g -> {
                g.stringField("Reference", f -> { });
                g.numberField("Premium", n -> n.fractionDigits(2));
                g.dateField("Start", d -> d.format("dd.MM.yyyy"));
                g.dateField("Finish", d -> d.format("dd.MM.yyyy"));
                g.dateField("Anniversary", d -> d.format("MM-dd"));
                g.dateField("ReportMonth", d -> d.format("yyyy-MM"));
                g.dateTimeField("Stamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"));
                g.timeField("ClockIn", t -> t.format("HH:mm:ss"));
                g.dateRangeField("Window", d -> d.format("dd.MM.yyyy").separator("-"));
                g.dateRangeField("Season", d -> d.format("yyyy-MM-dd").separator("/"));
                g.dateRangeField("MonthWindow", d -> d.format("MM").separator("/"));
                g.dateFragmentField("BirthMonth", d -> d.fragmentFormat("MM-dd"));
            }).build().toJson();

    private static final String DATE = "MVK_INVALID_COMPARE_TO_DATE";

    private static final String RANGE = "MVK_INVALID_COMPARE_TO_DATE_RANGE";

    private static String verdict(String comparison) {
        return KernelLaws.verdictModel(POLICY_JSON, GROUP, "../Reference",
                "FieldFilled(Reference) And " + comparison);
    }

    /** The three questions the date arm asks of two FORMATS. */
    @Test
    void whichFormatsADateComparisonAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two identical formats — the control", verdict("[Start] == [Finish]"));
        actual.put("a year-bearing format against one without", verdict("[Start] == [Anniversary]"));
        actual.put("two year-bearing DATE formats of different precision",
                verdict("[Start] == [ReportMonth]"));
        actual.put("a date against a date-and-TIME", verdict("[Start] == [Stamp]"));
        actual.put("the same two under ORDERING", verdict("[Start] < [Stamp]"));
        actual.put("a date against a pure TIME", verdict("[Start] == [ClockIn]"));
        actual.put("a date against Today", verdict("[Start] == Today"));
        actual.put("a format without a year against Today", verdict("[Anniversary] == Today"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two identical formats — the control", KernelLaws.ADMITTED);
        expected.put("a year-bearing format against one without", DATE);
        // The arm never compares the format STRINGS: day-precision and month-precision agree about the year
        // and about carrying a date at all, which is the whole test. The kernel's own comment calls this
        // condition weaker than it perhaps should be.
        expected.put("two year-bearing DATE formats of different precision", KernelLaws.ADMITTED);
        expected.put("a date against a date-and-TIME", DATE);
        // The time question is asked of equality ALONE, which is the control that attributes the refusal
        // above to the operator rather than to the two formats.
        expected.put("the same two under ORDERING", KernelLaws.ADMITTED);
        expected.put("a date against a pure TIME", DATE);
        expected.put("a date against Today", KernelLaws.ADMITTED);
        expected.put("a format without a year against Today", DATE);
        assertEquals(expected, actual, "which formats a date comparison admits");
    }

    /** A date's partner of another kind lands on the DATE arm, whichever arm would otherwise own it. */
    @Test
    void whatADatePartnerOfAnotherKindDraws() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("against a number", verdict("[Start] == [Premium]"));
        actual.put("against a text", verdict("[Start] == [Reference]"));
        actual.put("against a date RANGE", verdict("[Start] == [Window]"));
        actual.put("against a date FRAGMENT of matching components",
                verdict("[Anniversary] == [BirthMonth]"));
        actual.put("against a date fragment of DIFFERENT components", verdict("[Start] == [BirthMonth]"));
        actual.put("a number on the LEFT of a date", verdict("[Premium] == [Start]"));
        actual.put("a date ORDERED against a number", verdict("[Start] < [Premium]"));
        actual.put("a number ORDERED against a date", verdict("[Premium] < [Start]"));
        actual.put("a number CONSTANT ordered against a date", verdict("1 < [Start]"));
        actual.put("a date ordered against a number constant", verdict("[Start] < 1"));
        actual.put("a date ordered against a text", verdict("[Start] < [Reference]"));
        actual.put("a date within a TOLERANCE of a number",
                verdict("[Premium] DiffersWithToleranceRange10 [Start]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("against a number", DATE);
        expected.put("against a text", DATE);
        expected.put("against a date RANGE", DATE);
        expected.put("against a date FRAGMENT of matching components", KernelLaws.ADMITTED);
        expected.put("against a date fragment of DIFFERENT components", DATE);
        expected.put("a number on the LEFT of a date", DATE);
        // Ordering admits numbers and dates, so both sides clear the operator gate and the date arm still
        // refuses the pair — the one shape where this class answers a non-equality comparison.
        expected.put("a date ORDERED against a number", DATE);
        // The arm reads the PAIR, so the date's side does not matter, and a constant partner is no exception.
        expected.put("a number ORDERED against a date", DATE);
        expected.put("a number CONSTANT ordered against a date", DATE);
        expected.put("a date ordered against a number constant", DATE);
        // …and where the partner is not orderable at all, the operator gate answers instead.
        expected.put("a date ordered against a text", "MVK_INVALID_TYPE_FOR_COMPARISON");
        // The tolerance arm RETURNS once it has refused, so the date arm never runs on this pair.
        expected.put("a date within a TOLERANCE of a number", "MVK_INVALID_TYPE_FOR_TOLERANCE_OP");
        assertEquals(expected, actual, "what a date partner of another kind draws");
    }

    /** The date-range arm: exact component agreement, and the partner test that precedes it. */
    @Test
    void whatADateRangeComparisonDemands() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two ranges of the same COMPONENTS, different format and separator",
                verdict("[Window] == [Season]"));
        actual.put("the same two under inequality", verdict("[Window] != [Season]"));
        actual.put("two ranges of different components", verdict("[Window] == [MonthWindow]"));
        actual.put("a range against a text", verdict("[Window] == [Reference]"));
        actual.put("a range against a number", verdict("[Window] == [Premium]"));
        actual.put("two ranges under ORDERING", verdict("[Window] < [Season]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two ranges of the same COMPONENTS, different format and separator",
                KernelLaws.ADMITTED);
        expected.put("the same two under inequality", KernelLaws.ADMITTED);
        expected.put("two ranges of different components", RANGE);
        expected.put("a range against a text", RANGE);
        expected.put("a range against a number", RANGE);
        // The arm carries an ordering refusal of its own and cannot reach it: the operator gate refuses a
        // non-orderable side first, and a date range is not orderable.
        expected.put("two ranges under ORDERING", "MVK_INVALID_TYPE_FOR_COMPARISON");
        assertEquals(expected, actual, "what a date-range comparison demands");
    }
}
