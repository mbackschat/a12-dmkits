package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §6/§9 — <b>two temporal gates on two carriers, under two codes</b>
 * ([KF193], a12-kernel-lean {@code SPEC-2026-08-23-16}).
 *
 * <ul>
 * <li>The <b>entity-list aggregates</b> — {@code MaxValue}, {@code MinValue}, {@code NumberOfDifferentValues} —
 *     compare the operands' declared <em>component SETS</em> after optional Base-Year supplementation
 *     ({@code DateFormat.hasSameComponents(other, hasBaseYear)} via
 *     {@code CheckEntityListenUtils.checkOpTypesAreComparableDatetimeType}), and refuse a mismatch
 *     {@code MVK_DATEFORMATS_NOT_COMPATIBLE}.</li>
 * <li>{@code FieldValuesNotUnique} is the carrier whose temporal gate really is the declared <em>format
 *     string</em> ({@code DateFormat.hasSameKnownFormatStringIfExists} via {@code OperationResultType}), and it
 *     refuses under its own {@code MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED}.</li>
 * </ul>
 *
 * <p><b>Only one kind of row separates those two readings, and it carries this file:</b> a pair whose component
 * sets are EQUAL but whose format spellings DIFFER. This file locks two — {@code yyyy-MM-dd} beside
 * {@code dd.MM.yyyy}, and {@code yyyy-MM} beside {@code yyyyMM} — and each is admitted by the three aggregates
 * and refused by {@code FieldValuesNotUnique} <em>in the same model</em>. <b>Two is not the denominator:</b>
 * the twelve admitted formats yield seven such pairs across three families, and
 * {@link TemporalEqualSetPairDenominatorLawsTest} sweeps all of them over all five carriers
 * ({@code FirstFilledValue} included, which this file excludes below). Every same-format row is satisfied by both readings
 * and every different-SET row is refused by both, so without an equal-set pair the whole file would be
 * vacuous with respect to its own claim. The neighbouring
 * {@link FieldValuesNotUniqueAdmissionLawsTest} owns the format-string carrier's own matrix; this file owns
 * the contrast, so the two gates cannot be collapsed into one by a later edit to either.
 *
 * <p><b>The Base-Year rows are the second half of the aggregate gate</b> and are equally non-derivable: with a
 * declared Base Year the kernel adds YEAR to <em>both</em> operand sets before comparing, so a yearless operand
 * is admitted exactly when the remaining components already agree. {@code MM} joins {@code yyyy-MM} and
 * {@code MM-dd} joins a complete date, while both stay refused against the year-only {@code yyyy} — the
 * control that shows the Base Year supplements rather than disables the gate. The same Base Year does
 * <em>not</em> lift {@code FieldValuesNotUnique}'s refusal, which is what places the supplementation inside the
 * component-set gate rather than in a shared pre-pass.
 *
 * <p><b>A third reading is excluded by the class rows.</b> "Equal component set" is not "equal declared kind"
 * either: a DATE {@code yyyy} beside a DATE_FRAGMENT {@code yyyy} is admitted, while a DATE beside a TIME or a
 * DATETIME is refused. So the gate separates date-presence and time-presence and then compares sets — which is
 * why {@code DateListAggregate}'s own pre-check reads the temporal CLASS, and refusing on kind equality (as it
 * used to) would reject the admitted cross-kind pair.
 *
 * <p>Scope: {@code Sum} takes no part — it refuses a temporal operand outright ({@code MVK_NO_NUMBER}), locked
 * below as the family boundary. {@code FirstFilledValue} routes through a different checker arm
 * ({@code checkAllFieldsMatchingOperationType}) and keeps its own account.
 *
 * <p>Neighbour: {@link TemporalComparabilityGateLawsTest} separates this exact gate from the <em>coarse</em>
 * direct-comparison one; this file separates it from the format-string one.
 */
class TemporalAggregateComponentSetLawsTest {

    private static final String GROUP = "/Claim";

