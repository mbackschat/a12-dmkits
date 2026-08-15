package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §6 — <b>what a TIME-ONLY {@code DATE_TIME} field can actually be used for</b>: the authorable
 * surface, measured rather than assumed.
 *
 * <p><b>Why this file exists.</b> [IF301] fixed the interpreter's reading of a `DateTimeType` declared with the
 * degenerate time-only {@code HH:mm:ss} format, and its differential covers fill, equality and ordering. That
 * left a stated residual: the temporal extractors over such a field, datetime arithmetic on it, and comparison
 * against {@code Now} were untested — and therefore also unclaimed, since parity may not be asserted on rows
 * nobody ran.
 *
 * <p><b>The answer is that the residual surface does not exist, and it is stronger than the prediction.</b> The
 * expectation going in was "what the format carries is usable, what it omits is refused" — so the time extractor
 * and the hour arithmetic would work. They do not. EVERY datetime operator refuses a time-only operand, the time
 * extractor included, because the kernel wants a COMPLETE DateTime for all of them — the same reason
 * {@code TimeFromDateTime} rejects a plain TIME field ({@link TemporalExtractorComponentLawsTest}). So a
 * time-only {@code DATE_TIME} supports presence and same-declared-format comparison and nothing else, which is
 * exactly the surface [IF301]'s differential already covers: its parity claim is complete for what can be
 * authored, not merely for the rows that happened to be run.
 *
 * <p>Three DIFFERENT codes carry the refusals — {@code MVK_WRONG_DATE_FORMAT_FOR_OP} for the extractors,
 * {@code MVK_INVALID_TIME} for the arithmetic, {@code MVK_INVALID_COMPARE_TO_DATE} for {@code Now} — so this is
 * not one blanket gate, and a consumer meets three different correctives.
 *
 * <p><b>The ISO control is what makes each row attributable.</b> Every condition is run twice — once on the
 * time-only field, once on an ISO {@code DATE_TIME} sibling — so a refusal is charged to the DECLARED FORMAT
 * rather than to the operator, the model, or the comparison. Without it, "the extractor is refused" would be
 * equally satisfied by an operator that is simply unavailable here.
 *
 * <p>Oracle: {@code checkConsistency} through {@link KernelLaws}, which is terminal for accept/reject.
 */
class TimeOnlyDateTimeSurfaceLawsTest {

    private static final String GROUP = "/Shift";

    /** One time-only DATE_TIME, one ISO DATE_TIME control, and the sibling kinds each extractor compares to. */
    private static final String MODEL = DocumentModelDraft.named("shift").locale("en_US")
            .rootGroup("Shift", g -> g
                    .stringField("Marker", s -> { })
                    .dateTimeField("ClockedAt", d -> d.format("HH:mm:ss"))
                    .dateTimeField("ClockedOut", d -> d.format("HH:mm:ss"))
                    .dateTimeField("ReceivedAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateTimeField("ShippedAt", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .timeField("ShiftStart", t -> t.format("HH:mm:ss"))
                    .dateField("ShiftDay", d -> d.format("yyyy-MM-dd")))
            .build().toJson();

    /**
     * THE table. Each row is one condition shape applied to the time-only field and to the ISO control, so the
     * two columns together say whether the declared format is what decides.
     */
    @Test
    void theAuthorableSurfaceOfATimeOnlyDateTimeIsDecidedByItsDeclaredFormat() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String[] row : new String[][] {
            {"TimeFromDateTime(X) > [ShiftStart]", "[ShiftStart] < TimeFromDateTime(%s)"},
            {"DateFromDateTime(X) > [ShiftDay]", "[ShiftDay] < DateFromDateTime(%s)"},
            {"HoursFromTime(TimeFromDateTime(X)) == 14", "HoursFromTime(TimeFromDateTime(%s)) == 14"},
            {"YearFromDate(DateFromDateTime(X)) == 2024", "YearFromDate(DateFromDateTime(%s)) == 2024"},
            {"AddHours(X, 1) vs its own field", "[%1$s] < AddHours(%1$s, 1)"},
            {"DifferenceInHours(X, sibling) > 0", "DifferenceInHours(%s, %s) > 0"},
            {"[X] == Now", "[%s] == Now"},
            {"[X] > Now", "[%s] > Now"},
        }) {
            String label = row[0];
            String template = row[1];
            actual.put(label + "  — time-only", verdict(fill(template, "ClockedAt", "ClockedOut")));
            actual.put(label + "  — ISO control", verdict(fill(template, "ReceivedAt", "ShippedAt")));
        }

