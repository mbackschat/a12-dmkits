package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * A DATE-kind field declared with a clock format is a time to A12 wherever it is an operand, as KF211 measured for
 * the formal channel: its declared format decides and its kind is never read (KERNEL-FINDINGS KF211; the basis
 * for {@code BoundModel.date} refusing it, DEV-API-GAPS DG132).
 *
 * <p>The rows use it as a date in the two ways a date handle offers, a date comparison and a date extraction, and
 * the controls use it as the time it is declared as, with a true DATE field taking the date rows' place. The same
 * holds for the two siblings the handle factories share their admission with: a TIME field declared as a date is
 * refused as a time, and a DATE_TIME field declared as a date is refused as a date-time. A starred list or a
 * group of such fields is read the same way, so the operand typing of {@code BoundModel.flatten} and
 * {@code firstFilledGroup} rests here too (DEV-API-GAPS DG129).
 */
class ClockDeclaredDateFieldLawsTest {

    private static final String MODEL = DocumentModelDraft.named("visit-clock").locale("en_US")
            .rootGroup("Visit", v -> v
                    .dateField("Arrival", d -> d.format("HH:mm:ss"))
                    .dateField("Day", d -> d.format("yyyy-MM-dd"))
                    .timeField("Opening", t -> t.format("HH:mm:ss"))
                    .timeField("Shift", t -> t.format("yyyy-MM-dd"))
                    .dateTimeField("Booked", t -> t.format("yyyy-MM-dd"))
                    .dateTimeField("Moment", t -> t.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("ClockedAt", t -> t.format("HH:mm:ss")))
            .build().toJson();

    @Test
    void comparedWithADateItIsRefusedAsATime() {
        KernelLaws.rejectsModel(MODEL, "/Visit", "../Arrival",
                "AllFieldsFilled(Arrival, Day) And [Arrival] == [Day]", "MVK_INVALID_COMPARE_TO_DATE");
    }

    @Test
    void aDateExtractionOfItIsRefused() {
        KernelLaws.rejectsModel(MODEL, "/Visit", "../Arrival", "FieldFilled(Arrival) And YearFromDate(Arrival) > 2000");
    }

    @Test
    void asATimeItIsAdmitted() {
        KernelLaws.acceptsModel(MODEL, "/Visit", "../Arrival",
                "AllFieldsFilled(Arrival, Opening) And [Arrival] == [Opening]");
        KernelLaws.acceptsModel(MODEL, "/Visit", "../Arrival", "FieldFilled(Arrival) And HoursFromTime(Arrival) > 8");
    }

    @Test
    void aTimeFieldDeclaredAsADateIsRefusedAsATimeAndAdmittedAsADate() {
        KernelLaws.rejectsModel(MODEL, "/Visit", "../Shift", "FieldFilled(Shift) And HoursFromTime(Shift) > 8");
        KernelLaws.acceptsModel(MODEL, "/Visit", "../Shift", "FieldFilled(Shift) And YearFromDate(Shift) > 2000");
    }

    @Test
    void aDateTimeFieldDeclaredAsADateIsRefusedAsADateTimeAndAdmittedAsADate() {
        KernelLaws.rejectsModel(MODEL, "/Visit", "../Booked",
                "AllFieldsFilled(Booked, Moment) And [Booked] == [Moment]", "MVK_INVALID_COMPARE_TO_DATE");
        KernelLaws.acceptsModel(MODEL, "/Visit", "../Booked",
                "AllFieldsFilled(Booked, Day) And [Booked] == [Day]");
    }

    @Test
    void aDateTimeFieldDeclaredAsAClockIsRefusedAsADateTimeAndAdmittedAsATime() {
        KernelLaws.rejectsModel(MODEL, "/Visit", "../ClockedAt",
                "AllFieldsFilled(ClockedAt, Moment) And [ClockedAt] == [Moment]");
        KernelLaws.rejectsModel(MODEL, "/Visit", "../ClockedAt",
                "FieldFilled(ClockedAt) And YearFromDate(DateFromDateTime(ClockedAt)) > 2000");
        KernelLaws.acceptsModel(MODEL, "/Visit", "../ClockedAt",
                "AllFieldsFilled(ClockedAt, Opening) And [ClockedAt] == [Opening]");
        KernelLaws.acceptsModel(MODEL, "/Visit", "../ClockedAt",
                "FieldFilled(ClockedAt) And HoursFromTime(ClockedAt) > 8");
    }

    /** A list or a group of clock-declared DATE fields, and its TIME-kind twin declared as a date (DG129). */
    private static final String ROUNDS = DocumentModelDraft.named("visit-rounds").locale("en_US")
            .rootGroup("Round", r -> r
                    .dateField("Day", d -> d.format("yyyy-MM-dd"))
                    .timeField("Opening", t -> t.format("HH:mm:ss"))
                    .group("Stops", s -> s.repeatable(4).dateField("ArrivedAt", d -> d.format("HH:mm:ss")))
                    .group("Pause", p -> p.dateField("BreakAt", d -> d.format("HH:mm:ss")))
                    .group("Checks", c -> c.repeatable(4).timeField("CheckedOn", t -> t.format("yyyy-MM-dd")))
                    .group("Audit", a -> a.timeField("AuditedOn", t -> t.format("yyyy-MM-dd"))))
            .build().toJson();

    @Test
    void aListOrGroupOperandIsReadAsItsFieldsDeclaredClass() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FirstFilledValue(Stops*/ArrivedAt) == [Day]", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("FirstFilledValue(Pause) == [Day]", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("FirstFilledValue(Stops*/ArrivedAt) == [Opening]", KernelLaws.ADMITTED);
        expected.put("FirstFilledValue(Pause) == [Opening]", KernelLaws.ADMITTED);
        // the TIME-kind twins declared as dates: refused as times, admitted as dates
        expected.put("FirstFilledValue(Checks*/CheckedOn) == [Opening]", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("FirstFilledValue(Audit) == [Opening]", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("FirstFilledValue(Checks*/CheckedOn) == [Day]", KernelLaws.ADMITTED);
        expected.put("FirstFilledValue(Audit) == [Day]", KernelLaws.ADMITTED);

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> {
            String errorField = condition.substring(condition.lastIndexOf('[') + 1, condition.lastIndexOf(']'));
            actual.put(condition, KernelLaws.verdictModel(ROUNDS, "/Round", "../" + errorField, condition));
        });
        assertEquals(expected, actual);
    }

    @Test
    void aTrueDateFieldTakesTheDateRowsPlace() {
        KernelLaws.acceptsModel(MODEL, "/Visit", "../Day", "FieldFilled(Day) And YearFromDate(Day) > 2000");
    }
}
