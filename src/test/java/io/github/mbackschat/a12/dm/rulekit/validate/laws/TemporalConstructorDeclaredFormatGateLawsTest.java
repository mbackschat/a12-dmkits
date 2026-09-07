package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS — <b>{@code KF211}'s rule (the declared FORMAT decides, the kind is never read) reaching the
 * two CONSTRUCTOR operand gates</b>: a {@code DateRange(start, finish)} endpoint and the complete-Year position
 * of {@code Date(day, month, year)}. {@code KF211}'s own scope note excludes both — "rendering, computation
 * targets, and aggregate extrema on such a declaration are untouched here" — and predicts a third instance if
 * one is probed, so these rows are that prediction run rather than a new rule.
 *
 * <p>Reported by a12-kernel-lean as {@code SPEC-2026-09-07-07} (the endpoint) and {@code SPEC-2026-09-07-06}
 * (the Year position); measured here on our own fixture, against the kernel. Their endpoint rows were taken
 * through {@code rule check} and their Year rows through {@code computation add --dry-run}; every row below is
 * a rule condition, so the Year half sits on a <b>different carrier</b> from theirs and is a widening rather
 * than a re-run.
 *
 * <p><b>Both surviving conjuncts get their own necessity row, so an admission is attributed rather than
 * inferred.</b> The declared format is necessary — the same three kinds carrying a clock format are refused at
 * the endpoint, and carrying a complete-date format are refused at Year. The position is necessary — a
 * {@code yyyy} field is refused at Day and at Month. A rival account admitting any temporal field at either
 * gate, or a {@code yyyy} field anywhere in the constructor, fails one of those sets.
 *
 * <p>Own domain (a travel-cover policy).
 */
class TemporalConstructorDeclaredFormatGateLawsTest {

    private static final String GROUP = "/Cover";
    private static final String WRONG_FORMAT = "MVK_WRONG_DATE_FORMAT_FOR_OP";

