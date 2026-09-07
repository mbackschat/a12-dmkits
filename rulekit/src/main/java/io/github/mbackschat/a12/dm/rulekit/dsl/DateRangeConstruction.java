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
 * <p><b>Each endpoint must be an entity reference of a date-bearing or date-fragment kind</b>, and both
 * refusals below are measured against the kernel rather than assumed — the construction's operand gates in
 * KERNEL-FINDINGS §6, locked by {@code validate.laws.DateRangeConstructionOperandLawsTest}. The kernel grammar
 * admits only an {@code entitySpec} in either slot, so a constant, a shifted date, or a literal is a parse
 * error there; and a NUMBER, STRING or DATE_RANGE field draws {@code MVK_WRONG_DATE_FORMAT_FOR_OP}.
 *
 * <p><b>What the endpoint gate actually reads is the DECLARED FORMAT, so the kind check stops where the
 * measurement does.</b> A TIME- or DATE_TIME-declared field carrying {@code yyyy-MM-dd} is admitted in either
 * position and in both at once, and the listed DATE_TIME refusal is its stamp format refusing rather than its
 * kind ({@code validate.laws.TemporalConstructorDeclaredFormatGateLawsTest}). A record holding only a
 * {@link FieldRef} cannot see a declaration, so the remaining conjunct is the kernel's — which is the safe
 * direction, since delegating can only under-report. Five further constraints ride there for the same reason:
 * a declared format with no date component, a date whose {@code datePrecision} is partial, a wildcard operand,
 * endpoints whose formats carry different date components, and — the one that is context-dependent rather than
 * local — the {@code $} outer-iteration marker, which is legal only where a {@code Having} filter supplies the
 * iteration to correlate with.
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
        if (!isDateBearingKind(field.kind())) {
            throw new IllegalArgumentException("DateRange " + position + " field " + field.path()
                    + " is " + field.kind() + ", expected a date-bearing or date-fragment field");
        }
        return expression;
    }

    /**
     * The kinds whose declaration <em>can</em> carry date components — every temporal kind except DATE_RANGE,
     * plus DATE_FRAGMENT. Whether a given declaration actually does is the kernel's call, made against the
     * declared format, so this deliberately stops at the kind: gating on DATE alone refused the TIME- and
     * DATE_TIME-declared {@code yyyy-MM-dd} endpoints the kernel admits ({@code KF211}'s rule at this gate,
     * locked by {@code validate.laws.TemporalConstructorDeclaredFormatGateLawsTest}), and a record holding only
     * a {@link FieldRef} cannot see a declaration to do better.
     */
    private static boolean isDateBearingKind(ValueKind kind) {
        return kind == ValueKind.DATE || kind == ValueKind.DATE_FRAGMENT
                || kind == ValueKind.TIME || kind == ValueKind.DATE_TIME;
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
