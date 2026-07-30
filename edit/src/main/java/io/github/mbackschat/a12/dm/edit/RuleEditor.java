package io.github.mbackschat.a12.dm.edit;

import java.util.List;
import java.util.function.Function;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * L2c — a held reference to an existing rule (its full name = group-path + name), so re-expressing its
 * condition, severity, or messages — or removing it — doesn't re-type the path. Obtain from
 * {@link EditSession#rule(String)}. The rule's existence is checked when the edit applies (a missing one
 * fails fast), not at construction.
 *
 * <p>Renaming a rule is the {@link EditSession#renameRule} refactor (it has no references to rewrite, but
 * shares the safety-gate path); it isn't an editor method.
 */
public final class RuleEditor extends ElementMetadataEditor<RuleEditor> {

    RuleEditor(EditSession session, String fullName) {
        super(session, fullName);
    }

    /** This rule's full name (group-path + name). */
    public String name() {
        return elementPath;
    }

    /** Re-express this rule's condition via the typed DSL builder. Chains. */
    public RuleEditor modify(Function<BoundModel, Condition> condition) {
        session.modifyRule(elementPath, condition);
        return this;
    }

    /** Re-express this rule's condition from raw DSL text (the {@code apply}/spec path). Chains. */
    public RuleEditor modify(String conditionText) {
        session.modifyRule(elementPath, conditionText);
        return this;
    }

    /** Re-set this rule's severity in place (condition + messages untouched, FEATURE-GAPS). Chains. */
    public RuleEditor severity(Severity severity) {
        session.modifyRuleSeverity(elementPath, severity);
        return this;
    }

    /** Replace this rule's per-locale messages in place (condition + severity untouched). Chains. */
    public RuleEditor messages(LocalizedText... messages) {
        session.modifyRuleMessages(elementPath, List.of(messages));
        return this;
    }

    /** Remove this rule; returns the session (the rule no longer exists, so the editor is spent). */
    public EditSession remove() {
        return session.removeRule(elementPath);
    }
}
