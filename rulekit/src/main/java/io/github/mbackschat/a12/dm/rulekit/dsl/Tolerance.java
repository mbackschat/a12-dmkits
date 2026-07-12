package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The fixed tolerance ranges for a {@code DiffersWithToleranceRange{N}} comparison — only the kernel's
 * {@code 1}/{@code 2}/{@code 5}/{@code 10} variants exist, so the enum makes any other range
 * unrepresentable (study finding §9.1). Used by {@link NumberExpr#differsWithTolerance}.
 */
public enum Tolerance {
    RANGE_1(CompareOp.TOLERANCE_1),
    RANGE_2(CompareOp.TOLERANCE_2),
    RANGE_5(CompareOp.TOLERANCE_5),
    RANGE_10(CompareOp.TOLERANCE_10);

    private final CompareOp operator;

    Tolerance(CompareOp operator) {
        this.operator = operator;
    }

    CompareOp operator() {
        return operator;
    }
}
