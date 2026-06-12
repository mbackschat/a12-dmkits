package io.github.mbackschat.a12.dm.edit;

import java.util.function.Function;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * L2c — a held reference to an existing rule (its full name = group-path + name), so re-expressing its
 * condition or removing it doesn't re-type the path. Obtain from {@link EditSession#rule(String)}. The
 * rule's existence is checked when the edit applies (a missing one fails fast), not at construction.
 *
 * <p>Severity / message / rename edits are not yet exposed — they need write support beyond today's
 * condition re-express + remove; deferred (proposal §18.7).
 */
public final class RuleEditor {

    private final EditSession session;
    private final String fullName;

    RuleEditor(EditSession session, String fullName) {
        this.session = session;
        this.fullName = fullName;
    }

    /** This rule's full name (group-path + name). */
    public String name() {
        return fullName;
    }

    /** Re-express this rule's condition via the typed DSL builder. Chains. */
    public RuleEditor modify(Function<BoundModel, Condition> condition) {
        session.modifyRule(fullName, condition);
        return this;
    }

    /** Re-express this rule's condition from raw DSL text (the {@code apply}/spec path). Chains. */
    public RuleEditor modify(String conditionText) {
        session.modifyRule(fullName, conditionText);
        return this;
    }

    /** Remove this rule; returns the session (the rule no longer exists, so the editor is spent). */
    public EditSession remove() {
        return session.removeRule(fullName);
    }
}
