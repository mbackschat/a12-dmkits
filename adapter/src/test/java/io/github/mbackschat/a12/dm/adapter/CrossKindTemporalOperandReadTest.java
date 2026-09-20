package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.Verification;
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
 * read direction was left keyed on the declared KIND, at <b>three</b> sites, and each of them turns a
 * kernel-admitted condition into an {@code Opaque} rule: {@code ConditionConverter.dateRangeEndpoint} switches
 * on {@code ValueKind} and refuses TIME and DATE_TIME outright; the direct-comparison dispatch sends a TIME
 * field to {@code model.time(...)} whatever its declaration says, so the {@code TimeRef} it builds cannot be
 * compared against the date it was authored against; and {@code temporalExtremumKind} classified an aggregate's
 * operands by kind, so a cross-kind {@code MaxValue} fell through to the numeric arm. The third was found only
 * by a cold review of the fix for the first two (2026-09-07) — a sibling the sweep that fixed them missed while
 * advertising completeness, which is why the aggregate rows below live beside the other two rather than in a
 * file of their own.
 *
 * <p><b>Every row is gated by the kernel before it is read</b>, and by a gate this file proves is live rather
 * than assuming: {@code BoundModel.ofJson} — the bind inside {@link #readRule} — deserializes, expands and runs
 * {@code checkConsistency}, throwing on an ERROR verdict. So a row that reaches its assertion is one the kernel
 * accepted, which is what makes an {@code Opaque} verdict a defect here rather than a legitimate refusal.
 * {@link #theGateThatAdmitsEveryRowIsLive()} is that gate's positive control; note that
 * {@code EditSession.addRule} is <em>not</em> the gate — this file's overload of it performs no consistency
 * check, and reading it as one would have made every row above vacuous. The static admissions themselves are
 * owned by {@code rulekit.validate.laws.TemporalConstructorDeclaredFormatGateLawsTest} (the endpoint),
 * {@code adapter.laws.CrossKindTemporalComparisonGateDiffTest} (the comparison) and
 * {@code rulekit.validate.laws.TemporalAggregateComponentSetLawsTest} (the aggregate).
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
                        // The DATE_TIME class reached from a kind that is not DATE_TIME, and an ordinary
                        // stamp to compare it with — the pair that separates the class from the kind at the
                        // date-time operand site, which no other row here reaches.
                        .dateField("StampAsDate", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateTimeField("ServiceStamp", d -> d.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .dateRangeField("CoverWindow", r -> r.format("yyyy-MM-dd").separator("/")))
                .build().toJson();
    }

    /** Author from raw DSL text — the whole point is a condition the typed surface could not build. */
    private static ReadResult readRule(String name, String errorRel, String condition) {
        return KernelAdapter.of(gatedBind(name, errorRel, condition)).read("/Cover/" + name);
    }

    /** The kernel-gated binding one probe rule is read from — {@code ofJson} is the gate, not {@code addRule}. */
    private static BoundModel gatedBind(String name, String errorRel, String condition) {
        EditSession session = EditSession.read(cover());
        session.addRule("/Cover", name, errorRel, "PROBE", condition, "ERROR",
                List.of(en("The cover window disagrees with the recorded dates.")));
        return BoundModel.ofJson(session.toJson());
    }

    /**
     * {@code Converted} or the reason the read gave up, so a failure names its own cause — and the two ways
     * of giving up stay apart. This file's whole subject is a kernel-admitted operand a rulekit gate might
     * refuse, so collapsing "we don't model it" into "we refuse it" would erase the distinction the rows
     * below are measuring (DEV-API-GAPS DG1001).
     */
    private static String verdict(String name, String errorRel, String condition) {
        return switch (readRule(name, errorRel, condition)) {
            case ReadResult.Converted c -> "CONVERTED";
            case ReadResult.Opaque o -> "OPAQUE: " + o.reason();
            case ReadResult.Refused r -> "REFUSED " + r.code().name() + ": " + r.reason();
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
        // The same two diagonals with the cross-kind field on the RIGHT. The right operand is resolved by a
        // different function from the left (`convertTimeOperand` / `convertDateTimeOperand` rather than the
        // dispatch itself), so a left-only row leaves those two sites unconstrained — measured, by planting
        // `kind()` back at each and watching the mutant survive (2026-09-07).
        actual.put("TIME == a DATE declared HH:mm:ss — the diagonal, RIGHT operand",
                verdict("TimeAsDateRightCmp", "../ShiftFrom", "[/Cover/ShiftFrom] == [/Cover/ShiftAt]"));
        actual.put("DATE_TIME == a DATE declared as a stamp — the date-time site, RIGHT operand",
                verdict("StampRightCmp", "../ServiceStamp", "[/Cover/ServiceStamp] == [/Cover/StampAsDate]"));
        actual.put("a DATE declared as a stamp == DATE_TIME — the same pair, LEFT operand",
                verdict("StampLeftCmp", "../ServiceStamp", "[/Cover/StampAsDate] == [/Cover/ServiceStamp]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE == DATE — control", "CONVERTED");
        expected.put("TIME-declared yyyy-MM-dd == DATE", "CONVERTED");
        expected.put("DATE_TIME-declared yyyy-MM-dd == DATE", "CONVERTED");
        expected.put("DATE-declared HH:mm:ss == TIME — the diagonal", "CONVERTED");
        expected.put("TIME == a DATE declared HH:mm:ss — the diagonal, RIGHT operand", "CONVERTED");
        expected.put("DATE_TIME == a DATE declared as a stamp — the date-time site, RIGHT operand", "CONVERTED");
        expected.put("a DATE declared as a stamp == DATE_TIME — the same pair, LEFT operand", "CONVERTED");
        assertEquals(expected, actual, "the comparison read must key on the declaration, as the authoring side does");
    }

    /**
     * The SHIFT family's own classifier, {@code isDateTimeValue} — reached by nothing above, because it types an
     * {@code AddDays} rather than a field read. {@code AddDays} is temporal-output-polymorphic: over a date it
     * yields a date, over an instant a time-preserving wall-day shift, and which one it is decides what the
     * result may be compared against. Reading the kind made a stamp-declared DATE field's shift a DATE, so its
     * kernel-admitted equality against an ordinary stamp came back {@code Opaque}. This row exists because a
     * mutant planted at that site SURVIVED every other row in this file (2026-09-07).
     */
    @Test
    void aCrossKindTemporalShiftReadsBack() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("AddDays over an ordinary stamp — control", verdict("PlainShift", "../ServiceStamp",
                "AddDays(/Cover/ServiceStamp, 1) == [/Cover/ServiceStamp]"));
        actual.put("AddDays over a DATE declared as a stamp", verdict("StampShift", "../ServiceStamp",
                "AddDays(/Cover/StampAsDate, 1) == [/Cover/ServiceStamp]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("AddDays over an ordinary stamp — control", "CONVERTED");
        expected.put("AddDays over a DATE declared as a stamp", "CONVERTED");
        assertEquals(expected, actual, "the shift's output family follows the operand's declaration");
    }

    /**
     * The THIRD site, and the one the 2026-08-24 sweep missed: an entity-list extremum's operand classifier.
     * {@code temporalExtremumKind} collapsed DATE_FRAGMENT into the DATE class but read that class off the
     * KIND, so a {@code MaxValue} mixing a date-declared TIME field with a DATE — which
     * {@code TemporalAggregateComponentSetLawsTest} measures the kernel ADMITTING — took the numeric arm and
     * came back {@code Opaque}. Both aggregations and both cross-kind declarations are rowed, because the
     * defect was in the shared classifier and a single row cannot show that.
     */
    @Test
    void aCrossKindTemporalExtremumReadsBack() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DATE + DATE — control", verdict("PlainMax", "../StartsOn",
                "MaxValue(/Cover/StartsOn, /Cover/EndsOn) == [/Cover/StartsOn]"));
        actual.put("MaxValue over a TIME-declared yyyy-MM-dd beside a DATE", verdict("TimeMax", "../StartsOn",
                "MaxValue(/Cover/AuditedOn, /Cover/StartsOn) == [/Cover/StartsOn]"));
        actual.put("MinValue over a DATE_TIME-declared yyyy-MM-dd beside a DATE", verdict("StampMin",
                "../StartsOn", "MinValue(/Cover/FiledOn, /Cover/StartsOn) == [/Cover/StartsOn]"));
        actual.put("MaxValue over the two cross-kind declarations together", verdict("BothMax", "../StartsOn",
                "MaxValue(/Cover/AuditedOn, /Cover/FiledOn) == [/Cover/StartsOn]"));
        actual.put("MaxValue over two clock declarations — the TIME arm", verdict("ClockMax", "../ShiftFrom",
                "MaxValue(/Cover/ShiftAt, /Cover/ShiftFrom) == [/Cover/ShiftFrom]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DATE + DATE — control", "CONVERTED");
        expected.put("MaxValue over a TIME-declared yyyy-MM-dd beside a DATE", "CONVERTED");
        expected.put("MinValue over a DATE_TIME-declared yyyy-MM-dd beside a DATE", "CONVERTED");
        expected.put("MaxValue over the two cross-kind declarations together", "CONVERTED");
        expected.put("MaxValue over two clock declarations — the TIME arm", "CONVERTED");
        assertEquals(expected, actual, "the extremum read must classify each operand by its declaration");
    }

    /**
     * <b>The positive control for this file's premise.</b> Every row above is worth asserting only because the
     * bind it reads from refuses a model the kernel rejects — so that refusal is exercised here rather than
     * assumed, and the receipt is checked to be the kernel's rather than a structural-only degrade (which would
     * make the gate silently absent while every row still passed).
     */
    @Test
    void theGateThatAdmitsEveryRowIsLive() {
        assertEquals(Verification.KERNEL_CONFIRMED,
                gatedBind("Gated", "../CoverWindow",
                        "DateRange(/Cover/AuditedOn, /Cover/FiledOn) == [/Cover/CoverWindow]")
                        .consistencyGate().orElseThrow(),
                "an ungated bind would admit every row below regardless of the kernel's verdict");
        // A DateRange endpoint with no date component at all: refused by the kernel, so the bind must throw.
        assertThrows(RuntimeException.class,
                () -> gatedBind("Refused", "../CoverWindow",
                        "DateRange(/Cover/ShiftFrom, /Cover/EndsOn) == [/Cover/CoverWindow]"),
                "the bind is the gate; if this passes, no row in this file measures a kernel admission");
    }
}