        // THE VACUITY CONTROL, and it carries the whole table. Every operator row below is a refusal, which a
        // kernel that simply could not use this field at all would satisfy just as well. These two rows show the
        // field IS usable — it is filled, and it compares to a sibling of the same declared format — so what the
        // rows below measure is the OPERATOR surface, not a dead field.
        actual.put("FieldFilled(time-only) — vacuity control", verdict("FieldFilled(ClockedAt)"));
        actual.put("[time-only] == [time-only sibling] — vacuity control", verdict("[ClockedAt] == [ClockedOut]"));

        Map<String, String> expected = new LinkedHashMap<>();
        // MEASURED, and it refutes the prediction this file was written with. I expected "what the format
        // carries is usable, what it omits is refused" — so TimeFromDateTime and the hour arithmetic would work.
        // They do not: EVERY datetime operator refuses a time-only operand, including the time extractor over
        // the part the format does carry. The kernel wants a COMPLETE DateTime for all of them, the same reason
        // TimeFromDateTime rejects a plain TIME field (TemporalExtractorComponentLawsTest). Three DIFFERENT
        // codes, so this is not one blanket gate and a consumer meets three correctives.
        expected.put("TimeFromDateTime(X) > [ShiftStart]  — time-only", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("TimeFromDateTime(X) > [ShiftStart]  — ISO control", "admitted");
        expected.put("DateFromDateTime(X) > [ShiftDay]  — time-only", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("DateFromDateTime(X) > [ShiftDay]  — ISO control", "admitted");
        expected.put("HoursFromTime(TimeFromDateTime(X)) == 14  — time-only", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("HoursFromTime(TimeFromDateTime(X)) == 14  — ISO control", "admitted");
        expected.put("YearFromDate(DateFromDateTime(X)) == 2024  — time-only", "MVK_WRONG_DATE_FORMAT_FOR_OP");
        expected.put("YearFromDate(DateFromDateTime(X)) == 2024  — ISO control", "admitted");
        expected.put("AddHours(X, 1) vs its own field  — time-only", "MVK_INVALID_TIME");
        expected.put("AddHours(X, 1) vs its own field  — ISO control", "admitted");
        expected.put("DifferenceInHours(X, sibling) > 0  — time-only", "MVK_INVALID_TIME");
        expected.put("DifferenceInHours(X, sibling) > 0  — ISO control", "admitted");
        expected.put("[X] == Now  — time-only", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("[X] == Now  — ISO control", "admitted");
        expected.put("[X] > Now  — time-only", "MVK_INVALID_COMPARE_TO_DATE");
        expected.put("[X] > Now  — ISO control", "admitted");
        expected.put("FieldFilled(time-only) — vacuity control", "admitted");
        expected.put("[time-only] == [time-only sibling] — vacuity control", "admitted");
        assertEquals(expected, actual,
                "a time-only DATE_TIME supports presence and same-format comparison and NOTHING else — every "
                        + "datetime operator refuses it while the ISO column admits all of them, so the declared "
                        + "format decides and the refusals are not the field being unusable");
    }

    /** Both field names are always supplied; a template that needs only the first simply ignores the second. */
    private static String fill(String template, String self, String sibling) {
        return template.formatted(self, sibling);
    }

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, GROUP, "../Marker",
                "FieldFilled(Marker) And " + condition);
        return r.ok() ? "admitted" : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
