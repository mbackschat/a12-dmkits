package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.List;

import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The parameterless operand-list math functions — {@code Abs(value)}, {@code Min(v1, v2, …)},
 * {@code Max(v1, v2, …)} (the kernel's {@code IOpListOperation} members other than the rounding family,
 * which is {@link Rounding}). A {@link Calculation} over value <em>expressions</em>: {@code Abs} is
 * number-valued, while {@code Min}/{@code Max} carry their operands' kind — NUMBER over number expressions,
 * or DATE/TIME/DATETIME over homogeneous temporal operands (the kernel's {@code DateCombiner},
 * KERNEL-FINDINGS §6); see {@link #kind()}.
 * Distinct from the field-list {@link ListAggregate} {@code MaxValue}/{@code MinValue}, which aggregate a
 * repetition list. Operands render as values ({@code Min([A], [B])}), not bare entity refs.
 *
 * <p>{@code Abs} takes exactly one operand; {@code Min}/{@code Max} take one or more.
 */
public record OpListMath(Op op, List<Calculation> operands) implements Calculation {

    /** The three operand-list functions, each carrying its catalog operator-id. */
    public enum Op {
        ABS("Abs"),
        MIN("Min"),
        MAX("Max");

        private final String operatorId;

        Op(String operatorId) {
            this.operatorId = operatorId;
        }

        public String operatorId() {
            return operatorId;
        }
    }

    public OpListMath {
        operands = List.copyOf(operands);
        if (operands.isEmpty()) {
            throw new IllegalArgumentException(op + " needs at least one operand");
        }
        if (op == Op.ABS && operands.size() != 1) {
            throw new IllegalArgumentException("Abs takes exactly one operand, got " + operands.size());
        }
        ValueKind kind = resultKind(operands.getFirst());
        // BaseYear's public node reports its primary DATE role, but Dsl.baseYearNumber() selects the
        // kernel's numeric role before reaching Abs (the same polymorphic exception Computation owns).
        if (op == Op.ABS && kind != ValueKind.NUMBER && !(operands.getFirst() instanceof BaseYear)) {
            throw new IllegalArgumentException("Abs needs a NUMBER operand, got " + kind);
        }
        // The three refusals below each cite the code the kernel was OBSERVED to report for that exact
        // shape, measured 2026-09-22 on a purpose-made carrier and locked by OpListMathKindGateLawsTest.
        // They are separate codes rather than one because the kernel separates them, which a single
        // homogeneity refusal would have flattened: a DATE beside a TIME is not the same rejection as a
        // DATE beside a number. Four positive controls (NUMBER, DATE, TIME and DATE_TIME paired with
        // themselves) are admitted, so none of this is a gate the kernel does not also apply.
        if (op != Op.ABS && !isSortable(kind)) {
            throw Refusals.of(KernelCode.MVK_NOT_SORTABLE,
                    op + " needs NUMBER or date-like operands, got " + kind);
        }
        for (Calculation operand : operands) {
            ValueKind operandKind = resultKind(operand);
            if (operandKind != kind) {
                // Three codes, because the kernel reports three. The temporal axis decides: two temporal
                // kinds that disagree are a FORMAT incompatibility, one temporal beside anything else is
                // date-and-nondate, and with no temporal operand at all the offender is simply unsortable.
                // The last arm is the one a first pass got wrong by reading only the gate above, where the
                // unsortable kind sits FIRST: reversed, Min([Premium], [Holder]) reaches here instead, and
                // the kernel still says NOT_SORTABLE. `kind` is NUMBER or temporal, the gate above ensures it.
                KernelCode code;
                if (isTemporal(kind) && isTemporal(operandKind)) {
                    code = KernelCode.MVK_DATEFORMATS_NOT_COMPATIBLE;
                } else if (isTemporal(kind) || isTemporal(operandKind)) {
                    code = KernelCode.MVK_DATE_AND_NONDATE;
                } else {
                    code = KernelCode.MVK_NOT_SORTABLE;
                }
                throw Refusals.of(code, op + " needs operands of one kind, got " + kind
                        + " and " + operandKind);
            }
        }
    }

    /** The kernel's four admitted operand kinds here, each measured paired with itself (see the constructor). */
    private static boolean isSortable(ValueKind kind) {
        return kind == ValueKind.NUMBER || isTemporal(kind);
    }

    private static boolean isTemporal(ValueKind kind) {
        return kind == ValueKind.DATE || kind == ValueKind.TIME || kind == ValueKind.DATE_TIME;
    }

    @Override
    public ValueKind kind() {
        // Abs is number-only; Min/Max carry their operands' kind (NUMBER over number expressions,
        // DATE/TIME/DATETIME over temporal operands — the kernel's DateCombiner, KERNEL-FINDINGS §6).
        return op == Op.ABS ? ValueKind.NUMBER : resultKind(operands.getFirst());
    }

    private static ValueKind resultKind(Calculation operand) {
        return operand.kind() == ValueKind.DATE_FRAGMENT ? ValueKind.DATE : operand.kind();
    }

    public String operatorId() {
        return op.operatorId();
    }
}
