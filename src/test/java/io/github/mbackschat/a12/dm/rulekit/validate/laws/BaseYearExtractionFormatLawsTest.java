package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * KERNEL-FINDINGS §6 — does {@code BaseYear} carry a resolvable date FORMAT for the extraction gate?
 *
 * <p>The gate promoted in A3 is format-keyed: an extraction is admitted iff the operand's declared format
 * carries the unit's component ([KF1001](KERNEL-FINDINGS.md#kf1001)). {@code BaseYear} reports kind DATE but
 * has no field path, so nothing resolves a format for it and it takes the delegate branch. Whether that is a
 * MISS or the correct answer depends on what the kernel does, and a client-side refusal may never be wider
 * than its measured basis — so this measures it rather than reasoning from the operand's kind.
 *
 * <p>The reason it needs measuring rather than assuming: our own {@code Dsl.startOfBaseYear} records that the
 * kernel treats {@code BaseYear} as a date RANGE spanning the reference year, which is not the same claim as
 * "its format is {@code yyyy}".
 *
 * <p><b>Measured answer: it carries no format the gate reads, and the delegate branch is CORRECT.</b> A
 * {@code yyyy} FIELD is refused for month, day and quarter; {@code BaseYear} is admitted for all four units in
 * the same model with the same configured base year. So assigning it {@code yyyy} would refuse shapes the
 * kernel accepts — an over-rejection, and one that reaches the inbound converters, where it would make a legal
 * stored model unreadable rather than merely unauthorable.
 */
class BaseYearExtractionFormatLawsTest {

    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    /** A configured base year, or every row would draw {@code MVK_NO_BASE_YEAR} instead. */
    private static final String MODEL = DocumentModelDraft.named("base-year-extraction")
            .locale("en_US").baseYear(2024)
            .rootGroup("Cover", c -> c
                    .field("Ref", FieldKind.STRING)
                    .field("N", FieldKind.NUMBER)
                    .dateField("YearOnly", d -> d.format("yyyy"))
                    .dateField("FullDate", d -> d.format("yyyy-MM-dd")))
            .build().toJson();

    /**
     * The condition always references {@code N}, the error field: without that the kernel reports
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED} on every row and masks the gate under test.
     */
    private static String verdict(String condition) {
        var result = KernelLaws.checkModel(MODEL, "/Cover", "../N",
                condition + " And FieldFilled(N)");
        if (result.ok()) {
            return KernelLaws.ADMITTED;
        }
        return result.diagnostics().stream()
                .map(d -> d.code().orElse("NO_CODE"))
                .distinct()
                .sorted()
                .collect(java.util.stream.Collectors.joining("+"));
    }

