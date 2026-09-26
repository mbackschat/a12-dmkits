package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.ArrayList;
import java.util.List;

import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

/**
 * The minimal data needed to place a rule into a model for a ground-truth check: the group it lives
 * in, the error entity (a path relative to the rule), the rendered {@code errorCondition}, an error
 * code, a severity ({@code "ERROR"} / {@code "WARNING"} / {@code "INFO"}), and — optionally — the localized
 * error texts. Mirrors the DM-JSON {@code Rule} shape.
 *
 * <p><b>{@code messages} is what lets a candidate check reach the kernel's SECOND checker.</b> A rule must
 * carry error text or the kernel reports {@code MVK_ERROR_MESSAGE_MISSING}, so {@link RuleValidator} injects a
 * placeholder per declared locale — a text that is always valid, which is precisely why a candidate check never
 * exercised {@code RuleErrortextChecker}. Supplying messages here injects those instead, for the locales given;
 * the rest keep the placeholder, so a one-locale candidate checks the text under test rather than drawing a
 * missing-locale finding the caller never claimed to be authoring. Empty (the default) is the placeholder-only
 * behaviour every existing caller had.
 *
 * <p>Construction goes through {@link #builder()}: six of the seven components are {@code String}s, which is
 * exactly the silently-swap-prone shape the project's builder rule names. The six-argument constructor is kept
 * for the message-free case that predates this component.
 */
public record DraftRule(
        String groupPath,
        String name,
        String errorEntityRelPath,
        String errorCondition,
        String errorCode,
        String severity,
        List<LocalizedText> messages) {

    public DraftRule {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** The message-free draft: the kernel sees {@link RuleValidator}'s per-locale placeholder error text. */
    public DraftRule(String groupPath, String name, String errorEntityRelPath, String errorCondition,
            String errorCode, String severity) {
        this(groupPath, name, errorEntityRelPath, errorCondition, errorCode, severity, List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** This draft with another condition, every other component kept: one candidate of a near-miss sweep. */
    public DraftRule withErrorCondition(String condition) {
        return new DraftRule(groupPath, name, errorEntityRelPath, condition, errorCode, severity, messages);
    }

    /**
     * This draft with {@code messages} in place of its own, every other component kept. The builder's
     * {@link Builder#messages} adds rather than replaces, which is why this is not spelled through it.
     */
    public DraftRule withMessages(List<LocalizedText> messages) {
        return new DraftRule(groupPath, name, errorEntityRelPath, errorCondition, errorCode, severity, messages);
    }

    /** The fluent construction path; a null/empty argument means "leave this aspect unset". */
    public static final class Builder {
        private String groupPath;
        private String name;
        private String errorEntityRelPath;
        private String errorCondition;
        private String errorCode;
        private String severity;
        private final List<LocalizedText> messages = new ArrayList<>();

        private Builder() {
        }

        public Builder groupPath(String v) { this.groupPath = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder errorEntityRelPath(String v) { this.errorEntityRelPath = v; return this; }
        public Builder errorCondition(String v) { this.errorCondition = v; return this; }
        public Builder errorCode(String v) { this.errorCode = v; return this; }
        public Builder severity(String v) { this.severity = v; return this; }

        /** Add localized error texts to check; repeatable, and cumulative across calls. */
        public Builder messages(List<LocalizedText> v) {
            if (v != null) {
                this.messages.addAll(v);
            }
            return this;
        }

        public DraftRule build() {
            return new DraftRule(groupPath, name, errorEntityRelPath, errorCondition, errorCode, severity,
                    messages);
        }
    }
}
