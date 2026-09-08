package io.github.mbackschat.a12.dm.rulekit.dsl;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * {@code DateRange(start, end)} — the kernel's date-range <b>constructor</b> (its
 * {@code IDateRangeConstructionOperation}): builds a date-range value from a start and an end date.
 * A {@link Calculation} of {@link ValueKind#DATE_RANGE} and a {@link DateRangeOperand} for equality
 * or inequality against a date-range field.
 *
 * <p>It can be assigned to a date-range field or compared with one using {@code ==}/{@code !=}.
 * The overlap predicates and bound extractors still take date-range <em>fields</em>, not nested
 * constructions. Build via {@link Dsl#dateRange}.
 *
 * <p><b>Each endpoint must be a date or date-fragment entity reference</b>, and both refusals below are
 * measured against the kernel rather than assumed — the construction's operand gates in KERNEL-FINDINGS §6,
 * locked by {@code validate.laws.DateRangeConstructionOperandLawsTest}. The kernel grammar admits only an
 * {@code entitySpec} in either slot, so a constant, a shifted date, or a literal is a parse error there;
 * and a non-date field draws {@code MVK_WRONG_DATE_FORMAT_FOR_OP}.
 *
 * <p>Endpoints whose formats carry different date components draw that same code, but the check needs the
 * declarations and so cannot live here — it is {@link BoundModel#dateRangeOf}, locked by
 * {@code validate.laws.DateRangeConstructionEndpointComponentLawsTest}. Three further constraints the kernel
 * enforces are deliberately NOT pre-checked at all, because delegating can only under-report: a date whose
 * {@code datePrecision} is partial, a wildcard operand, and — the one that is context-dependent rather than
 * local — the {@code $} outer-iteration marker, which is legal only where a {@code Having} filter supplies
 * the iteration to correlate with.
 */
public record DateRangeConstruction(Calculation start, Calculation end)
        implements Calculation, DateRangeOperand {

    public DateRangeConstruction {
        start = requireOwnerEntity(start, "start");
        end = requireOwnerEntity(end, "end");
    }

    private static Calculation requireOwnerEntity(Calculation expression, String position) {
        FieldRef field = switch (expression) {
            case FieldValue value -> value.field();
            case IndexedFieldValue value -> value.field();
            default -> throw new IllegalArgumentException("DateRange " + position
                    + " must be a date or date-fragment entity reference");
        };
        if (field.kind() != ValueKind.DATE && field.kind() != ValueKind.DATE_FRAGMENT) {
            throw new IllegalArgumentException("DateRange " + position + " field " + field.path()
                    + " is " + field.kind() + ", expected DATE or DATE_FRAGMENT");
        }
        return expression;
    }

    @Override
    public Calculation expression() {
        return this;
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE_RANGE;
    }

    public String operatorId() {
        return "DateRange";
    }

}
