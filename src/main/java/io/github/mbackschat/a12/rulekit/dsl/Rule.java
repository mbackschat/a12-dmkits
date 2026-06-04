package io.github.mbackschat.a12.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.rulekit.validate.PrecheckException;

/**
 * A typed validation rule — the F2a authoring object (study §4.1): the rule lives in a group by
 * construction ({@link #in(GroupRef)}), {@code errorEntityRelPath} is computed from the error-field
 * handle, and the error-field-referenced rule is pre-checked at {@link Builder#build()} (directly,
 * or via an enclosing group reference — kernel-verified), so {@code MVK_ERROR_FIELD_NOT_REFERENCED}
 * and the rule-at-root mistake are caught here with the fix named.
 *
 * <p>The condition stays an AST (D14 — the working representation); render is on demand. v1 scope:
 * the error entity is a field under the rule's group (the kernel also allows group error entities —
 * later, on demand). Messages are plain localized texts; the typed interpolation helper is the F2a
 * follow-up.
 */
public record Rule(
        GroupRef group,
        String name,
        FieldRef errorField,
        Condition condition,
        String code,
        Severity severity,
        List<LocalizedText> messages) {

    public Rule {
        messages = List.copyOf(messages);
    }

    // Builders are created via BoundModel#rule(GroupRef) — model-aware so messages can be checked
    // against the model's DECLARED locales (which are arbitrary; en/de are not special).

    /** The error entity's path relative to the rule element (which sits inside the group). */
    public String errorEntityRelPath() {
        return "../" + errorField.path().substring(group.path().length() + 1);
    }

    /** Canonical EN condition text. */
    public String render() {
        return new DslRenderer().render(condition);
    }

    public String render(ConditionLanguage language) {
        return new DslRenderer(language).render(condition);
    }

    /** The bridge to the F4 validator ({@code RuleValidator.check(modelJson, rule.draft())}). */
    public DraftRule draft() {
        return new DraftRule(group.path(), name, errorEntityRelPath(), render(), code, severity.name());
    }

    public static final class Builder {

        private final GroupRef group;
        private final List<String> declaredLocales;
        private String name;
        private FieldRef errorField;
        private Condition condition;
        private String code;
        private Severity severity;
        private final List<LocalizedText> messages = new ArrayList<>();

        Builder(GroupRef group, List<String> declaredLocales) {
            this.group = group;
            this.declaredLocales = List.copyOf(declaredLocales);
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder errorField(FieldRef field) {
            this.errorField = field;
            return this;
        }

        public Builder errorField(NumberRef field) {
            return errorField(field.field());
        }

        public Builder errorField(StringRef field) {
            return errorField(field.field());
        }

        public Builder errorField(EnumRef field) {
            return errorField(field.field());
        }

        public Builder errorField(BoolRef field) {
            return errorField(field.field());
        }

        public Builder errorField(ConfirmRef field) {
            return errorField(field.field());
        }

        /** The error condition — true ⇒ the data is invalid (the kernel's error-scenario inversion). */
        public Builder when(Condition condition) {
            this.condition = condition;
            return this;
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder severity(Severity severity) {
            this.severity = severity;
            return this;
        }

        public Builder message(LocalizedText... texts) {
            messages.addAll(List.of(texts));
            return this;
        }

        public Rule build() {
            check(name != null && !name.isBlank(), "RK_INCOMPLETE_RULE",
                    "a rule needs a name (the element name in its group)", null);
            check(errorField != null, "RK_INCOMPLETE_RULE",
                    "a rule needs an error field — errorField(...)", "errorField(...)");
            check(condition != null, "RK_INCOMPLETE_RULE",
                    "a rule needs an error condition — when(...)", "when(...)");
            check(code != null && !code.isBlank(), "RK_INCOMPLETE_RULE",
                    "a rule needs an error code — code(...)", "code(...)");
            check(severity != null, "RK_INCOMPLETE_RULE",
                    "a rule needs a severity — severity(...)", "severity(...)");
            check(!messages.isEmpty(), "MVK_ERROR_MESSAGE_MISSING",
                    "a rule needs at least one error message (the kernel rejects message-less rules"
                            + " with MVK_ERROR_MESSAGE_MISSING) — message(en(...))", "message(en(...))");
            LocalizedText.checkDeclared(declaredLocales, messages, "rule");
            check(errorField.path().startsWith(group.path() + "/"), "RK_ERROR_FIELD_OUTSIDE_GROUP",
                    "the error field " + errorField.path() + " must lie under the rule's group "
                            + group.path() + " — author the rule in a containing group",
                    "author the rule in a group that contains " + errorField.path());
            check(ConditionReferences.of(condition).covers(errorField.path()), "MVK_ERROR_FIELD_NOT_REFERENCED",
                    "the error field " + errorField.path() + " is not referenced by the condition"
                            + " (directly or via an enclosing group reference) — the kernel rejects this"
                            + " with MVK_ERROR_FIELD_NOT_REFERENCED; reference it, or pick a referenced"
                            + " field as the error field",
                    "reference " + errorField.path() + " in the condition, or pick a referenced field as the error field");
            return new Rule(group, name, errorField, condition, code, severity, messages);
        }

        /** Fail fast with a structured PRECHECK diagnostic (still an {@code IllegalStateException}). */
        private void check(boolean ok, String code, String summary, String fix) {
            if (ok) {
                return;
            }
            String rulePath = name == null || name.isBlank() ? group.path() : group.path() + "/" + name;
            throw new PrecheckException(Diagnostic.precheck(Optional.of(code), summary,
                    new Diagnostic.Where(rulePath, Optional.empty()), Optional.ofNullable(fix), Optional.empty()));
        }
    }
}
