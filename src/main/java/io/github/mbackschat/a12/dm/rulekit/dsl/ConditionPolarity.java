package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.Optional;

/**
 * The logical <b>complement</b> of a {@link Condition}, computed by exhaustive switch over the sealed
 * AST — the pure-AST primitive behind {@code model diff}'s structural {@code POLARITY_INVERTED}
 * detection (MODEL-REVIEW-SPEC §5c level 1, LEDGER MR1).
 *
 * <p>The kernel has <b>no negation operator</b> (see {@link LogicCondition}), so a negation is pushed
 * down into the leaf polarity bits and across {@code And}/{@code Or} by De&nbsp;Morgan:
 * <ul>
 *   <li>{@link ComparisonCondition} — flip the {@link CompareOp} ({@code <}↔{@code >=}, …);</li>
 *   <li>{@link FieldPresenceCondition}/{@link GroupPresenceCondition} — flip {@code filled};</li>
 *   <li>{@link PatternCondition} — flip {@code matched}; {@link ValueListCondition} — flip {@code included};</li>
 *   <li>{@link LogicCondition} — {@code And}↔{@code Or} with both children complemented.</li>
 * </ul>
 *
 * <p><b>Conservative by design:</b> a node whose negation is not a single clean complement (the
 * quantifier predicates, custom conditions, date/custom-type validity, and the tolerance comparisons)
 * yields {@link Optional#empty()}, and any subtree that can't be complemented propagates the empty up.
 * So {@link #isInversionOf} returns {@code true} only on a <em>provable</em> full structural flip and
 * never claims an inversion it can't establish (a false positive here would mis-grade a HIGH risk).
 */
public final class ConditionPolarity {

    /**
     * The comparison key for {@link #isInversionOf}: the canonical rendering. It is style-independent
     * (a {@link FieldRef}'s {@link PathStyle} hint is authored-notation provenance, not semantics, and
     * the renderer emits the absolute path regardless) — so two conditions authored via different
     * notations but referencing the same elements compare equal, which record {@code .equals()} would not.
     */
    private static final DslRenderer CANONICAL = new DslRenderer();

    private ConditionPolarity() {}

    /** The logical complement of {@code condition}, or {@link Optional#empty()} when no clean one exists. */
    public static Optional<Condition> complement(Condition condition) {
        return switch (condition) {
            case ComparisonCondition c ->
                    c.operator().complement().map(op -> new ComparisonCondition(c.left(), op, c.right()));
            case FieldPresenceCondition f ->
                    Optional.of(new FieldPresenceCondition(f.field(), !f.filled()));
            case GroupPresenceCondition g ->
                    Optional.of(new GroupPresenceCondition(g.group(), !g.filled()));
            case RuleGroupPresenceCondition r ->
                    Optional.of(new RuleGroupPresenceCondition(!r.filled()));
            case IndexedPresenceCondition p ->
                    Optional.of(new IndexedPresenceCondition(p.index(), !p.filled()));
            case PatternCondition p ->
                    Optional.of(new PatternCondition(p.field(), !p.matched(), p.regex()));
            case ValueListCondition v ->
                    Optional.of(new ValueListCondition(v.field(), !v.included(), v.values()));
            case LogicCondition l -> deMorgan(l);
            // No clean single complement (a quantifier flip is not just a per-node negation, and the
            // rest carry no polarity bit) — stay conservative so an inversion is never claimed falsely.
            case FieldListPredicate p -> Optional.empty();
            case GroupListPredicate p -> Optional.empty();
            case FieldListValueListCondition v -> Optional.empty();
            case FieldListEntityListCondition v -> Optional.empty();
            case GroupScopedFieldFill v -> Optional.empty();
            case RepetitionNotUniqueCondition r -> Optional.empty();
            case CustomConditionRef c -> Optional.empty();
            case DateValidity v -> Optional.empty();
            case CustomTypeValidity v -> Optional.empty();
        };
    }

    /**
     * Whether {@code head} is the logical complement of {@code base} — a provable, full structural
     * polarity flip (the structural {@code POLARITY_INVERTED} signal, {@code confidence:high}). False
     * whenever either side has no clean complement, so a partial flip or a threshold change is not an
     * inversion.
     */
    public static boolean isInversionOf(Condition head, Condition base) {
        return complement(base).map(c -> CANONICAL.render(c).equals(CANONICAL.render(head))).orElse(false);
    }

    private static Optional<Condition> deMorgan(LogicCondition l) {
        Optional<Condition> left = complement(l.left());
        Optional<Condition> right = complement(l.right());
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        LogicCondition.Op flipped = l.op() == LogicCondition.Op.AND ? LogicCondition.Op.OR : LogicCondition.Op.AND;
        return Optional.of(new LogicCondition(flipped, left.get(), right.get()));
    }
}
