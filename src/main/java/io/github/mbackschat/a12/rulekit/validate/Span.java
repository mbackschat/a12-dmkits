package io.github.mbackschat.a12.rulekit.validate;

/**
 * A half-open character range {@code [start, end)} into a rule's rendered {@code errorCondition}
 * text — the F4a span-pointing locator on {@link Diagnostic.Where}. For rulekit-authored rules the
 * renderer produces both the text and the per-node spans (an emit-map), so a diagnostic can point at
 * the exact substring; for ingested foreign rules the spans come from the kernel parse tree instead.
 */
public record Span(int start, int end) {

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span [" + start + ", " + end + ")");
        }
    }
}
