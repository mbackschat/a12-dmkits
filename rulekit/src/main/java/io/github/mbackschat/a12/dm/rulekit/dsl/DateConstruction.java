package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;
import java.util.stream.Stream;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

/**
 * {@code Date(day, month)} / {@code Date(day, month, year)} / {@code Date(day, month, century, decade)} — the
 * kernel's date construction from temporal components (its {@code IDateConstructionOperation}: {@code day},
 * {@code month}, {@code year1} = full year or century, optional {@code year2} = 2-digit decade). The year part
 * is a closed {@link DateYear}: absent, so the model's Base Year applies (legal only when the model declares
 * one, else {@code MVK_MISSING_YEAR}, KF403), complete, or split. A {@link Calculation}
 * of {@link ValueKind#DATE}; wrap in a {@link DateExpr} to compare/shift it. Components render in
 * argument position — a field bare, a literal as its value, an extraction as its call.
 *
 * <p>The kernel requires each component declaration to be position-compatible (bounded Number, exact
 * digit-pattern String, or the complete year's exact {@code yyyy} declaration — on any date-bearing kind,
 * because that gate reads the format rather than the kind ({@code KF271}); §6:
 * {@code MVK_WRONG_DATE_FORMAT_FOR_OP}/{@code MVK_NO_DIGIT_PATTERN}).
 *
 * <p>The NUMBER-bounds conjunct IS pre-checked, though not here: a field's bounds are model data, so it lives
 * on {@link BoundModel#dateOf}, which refuses a NUMBER component whose declared bounds are not its SLOT's
 * (locked by {@code dsl.DateConstructionSlotBoundsGateTest}). The other two conjuncts stay the kernel's call
 * (F4/N7) for want of a measurement: a String component's exact digit pattern, and the year slot's
 * {@code yyyy} Date exception.
 */
public record DateConstruction(Calculation day, Calculation month, DateYear year) implements Calculation {

    /** The year part of a construction; a split year without a century cannot be expressed. */
    public sealed interface DateYear permits BaseYearImplied, FullYear, SplitYear {
        /** The written year components, in argument order. */
        List<Calculation> components();
    }

    /** No year argument: the model's Base Year supplies it. */
    public record BaseYearImplied() implements DateYear {
        @Override
        public List<Calculation> components() {
            return List.of();
        }
    }

    /** The year given in full. */
    public record FullYear(Calculation year) implements DateYear {
        @Override
        public List<Calculation> components() {
            return List.of(year);
        }
    }

    /** The four-component form: century, then 2-digit decade. */
    public record SplitYear(Calculation century, Calculation decade) implements DateYear {
        @Override
        public List<Calculation> components() {
            return List.of(century, decade);
        }
    }

    /**
     * An extractor component must be admissible in a Date construction AND sit at its own position. The two
     * refusals partition differently and the kernel reports them under different codes
     * ({@code validate.laws.TemporalExtractorComponentLawsTest}): a DATE-family extractor at the wrong
     * position is {@code MVK_INVALID_DATE_OR_TIME_EXTRACT_OP}, while one no Date position accepts at all —
     * {@code QuarterFromDate}, or any TIME-family extractor — is
     * {@code MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED}. The four-component form's own code covers its two
     * YEAR positions — century and decade, the ones the form splits — while its day and month slots keep the
     * ordinary position and family rules, so a matching extractor there is admitted.
     *
     * <p>Intrinsic: which extractor sits in which slot is visible from the operands, so this belongs here
     * rather than on the model-bound facade (API-FINDINGS AF153).
     */
    public DateConstruction {
        Stream.concat(Stream.of(day, month), year.components().stream())
                .forEach(component -> TemporalComponentGrammar.refuseOutsideGrammar("Date", component));
        checkComponent(day, DateExtraction.Unit.DAY, false);
        checkComponent(month, DateExtraction.Unit.MONTH, false);
        switch (year) {
            case BaseYearImplied implied -> { }
            case FullYear full -> checkComponent(full.year(), DateExtraction.Unit.YEAR, false);
            case SplitYear split -> {
                checkComponent(split.century(), DateExtraction.Unit.YEAR, true);
                checkComponent(split.decade(), null, true);
            }
        }
    }

    private static void checkComponent(Calculation component, DateExtraction.Unit expected, boolean fourPart) {
        if (component instanceof DateTimePart split) {
            throw fourPart
                    ? Refusals.of(KernelCode.MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS,
                            "the four-component Date form admits no extractor in its century or decade component; got "
                                    + split.unit().operatorId())
                    : Refusals.of(KernelCode.MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED,
                            "a Date construction admits no " + split.unit().operatorId() + " in any position");
        }
        DateExtraction extraction = unwrapExtraction(component);
        if (extraction == null) {
            return;
        }
        if (fourPart) {
            throw Refusals.of(KernelCode.MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS,
                    "the four-component Date form admits no extractor in its "
                            + "century or decade component; got " + extraction.unit());
        }
        if (!DATE_POSITIONS.contains(extraction.unit())) {
            throw Refusals.of(KernelCode.MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED,
                    "a Date construction does not admit a "
                            + extraction.unit() + " extractor in any position ");
        }
        if (extraction.unit() != expected) {
            throw Refusals.of(KernelCode.MVK_INVALID_DATE_OR_TIME_EXTRACT_OP,
                    "a " + extraction.unit() + " extractor does not belong in the "
                            + expected + " component of a Date construction");
        }
    }

    /** The units a Date construction admits at all; QUARTER and the time family are outside it. */
    private static final java.util.Set<DateExtraction.Unit> DATE_POSITIONS = java.util.EnumSet.of(
            DateExtraction.Unit.DAY, DateExtraction.Unit.MONTH, DateExtraction.Unit.YEAR);

    private static DateExtraction unwrapExtraction(Calculation component) {
        return component instanceof DateExtraction extraction ? extraction : null;
    }

    /** A three-component date (year given in full). */
    public DateConstruction(Calculation day, Calculation month, Calculation year) {
        this(day, month, new FullYear(year));
    }

    /** Every written component in argument order: day, month, then the year part's. */
    public List<Calculation> components() {
        return Stream.concat(Stream.of(day, month), year.components().stream()).toList();
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }

    public String operatorId() {
        return "Date";
    }
}