    /**
     * The table, and the {@code yyyy} field rows are what make it a measurement rather than an observation:
     * they carry exactly the component set the {@code yyyy} hypothesis assigns to {@code BaseYear}, so the two
     * disagreeing on three of four units is the discriminator. The {@code YearOnly} field rows are the CONTROL: they carry the same component set the
     * report's hypothesis assigns to {@code BaseYear}, so any row where the two disagree is the discriminator
     * between "BaseYear declares {@code yyyy}" and "BaseYear declares nothing the gate can read".
     */
    @Test
    void baseYearsExtractionVerdictsAgainstAYearOnlyFieldControl() {
        var actual = new LinkedHashMap<String, String>();
        for (String unit : new String[] {"Year", "Month", "Day", "Quarter"}) {
            actual.put("BaseYear/" + unit, verdict(unit + "FromDate(BaseYear) > 1"));
            actual.put("yyyy field/" + unit, verdict(unit + "FromDate(YearOnly) > 1"));
        }
        // A full date is the liveness control for the gate itself: every date unit is admitted there.
        for (String unit : new String[] {"Year", "Month", "Day", "Quarter"}) {
            actual.put("yyyy-MM-dd field/" + unit, verdict(unit + "FromDate(FullDate) > 1"));
        }
        // THE MECHANISM DISCRIMINATOR. A date unit cannot separate "reads no format" from "reads a COMPLETE
        // date format" — a complete date admits all four, so both accounts predict every cell above. A
        // TIME-family unit does: a complete DATE lacks the hour, so the second account predicts a refusal for
        // BaseYear exactly where the first predicts a delegation.
        for (String unit : new String[] {"Hours", "Minutes", "Seconds"}) {
            actual.put("BaseYear/" + unit, verdict(unit + "FromTime(BaseYear) > 1"));
            actual.put("yyyy-MM-dd field/" + unit, verdict(unit + "FromTime(FullDate) > 1"));
        }
        // IS THIS BASEYEAR-SPECIFIC? The other two point-in-time constants are the control for that: `Today`
        // is DATE-kind like BaseYear, `Now` is DATE_TIME. If the account is "a constant with no declaration of
        // its own answers its KIND's default", all three follow one rule and none is a special case.
        for (String unit : new String[] {"Year", "Month", "Day", "Quarter"}) {
            actual.put("Today/" + unit, verdict(unit + "FromDate(Today) > 1"));
            actual.put("Now/" + unit, verdict(unit + "FromDate(Now) > 1"));
        }
        for (String unit : new String[] {"Hours", "Minutes", "Seconds"}) {
            actual.put("Today/" + unit, verdict(unit + "FromTime(Today) > 1"));
            actual.put("Now/" + unit, verdict(unit + "FromTime(Now) > 1"));
        }

        // The SPLIT family is the other carrier of the same gate, so the denominator covers its two units too.
        for (String part : new String[] {"Date", "Time"}) {
            actual.put("BaseYear/" + part + "FromDateTime",
                    verdict(part + "FromDateTime(BaseYear) == " + (part.equals("Date") ? "FullDate" : "FullDate")));
            actual.put("yyyy-MM-dd field/" + part + "FromDateTime",
                    verdict(part + "FromDateTime(FullDate) == FullDate"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("BaseYear/Year", KernelLaws.ADMITTED);
        expected.put("yyyy field/Year", KernelLaws.ADMITTED);
        expected.put("BaseYear/Month", KernelLaws.ADMITTED);
        expected.put("yyyy field/Month", WRONG_FORMAT);
        expected.put("BaseYear/Day", KernelLaws.ADMITTED);
        expected.put("yyyy field/Day", WRONG_FORMAT);
        expected.put("BaseYear/Quarter", KernelLaws.ADMITTED);
        expected.put("yyyy field/Quarter", WRONG_FORMAT);
        expected.put("yyyy-MM-dd field/Year", KernelLaws.ADMITTED);
        expected.put("yyyy-MM-dd field/Month", KernelLaws.ADMITTED);
        expected.put("yyyy-MM-dd field/Day", KernelLaws.ADMITTED);
        expected.put("yyyy-MM-dd field/Quarter", KernelLaws.ADMITTED);
        for (String unit : new String[] {"Hours", "Minutes", "Seconds"}) {
            expected.put("BaseYear/" + unit, WRONG_FORMAT);
            expected.put("yyyy-MM-dd field/" + unit, WRONG_FORMAT);
        }
        for (String unit : new String[] {"Year", "Month", "Day", "Quarter"}) {
            expected.put("Today/" + unit, KernelLaws.ADMITTED);
            expected.put("Now/" + unit, KernelLaws.ADMITTED);
        }
        for (String unit : new String[] {"Hours", "Minutes", "Seconds"}) {
            expected.put("Today/" + unit, WRONG_FORMAT);
            expected.put("Now/" + unit, KernelLaws.ADMITTED);
        }
        for (String part : new String[] {"Date", "Time"}) {
            expected.put("BaseYear/" + part + "FromDateTime", WRONG_FORMAT);
            expected.put("yyyy-MM-dd field/" + part + "FromDateTime", WRONG_FORMAT);
        }

        assertEquals(expected, actual);
    }
}
