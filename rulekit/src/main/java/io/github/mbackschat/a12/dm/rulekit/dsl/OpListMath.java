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
        //
        // Abs answers ONE code for every kind it turns away, temporal included — it takes exactly one
        // operand, so the operand order that splits Min/Max below into three codes has nothing to vary.
        // The class is the one MVK-LEDGER already records for the field-valued AbsValue (KF395); this is
        // the expression form of the same demand, measured on the same carrier as the grid below.
        if (op == Op.ABS && kind != ValueKind.NUMBER && !(operands.getFirst() instanceof BaseYear)) {
            throw Refusals.of(KernelCode.MVK_NO_NUMBER, "Abs needs a NUMBER operand, got " + kind);
        }
        // The refusals below cite the codes the kernel reports for these shapes, locked cell by cell in
        // OpListMathKindGateLawsTest (KERNEL-FINDINGS KF457, carrying KF263's grid to this spelling) — read the
        // lock rather than trusting this comment for coverage. They are separate codes because the kernel
        // separates them, which one homogeneity refusal would have flattened: a DATE beside a TIME is not the
        // same rejection as a DATE beside a number, and neither is a NUMBER beside a DATE.
        //
        // This gate compares declared KINDS; the kernel compares temporal COMPONENT SETS (KF263). So it goes
        // quiet wherever the two disagree — a DateType carrying a datetime format, a fragment with fewer
        // components than the date beside it — which under-reports and is safe. Refusing there would need
        // the declared formats, which a Calculation does not carry.
        if (op != Op.ABS && !isSortable(kind)) {
            throw Refusals.of(KernelCode.MVK_NOT_SORTABLE,
                    op + " needs NUMBER or date-like operands, got " + kind);
        }
        for (Calculation operand : operands) {
            ValueKind operandKind = resultKind(operand);
            if (operandKind != kind) {
                // THE FIRST OPERAND'S KIND DECIDES, and nothing else does (KF249's position law). A
                // non-temporal head is always MVK_NOT_SORTABLE whatever follows it — Min([Premium],
                // [StartDate]) included, the cell an "either operand temporal" reading gets wrong; a temporal
                // head asks only whether the offender is temporal too, which is a format incompatibility, or
                // not, which is date-and-nondate. `kind` is NUMBER or temporal — the gate above ensures it — so
                // !isTemporal means NUMBER.
                KernelCode code;
                if (!isTemporal(kind)) {
                    code = KernelCode.MVK_NOT_SORTABLE;
                } else if (isTemporal(operandKind)) {
                    code = KernelCode.MVK_DATEFORMATS_NOT_COMPATIBLE;
                } else {
                    code = KernelCode.MVK_DATE_AND_NONDATE;
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
