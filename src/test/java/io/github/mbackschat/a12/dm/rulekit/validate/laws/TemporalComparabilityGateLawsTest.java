package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-SEMANTICS §5 — the two DISTINCT temporal admission gates (SPEC-2026-07-22-01):
 *
 * <ul>
 *   <li><b>Direct comparison</b> is <em>coarse</em> ({@code CheckVergleichsBedingungImpl.checkDateType} →
 *       {@code DateFormat.formatsConsistentRegardingFormat}): after optional Base-Year supplementation it
 *       needs only matching YEAR presence and matching date/no-date class; {@code ==}/{@code !=} additionally
 *       need matching time/no-time class. No direct operator requires identical component sets — so a
 *       {@code yyyy-MM} fragment and a {@code yyyy-MM-dd} date DO compare.</li>
 *   <li><b>Temporal extrema admission</b> is <em>exact</em> ({@code DateFormat.hasSameComponents} via
 *       {@code CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType}): a multi-operand
 *       {@code Min}/{@code Max}/{@code MinValue}/{@code MaxValue} needs identical component sets, so the SAME
 *       {@code yyyy-MM} + {@code yyyy-MM-dd} pair is rejected.</li>
 * </ul>
 *
 * a12-dmkits delegates both gates to the kernel; this locks the accept/reject contract that separates them.
 * Neighbor: {@link DateFragmentComparabilityLawsTest} (the year-presence partition).
 */
class TemporalComparabilityGateLawsTest {

    /** A field of every temporal shape, plus a repeatable group, in one own-domain scheduling model. */
    private static String model(Integer baseYear) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("temporal-gates").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return b.rootGroup("Doc", d -> d
                        .dateFragmentField("MonthYear", f -> f.fragmentFormat("yyyy-MM"))
                        .dateFragmentField("MonthOnly", f -> f.fragmentFormat("MM"))
                        .dateField("FullDate", dd -> dd.format("yyyy-MM-dd"))
                        .dateTimeField("Stamp", dt -> dt.format("yyyy-MM-dd'T'HH:mm:ss"))
                        .timeField("Clock", t -> t.format("HH:mm:ss")))
                .build().toJson();
    }

    private static final String NO_BASE_YEAR = model(null);
    private static final String WITH_BASE_YEAR = model(2000);

    // ── the headline: the SAME yyyy-MM + yyyy-MM-dd pair — legal DIRECT, illegal as EXTREMUM operands ──────
    @Test
    void directComparisonAcceptsMismatchedComponentSets() {
        KernelLaws.acceptsModel(NO_BASE_YEAR, "/Doc", "../FullDate",
                "AllFieldsFilled(MonthYear, FullDate) And [MonthYear] == [FullDate]");
    }

    @Test
    void temporalExtremumRejectsMismatchedComponentSets() {
        KernelLaws.rejectsModel(NO_BASE_YEAR, "/Doc", "../FullDate",
                "AllFieldsFilled(MonthYear, FullDate) And [FullDate] != Min([MonthYear], [FullDate])",
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    // ── date vs datetime: ordering ignores time-presence, equality requires it ─────────────────────────────
    @Test
    void dateVsDateTimeOrderingIsAccepted() {
        KernelLaws.acceptsModel(NO_BASE_YEAR, "/Doc", "../FullDate",
                "AllFieldsFilled(FullDate, Stamp) And [FullDate] < [Stamp]");
    }

    @Test
    void dateVsDateTimeEqualityIsRejected() {
        KernelLaws.rejectsModel(NO_BASE_YEAR, "/Doc", "../FullDate",
                "AllFieldsFilled(FullDate, Stamp) And [FullDate] == [Stamp]",
                "MVK_INVALID_COMPARE_TO_DATE");
    }

    // ── date vs time: mismatched date/no-date class → rejected in either direction ─────────────────────────
    @Test
    void dateVsTimeComparisonIsRejected() {
        KernelLaws.rejectsModel(NO_BASE_YEAR, "/Doc", "../FullDate",
                "AllFieldsFilled(FullDate, Clock) And [FullDate] == [Clock]",
                "MVK_INVALID_COMPARE_TO_DATE");
    }

    // ── Base-Year supplementation: a year-less fragment gains the model year, matching a year-bearing date ─
    @Test
    void yearlessFragmentVsDateIsRejectedWithoutBaseYear() {
        KernelLaws.rejectsModel(NO_BASE_YEAR, "/Doc", "../FullDate",
                "AllFieldsFilled(MonthOnly, FullDate) And [MonthOnly] == [FullDate]",
                "MVK_INVALID_COMPARE_TO_DATE");
    }

    @Test
    void yearlessFragmentVsDateIsAcceptedWithBaseYear() {
        KernelLaws.acceptsModel(WITH_BASE_YEAR, "/Doc", "../FullDate",
                "AllFieldsFilled(MonthOnly, FullDate) And [MonthOnly] == [FullDate]");
    }
}
