package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateTimeRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * Whether a temporal extractor takes a direct LITERAL, measured per family.
 *
 * <p>Discharges a12-kernel-lean's {@code SPEC-2026-09-08-05}: the DateTime PROJECTIONS admit a recognized
 * complete stamp literal while the numeric COMPONENT extractors refuse the same slot as a constant.
 *
 * <p><b>Every literal here is spelled in A12's own four shapes</b> ({@code dd.MM.}, {@code HH:mm:ss},
 * {@code dd.MM.yyyy}, {@code dd.MM.yyyy'T'HH:mm:ss} — KF398's length-keyed table). An ISO spelling is not a
 * near miss but a different question: it fails literal RECOGNITION and draws
 * {@code MVK_INVALID_DATE_STRING} before any family gate, which is how the first run of this grid reported a
 * uniform refusal that said nothing about the boundary under test.
 */
class DateTimeProjectionLiteralOperandLawsTest {

    private static final String GROUP = "/Booking";

    private static final String BOOKINGS = DocumentModelDraft.named("bookings").locale("en_US")
            .rootGroup("Booking", b -> b
                    .stringField("Reference", f -> { })
                    .dateTimeField("ScheduledAt", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .timeField("PickupTime", t -> t.format("HH:mm:ss"))
                    .field("OrderDate", FieldKind.DATE))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(BOOKINGS);

    private static final FieldRef REFERENCE = M.field("/Booking/Reference");
    private static final DateTimeRef SCHEDULED_AT = M.dateTime("/Booking/ScheduledAt");
    private static final DateRef ORDER_DATE = M.date("/Booking/OrderDate");

    /**
     * The text path. Every LITERAL row takes it, and by construction: the kit composes a stamp from typed
     * parts ({@code dateTime(DateOperand, TimeOperand)}) and has no string-literal temporal operand at all,
     * which is exactly the slot this law varies.
     */
    private static String verdict(String condition) {
        return classes(KernelLaws.checkModel(BOOKINGS, GROUP, "../Reference",
                "FieldFilled(Reference) And " + condition));
    }

    /** The typed path, for the two FIELD controls that prove the ladder is live rather than uniformly refusing. */
    private static String verdict(Condition condition) {
        return classes(KernelLaws.checkModel(BOOKINGS, GROUP, "../Reference",
                Dsl.fieldFilled(REFERENCE).and(condition)));
    }

    private static String classes(ValidationResult r) {
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    @Test
    void aProjectionTakesAStampLiteralAndANumericExtractorRefusesIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DateFromDateTime on the field — control", verdict(Dsl.dateFromDateTime(SCHEDULED_AT).after(Dsl.today())));
        actual.put("DateFromDateTime on a complete stamp literal",
                verdict("DateFromDateTime(\"15.01.2026T10:30:00\") > Today"));
        actual.put("TimeFromDateTime on a complete stamp literal",
                verdict("TimeFromDateTime(\"15.01.2026T10:30:00\") != [PickupTime]"));
        actual.put("YearFromDate on the field — control", verdict(Dsl.yearFromDate(ORDER_DATE).lt(2020)));
        actual.put("YearFromDate on a date literal", verdict("YearFromDate(\"15.01.2026\") < 2020"));
        actual.put("YearFromDate on a complete stamp literal",
                verdict("YearFromDate(\"15.01.2026T10:30:00\") < 2020"));
        actual.put("HoursFromTime on a time literal", verdict("HoursFromTime(\"10:30:00\") < 12"));
        actual.put("DateFromDateTime on an unrecognized literal",
                verdict("DateFromDateTime(\"not-a-datetime\") > Today"));
        actual.put("DateFromDateTime on an ISO-spelled stamp — unrecognized, not a near miss",
                verdict("DateFromDateTime(\"2026-01-15T10:30:00\") > Today"));
        actual.put("DateFromDateTime on a recognized but incomplete literal",
                verdict("DateFromDateTime(\"15.01.2026\") > Today"));
        actual.put("TimeFromDateTime on a recognized but incomplete literal",
                verdict("TimeFromDateTime(\"15.01.2026\") != [PickupTime]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DateFromDateTime on the field — control", KernelLaws.ADMITTED);
        // The projections take the literal: this is the boundary the entry is about.
        expected.put("DateFromDateTime on a complete stamp literal", KernelLaws.ADMITTED);
        expected.put("TimeFromDateTime on a complete stamp literal", KernelLaws.ADMITTED);
        expected.put("YearFromDate on the field — control", KernelLaws.ADMITTED);
        // …and the numeric component extractors refuse the same slot as a constant, whichever recognized
        // shape it carries, so the split is by extractor family rather than by the literal's completeness.
        expected.put("YearFromDate on a date literal", "MVK_NO_CONST");
        expected.put("YearFromDate on a complete stamp literal", "MVK_NO_CONST");
        expected.put("HoursFromTime on a time literal", "MVK_NO_CONST");
        // Recognition is asked FIRST, so an unrecognized spelling never reaches either family gate.
        expected.put("DateFromDateTime on an unrecognized literal", "MVK_INVALID_DATE_STRING");
        expected.put("DateFromDateTime on an ISO-spelled stamp — unrecognized, not a near miss",
                "MVK_INVALID_DATE_STRING");
        // Recognized but of the wrong temporal shape for the projection: a third, distinct class.
        expected.put("DateFromDateTime on a recognized but incomplete literal",
                "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("TimeFromDateTime on a recognized but incomplete literal",
                "MVK_WRONG_DATE_FORMAT_FOR_OP");
        assertEquals(expected, actual, "the projection/extractor literal boundary");
    }
}
