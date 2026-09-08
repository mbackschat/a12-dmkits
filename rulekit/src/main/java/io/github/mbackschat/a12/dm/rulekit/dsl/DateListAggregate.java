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
 * direct-duplicate arm, and whether each FIELD operand is <em>date-like at all</em> — the routing question
 * that separates this class from {@link ListAggregate}. Everything finer is deliberately left to the kernel,
 * because it is decided by MODEL DATA this record cannot see: the operands' declared component SETS after
 * optional Base-Year supplementation ({@code DateFormat.hasSameComponents}), and — the half that cost a live
 * over-rejection — which temporal CLASS each declaration selects ([KF271]), so a TIME field declared
 * {@code yyyy-MM-dd} is a legal member of a DATE list. An over-strict copy of either refuses lists the kernel
 * admits.
 */
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;

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
            throw Refusals.of(KernelCode.MVK_NOT_SORTABLE,
                    mode.operatorId() + " over date-like values needs a DATE, TIME,"
                            + " or DATETIME operand kind; got " + kind);
        }
        if (operands.size() < 2 && operands.stream().noneMatch(ListAggregate::satisfiesArityAlone)) {
            throw Refusals.of(KernelCode.MVK_PARAMSIZE_INVALIDN, mode.operatorId() + " needs more than one field, a"
                    + " repetition list, or a group scope");
        }
        // Ahead of the kind loop, matching the kernel: its routine checks duplicates before the type
        // gates, so an overlap is answered even when the descendant is also the wrong temporal class.
        DuplicateGate.rejectDuplicates(mode.operatorId(), operands);
        for (AggregateOperand operand : operands) {
            switch (operand) {
                // The FIELD arm asks only whether the operand is date-like AT ALL — the routing question, whose
                // answer picks between this class and the numeric `ListAggregate`, and the only part of it a
                // kind can settle. It deliberately does NOT compare temporal classes: the class the kernel's
                // gate reads is the DECLARED FORMAT's, not the kind's ([KF271]), and a `ValueKind` cannot see a
                // declaration — so a class comparison here refuses lists the kernel admits, measured on
                // `MaxValue(<TIME yyyy-MM-dd>, <DATE yyyy-MM-dd>)` and its DATETIME twin
                // (`validate.laws.TemporalAggregateComponentSetLawsTest`, 2026-09-07). Both the exact class
                // agreement and the component-set agreement are model data the kernel arbitrates.
                case FieldOperand f -> {
                    if (!isDateLike(f.kind())) {
                        throw new IllegalArgumentException(mode.operatorId() + " was built for " + kind
                                + " but " + f.renderedPath() + " is " + f.kind());
                    }
                }
                // A GROUP carries no kind of its own — a group's kind lives in its EXPANSION, which
                // `AggregateOperand` delegates to the kernel rather than re-implementing the extent — so its
                // arm takes the factory's declared kind and lets the kernel's own gate judge the expansion
                // (`MVK_NOT_SORTABLE`), exactly as the numeric aggregates already do. An index-selected
                // group is the same shape — the index picks WHICH repetition, not what it expands to.
                case GroupListRef ignored -> { }
                case IndexedGroupValue ignored -> { }
            }
        }
    }

    /** Whether a kind can carry a date-like value at all — the routing question, and all a kind can answer. */
    private static boolean isDateLike(ValueKind kind) {
        return kind == ValueKind.DATE || kind == ValueKind.DATE_FRAGMENT
                || kind == ValueKind.TIME || kind == ValueKind.DATE_TIME;
    }

    /**
     * Collapse DATE_FRAGMENT into the DATE class — the one class fact a bare {@link ValueKind} settles, since a
     * date fragment carries a date and no clock and the kernel admits a list mixing the two kinds (measured,
     * {@code validate.laws.TemporalAggregateComponentSetLawsTest}).
     *
     * <p><b>This is NOT the operand's comparability class, and it must not be used as one.</b> The class the
     * kernel's gate reads is selected by the DECLARED FORMAT ([KF271]) — a TIME field declared
     * {@code yyyy-MM-dd} sits in the DATE class — which no function of a kind can see. The READ path composes
     * this with {@link FieldInfo#temporalClass()}, which resolves the declaration first; this class's own
     * authoring gate asks only {@link #isDateLike}. Public for that composition (2026-08-24), narrowed to it
     * after a class comparison keyed on kind alone was measured refusing kernel-admitted lists (2026-09-07).
     */
    public static ValueKind temporalClassOf(ValueKind kind) {
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
