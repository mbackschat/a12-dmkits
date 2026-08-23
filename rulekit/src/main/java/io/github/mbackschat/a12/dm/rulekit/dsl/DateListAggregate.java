package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * A <b>date-like</b> {@code MaxValue(…)} / {@code MinValue(…)} — the latest/earliest DATE, TIME, or DATETIME
 * over an entity list of a date-like kind. The kernel folds all three through one {@code DateCombiner}
 * (KERNEL-FINDINGS §6), reusing the {@code MaxValue}/{@code MinValue} keywords (the numeric variant is
 * {@link ListAggregate}); all ignore unspecified entries (KERNEL-SEMANTICS §9). A {@link Calculation} whose
 * {@link #kind()} the factory names, so wrapping it in the matching operand expression ({@link DateExpr} /
 * {@link TimeExpr} / {@link DateTimeExpr}) makes it a comparable {@link TemporalOperand}. Build via
 * {@link Dsl#latestOf}/{@link Dsl#earliestOf} (DATE), {@link Dsl#latestTimeOf}/{@link Dsl#earliestTimeOf}
 * (TIME), or {@link Dsl#latestDateTimeOf}/{@link Dsl#earliestDateTimeOf} (DATETIME).
 *
 * <p><b>The operand list is a list, exactly as {@link ListAggregate}'s is</b> (widened 2026-08-24 — DG38).
 * It used to hold a single scope, which made {@code MaxValue(FiledOn, ClosedOn)} over two direct DATE fields
 * unauthorable <em>and</em>, the sharper half, unreadable: the adapter's read path fell through to the numeric
 * arm and {@code rule read} returned the rule Opaque with {@code MaxValue takes number fields}. The kernel
 * admits the shape — {@code CheckFeldListenOpImpl} routes a DATETIME-based first operand to
 * {@code checkAllFieldsAreComparableDatetimeType} over the whole list — so the single-operand erasure was a
 * client-side refusal with no measured basis.
 *
 * <p>The gates pre-checked here are the ones {@link ListAggregate} already pre-checks and this family shares
 * (all kernel-measured, {@code validate.laws.TemporalAggregateComponentSetLawsTest}): the param law, the
 * direct-duplicate arm, and each FIELD operand's temporal CLASS. The <b>format</b> gate is deliberately
 * <em>not</em> pre-checked: the kernel compares the operands' declared component SETS after optional Base-Year
 * supplementation ({@code DateFormat.hasSameComponents}), which is model data, and an over-strict copy would
 * refuse the legal equal-set-different-spelling pairs the kernel admits.
 */
public record DateListAggregate(Mode mode, List<AggregateOperand> operands, ValueKind kind) implements Calculation {

    /** The two date aggregations, carrying the catalog operator-id shared with the numeric variant. */
    public enum Mode {
        LATEST("MaxValue"),
        EARLIEST("MinValue");

        private final String operatorId;

        Mode(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public DateListAggregate {
        operands = List.copyOf(operands);
        if (kind != ValueKind.DATE && kind != ValueKind.TIME && kind != ValueKind.DATE_TIME) {
            throw new IllegalArgumentException(mode.operatorId() + " over date-like values needs a DATE, TIME,"
                    + " or DATETIME operand kind; got " + kind + " (MVK_NOT_SORTABLE)");
        }
        if (operands.size() < 2 && operands.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
            throw new IllegalArgumentException(mode.operatorId() + " needs more than one field, a"
                    + " repetition list, or a group scope (MVK_PARAMSIZE_INVALIDN)");
        }
        for (AggregateOperand operand : operands) {
            switch (operand) {
                // The FIELD arm keeps a kind check: a field or field list KNOWS its kind, so a DATE list handed
                // to the TIME factory must fail fast rather than mis-type the aggregate's RESULT operand. It
                // checks the temporal CLASS rather than kind equality, because kind equality is broader than
                // the kernel: `MaxValue(<DATE yyyy>, <DATEFRAGMENT yyyy>)` is admitted (measured), while a DATE
                // beside a TIME or a DATETIME is refused — date-presence and time-presence are what separate
                // the classes, and the remaining component-set agreement is model data the kernel arbitrates.
                case FieldOperand f -> {
                    if (temporalClassOf(f.kind()) != temporalClassOf(kind)) {
                        throw new IllegalArgumentException(mode.operatorId() + " was built for " + kind
                                + " but " + f.renderedPath() + " is " + f.kind());
                    }
                }
                // A GROUP carries no kind of its own — a group's kind lives in its EXPANSION, which
                // `AggregateOperand` delegates to the kernel rather than re-implementing the extent — so its
                // arm takes the factory's declared kind and lets the kernel's own gate judge the expansion
                // (`MVK_NOT_SORTABLE`), exactly as the numeric aggregates already do.
                case GroupListRef ignored -> { }
            }
        }
        ListAggregate.checkNoDuplicates(mode.operatorId(), operands);
    }

    /**
     * The operand's temporal comparability class — what the kernel's gate separates before it compares
     * component sets. DATE and DATE_FRAGMENT share one class (both carry a date and no clock); TIME and
     * DATE_TIME are their own. Anything else has no class and can never match a date-like factory.
     */
    private static ValueKind temporalClassOf(ValueKind kind) {
        return kind == ValueKind.DATE_FRAGMENT ? ValueKind.DATE : kind;
    }

    @Override
    public ValueKind kind() {
        return kind;
    }

    public String operatorId() {
        return mode.operatorId();
    }
}
