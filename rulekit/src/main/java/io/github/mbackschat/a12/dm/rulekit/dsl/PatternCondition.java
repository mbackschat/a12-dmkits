package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code [Field] PatternMatched "regex"} / {@code [Field] PatternViolated "regex"} — anchored
 * whole-value regex test on a string field (KERNEL-SEMANTICS §7). The kernel's slot rules are
 * structural here (KF387): the left side must be a <em>string field cell</em>, plain or selected by
 * an index (no expressions, no constants), and the right side a <em>string
 * constant</em>. The existing {@link StringRef} factory enforces string kind and pattern admission;
 * the indexed-cell factory keeps the key and delegates both questions to kernel validation, since
 * the kind question can precede pattern admission (KF387).
 * The node excludes
 * arbitrary left expressions, erasing {@code MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON},
 * {@code MVK_MISSING_CONST_IN_PATTERN_COMP}, and {@code MVK_INVALID_COMPARE_TO_CONST}.
 */
public record PatternCondition(FieldOperand.Cell field, boolean matched, String regex) implements Condition {

    public PatternCondition(FieldRef field, boolean matched, String regex) {
        this((FieldOperand.Cell) field, matched, regex);
    }

    @Override
    public String operatorId() {
        return matched ? "PatternMatched" : "PatternViolated";
    }
}
