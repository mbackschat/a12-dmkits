package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

/**
 * The comparison operators. Each maps to its catalog operator-id (D14); the catalog's keywords for
 * these are fixed symbols identical in every condition language (unlike worded keywords such as
 * {@code And}/{@code Und}), but the renderer still looks them up from the catalog like any other
 * operator — keyword knowledge lives only there.
 */
public enum CompareOp {
    EQ("Equal"),
    NE("NotEqual"),
    LT("LessThan"),
    LE("LessThanOrEqual"),
    GT("GreaterThan"),
    GE("GreaterThanOrEqual"),
    // tolerance comparisons (the kernel's fixed 1/2/5/10 variants): "unequal within a tolerance of N",
    // i.e. the two numbers differ by more than N. Worded keywords (DiffersWithToleranceRange{N}).
    TOLERANCE_1("DiffersWithToleranceRange1"),
    TOLERANCE_2("DiffersWithToleranceRange2"),
    TOLERANCE_5("DiffersWithToleranceRange5"),
    TOLERANCE_10("DiffersWithToleranceRange10");

    private final String operatorId;

    CompareOp(String operatorId) {
        this.operatorId = operatorId;
    }

    public String operatorId() {
        return operatorId;
    }

    /**
     * The logical complement, when one exists as a single operator: the ordered pairs
     * {@code EQ↔NE}, {@code LT↔GE}, {@code LE↔GT}. The tolerance operators have no single-operator
     * negation in the kernel, so they yield {@link Optional#empty()} — the conservative answer that
     * keeps {@link ConditionPolarity} from ever claiming a false inversion.
     */
    public Optional<CompareOp> complement() {
        return switch (this) {
            case EQ -> Optional.of(NE);
            case NE -> Optional.of(EQ);
            case LT -> Optional.of(GE);
            case GE -> Optional.of(LT);
            case LE -> Optional.of(GT);
            case GT -> Optional.of(LE);
            case TOLERANCE_1, TOLERANCE_2, TOLERANCE_5, TOLERANCE_10 -> Optional.empty();
        };
    }
}
