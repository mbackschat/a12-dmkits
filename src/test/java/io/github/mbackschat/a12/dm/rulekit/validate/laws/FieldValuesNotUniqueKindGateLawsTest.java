package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * What KIND of field {@code FieldValuesNotUnique} admits, and where that gate sits among its neighbours.
 *
 * <p>{@code CheckFeldListenBedingungImpl}'s {@code FELD_WERTE_NICHT_EINDEUTIG} arm runs four gates in a fixed
 * order: the arity one, then {@code checkOnlyStringEnumNumberOrDateWithSameFormat}
 * ({@code MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED}), then {@code checkSameBaseType}
 * ({@code MVK_VARYING_TYPES_NOT_ALLOWED}), then {@code checkAllAreValueValidation}
 * ({@code MVK_INVALID_RAW_TYPE}). Only the second is this file's; the others are measured here solely as the
 * rows that fix its POSITION, since a gate's class means nothing without knowing what out-ranks it.
 *
 * <p><b>The gate asks two different questions under one class, which is why both halves are measured.</b> A
 * field that is neither string-like nor a date is refused outright; and among the DATES, the declared format
 * STRINGS must be equal — not their component sets, which is the neighbouring aggregates' gate
 * ({@link TemporalAggregateComponentSetLawsTest}). The pair {@code yyyy-MM-dd} beside {@code dd.MM.yyyy} is
 * what separates the two readings: one component set, two spellings, admitted there and refused here.
 */
class FieldValuesNotUniqueKindGateLawsTest {

    private static final String GROUP = "/Claim";

    private static final String CLAIMS = DocumentModelDraft.named("motor-claims").locale("en_US")
            .rootGroup("Claim", c -> c
                    .stringField("Reference", s -> { })
                    .stringField("Adjuster", s -> { })
                    .numberField("Excess", n -> n.fractionDigits(2))
                    .numberField("Deductible", n -> n.fractionDigits(2))
                    .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("ClosedOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("SpelledOtherwise", d -> d.format("dd.MM.yyyy"))
                    .dateRangeField("CoverPeriod", r -> r.format("yyyy-MM-dd").separator("/"))
                    .timeField("LoggedAt", t -> t.format("HH:mm:ss"))
                    .dateTimeField("SealedAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy"))
                    .enumField("Peril", e -> e.value("FIRE", v -> v.label("en_US", "Fire"))
                            .value("FLOOD", v -> v.label("en_US", "Flood")))
                    .stringField("Raw", s -> s.lineBreaks().noValueValidation()))
            .build().toJson();

    private static String verdict(String list) {
        return KernelLaws.verdictModel(CLAIMS, GROUP, "../Reference",
                "FieldFilled(Reference) And FieldValuesNotUnique(" + list + ")");
    }

    /** Which kinds the gate admits, and which it refuses under its own class. */
    @Test
    void whichKindsTheListAdmits() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two text fields", verdict("Adjuster, Reference"));
        actual.put("two number fields", verdict("Excess, Deductible"));
        actual.put("two dates spelled alike", verdict("FiledOn, ClosedOn"));
        actual.put("an ENUMERATION beside a text field", verdict("Peril, Adjuster"));
        actual.put("a DATE RANGE beside a text field", verdict("CoverPeriod, Adjuster"));
        actual.put("a TIME beside a text field", verdict("LoggedAt, Adjuster"));
        actual.put("a DATE-TIME beside a text field", verdict("SealedAt, Adjuster"));
        actual.put("a DATE FRAGMENT beside a text field", verdict("ReportingYear, Adjuster"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two text fields", "ADMITTED");
        expected.put("two number fields", "ADMITTED");
        expected.put("two dates spelled alike", "ADMITTED");
        // Everything but the range clears the KIND gate; the base-type gate after it is what refuses them,
        // which is why guessing this set from the class NAME would name the wrong class on a correct
        // rejection — `isDate` accepts the clock and stamp kinds too.
        expected.put("an ENUMERATION beside a text field", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a DATE RANGE beside a text field", "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
        expected.put("a TIME beside a text field", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a DATE-TIME beside a text field", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a DATE FRAGMENT beside a text field", "MVK_VARYING_TYPES_NOT_ALLOWED");
        assertEquals(expected, actual, "which kinds the list admits");
    }

    /**
     * Among the temporal kinds, the declared format STRINGS must agree — not their component sets.
     *
     * <p>The second row decides how wide the format comparison is: a DATE beside a DATE-TIME are two kinds
     * whose formats necessarily differ, and the kind gate runs before the base-type one, so this class is
     * what answers rather than {@code MVK_VARYING_TYPES_NOT_ALLOWED}.
     */
    @Test
    void twoTemporalFieldsSpelledDifferentlyAreRefusedByThisGateAndNotTheNext() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one component set, two spellings", verdict("FiledOn, SpelledOtherwise"));
        actual.put("a date beside a date-time", verdict("FiledOn, SealedAt"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one component set, two spellings", "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
        expected.put("a date beside a date-time", "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
        assertEquals(expected, actual, "two temporal fields spelled differently");
    }

    /** Where the gate sits: what it out-ranks, and what out-ranks it. */
    @Test
    void whereTheKindGateSitsAmongItsNeighbours() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text field beside a number one — the base-type gate after it", verdict("Adjuster, Excess"));
        actual.put("a raw string field — the value-validation gate after it", verdict("Raw, Adjuster"));
        actual.put("one field only — the arity gate before it", verdict("CoverPeriod"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a text field beside a number one — the base-type gate after it", "MVK_VARYING_TYPES_NOT_ALLOWED");
        expected.put("a raw string field — the value-validation gate after it", "MVK_INVALID_RAW_TYPE");
        expected.put("one field only — the arity gate before it", "MVK_PARAMSIZE_INVALIDN");
        assertEquals(expected, actual, "where the kind gate sits");
    }
}