    /**
     * A motor-claims model carrying, for each component set, two fields spelled DIFFERENTLY plus one same-format
     * control: complete dates as {@code yyyy-MM-dd} and {@code dd.MM.yyyy}, year-months as {@code yyyy-MM} and
     * {@code yyyyMM}, and the two yearless formats. {@code Window} is a NON-repeatable group whose expansion is
     * exactly one differently-spelled complete-date pair, so the group arm measures the same gate over an
     * expansion; {@code Visits} is repeatable, for the starred arm.
     */
    private static String claims(Integer baseYear) {
        DocumentModelDraft.Builder b = DocumentModelDraft.named("motor-claims").locale("en_US");
        if (baseYear != null) {
            b.baseYear(baseYear);
        }
        return b.rootGroup("Claim", c -> c
                        .stringField("Reference", s -> { })
                        .dateField("FiledOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("ClosedOn", d -> d.format("dd.MM.yyyy"))
                        .dateField("ReopenedOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("CoverFrom", d -> d.format("yyyy-MM"))
                        .dateField("CoverTo", d -> d.format("yyyyMM"))
                        .dateField("CoverAlt", d -> d.format("yyyy-MM"))
                        .dateField("RenewalMonth", d -> d.format("MM"))
                        .dateField("Anniversary", d -> d.format("MM-dd"))
                        .dateField("UnderwritingYear", d -> d.format("yyyy"))
                        .dateField("UwYearAlt", d -> d.format("yyyy"))
                        .dateFragmentField("ReportingYear", f -> f.fragmentFormat("yyyy"))
                        .dateTimeField("SealedAt", dt -> { })
                        .timeField("ShiftStart", t -> { })
                        .timeField("ShiftEnd", t -> { })
                        .timeField("ShiftAudit", t -> { })
                        .group("Visits", v -> v.repeatable(20)
                                .dateField("VisitOn", d -> d.format("yyyy-MM-dd")))
                        .group("Window", w -> w
                                .dateField("OpenedOn", d -> d.format("yyyy-MM-dd"))
                                .dateField("ShutOn", d -> d.format("dd.MM.yyyy"))))
                .build().toJson();
    }

    private static final String NO_BASE_YEAR = claims(null);
    private static final String WITH_BASE_YEAR = claims(2024);
    private static final BoundModel UNCONFIGURED = BoundModel.ofJson(NO_BASE_YEAR);
    private static final BoundModel CONFIGURED = BoundModel.ofJson(WITH_BASE_YEAR);

    /**
     * Guard the aggregate so the probe rule references its own error field
     * ({@code MVK_ERROR_FIELD_NOT_REFERENCED}) — the same shape the catalog's published examples use.
     */
    private static String guarded(BoundModel m, Condition aggregate) {
        return new DslRenderer().render(Dsl.fieldFilled(m.field(GROUP + "/Reference")).and(aggregate));
    }

    private static void admits(String json, BoundModel m, Condition aggregate) {
        KernelLaws.acceptsModel(json, GROUP, "../Reference", guarded(m, aggregate));
    }

    private static void refuses(String json, BoundModel m, Condition aggregate, String code) {
        KernelLaws.rejectsModel(json, GROUP, "../Reference", guarded(m, aggregate), code);
    }

    /**
     * A guarded condition written out, for the shapes the typed surface refuses client-side. The typed refusal
     * is asserted separately; this is the kernel row that grounds it, which a pre-check must always have
     * ("a client-side refusal needs a measured kernel basis").
     */
    private static void refusesRaw(String json, String aggregate, String code) {
        KernelLaws.rejectsModel(json, GROUP, "../Reference", "FieldFilled(Reference) And " + aggregate, code);
    }

    // ── the separator: EQUAL component set, DIFFERENT format spelling ─────────────────────────────────

    /** {@code yyyy-MM-dd} beside {@code dd.MM.yyyy} — same {YEAR, MONTH, DAY}, different spelling. */
    @Test
    void aLatestOverTwoSpellingsOfOneCompleteDateSetIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.field(GROUP + "/FiledOn"), UNCONFIGURED.field(GROUP + "/ClosedOn"))
                        .eq(UNCONFIGURED.date(GROUP + "/ReopenedOn")));
    }

    /** The same-format control, so the admission above is not "any two complete dates are admitted". */
    @Test
    void aLatestOverOneCompleteDateSpellingIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.field(GROUP + "/FiledOn"), UNCONFIGURED.field(GROUP + "/ReopenedOn"))
                        .eq(UNCONFIGURED.date(GROUP + "/ClosedOn")));
    }

    /** {@code yyyy-MM} beside {@code yyyyMM} — same {YEAR, MONTH}, different spelling. The second pair matters:
     *  one pair alone leaves "the separator only tolerates a reordering" open. */
    @Test
    void anEarliestOverTwoSpellingsOfOneYearMonthSetIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.earliestOf(UNCONFIGURED.field(GROUP + "/CoverFrom"), UNCONFIGURED.field(GROUP + "/CoverTo"))
                        .eq(UNCONFIGURED.date(GROUP + "/CoverAlt")));
    }

    /** The count carrier reads the same gate — so it is the gate, not one operator's quirk. */
    @Test
    void aDifferentValuesCountOverTwoSpellingsOfOneSetIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.numberOfDifferentValues(UNCONFIGURED.field(GROUP + "/FiledOn"),
                        UNCONFIGURED.field(GROUP + "/ClosedOn")).gt(1));
    }

    /** A differing component SET is refused, which is what the gate actually reads. */
    @Test
    void aLatestOverDifferingComponentSetsIsRefused() {
        refuses(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.field(GROUP + "/FiledOn"), UNCONFIGURED.field(GROUP + "/CoverFrom"))
                        .eq(UNCONFIGURED.date(GROUP + "/ReopenedOn")),
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    @Test
    void aDifferentValuesCountOverDifferingComponentSetsIsRefused() {
        refuses(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.numberOfDifferentValues(UNCONFIGURED.field(GROUP + "/FiledOn"),
                        UNCONFIGURED.field(GROUP + "/CoverFrom")).gt(1),
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    // ── the other carrier: FieldValuesNotUnique refuses the SAME two pairs, under its OWN code ────────

    @Test
    void uniquenessRefusesTwoSpellingsOfOneCompleteDateSet() {
        refuses(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.fieldValuesNotUnique(UNCONFIGURED.field(GROUP + "/FiledOn"),
                        UNCONFIGURED.field(GROUP + "/ClosedOn")),
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    @Test
    void uniquenessRefusesTwoSpellingsOfOneYearMonthSet() {
        refuses(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.fieldValuesNotUnique(UNCONFIGURED.field(GROUP + "/CoverFrom"),
                        UNCONFIGURED.field(GROUP + "/CoverTo")),
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    /** …and admits the same-format pair, so the refusals above are the format string and not the kind. */
    @Test
    void uniquenessAdmitsOneCompleteDateSpelling() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.fieldValuesNotUnique(UNCONFIGURED.field(GROUP + "/FiledOn"),
                        UNCONFIGURED.field(GROUP + "/ReopenedOn")));
    }

    // ── the Base Year supplements the YEAR component, inside the aggregate gate ──────────────────────

    /** {@code MM} + {@code yyyy-MM}: refused unconfigured, admitted once a Base Year supplies the year. */
    @Test
    void aYearlessMonthJoinsAYearMonthOnlyWithABaseYear() {
        refuses(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.field(GROUP + "/RenewalMonth"), UNCONFIGURED.field(GROUP + "/CoverFrom"))
                        .eq(UNCONFIGURED.date(GROUP + "/CoverAlt")),
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
        admits(WITH_BASE_YEAR, CONFIGURED,
                Dsl.latestOf(CONFIGURED.field(GROUP + "/RenewalMonth"), CONFIGURED.field(GROUP + "/CoverFrom"))
                        .eq(CONFIGURED.date(GROUP + "/CoverAlt")));
    }

    /** {@code MM-dd} + a complete date: the same lift one component deeper. */
    @Test
    void aYearlessMonthDayJoinsACompleteDateOnlyWithABaseYear() {
        refuses(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.field(GROUP + "/Anniversary"), UNCONFIGURED.field(GROUP + "/FiledOn"))
                        .eq(UNCONFIGURED.date(GROUP + "/ReopenedOn")),
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
        admits(WITH_BASE_YEAR, CONFIGURED,
                Dsl.latestOf(CONFIGURED.field(GROUP + "/Anniversary"), CONFIGURED.field(GROUP + "/FiledOn"))
                        .eq(CONFIGURED.date(GROUP + "/ReopenedOn")));
    }

    /**
     * The control that keeps the lift a SUPPLEMENT and not a disable: against the year-only {@code yyyy} the
     * remaining components still disagree, so a Base Year changes nothing.
     */
    @Test
    void aBaseYearDoesNotJoinAYearlessMonthToAYearOnlyOperand() {
        refuses(WITH_BASE_YEAR, CONFIGURED,
                Dsl.latestOf(CONFIGURED.field(GROUP + "/RenewalMonth"),
                        CONFIGURED.field(GROUP + "/UnderwritingYear")).eq(CONFIGURED.date(GROUP + "/CoverAlt")),
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    /** …nor does it lift a genuinely differing set among two year-bearing operands. */
    @Test
    void aBaseYearDoesNotJoinACompleteDateToAYearMonth() {
        refuses(WITH_BASE_YEAR, CONFIGURED,
                Dsl.latestOf(CONFIGURED.field(GROUP + "/FiledOn"), CONFIGURED.field(GROUP + "/CoverFrom"))
                        .eq(CONFIGURED.date(GROUP + "/ReopenedOn")),
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    /** …and it does not reach the other carrier at all, which is what localizes it to the component-set gate. */
    @Test
    void aBaseYearDoesNotLiftTheUniquenessFormatRefusal() {
        refuses(WITH_BASE_YEAR, CONFIGURED,
                Dsl.fieldValuesNotUnique(CONFIGURED.field(GROUP + "/RenewalMonth"),
                        CONFIGURED.field(GROUP + "/CoverFrom")),
                "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED");
    }

    // ── the same gate at the other two operand shapes ────────────────────────────────────────────────

    /** A direct field beside a STARRED list of the same set. */
    @Test
    void aLatestOverADirectFieldAndAStarredListIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.field(GROUP + "/FiledOn"),
                        UNCONFIGURED.flatten(GROUP + "/Visits*/VisitOn")).eq(UNCONFIGURED.date(GROUP + "/ReopenedOn")));
    }

    /**
     * A non-repeatable GROUP operand whose expansion is the differently-spelled pair — so the gate reads the
     * expansion by component set too, not the group's own (absent) format.
     */
    @Test
    void aLatestOverAGroupExpandingToTwoSpellingsIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.flattenGroup(GROUP + "/Window"))
                        .eq(UNCONFIGURED.date(GROUP + "/ReopenedOn")));
    }

    // ── the class boundary: DATE and DATE_FRAGMENT are ONE class, TIME and DATETIME their own ───────

    /**
     * A DATE {@code yyyy} beside a DATE_FRAGMENT {@code yyyy} is admitted — two <em>kinds</em>, one component
     * set. So the gate is not kind equality either, which is the second reading this file has to exclude, and
     * it is why {@link io.github.mbackschat.a12.dm.rulekit.dsl.DateListAggregate}'s own pre-check reads the
     * temporal CLASS rather than the declared kind.
     */
    @Test
    void aLatestOverADateAndADateFragmentOfOneSetIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestOf(UNCONFIGURED.field(GROUP + "/UnderwritingYear"),
                        UNCONFIGURED.field(GROUP + "/ReportingYear")).eq(UNCONFIGURED.date(GROUP + "/UwYearAlt")));
    }

    /** A DATE beside a TIME crosses the date-presence boundary and is refused. */
    @Test
    void aLatestOverADateAndATimeIsRefused() {
        refusesRaw(NO_BASE_YEAR, "MaxValue(FiledOn, ShiftStart) == [ReopenedOn]",
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    /** …and a DATE beside a DATETIME crosses the time-presence one. Both are the class boundary's controls. */
    @Test
    void aLatestOverADateAndADateTimeIsRefused() {
        refusesRaw(NO_BASE_YEAR, "MaxValue(FiledOn, SealedAt) == [ReopenedOn]",
                "MVK_DATEFORMATS_NOT_COMPATIBLE");
    }

    /** The TIME arm of the same gate, so "the class boundary" is not a DATE-only observation. */
    @Test
    void aLatestOverTwoTimesIsAdmitted() {
        admits(NO_BASE_YEAR, UNCONFIGURED,
                Dsl.latestTimeOf(UNCONFIGURED.field(GROUP + "/ShiftStart"), UNCONFIGURED.field(GROUP + "/ShiftEnd"))
                        .eq(UNCONFIGURED.time(GROUP + "/ShiftAudit")));
    }

    /**
     * The typed surface refuses the two class-crossing shapes above <em>before</em> the kernel sees them, and
     * that is the pre-check's whole justification — so it is asserted beside the kernel rows that ground it.
     * Refusing without such a row is the defect this file's own widening removed.
     */
    @Test
    void theTypedFactoryRefusesAClassCrossingOperand() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dsl.latestOf(UNCONFIGURED.field(GROUP + "/FiledOn"),
                        UNCONFIGURED.field(GROUP + "/ShiftStart")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dsl.latestOf(UNCONFIGURED.field(GROUP + "/FiledOn"),
                        UNCONFIGURED.field(GROUP + "/SealedAt")));
    }

    // ── family boundary: Sum takes no part in this gate ──────────────────────────────────────────────

    /**
     * {@code Sum} never reaches the temporal gate: it refuses a date operand outright with its own code. The
     * kernel row is authored raw because the typed {@code Dsl.sum} pre-checks the same refusal — which is
     * exactly the measured basis that pre-check needs, asserted here beside it rather than assumed.
     */
    @Test
    void sumRefusesTemporalOperandsOutright() {
        refusesRaw(NO_BASE_YEAR, "Sum(FiledOn, ReopenedOn) > 1", "MVK_NO_NUMBER");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> Dsl.sum(UNCONFIGURED.field(GROUP + "/FiledOn"), UNCONFIGURED.field(GROUP + "/ReopenedOn")));
    }
}
