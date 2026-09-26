package io.github.mbackschat.a12.dm.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One authored rule of an expanded model, as {@link ModelIndex#rules()} enumerates it — kernel-free, and the
 * rule counterpart of {@link ComputationInfo}. It carries the content A12's own {@code Rule} holds
 * ({@code kernel-md-a12internal}'s {@code model.a12internal.Rule}): the error entity, code, condition, severity
 * and the error message as a locale-to-text map, the shape of its {@code ILocalizedTextMap}.
 *
 * <p>The components are the model's text as written. An absent {@code severity} reads as {@code "ERROR"}, the
 * default A12's {@code Rule} declares; an absent error entity or condition reads as the empty string, and
 * {@link #errorEntityPath()} is then empty. The declared {@code errorEntityRelPath} is relative to the rule
 * <em>element</em>, as a computation's target is, so a field of the rule's own group is {@code "../Field"}.
 *
 * <p>Construction goes through {@link #builder()}: seven of the eight components are {@code String}s.
 *
 * @param path               the rule element's name-path (e.g. {@code /Claim/DamageKnown})
 * @param groupPath          the containing group's name-path (e.g. {@code /Claim})
 * @param name               the rule's short name (last path segment)
 * @param errorEntityRelPath the declared error entity, relative to the rule element
 * @param errorCondition     the condition text, in the model's condition language
 * @param errorCode          the error code
 * @param severity           {@code ERROR}, {@code WARNING} or {@code INFO} as authored
 * @param messages           the error message per locale code, in model order
 */
public record RuleInfo(
        String path,
        String groupPath,
        String name,
        String errorEntityRelPath,
        String errorCondition,
        String errorCode,
        String severity,
        Map<String, String> messages) {

    public RuleInfo {
        messages = messages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(messages));
    }

    /** The error entity's absolute name-path, resolved against the rule element; empty when none is declared. */
    public Optional<String> errorEntityPath() {
        return errorEntityRelPath == null || errorEntityRelPath.isEmpty()
                ? Optional.empty()
                : Optional.of(ModelReader.resolveRelPath(path, errorEntityRelPath));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The fluent construction path; an unset component stays {@code null}. */
    public static final class Builder {
        private String path;
        private String groupPath;
        private String name;
        private String errorEntityRelPath;
        private String errorCondition;
        private String errorCode;
        private String severity;
        private Map<String, String> messages = Map.of();

        private Builder() {
        }

        public Builder path(String v) { this.path = v; return this; }
        public Builder groupPath(String v) { this.groupPath = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder errorEntityRelPath(String v) { this.errorEntityRelPath = v; return this; }
        public Builder errorCondition(String v) { this.errorCondition = v; return this; }
        public Builder errorCode(String v) { this.errorCode = v; return this; }
        public Builder severity(String v) { this.severity = v; return this; }
        public Builder messages(Map<String, String> v) { this.messages = v; return this; }

        public RuleInfo build() {
            return new RuleInfo(path, groupPath, name, errorEntityRelPath, errorCondition, errorCode, severity,
                    messages);
        }
    }
}
