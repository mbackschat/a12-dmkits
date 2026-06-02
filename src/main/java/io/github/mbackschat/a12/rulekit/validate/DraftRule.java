package io.github.mbackschat.a12.rulekit.validate;

/**
 * The minimal data needed to place a rule into a model for a ground-truth check: the group it lives
 * in, the error entity (a path relative to the rule), the rendered {@code errorCondition}, an error
 * code, and a severity ({@code "ERROR"} / {@code "WARNING"}). Mirrors the DM-JSON {@code Rule} shape.
 */
public record DraftRule(
        String groupPath,
        String name,
        String errorEntityRelPath,
        String errorCondition,
        String errorCode,
        String severity) {
}
