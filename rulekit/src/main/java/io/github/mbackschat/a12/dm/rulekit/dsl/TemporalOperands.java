package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Set;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The exact operand refusal the date/time extraction families can state from a bare {@link Calculation}.
 *
 * <p>A12's temporal domain is keyed by the DECLARED FORMAT rather than by the field's kind, so which of
 * {@code YearFromDate}/{@code HoursFromTime}/{@code DateFromDateTime} a given temporal field answers is not
 * readable off {@link Calculation#kind()} (KERNEL-FINDINGS KF211; API-FINDINGS 130). The kernel law instead
 * establishes one exact refusal denominator: NUMBER, STRING, ENUMERATION, BOOLEAN, CONFIRM, DATE_RANGE and
 * CUSTOM each report {@code MVK_NO_DATE} on both public extraction carriers. This states that table and
 * nothing wider, leaving the choice among the temporal kinds to the kernel's authoring gate. UNKNOWN has no
 * authorable A12 counterpart for that oracle, so it is deliberately left kernel-arbitrated rather than
 * borrowing the measured kinds' authority.
 */
final class TemporalOperands {

    /** The complete owner-backed denominator measured under MVK_NO_DATE on both extraction carriers. */
    private static final Set<ValueKind> MEASURED_NON_POINT_KINDS = Set.of(
            ValueKind.NUMBER, ValueKind.STRING, ValueKind.ENUMERATION, ValueKind.BOOLEAN,
            ValueKind.CONFIRM, ValueKind.DATE_RANGE, ValueKind.CUSTOM);

    private TemporalOperands() {
    }

    /** @throws IllegalArgumentException if {@code source} is in the kernel-measured refusal denominator. */
    static void refuseMeasuredNonPointKind(String operatorId, Calculation source) {
        if (MEASURED_NON_POINT_KINDS.contains(source.kind())) {
            throw new IllegalArgumentException(operatorId + " needs a date or time operand, but the source is "
                    + source.kind() + " (MVK_NO_DATE)");
        }
    }
}
