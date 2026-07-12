package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code [Field] PatternMatched "regex"} / {@code [Field] PatternViolated "regex"} — anchored
 * whole-value regex test on a string field (KERNEL-SEMANTICS §7). The kernel's slot rules are
 * structural here (verified 2026-06-04, KERNEL-FINDINGS §7): the left side must be a <em>bare
 * string field value</em> (no expressions, no constants) and the right side a <em>string
 * constant</em> — this node can express nothing else, erasing
 * {@code MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON}, {@code MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON},
 * {@code MVK_MISSING_CONST_IN_PATTERN_COMP}, and {@code MVK_INVALID_COMPARE_TO_CONST}.
 */
public record PatternCondition(FieldRef field, boolean matched, String regex) implements Condition {

    @Override
    public String operatorId() {
        return matched ? "PatternMatched" : "PatternViolated";
    }
}
