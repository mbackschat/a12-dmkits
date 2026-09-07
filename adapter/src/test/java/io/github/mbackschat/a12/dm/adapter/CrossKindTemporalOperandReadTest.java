package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The READ half of {@code KF211} at the operand gates a temporal field's declared FORMAT decides — the half
 * that makes a legal model <b>unreadable</b> rather than merely unauthorable.
 *
 * <p>{@code KF211} widened the authoring surface: {@code BoundModel.date} admits any temporal field whose
 * declared format is date-bearing, so a TIME field declared {@code yyyy-MM-dd} yields a {@code DateRef}. The
 * read direction was left keyed on the declared KIND, at two sites, and each of them turns a kernel-admitted
 * condition into an {@code Opaque} rule: {@code ConditionConverter.dateRangeEndpoint} switches on
 * {@code ValueKind} and refuses TIME and DATE_TIME outright, and the direct-comparison dispatch sends a TIME
 * field to {@code model.time(...)} whatever its declaration says, so the {@code TimeRef} it builds cannot be
 * compared against the date it was authored against.
 *
 * <p><b>Every row is gated by the kernel before it is read.</b> {@link EditSession#addRule} runs the model
 * through {@code checkConsistency}, so a row that reaches the assertion is one the kernel accepted — which is
 * what makes an {@code Opaque} verdict a defect here rather than a legitimate refusal. The static admissions
 * themselves are owned by {@code rulekit.validate.laws.TemporalConstructorDeclaredFormatGateLawsTest} (the
 * endpoint) and {@code adapter.laws.CrossKindTemporalComparisonGateDiffTest} (the comparison).
 *
 * <p>Own domain (a travel-cover policy).
 */
class CrossKindTemporalOperandReadTest {

    /**
     * One model in which each date-bearing kind carries a complete-date format, so kind and declaration
     * disagree on two of the three. {@code ShiftAt} is the diagonal — a DATE declared with a clock format —
     * which keeps "the format decides" separable from "DATE is the permissive kind".
     */
    private static String cover() {
        return DocumentModelDraft.named("travel-cover").locale("en_US")
                .rootGroup("Cover", c -> c
                        .stringField("Holder", s -> { })
                        .dateField("StartsOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("EndsOn", d -> d.format("yyyy-MM-dd"))
                        .timeField("AuditedOn", t -> t.format("yyyy-MM-dd"))
                        .dateTimeField("FiledOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("ShiftAt", d -> d.format("HH:mm:ss"))
                        .timeField("ShiftFrom", t -> t.format("HH:mm:ss"))
                        .dateRangeField("CoverWindow", r -> r.format("yyyy-MM-dd").separator("/")))
                .build().toJson();
    }

    /** Author from raw DSL text — the whole point is a condition the typed surface could not build. */
    private static ReadResult readRule(String name, String errorRel, String condition) {
        EditSession session = EditSession.read(cover());
        session.addRule("/Cover", name, errorRel, "PROBE", condition, "ERROR",
                List.of(en("The cover window disagrees with the recorded dates.")));
        return KernelAdapter.of(BoundModel.ofJson(session.toJson())).read("/Cover/" + name);
    }

    /** {@code Converted} or the reason the read gave up, so a failure names its own cause. */
    private static String verdict(String name, String errorRel, String condition) {
        return switch (readRule(name, errorRel, condition)) {
            case ReadResult.Converted c -> "CONVERTED";
            case ReadResult.Opaque o -> "OPAQUE: " + o.reason();
        };
    }

    @Test
    void aCrossKindDateRangeEndpointReadsBack() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE + DATE — control", verdict("PlainRange", "../CoverWindow",
                "DateRange(/Cover/StartsOn, /Cover/EndsOn) == [/Cover/CoverWindow]"));
        actual.put("TIME-declared yyyy-MM-dd as the START", verdict("TimeStartRange", "../CoverWindow",
                "DateRange(/Cover/AuditedOn, /Cover/EndsOn) == [/Cover/CoverWindow]"));
        actual.put("TIME-declared yyyy-MM-dd as the FINISH", verdict("TimeFinishRange", "../CoverWindow",
                "DateRange(/Cover/StartsOn, /Cover/AuditedOn) == [/Cover/CoverWindow]"));
        actual.put("DATE_TIME-declared yyyy-MM-dd as the START", verdict("StampStartRange", "../CoverWindow",
                "DateRange(/Cover/FiledOn, /Cover/EndsOn) == [/Cover/CoverWindow]"));
        actual.put("both endpoints cross-kind", verdict("BothRange", "../CoverWindow",
                "DateRange(/Cover/AuditedOn, /Cover/FiledOn) == [/Cover/CoverWindow]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE + DATE — control", "CONVERTED");
        expected.put("TIME-declared yyyy-MM-dd as the START", "CONVERTED");
        expected.put("TIME-declared yyyy-MM-dd as the FINISH", "CONVERTED");
        expected.put("DATE_TIME-declared yyyy-MM-dd as the START", "CONVERTED");
        expected.put("both endpoints cross-kind", "CONVERTED");
        assertEquals(expected, actual, "a kernel-admitted endpoint must be readable, whatever the declared kind");
    }

    /**
     * The sibling site the same sweep turned up: the direct-comparison dispatch. {@code KF211}'s own read-gate
     * table records {@code [TimeAsDate] == [DateAsDate]} as kernel-ADMITTED, and its mirror is measured in
     * {@code CrossKindTemporalComparisonGateDiffTest}; neither ever asked whether the adapter can read one.
     */
    @Test
    void aCrossKindTemporalComparisonReadsBack() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE == DATE — control", verdict("PlainCmp", "../StartsOn",
                "[/Cover/StartsOn] == [/Cover/EndsOn]"));
        actual.put("TIME-declared yyyy-MM-dd == DATE", verdict("TimeAsDateCmp", "../StartsOn",
                "[/Cover/AuditedOn] == [/Cover/StartsOn]"));
        actual.put("DATE_TIME-declared yyyy-MM-dd == DATE", verdict("StampAsDateCmp", "../StartsOn",
                "[/Cover/FiledOn] == [/Cover/StartsOn]"));
        actual.put("DATE-declared HH:mm:ss == TIME — the diagonal", verdict("DateAsTimeCmp", "../ShiftFrom",
                "[/Cover/ShiftAt] == [/Cover/ShiftFrom]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE == DATE — control", "CONVERTED");
        expected.put("TIME-declared yyyy-MM-dd == DATE", "CONVERTED");
        expected.put("DATE_TIME-declared yyyy-MM-dd == DATE", "CONVERTED");
        expected.put("DATE-declared HH:mm:ss == TIME — the diagonal", "CONVERTED");
        assertEquals(expected, actual, "the comparison read must key on the declaration, as the authoring side does");
    }

    /** A read that came back {@code Converted} is only worth asserting if the model was kernel-gated first. */
    @Test
    void theFixtureItselfIsKernelAdmitted() {
        EditSession session = EditSession.read(cover());
        session.addRule("/Cover", "Gated", "../CoverWindow", "PROBE",
                "DateRange(/Cover/AuditedOn, /Cover/FiledOn) == [/Cover/CoverWindow]", "ERROR",
                List.of(en("Gate check.")));
        assertTrue(session.toJson().contains("Gated"),
                "EditSession.addRule runs checkConsistency, so reaching here is the kernel's admission");
    }
}
