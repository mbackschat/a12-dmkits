package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;

/**
 * A typed validation rule — the F2a authoring object (study §4.1): the rule lives in a group by
 * construction ({@link #in(GroupRef)}), and {@code errorEntityRelPath} is computed from the
 * error-field handle. {@link Builder#build()} hard-fails only on completeness and rulekit's own
 * structural invariants; the kernel-semantic error-field-referenced check ({@code
 * MVK_ERROR_FIELD_NOT_REFERENCED}, the {@code covers()} heuristic) is <i>deferred</i> to the live
 * kernel (F11) via {@link #deferredChecks()}, so a heuristic that drifts from a newer kernel can
 * never be the sole reason an accepted rule is reported corrupt.
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

    /** Canonical EN condition text — paths rendered relative to the rule's group (fork #9). */
    public String render() {
        return new DslRenderer(ConditionLanguage.EN, group.path()).render(condition);
    }

    public String render(ConditionLanguage language) {
        return new DslRenderer(language, group.path()).render(condition);
    }

    /** The bridge to the F4 validator ({@code RuleValidator.check(modelJson, rule.draft())}). */
    public DraftRule draft() {
        return new DraftRule(group.path(), name, errorEntityRelPath(), render(), code, severity.name());
    }

    /**
     * Kernel-semantic pre-checks <i>deferred</i> to the live kernel (F11): rulekit predicts them but
     * does NOT hard-fail {@code build()} on them, so a heuristic that drifts from a newer kernel can
     * never be the sole reason a rule the kernel itself accepts is reported corrupt. Surfaced as
     * advisory WARNINGs by {@link io.github.mbackschat.a12.dm.rulekit.lint.RuleDiagnostics}, and
     * suppressed there when the live kernel already ruled on the same {@code MVK_*} code.
     *
     * <p>Currently one: the error field referenced by the condition (the {@code covers()} heuristic,
     * which only approximates the kernel's {@code MVK_ERROR_FIELD_NOT_REFERENCED}). Completeness
     * pre-checks (name/condition/error-field/code/severity/message) stay hard in {@code build()} —
     * they are rulekit's own API contract, and the validator masks {@code MVK_ERROR_MESSAGE_MISSING}
     * so the kernel can't arbitrate message presence — and the under-group placement check guards
     * rulekit's own relative-path math. Neither is deferred.
     */
    public List<Diagnostic> deferredChecks() {
        if (ConditionReferences.of(condition).covers(errorField.path())) {
            return List.of();
        }
        return List.of(Diagnostic.precheckWarning(
                Optional.of("MVK_ERROR_FIELD_NOT_REFERENCED"),
                "the error field " + errorField.path() + " appears unreferenced by the condition"
                        + " (directly or via an enclosing group reference); the kernel typically rejects"
                        + " this with MVK_ERROR_FIELD_NOT_REFERENCED — if your kernel accepts it, this is"
                        + " advisory only",
                new Diagnostic.Where(group.path() + "/" + name, Optional.empty()),
                Optional.of("reference " + errorField.path()
                        + " in the condition, or pick a referenced field as the error field"),
                Optional.empty()));
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

        /**
         * The <b>error condition</b>: TRUE exactly when the document is <b>invalid</b>. A rule states the
         * <em>violation</em>, not the requirement — the kernel's error-scenario inversion, and there is
         * no {@code Not} operator (use the negative-form predicate). Enforce a requirement by writing what
         * makes it <em>fail</em>:
         * <ul>
         *   <li>"X must be provided" &rarr; {@code when(x.notFilled())} — not {@code x.filled()}</li>
         *   <li>"amount must be &le; 1000" &rarr; {@code when(amount.gt(1000))} — not {@code amount.le(1000)}</li>
         * </ul>
         * Neither the compiler nor {@link #build()} can catch a back-to-front condition (both polarities
         * are valid), so re-read it as "true exactly when the data is wrong". The {@code lint.RuleDiagnostics}
         * backstop flags the common inversion ({@code RK_INVERTED_REQUIREDNESS}).
         */
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

        /**
         * Validate the typed slots and produce the immutable {@link Rule}. {@code build()} fail-fasts on the
         * <b>structural</b> requirements (name, error field, condition, code, severity, &ge;1 message per
         * declared locale, error field under the rule's group) but does <b>not</b> run the semantic lint.
         * Valid-but-suspicious shapes the type system can't see — a wrong-{@link #when(Condition) polarity}
         * condition, a negative presence test under a repeatable group, an unguarded number comparison — are
         * surfaced by {@code lint.RuleDiagnostics.diagnose(model, rule)} (the developer's lint backstop), and
         * the real-kernel verdict by {@code validate.RuleValidator}. A structural violation throws a
         * {@code PrecheckException} (an {@code IllegalStateException}) naming the {@code MVK_*}/{@code RK_*} it prevents.
         */
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
            // MVK_ERROR_FIELD_NOT_REFERENCED is NOT hard-checked here (F11) — it mirrors a kernel
            // judgment the live kernel arbitrates, so it is deferred to Rule#deferredChecks().
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
