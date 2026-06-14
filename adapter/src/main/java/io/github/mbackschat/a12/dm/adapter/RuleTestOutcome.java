package io.github.mbackschat.a12.dm.adapter;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of {@link KernelAdapter#testRule testing one rule} against a document instance (FEATURE-GAPS
 * §3.3) — the empirical polarity check, scoped to a single rule. The point is the three-way {@link Verdict}:
 * a plain "did it fire" answer conflates a genuine <b>PASS</b> with a <b>SUPPRESSED</b> rule (one the kernel
 * never evaluated because a field it references is formally invalid — too many decimals, etc.; KERNEL-FINDINGS),
 * and that conflation misleads. When suppressed, {@link #suppressedBy} names the offending referenced fields.
 * When FIRED, {@link #message} carries the <b>interpolated</b> end-user text the kernel produced (G1) — the
 * authored message with its {@code $Field$}/{@code $Field.value$} tokens resolved against this instance.
 *
 * @param rule         the rule's full slash-path
 * @param verdict      FIRED (a violation), PASSED (evaluated, condition false), or SUPPRESSED (not evaluated)
 * @param suppressedBy the referenced fields that were formally invalid (non-empty iff {@code verdict} is SUPPRESSED)
 * @param message      the interpolated end-user message (present iff {@code verdict} is FIRED)
 */
public record RuleTestOutcome(String rule, Verdict verdict, List<Suppression> suppressedBy,
        Optional<String> message) {

    public RuleTestOutcome {
        suppressedBy = List.copyOf(suppressedBy);
    }

    /** Whether the rule fired (a violation was present on the data). */
    public boolean fired() {
        return verdict == Verdict.FIRED;
    }

    /** The three distinguishable runtime outcomes for a single rule. */
    public enum Verdict {
        /** The rule's condition was true on the data — a violation. */
        FIRED,
        /** The rule was evaluated and its condition was false — a genuine pass. */
        PASSED,
        /** The rule was never evaluated: a field it references is formally invalid (unknown). */
        SUPPRESSED
    }

    /** A referenced field whose formal-validity error kept the rule from being evaluated. */
    public record Suppression(String field, String formalErrorCode) {
    }
}
