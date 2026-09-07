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
 * {@code MVK_WRONG_DATE_FORMAT_FOR_OP}/{@code MVK_NO_DIGIT_PATTERN}) — that model-dependent gate is the
 * kernel's call (F4/N7), not pre-checked here.
 */
public record DateConstruction(Calculation day, Calculation month, Calculation year1,
        Optional<Calculation> year2) implements Calculation {

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
