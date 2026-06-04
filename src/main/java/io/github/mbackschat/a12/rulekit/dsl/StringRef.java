package io.github.mbackschat.a12.rulekit.dsl;

/**
 * A typed handle to a string field. Offers string equality (the kernel has no ordering on strings —
 * §5b) and {@code Length(...)} as a numeric expression. Obtain from {@link BoundModel#str(String)}.
 */
public record StringRef(FieldRef field) {

    public Condition filled() {
        return field.filled();
    }

    public Condition notFilled() {
        return field.notFilled();
    }

    public Condition eq(String literal) {
        return new ComparisonCondition(field.value(), CompareOp.EQ, new StringLiteral(literal));
    }

    public Condition ne(String literal) {
        return new ComparisonCondition(field.value(), CompareOp.NE, new StringLiteral(literal));
    }

    /** {@code Length(field)} as a numeric expression for comparison. */
    public NumberExpr length() {
        return new NumberExpr(new LengthOf(field));
    }

    /** {@code FieldValueIncludedInValueList(field, "v1", …)} — membership in a literal list. */
    public Condition in(String first, String... rest) {
        return new ValueListCondition(field, true, stringValues(first, rest));
    }

    /** {@code FieldValueNotIncludedInValueList(field, "v1", …)}. */
    public Condition notIn(String first, String... rest) {
        return new ValueListCondition(field, false, stringValues(first, rest));
    }

    static java.util.List<Calculation> stringValues(String first, String... rest) {
        var values = new java.util.ArrayList<Calculation>();
        values.add(new StringLiteral(first));
        for (String v : rest) {
            values.add(new StringLiteral(v));
        }
        return values;
    }

    /** {@code [field] PatternMatched "regex"} — anchored whole-value match (KERNEL-SEMANTICS §7). */
    public Condition matches(String regex) {
        return new PatternCondition(field, true, checkRegex(regex));
    }

    /** {@code [field] PatternViolated "regex"} — anchored whole-value non-match. */
    public Condition violates(String regex) {
        return new PatternCondition(field, false, checkRegex(regex));
    }

    /**
     * Fail-fast necessary-condition check for {@code MVK_INVALID_PATTERN}: the kernel's gate is
     * Java {@code Pattern.compile} <em>plus</em> a portable-subset check (e.g. lookbehind is
     * Java-valid yet rejected — verified 2026-06-04, KERNEL-FINDINGS §7). Anything Java rejects the
     * kernel rejects too; the subset itself stays kernel-delegated (F4/N7 has the final word).
     */
    private static String checkRegex(String regex) {
        try {
            java.util.regex.Pattern.compile(regex);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "not a valid regular expression (the kernel would reject it with MVK_INVALID_PATTERN): "
                            + regex, e);
        }
        return regex;
    }
}
