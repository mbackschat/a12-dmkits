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
 * constructions. Build via {@link Dsl#dateRange}. Each endpoint must be a full date or date-fragment
 * entity reference; their formats must contain the same date components and no time component. Format
 * compatibility is a model property and remains kernel-validated.
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