    /**
     * One model carrying each date-bearing kind twice — once declared {@code yyyy-MM-dd} and once declared
     * {@code yyyy} — plus a DATE declared with a clock format as the diagonal control. Every row below moves
     * exactly one of (declared format, declared kind, operand position) against this one declaration set.
     */
    private static final String COVER = DocumentModelDraft.named("travel-cover").locale("en_US")
            .rootGroup("Cover", c -> c
                    .stringField("Holder", s -> { })
                    .numberField("Excess", n -> n.fractionDigits(0))
                    // --- complete-date declarations, one per date-bearing kind ---
                    .dateField("StartsOn", d -> d.format("yyyy-MM-dd"))
                    .dateField("EndsOn", d -> d.format("yyyy-MM-dd"))
                    .timeField("AuditedOn", t -> t.format("yyyy-MM-dd"))
                    .dateTimeField("FiledOn", d -> d.format("yyyy-MM-dd"))
                    // --- year-only declarations, one per date-bearing kind ---
                    .dateField("SeasonYear", d -> d.format("yyyy"))
                    .timeField("AuditYear", t -> t.format("yyyy"))
                    .dateTimeField("FiledYear", d -> d.format("yyyy"))
                    // --- the diagonal and stamp controls ---
                    .dateField("ShiftAt", d -> d.format("HH:mm:ss"))
                    .dateTimeField("Stamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                    .dateRangeField("CoverWindow", r -> r.format("yyyy-MM-dd").separator("/")))
            .build().toJson();

    private static String verdict(String errorRel, String condition) {
        ValidationResult r = KernelLaws.checkModel(COVER, GROUP, errorRel, condition);
        return r.ok() ? KernelLaws.ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                .collect(Collectors.joining(","));
    }

    /** An endpoint row: the constructed range equalled against the stored window. */
    private static String endpoint(String start, String finish) {
        return verdict("../CoverWindow",
                "DateRange(/Cover/" + start + ", /Cover/" + finish + ") == [/Cover/CoverWindow]");
    }

    /** A Year-position row: the construction compared against a complete date the condition already names. */
    private static String yearPosition(String field) {
        return verdict("../StartsOn",
                "[/Cover/StartsOn] != Date(\"15\", \"6\", /Cover/" + field + ")");
    }

    @Test
    void aDateRangeEndpointReadsTheDeclaredFormatAndNotTheKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE + DATE, both yyyy-MM-dd — control", endpoint("StartsOn", "EndsOn"));
        actual.put("TIME-declared yyyy-MM-dd in the START position", endpoint("AuditedOn", "EndsOn"));
        actual.put("TIME-declared yyyy-MM-dd in the FINISH position", endpoint("StartsOn", "AuditedOn"));
        actual.put("DATE_TIME-declared yyyy-MM-dd in the START position", endpoint("FiledOn", "EndsOn"));
        actual.put("DATE_TIME-declared yyyy-MM-dd in the FINISH position", endpoint("StartsOn", "FiledOn"));
        actual.put("both endpoints cross-kind", endpoint("AuditedOn", "FiledOn"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE + DATE, both yyyy-MM-dd — control", KernelLaws.ADMITTED);
        expected.put("TIME-declared yyyy-MM-dd in the START position", KernelLaws.ADMITTED);
        expected.put("TIME-declared yyyy-MM-dd in the FINISH position", KernelLaws.ADMITTED);
        expected.put("DATE_TIME-declared yyyy-MM-dd in the START position", KernelLaws.ADMITTED);
        expected.put("DATE_TIME-declared yyyy-MM-dd in the FINISH position", KernelLaws.ADMITTED);
        expected.put("both endpoints cross-kind", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the endpoint gate reads the declared format's component set");
    }

    /**
     * The endpoint's necessity rows. Without them the admissions above are equally consistent with a dead
     * gate — and the DATE-declared clock row is the diagonal one, which additionally excludes a
     * "non-DATE kinds are refused" reading.
     */
    @Test
    void anEndpointCarryingNoDateComponentsIsRefusedOnEveryKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE declared HH:mm:ss — the diagonal", endpoint("ShiftAt", "EndsOn"));
        actual.put("DATE_TIME declared with a stamp format", endpoint("Stamp", "EndsOn"));
        actual.put("a NUMBER field — the live control", endpoint("Excess", "EndsOn"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE declared HH:mm:ss — the diagonal", WRONG_FORMAT);
        expected.put("DATE_TIME declared with a stamp format", WRONG_FORMAT);
        expected.put("a NUMBER field — the live control", WRONG_FORMAT);
        assertEquals(expected, actual, "no date component in the declared format refuses, whatever the kind");
    }

    @Test
    void theCompleteYearPositionReadsTheDeclaredFormatAndNotTheKind() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE declared yyyy — control", yearPosition("SeasonYear"));
        actual.put("TIME declared yyyy", yearPosition("AuditYear"));
        actual.put("DATE_TIME declared yyyy", yearPosition("FiledYear"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE declared yyyy — control", KernelLaws.ADMITTED);
        expected.put("TIME declared yyyy", KernelLaws.ADMITTED);
        expected.put("DATE_TIME declared yyyy", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the Year position reads the declared format's component set");
    }

    /**
     * The Year position's two necessity sets, and the LIVENESS row without which both are unreadable. The
     * format rows hold the position fixed and move the declaration; the position rows hold the declaration
     * fixed and move the slot. The all-literal construction is what shows the shape itself is admitted — six
     * refusals with no admission are equally consistent with "this construction is never legal here"
     * (added 2026-09-07, cold review).
     */
    @Test
    void theYearPositionRefusesEveryOtherDeclarationAndTheYearFieldEveryOtherPosition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE declared yyyy-MM-dd at Year", yearPosition("StartsOn"));
        actual.put("TIME declared yyyy-MM-dd at Year", yearPosition("AuditedOn"));
        actual.put("DATE_TIME declared yyyy-MM-dd at Year", yearPosition("FiledOn"));
        actual.put("DATE declared HH:mm:ss at Year", yearPosition("ShiftAt"));
        actual.put("a yyyy field at the DAY position", verdict("../StartsOn",
                "[/Cover/StartsOn] != Date(/Cover/SeasonYear, \"6\", \"2024\")"));
        actual.put("a yyyy field at the MONTH position", verdict("../StartsOn",
                "[/Cover/StartsOn] != Date(\"15\", /Cover/SeasonYear, \"2024\")"));
        actual.put("all three positions as literals — the liveness control", verdict("../StartsOn",
                "[/Cover/StartsOn] != Date(\"15\", \"6\", \"2024\")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE declared yyyy-MM-dd at Year", WRONG_FORMAT);
        expected.put("TIME declared yyyy-MM-dd at Year", WRONG_FORMAT);
        expected.put("DATE_TIME declared yyyy-MM-dd at Year", WRONG_FORMAT);
        expected.put("DATE declared HH:mm:ss at Year", WRONG_FORMAT);
        expected.put("a yyyy field at the DAY position", WRONG_FORMAT);
        expected.put("a yyyy field at the MONTH position", WRONG_FORMAT);
        expected.put("all three positions as literals — the liveness control", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "both conjuncts are necessary: the format AND the position");
    }

    /**
     * The client mirror. {@code DateRangeConstruction} gated its endpoints on {@code kind == DATE ||
     * DATE_FRAGMENT}, so it refused every admission above — and its own javadoc called both of its refusals
     * measured, which is the shape the "a client-side refusal needs a measured kernel basis" rule exists to
     * catch. The kind check now stops at the date-bearing kinds, leaving the declared-format conjunct to the
     * kernel; the three measured refusals below still fire, so the widening is not a removal.
     */
    @Test
    void theTypedSurfaceBuildsEveryEndpointTheseRowsAdmitAndStillRefusesTheMeasuredOnes() {
        BoundModel m = BoundModel.ofJson(COVER);
        DslRenderer renderer = new DslRenderer();

        assertEquals("DateRange(/Cover/AuditedOn, /Cover/EndsOn)", renderer.render(
                Dsl.dateRange(m.date("/Cover/AuditedOn"), m.date("/Cover/EndsOn"))),
                "a TIME field declared yyyy-MM-dd is an admitted endpoint");
        assertEquals("DateRange(/Cover/StartsOn, /Cover/FiledOn)", renderer.render(
                Dsl.dateRange(m.date("/Cover/StartsOn"), m.date("/Cover/FiledOn"))),
                "a DATE_TIME field declared yyyy-MM-dd is an admitted endpoint");
        assertEquals("DateRange(/Cover/AuditedOn, /Cover/FiledOn)", renderer.render(
                Dsl.dateRange(m.date("/Cover/AuditedOn"), m.date("/Cover/FiledOn"))),
                "both endpoints cross-kind");

        // The refusals this file measured stay refused — a DateRef over a non-date FieldRef is the only way to
        // reach the check, since BoundModel.date refuses these paths outright.
        assertThrows(IllegalArgumentException.class, () -> Dsl.dateRange(
                new DateRef(new FieldRef("/Cover/Excess", ValueKind.NUMBER)), m.date("/Cover/EndsOn")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.dateRange(
                new DateRef(new FieldRef("/Cover/Holder", ValueKind.STRING)), m.date("/Cover/EndsOn")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.dateRange(
                new DateRef(new FieldRef("/Cover/CoverWindow", ValueKind.DATE_RANGE)), m.date("/Cover/EndsOn")));
    }
}
