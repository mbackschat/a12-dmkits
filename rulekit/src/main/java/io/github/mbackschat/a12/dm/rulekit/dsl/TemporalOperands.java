package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Set;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The operand rule the date/time extraction families can state from a bare {@link Calculation}.
 *
 * <p>A12's temporal domain is keyed by the DECLARED FORMAT rather than by the field's kind, so which of
 * {@code YearFromDate}/{@code HoursFromTime}/{@code DateFromDateTime} a given temporal field answers is not
 * readable off {@link Calculation#kind()} (KERNEL-FINDINGS KF211; API-FINDINGS 130). One thing is: a source
 * that is not a point in time at all carries no component to extract under any declaration, and the kernel
 * reports {@code MVK_NO_DATE} for it — measured on a string and on a date RANGE in
 * {@code validate.laws.DateExtractionLawsTest}. This states that rule and nothing wider, leaving the choice
 * among the temporal kinds to the kernel's authoring gate.
 */
final class TemporalOperands {

    /** The point-in-time kinds. A {@link ValueKind#DATE_RANGE} is a span, not a point, and is excluded. */
    private static final Set<ValueKind> POINT_IN_TIME =
            Set.of(ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.TIME, ValueKind.DATE_TIME);

    private TemporalOperands() {
    }

    /** @throws IllegalArgumentException if {@code source} is not a point in time, citing the kernel's code. */
    static void requirePointInTime(String operatorId, Calculation source) {
        if (!POINT_IN_TIME.contains(source.kind())) {
            throw new IllegalArgumentException(operatorId + " needs a date or time operand, but the source is "
                    + source.kind() + " (MVK_NO_DATE)");
        }
    }
}
