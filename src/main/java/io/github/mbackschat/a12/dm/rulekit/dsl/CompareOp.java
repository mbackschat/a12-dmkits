package io.github.mbackschat.a12.dm.rulekit.dsl;

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
    GE("GreaterThanOrEqual");

    private final String operatorId;

    CompareOp(String operatorId) {
        this.operatorId = operatorId;
    }

    public String operatorId() {
        return operatorId;
    }
}
