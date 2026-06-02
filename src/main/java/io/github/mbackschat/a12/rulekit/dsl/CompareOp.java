package io.github.mbackschat.a12.rulekit.dsl;

/**
 * The comparison operators. These render as fixed symbols that are identical in every condition
 * language (unlike worded keywords such as {@code And}/{@code Und}, which come from the catalog).
 */
public enum CompareOp {
    EQ("=="),
    NE("!="),
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">=");

    private final String symbol;

    CompareOp(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
