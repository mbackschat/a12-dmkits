package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * KERNEL-FINDINGS §6 — <b>every date operator takes the same DATE-ARGUMENT slot, and that slot has its own
 * gates, asked before the operator's own</b>.
 *
 * <p>{@code CheckDatumAngabeImpl.check} is the shared checker for the grammar's {@code datumAngabe}, which
 * the difference, shift, extraction and construction operators all hand their operands to. It branches on
 * which of the slot's forms was written — a field spec, a string literal, or a nested extraction — and each
 * form has a different question.
 *
 * <p><b>The FIELD form is four gates in a fixed order</b>: no category, a field rather than a group, no
 * wildcard, and the declaration must be a date. The kind gate is the one worth measuring member by member:
 * {@code FieldUtils.isDate} tests {@code IDateType}, and which of A12's other temporal declarations that
 * interface actually covers is not something the name settles.
 *
 * <p>Own domain (an e-commerce order).
 */
class DateArgumentSlotLawsTest {

    private static final String GROUP = "/Order";
    private static final String ERROR_REL = "../Reference";

    private static final String NOT_A_DATE = "MVK_NO_DATE";
    private static final String BAD_STRING = "MVK_INVALID_DATE_STRING";

    private static final String ORDER = DocumentModelDraft.named("order-date-arguments").locale("en_US")
            .rootGroup("Order", order -> order
                    .stringField("Reference", f -> { })
                    .stringField("Notes", f -> { })
                    .numberField("Quantity", f -> f.fractionDigits(0))
                    .booleanField("Expedited", f -> { })
                    .confirmField("Acknowledged", f -> { })
                    .customField("Courier", f -> f.type("CourierCode"))
                    .enumField("Channel", e -> e.value("WEB").value("STORE").category("Band", "X", "Y"))
                    .dateField("OrderedOn", f -> f.format("dd.MM.yyyy"))
                    .dateField("ShippedOn", f -> f.format("dd.MM.yyyy"))
                    .timeField("PickupTime", f -> f.format("HH:mm:ss"))
                    .dateTimeField("ScheduledAt", f -> f.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateFragmentField("BillingMonth", f -> f.fragmentFormat("yyyy-MM"))
                    .dateRangeField("DeliveryWindow", f -> f.format("dd.MM.yyyy").separator("-"))
                    .group("Billing", g -> g.dateField("InvoicedOn", f -> f.format("dd.MM.yyyy")))
                    .group("Lines", g -> g.repeatable(20)
                            .dateField("LineDate", f -> f.format("dd.MM.yyyy"))))
            .build().toJson();

    private static String verdict(String condition) {
        return KernelLaws.verdictModel(ORDER, GROUP, ERROR_REL, "FieldFilled(Reference) And " + condition);
    }

    /** The FIELD form's four gates, and which declarations the kind gate calls a date. */
    @Test
    void whatTheFieldFormOfTheSlotDemands() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two date fields — the control", verdict("DifferenceInDays(OrderedOn, ShippedOn) > 0"));
        actual.put("a group", verdict("DifferenceInDays(Billing, ShippedOn) > 0"));
        actual.put("a starred field", verdict("DifferenceInDays(Lines*/LineDate, ShippedOn) > 0"));
        actual.put("a star on the field, not on its level", verdict("DifferenceInDays(Lines/LineDate*, ShippedOn) > 0"));
        actual.put("a star on a fixed group above the field",
                verdict("DifferenceInDays(Billing*/InvoicedOn, ShippedOn) > 0"));
        actual.put("the same without the star — the twin",
                verdict("DifferenceInDays(Billing/InvoicedOn, ShippedOn) > 0"));
        actual.put("a category access", verdict("DifferenceInDays(Channel -> Band, ShippedOn) > 0"));
        actual.put("a text field", verdict("DifferenceInDays(Notes, ShippedOn) > 0"));
        actual.put("a number field", verdict("DifferenceInDays(Quantity, ShippedOn) > 0"));
        actual.put("a boolean field", verdict("DifferenceInDays(Expedited, ShippedOn) > 0"));
        actual.put("an enumeration field", verdict("DifferenceInDays(Channel, ShippedOn) > 0"));
        actual.put("a confirm field", verdict("DifferenceInDays(Acknowledged, ShippedOn) > 0"));
        actual.put("a CUSTOM-typed field", verdict("DifferenceInDays(Courier, ShippedOn) > 0"));
        actual.put("a TIME field", verdict("DifferenceInDays(PickupTime, ShippedOn) > 0"));
        actual.put("a DATE-TIME field", verdict("DifferenceInDays(ScheduledAt, ShippedOn) > 0"));
        actual.put("a DATE-FRAGMENT field", verdict("DifferenceInDays(BillingMonth, ShippedOn) > 0"));
        actual.put("a DATE-RANGE field", verdict("DifferenceInDays(DeliveryWindow, ShippedOn) > 0"));
        actual.put("the defect in the SECOND slot rather than the first",
                verdict("DifferenceInDays(OrderedOn, Notes) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("two date fields — the control", KernelLaws.ADMITTED);
        expected.put("a group", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a starred field", "MVK_NO_WILDCARDS_ALLOWED");
        // The resolver's wildcard gate answers before the slot is asked anything.
        expected.put("a star on the field, not on its level", "MVK_INVALID_WILDCARD");
        expected.put("a star on a fixed group above the field", "MVK_INVALID_WILDCARD");
        expected.put("the same without the star — the twin", KernelLaws.ADMITTED);
        expected.put("a category access", "MVK_CATEGORY_NOT_ALLOWED");
        expected.put("a text field", NOT_A_DATE);
        expected.put("a number field", NOT_A_DATE);
        expected.put("a boolean field", NOT_A_DATE);
        expected.put("an enumeration field", NOT_A_DATE);
        // Measured rather than transferred from the text row: the custom declaration rides WITH text on two
        // other gates in this estate, so its answer here could not be assumed from either.
        expected.put("a confirm field", NOT_A_DATE);
        expected.put("a CUSTOM-typed field", NOT_A_DATE);
        // The slot's kind gate takes ALL FOUR temporal declarations — `IDateType` covers time, stamp and
        // fragment as well as date — so what refuses the time and the fragment here is the CARRIER's own
        // format gate one layer up, not this one. The date range is the only temporal kind the slot itself
        // refuses, and these four rows are what separate the two layers.
        expected.put("a TIME field", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("a DATE-TIME field", KernelLaws.ADMITTED);
        expected.put("a DATE-FRAGMENT field", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("a DATE-RANGE field", NOT_A_DATE);
        expected.put("the defect in the SECOND slot rather than the first", NOT_A_DATE);
        assertEquals(expected, actual, "what the field form of the date-argument slot demands");
    }

    /** The STRING form: two spellings and nothing else. */
    @Test
    void whatTheStringFormOfTheSlotAccepts() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a full day.month.year", verdict("DifferenceInDays(\"01.02.2024\", ShippedOn) > 0"));
        actual.put("a day.month. with no year, against a dated field",
                verdict("DifferenceInDays(\"01.02.\", ShippedOn) > 0"));
        actual.put("a day.month. with no year, against another of its kind",
                verdict("DifferenceInDays(\"01.02.\", \"03.04.\") > 0"));
        actual.put("a day.month with no trailing dot", verdict("DifferenceInDays(\"01.02\", ShippedOn) > 0"));
        actual.put("a TIME constant", verdict("DifferenceInDays(\"10:30:00\", ShippedOn) > 0"));
        actual.put("a DATE-TIME constant",
                verdict("DifferenceInDays(\"01.02.2024T10:30:00\", ShippedOn) > 0"));
        actual.put("a date-time constant with a SPACE instead of the T",
                verdict("DifferenceInDays(\"01.02.2024 10:30:00\", ShippedOn) > 0"));
        actual.put("the 29th of February with no year",
                verdict("DifferenceInDays(\"29.02.\", \"03.04.\") > 0"));
        actual.put("the 29th of February in a leap year",
                verdict("DifferenceInDays(\"29.02.2024\", ShippedOn) > 0"));
        actual.put("the 29th of February in a NON-leap year",
                verdict("DifferenceInDays(\"29.02.2023\", ShippedOn) > 0"));
        actual.put("an ISO spelling", verdict("DifferenceInDays(\"2024-02-01\", ShippedOn) > 0"));
        actual.put("a day out of range", verdict("DifferenceInDays(\"32.01.2024\", ShippedOn) > 0"));
        actual.put("a month out of range", verdict("DifferenceInDays(\"01.13.2024\", ShippedOn) > 0"));
        actual.put("no date at all", verdict("DifferenceInDays(\"soon\", ShippedOn) > 0"));
        actual.put("an empty string", verdict("DifferenceInDays(\"\", ShippedOn) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a full day.month.year", KernelLaws.ADMITTED);
        // `isStringAValidDate` switches on the string's LENGTH and then matches one pattern, so the slot
        // accepts FOUR shapes rather than the two its own javadoc names — and the year-less one carries a
        // TRAILING DOT, which is why dropping it is refused rather than read as a shorter date.
        // The slot ACCEPTS the year-less spelling and the carrier then refuses the MIXTURE — which is the
        // whole layering in one pair: the second row is the same constant with nothing to disagree with.
        expected.put("a day.month. with no year, against a dated field", "MVK_DATE_WITH_AND_WITHOUT_YEAR");
        expected.put("a day.month. with no year, against another of its kind", KernelLaws.ADMITTED);
        expected.put("a day.month with no trailing dot", BAD_STRING);
        expected.put("a TIME constant", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("a DATE-TIME constant", KernelLaws.ADMITTED);
        // The length switch picks the pattern, so a 19-character stamp spelled with a space reaches the
        // date-time pattern and fails it — one character apart from the row above.
        expected.put("a date-time constant with a SPACE instead of the T", BAD_STRING);
        // The parse behind the pattern is non-lenient, so the calendar decides — and the year-less shape
        // still has a year to decide February with: `SimpleDateFormat`'s epoch default, 1970, which is not
        // a leap one. So the year-less spelling is NOT year-agnostic, which is the row that settles it.
        expected.put("the 29th of February with no year", BAD_STRING);
        expected.put("the 29th of February in a leap year", KernelLaws.ADMITTED);
        expected.put("the 29th of February in a NON-leap year", BAD_STRING);
        expected.put("an ISO spelling", BAD_STRING);
        expected.put("a day out of range", BAD_STRING);
        expected.put("a month out of range", BAD_STRING);
        expected.put("no date at all", BAD_STRING);
        expected.put("an empty string", BAD_STRING);
        assertEquals(expected, actual, "what the string form of the date-argument slot accepts");
    }

    /**
     * The same slot on other carriers, and the NESTED form.
     *
     * <p>The carrier rows are what make this a slot law rather than one operator's: the gates are the same
     * bytes for every operator that takes a date, so a family keyed on the operator would need one table per
     * carrier and would be wrong the moment a new one appeared.
     */
    @Test
    void theSlotIsTheSameOnEveryCarrierThatTakesADate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a group under a SHIFT", verdict("[ShippedOn] > AddDays(Billing, 7)"));
        actual.put("a text under a SHIFT", verdict("[ShippedOn] > AddDays(Notes, 7)"));
        actual.put("a bad date string under a SHIFT", verdict("[ShippedOn] > AddDays(\"soon\", 7)"));
        actual.put("a text under an EXTRACTION", verdict("YearFromDate(Notes) > 2020"));
        actual.put("a group under an EXTRACTION", verdict("YearFromDate(Billing) > 2020"));
        actual.put("a starred field under an EXTRACTION", verdict("YearFromDate(Lines*/LineDate) > 2020"));
        actual.put("a text under another DIFFERENCE", verdict("DifferenceInHours(Notes, ScheduledAt) > 0"));
        actual.put("a text under another SHIFT", verdict("[ShippedOn] > AddMonths(Notes, 3)"));
        actual.put("a text under a CLOCK extraction", verdict("HoursFromTime(Notes) > 9"));
        actual.put("a text under a STAMP extraction", verdict("DateFromDateTime(Notes) > Today"));
        actual.put("a NUMERIC extraction nested in a date slot",
                verdict("DifferenceInDays(YearFromDate(OrderedOn), ShippedOn) > 0"));
        actual.put("a TEMPORAL extraction nested in a date slot",
                verdict("DifferenceInDays(DateFromDateTime(ScheduledAt), ShippedOn) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a group under a SHIFT", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a text under a SHIFT", NOT_A_DATE);
        expected.put("a bad date string under a SHIFT", BAD_STRING);
        expected.put("a text under an EXTRACTION", NOT_A_DATE);
        expected.put("a group under an EXTRACTION", "MVK_NO_GROUPS_ALLOWED");
        expected.put("a starred field under an EXTRACTION", "MVK_NO_WILDCARDS_ALLOWED");
        // The nested form's own gate: an extraction standing in a date slot must itself yield a date, which
        // the component extractors do not — they yield a number.
        // One sibling per grammar family, so the carrier set the checker takes is measured across all four
        // shapes rather than transferred from the three rows above.
        expected.put("a text under another DIFFERENCE", NOT_A_DATE);
        expected.put("a text under another SHIFT", NOT_A_DATE);
        expected.put("a text under a CLOCK extraction", NOT_A_DATE);
        expected.put("a text under a STAMP extraction", NOT_A_DATE);
        expected.put("a NUMERIC extraction nested in a date slot", "MVK_INVALID_DATE_FOR_OP");
        expected.put("a TEMPORAL extraction nested in a date slot", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the slot is the same on every carrier that takes a date");
    }
}
