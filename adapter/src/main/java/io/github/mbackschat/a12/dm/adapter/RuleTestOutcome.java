package io.github.mbackschat.a12.dm.adapter;

import java.util.List;
import java.util.Optional;

/**
 * The outcome of {@link KernelAdapter#testRule testing one rule} against a document instance (FEATURE-GAPS
 * §3.3) — the empirical polarity check, scoped to a single rule. The point is the three-way {@link Verdict}:
 * a plain "did it fire" answer conflates a genuine <b>PASS</b> with a <b>SUPPRESSED</b> rule (one the kernel
 * never evaluated because a field it references is formally invalid — too many decimals, etc.; KERNEL-FINDINGS),
 * and that conflation misleads. When suppressed, {@link #suppressedBy} names the offending referenced fields.
 *
 * <p>When FIRED, {@link #firing} carries the <b>whole kernel message</b> that fired — its code, error-field
 * pointer, severity, VALUE/OMISSION type, interpolated text (G1) and the operands it read — not just the rendered
 * line. The type is a semantic observable rather than presentation (for a uniqueness predicate it is the only
 * difference between a filtered and an unfiltered firing on identical data), and a scoped single-rule check that
 * dropped it forced a consumer back to whole-document evaluation to measure it. {@link #message} stays available
 * as a derived read.
 *
 * @param rule         the rule's full slash-path
 * @param verdict      FIRED (a violation), PASSED (evaluated, condition false), or SUPPRESSED (not evaluated)
 * @param suppressedBy the referenced fields that were formally invalid (non-empty iff {@code verdict} is SUPPRESSED)
 * @param firing       the kernel message this rule fired with (present iff {@code verdict} is FIRED)
 */
public record RuleTestOutcome(String rule, Verdict verdict, List<Suppression> suppressedBy,
        Optional<EvalMessage> firing) {

    public RuleTestOutcome {
        suppressedBy = List.copyOf(suppressedBy);
    }

    /** The interpolated end-user text of {@link #firing}; empty when nothing fired. */
    public Optional<String> message() {
        return firing.map(EvalMessage::message);
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
