package io.github.mbackschat.a12.dm.rulekit.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.predicates.ElementNamePredicatesKt;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.Refusals;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/**
 * A typed validation rule — the F2a authoring object (study §4.1): the rule lives in a group by
 * construction ({@link #in(GroupRef)}), and {@code errorEntityRelPath} is computed from the
 * error-entity handle, a field or a group ({@link ErrorEntity}). {@link Builder#build()} hard-fails only on completeness and rulekit's own
 * structural invariants; the kernel-semantic error-field-referenced check ({@code
 * MVK_ERROR_FIELD_NOT_REFERENCED}, the {@code covers()} heuristic) is <i>deferred</i> to the live
 * kernel (F11) via {@link #deferredChecks()}, so a heuristic that drifts from a newer kernel can
 * never be the sole reason an accepted rule is reported corrupt.
 *
 * <p>The condition stays an AST (D14 — the working representation); render is on demand. The error entity
 * lies strictly under the rule's group. Messages are plain localized texts.
 */
public record Rule(
        GroupRef group,
        String name,
        ErrorEntity errorEntity,
        Condition condition,
        String code,
        Severity severity,
        List<LocalizedText> messages,
        Comments comments) {

    public Rule {
        messages = List.copyOf(messages);
    }

    // Builders are created via BoundModel#rule(GroupRef) — model-aware so messages can be checked
    // against the model's DECLARED locales (which are arbitrary; en/de are not special).

    /**
     * The error entity's path relative to the rule element (which sits inside the group) — the same
     * single-sourced convention {@link Computation#computedFieldRelPath()} uses, so the two cannot drift.
     */
    public String errorEntityRelPath() {
        return group.path().elementReference(errorEntity.path());
    }

    /**
     * Canonical EN condition text — paths rendered relative to the rule's group, with any
     * authored {@link #comments} emitted as {@code ;;} lines at their node's position. An empty
     * side-channel renders the plain canonical text.
     */
    public String render() {
        return render(ConditionLanguage.EN);
    }

    /** Canonical condition text in {@code language}, with paths relative to the rule's group. */
    public String render(ConditionLanguage language) {
        return new DslRenderer(language, group.path().value()).render(condition, comments);
    }

    /** The bridge to the F4 validator ({@code RuleValidator.check(modelJson, rule.draft())}). */
    public DraftRule draft() {
        return draft(ConditionLanguage.EN);
    }

    /** The F4 validator draft, including authored messages, rendered in the destination model's condition {@code language}. */
    public DraftRule draft(ConditionLanguage language) {
        return DraftRule.builder().groupPath(group.path().value()).name(name)
                .errorEntityRelPath(errorEntityRelPath()).errorCondition(render(language))
                .errorCode(code).severity(severity.name()).messages(messages).build();
    }

    /**
     * Kernel-semantic pre-checks <i>deferred</i> to the live kernel (F11): rulekit predicts them but
     * does NOT hard-fail {@code build()} on them, so a heuristic that drifts from a newer kernel can
     * never be the sole reason a rule the kernel itself accepts is reported corrupt. Surfaced as
     * advisory WARNINGs by {@link io.github.mbackschat.a12.dm.rulekit.lint.RuleDiagnostics}, and
     * suppressed there when the live kernel already ruled on the same {@code MVK_*} code.
     *
     * <p>Currently one: the error entity referenced by the condition (the {@code covers()} heuristic for a
     * field, {@code coversGroup()} for a group, which only approximate the kernel's
     * {@code MVK_ERROR_FIELD_NOT_REFERENCED}). Completeness
     * pre-checks (name/condition/error-field/code/severity/message) stay hard in {@code build()} —
     * they are rulekit's own API contract, and the validator masks {@code MVK_ERROR_MESSAGE_MISSING}
     * so the kernel can't arbitrate message presence — and the under-group placement check guards
     * rulekit's own relative-path math. Neither is deferred.
     */
    public List<Diagnostic> deferredChecks() {
        ConditionReferences references = ConditionReferences.of(condition);
        String target = errorEntity.path().value();
        boolean referenced = switch (errorEntity) {
            case FieldRef ignored -> references.covers(target);
            case GroupRef ignored -> references.coversGroup(target);
        };
        if (referenced || ConditionReferences.referencesRuleGroup(condition)) {
            return List.of();
        }
        return List.of(Diagnostic.precheckWarning(
                Optional.of(KernelCode.MVK_ERROR_FIELD_NOT_REFERENCED.name()),
                "the error entity " + errorEntity.path() + " appears unreferenced by the condition"
                        + " (directly or via an enclosing group reference); the kernel typically rejects"
                        + " this — if your kernel accepts it, this is"
                        + " advisory only",
                new Diagnostic.Where(group.path() + "/" + name, Optional.empty()),
                Optional.of("reference " + errorEntity.path()
                        + " in the condition, or pick a referenced entity as the error entity"),
                Optional.empty()));
    }

    public static final class Builder {

        private final GroupRef group;
        private final List<String> declaredLocales;
        private String name;
        private ErrorEntity errorEntity;
        private Condition condition;
        private String code;
        private Severity severity;
        private final List<LocalizedText> messages = new ArrayList<>();
        private Comments comments = Comments.none();
        private String rootComment;
        private String rootCommentAfter;

        Builder(GroupRef group, List<String> declaredLocales) {
            this.group = group;
            this.declaredLocales = List.copyOf(declaredLocales);
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder errorField(FieldRef field) {
            this.errorEntity = field;
            return this;
        }

        /**
         * Report on any error entity, a group included: a group's message sits at the group's pointer
         * (KERNEL-FINDINGS KF603). The typed field overloads below are the same setter for a field handle.
         */
        public Builder errorEntity(ErrorEntity entity) {
            this.errorEntity = entity;
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

        public Builder errorField(DateRef field) {
            return errorField(field.field());
        }

        public Builder errorField(TimeRef field) {
            return errorField(field.field());
        }

        public Builder errorField(DateTimeRef field) {
            return errorField(field.field());
        }

        public Builder errorField(DateFragmentRef field) {
            return errorField(field.field());
        }

        public Builder errorField(DateRangeRef field) {
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
         * Attach a {@code ;;} comment to the whole rule — rendered as a leading line ahead of the
         * condition (e.g. {@code ;; cap the monthly fee}). The kernel tolerates the comment as concrete
         * syntax; it survives the typed write. To comment one branch instead of the whole rule, use
         * {@link #comment(Condition, String)}.
         */
        public Builder comment(String text) {
            this.rootComment = text;
            return this;
        }

        /**
         * Attach a {@code ;;} comment to a single condition branch, identified by the very
         * {@link Condition} object handed to {@link #when(Condition)} or one of its subconditions
         * (identity-keyed, so two structurally-equal branches keep distinct comments). Rendered ahead of
         * that branch.
         */
        public Builder comment(Condition node, String text) {
            this.comments = comments.before(node, text);
            return this;
        }

        /**
         * Attach a <em>trailing</em> {@code ;;} comment to the whole rule — rendered at the end of the
         * condition line (e.g. {@code … > 1000 ;; hard cap per policy}). The trailing counterpart of
         * {@link #comment(String)}; rendered newline-terminated so nothing after it is swallowed.
         */
        public Builder commentAfter(String text) {
            this.rootCommentAfter = text;
            return this;
        }

        /**
         * Attach a <em>trailing</em> {@code ;;} comment to a single condition branch, identified by the
         * {@link Condition} object handed to {@link #when(Condition)} or one of its subconditions — the
         * trailing counterpart of {@link #comment(Condition, String)}.
         */
        public Builder commentAfter(Condition node, String text) {
            this.comments = comments.after(node, text);
            return this;
        }

        /**
         * Validate the typed slots and produce the immutable {@link Rule}. {@code build()} fail-fasts on the
         * <b>structural</b> requirements (name, error entity, condition, code, &ge;1 message per declared
         * locale, error entity under the rule's group) but does <b>not</b> run the semantic lint. An unset
         * {@link #severity(Severity)} is DEFAULTED rather than refused — see the note at its check.
         * Valid-but-suspicious shapes the type system can't see — a wrong-{@link #when(Condition) polarity}
         * condition, a negative presence test under a repeatable group, an unguarded number comparison — are
         * surfaced by {@code lint.RuleDiagnostics.diagnose(model, rule)} (the developer's lint backstop), and
         * the real-kernel verdict by {@code validate.RuleValidator}. A structural violation throws a
         * {@code PrecheckException} (an {@code IllegalStateException}) naming the {@code MVK_*}/{@code RK_*} it prevents.
         */
        public Rule build() {
            check(name != null && !name.isBlank(), RkCode.INCOMPLETE_RULE,
                    "a rule needs a name (the element name in its group)", null);
            // The law itself is in the shared leaf, so this builder, Computation.Builder and the portable
            // checker cannot drift apart — which they already had: the computation copy was missing until
            // 2026-09-12, because the code reads as a rule's law.
            check(!ElementNamePredicatesKt.isReservedElementName(name), KernelCode.MVK_RULE_NAME_INVALID,
                    "rule names beginning with vk_vk (any letter case) are reserved by the kernel"
                            + " — pick a different name",
                    "name(...) without the vk_vk prefix");
            check(errorEntity != null, RkCode.INCOMPLETE_RULE,
                    "a rule needs an error entity — errorField(...) or errorEntity(...)", "errorField(...)");
            check(condition != null, RkCode.INCOMPLETE_RULE,
                    "a rule needs an error condition — when(...)", "when(...)");
            check(code != null && !code.isBlank(), RkCode.INCOMPLETE_RULE,
                    "a rule needs an error code — code(...)", "code(...)");
            // NOT checked, and the refusal that used to stand here was an over-rejection: A12 ACCEPTS a
            // stored rule with no `severity` member, and no spelling of absent/null/empty fires
            // MVK_SEVERITY_MISSING (KERNEL-FINDINGS KF291, validate.laws.RuleCodeAndSeverityLawsTest). The
            // default is the CLI's published one — `rule add`'s schema has always said "default ERROR" — so
            // the two authoring surfaces agree and `severity()` stays non-null for every consumer. Emitting
            // no member at all is the other option and is declined while A12's runtime meaning for an absent
            // severity is unmeasured (locked: dsl.RuleSeverityDefaultTest).
            Severity declaredSeverity = severity != null ? severity : Severity.ERROR;
            check(!messages.isEmpty(), KernelCode.MVK_ERROR_MESSAGE_MISSING,
                    "a rule needs at least one error message (the kernel rejects message-less rules)"
                            + " — message(en(...))", "message(en(...))");
            LocalizedText.checkDeclared(declaredLocales, messages, "rule");
            // Strictly: the rule's own group is refused too, since A12 cannot build a model whose rule reports on
            // it (dsl.RuleGroupErrorEntityTest.theRulesOwnGroupIsRefusedAndA12CannotBuildIt).
            check(errorEntity.isStrictlyInside(group), RkCode.ERROR_FIELD_OUTSIDE_GROUP,
                    "the error entity " + errorEntity.path() + " must lie under the rule's group "
                            + group.path() + " — author the rule in a containing group",
                    "author the rule in a group that contains " + errorEntity.path());
            // MVK_ERROR_FIELD_NOT_REFERENCED is NOT hard-checked here (F11) — it mirrors a kernel
            // judgment the live kernel arbitrates, so it is deferred to Rule#deferredChecks().
            Comments finalComments = comments;
            if (rootComment != null) {
                finalComments = finalComments.before(condition, rootComment);
            }
            if (rootCommentAfter != null) {
                finalComments = finalComments.after(condition, rootCommentAfter);
            }
            return new Rule(group, name, errorEntity, condition, code, declaredSeverity, messages,
                    finalComments);
        }

        /** A refusal naming the KERNEL code it prevents. */
        private void check(boolean ok, KernelCode code, String summary, String fix) {
            check(ok, code.name(), Refusals.message(code, summary), fix);
        }

        /** A refusal naming rulekit's OWN code — a shape the kernel has no diagnostic for. */
        private void check(boolean ok, RkCode code, String summary, String fix) {
            check(ok, code.code(), summary, fix);
        }

        /** Fail fast with a structured PRECHECK diagnostic (still an {@code IllegalStateException}). */
        private void check(boolean ok, String code, String summary, String fix) {
            if (ok) {
                return;
            }
            String rulePath = name == null || name.isBlank() ? group.path().value() : group.path().value() + "/" + name;
            throw new PrecheckException(Diagnostic.precheck(Optional.of(code), summary,
                    new Diagnostic.Where(rulePath, Optional.empty()), Optional.ofNullable(fix), Optional.empty()));
        }
    }
}
