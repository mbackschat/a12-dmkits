package io.github.mbackschat.a12.dm.rulekit.dsl;

/**
 * {@code GroupFilled(RuleGroup)} / {@code GroupNotFilled(RuleGroup)} — presence of the rule's OWN group
 * (the kernel's {@code RegelKontext} self-reference, KERNEL-FINDINGS "Condition directives" / IF63). A
 * distinct node from {@link GroupPresenceCondition} because its operand is the reserved self-reference
 * keyword (rendered {@code RuleGroup} / DE {@code RegelKontext}), never a qualified group path — it is
 * never starred ({@code MVK_INVALID_WILDCARD_FOR_RULEGROUP}) and never a path prefix.
 *
 * <p>Semantics (kernel-verified): on a rule iterating a repeatable group, {@code GroupFilled(RuleGroup)}
 * is TRUE for every instantiated row (an "is this row present" guard); on a non-repeatable group it is
 * the ordinary "has a filled descendant". {@code GroupNotFilled(RuleGroup)} on a repeatable iteration
 * group is authoring-rejected by the kernel ({@code MVK_NEG_CONDITION_IN_ITERATION}).
 */
public record RuleGroupPresenceCondition(boolean filled) implements Condition {

    @Override
    public String operatorId() {
        return filled ? "GroupFilled" : "GroupNotFilled";
    }
}
