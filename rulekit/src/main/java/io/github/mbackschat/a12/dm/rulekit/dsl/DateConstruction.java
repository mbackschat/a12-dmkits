package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code Date(day, month, year)} / {@code Date(day, month, century, decade)} — the kernel's date
 * construction from temporal components (its {@code IDateConstructionOperation}: {@code day}, {@code month},
 * {@code year1} = full year or century, optional {@code year2} = 2-digit decade). A {@link Calculation}
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
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

public record DateConstruction(Calculation day, Calculation month, Calculation year1,
        Optional<Calculation> year2) implements Calculation {

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
        checkComponent(day, DateExtraction.Unit.DAY, false);
        checkComponent(month, DateExtraction.Unit.MONTH, false);
        checkComponent(year1, DateExtraction.Unit.YEAR, year2.isPresent());
        year2.ifPresent(v -> checkComponent(v, null, true));
    }

    private static void checkComponent(Calculation component, DateExtraction.Unit expected, boolean fourPart) {
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
    public DateConstruction(Calculation day, Calculation month, Calculation year1) {
        this(day, month, year1, Optional.empty());
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }

    public String operatorId() {
        return "Date";
    }
}
