package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * The {@code RuleGroup} self-reference handle (DE {@code RegelKontext}) — the rule's OWN group, the one
 * self-reference in the condition grammar. Obtain from {@link Dsl#ruleGroup()}; its only operations are
 * the two presence predicates ({@link #filled()} / {@link #notFilled()}), mirroring {@link GroupRef} but
 * carrying no path — it always renders as the reserved keyword. See {@link RuleGroupPresenceCondition}.
 */
public record RuleGroupRef() {

    /** {@code GroupFilled(RuleGroup)} — the rule's own group is present (per-row on a repeatable group). */
    public Condition filled() {
        return new RuleGroupPresenceCondition(true);
    }

    /** {@code GroupNotFilled(RuleGroup)} — rejected on a repeatable iteration group (MVK_NEG_CONDITION_IN_ITERATION). */
    public Condition notFilled() {
        return new RuleGroupPresenceCondition(false);
    }
}
