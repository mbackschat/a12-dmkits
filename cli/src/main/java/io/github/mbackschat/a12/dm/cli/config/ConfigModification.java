package io.github.mbackschat.a12.dm.cli.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.cli.RolesMerge;
import io.github.mbackschat.a12.dm.edit.EditSession;

/**
 * The shared {@code config modify} setter cascade (T4) — a value object holding the document-config
 * directives, single-sourcing the mutation + {@code changed}-report between the standalone
 * {@code config modify} verb and the {@code apply} {@code CONFIG_MODIFY} op. Both build one of these
 * (from picocli options / from a JSON op) and call {@link #applyTo}; the cascade runs exactly once, so the
 * two surfaces can't drift. The receiver is a mutable {@link EditSession} for both — the standalone was
 * migrated off the immutable {@code ModelDocument} onto {@code EditSession} (the cleaner shape: it matches
 * the sibling {@code group modify}, and {@code EditSession} already carries every config setter + the
 * {@code validate().toJson()} terminal). Mirrors the {@code FieldSpec}/{@code FieldModification} pattern.
 *
 * <p>Built via {@link #builder()} — a positional 15-arg constructor over this many same-typed directives
 * (several strings, booleans, and lists) is too swap-prone (the {@code FieldModification} rule). An absent
 * directive (a {@code null} scalar / empty collection / {@code false} flag) means "leave it unchanged".
 */
public record ConfigModification(
        String decimalSeparator, String timeZone, String conditionLanguage,
        Map<String, String> labels, Map<String, String> annotations,
        List<String> supportedCharacters, String comment, boolean clearComment,
        Integer baseYear, Boolean fieldRefByShortName,
        List<String> addLocales, List<String> removeLocales,
        List<String> roles, boolean clearRoles, List<String> removeAnnotations) {

    public ConfigModification {
        labels = copy(labels);
        annotations = copy(annotations);
        supportedCharacters = copy(supportedCharacters);
        addLocales = copy(addLocales);
        removeLocales = copy(removeLocales);
        roles = copy(roles);
        removeAnnotations = copy(removeAnnotations);
    }

    /** The {@code CONFIG_MODIFY} op-record keys (single-sourced for the apply op's key set). */
    public static final Set<String> KEYS = Set.of("decimal-separator", "time-zone", "condition-language",
            "label", "annotation", "comment", "clear-comment", "add-locale", "remove-locale", "role", "clear-roles",
            "supported-character", "base-year", "field-ref-by-short-name", "remove-annotation");

    public static Builder builder() {
        return new Builder();
    }

    /** Parse an {@code apply} {@code CONFIG_MODIFY} op-record into a modification (a scalar binds like a 1-element list). */
    public static ConfigModification fromJson(JsonNode op) {
        return builder()
                .decimalSeparator(text(op, "decimal-separator")).timeZone(text(op, "time-zone"))
                .conditionLanguage(text(op, "condition-language"))
                .labels(textMap(op, "label")).annotations(textMap(op, "annotation"))
                .supportedCharacters(stringValues(op, "supported-character")).comment(text(op, "comment"))
                .clearComment(op.path("clear-comment").asBoolean(false))
                .baseYear(op.hasNonNull("base-year") ? op.get("base-year").asInt() : null)
                .fieldRefByShortName(op.hasNonNull("field-ref-by-short-name")
                        ? op.get("field-ref-by-short-name").asBoolean() : null)
                .addLocales(stringValues(op, "add-locale")).removeLocales(stringValues(op, "remove-locale"))
                .roles(stringValues(op, "role")).clearRoles(op.path("clear-roles").asBoolean(false))
                .removeAnnotations(stringValues(op, "remove-annotation"))
                .build();
    }

    /** True when no directive is set — the "nothing to change" guard, shared by both surfaces. */
    public boolean isEmpty() {
        return decimalSeparator == null && timeZone == null && conditionLanguage == null
                && labels.isEmpty() && annotations.isEmpty() && supportedCharacters.isEmpty() && comment == null
                && addLocales.isEmpty() && removeLocales.isEmpty() && roles.isEmpty() && !clearRoles
                && baseYear == null && fieldRefByShortName == null && !clearComment && removeAnnotations.isEmpty();
    }

    /** Run the config setter cascade on {@code session}, recording each change in {@code changed}. */
    public void applyTo(EditSession session, ObjectNode changed) {
        if (decimalSeparator != null) {
            session.setDecimalSeparator(decimalSeparator);
            changed.put("decimalSeparator", decimalSeparator);
        }
        if (timeZone != null) {
            session.setTimeZone(timeZone);
            changed.put("timeZone", timeZone);
        }
        if (conditionLanguage != null) {
            session.setConditionLanguage(conditionLanguage);
            changed.put("conditionLanguage", conditionLanguage);
        }
        labels.forEach((locale, text) -> {
            session.setModelLabel(locale, text);
            changed.put("label." + locale, text);
        });
        annotations.forEach((name, value) -> {
            session.setModelAnnotation(name, value);
            changed.put("annotation." + name, value);
        });
        if (!supportedCharacters.isEmpty()) {
            session.setSupportedCharacters(supportedCharacters);
            changed.put("supportedCharacters", String.join(" ", supportedCharacters));
        }
        if (comment != null) {
            session.setModelComment(comment);
            changed.put("comment", comment);
        }
        if (baseYear != null) {
            session.setBaseYear(baseYear);
            changed.put("baseYear", baseYear);
        }
        if (fieldRefByShortName != null) {
            session.setFieldRefByShortNameAllowed(fieldRefByShortName);
            changed.put("fieldRefByShortNameAllowed", fieldRefByShortName);
        }
        if (clearComment) {
            session.clearModelComment();
            changed.putNull("comment");
        }
        for (String name : removeAnnotations) {
            session.removeModelAnnotation(name);
            changed.putNull("annotation." + name);
        }
        applyLocales(session, changed);
        if (!roles.isEmpty()) {
            // MERGE into the comma-separated `roles` annotation (the shared G26 algorithm): a one-role edit
            // keeps the rest, unlike a raw `annotation roles=…` that clobbers the list.
            String joined = RolesMerge.merge(session.modelAnnotations().get("roles"), roles);
            session.setModelAnnotation("roles", joined);
            changed.put("roles", joined);
        }
        if (clearRoles) {
            session.removeModelAnnotation("roles");
            changed.putNull("roles");
        }
    }

    /** Add/remove display locales idempotently — only actually-changed codes are applied and reported (as arrays). */
    private void applyLocales(EditSession session, ObjectNode changed) {
        Set<String> current = new LinkedHashSet<>(session.locales());
        ArrayNode added = null;
        for (String code : addLocales) {
            if (current.add(code)) {
                session.addLocale(code);
                if (added == null) {
                    added = changed.putArray("addedLocales");
                }
                added.add(code);
            }
        }
        ArrayNode removed = null;
        for (String code : removeLocales) {
            if (current.remove(code)) {
                session.removeLocale(code);
                if (removed == null) {
                    removed = changed.putArray("removedLocales");
                }
                removed.add(code);
            }
        }
    }

    private static Map<String, String> copy(Map<String, String> m) {
        return m == null ? Map.of() : Map.copyOf(m);
    }

    private static List<String> copy(List<String> l) {
        return l == null ? List.of() : List.copyOf(l);
    }

    private static String text(JsonNode op, String key) {
        return op.hasNonNull(key) ? op.get(key).asText() : null;
    }

    private static Map<String, String> textMap(JsonNode op, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (op.hasNonNull(key)) {
            op.get(key).fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        }
        return out;
    }

    /** A repeatable op-record key: an array yields its elements, a bare scalar binds as a 1-element list (Issue A). */
    private static List<String> stringValues(JsonNode op, String key) {
        List<String> out = new ArrayList<>();
        JsonNode node = op.get(key);
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        } else if (node != null && !node.isNull()) {
            out.add(node.asText());
        }
        return out;
    }

    /** Collects the directives; a {@code null}/empty/{@code false} argument means "leave this setting unchanged". */
    public static final class Builder {
        private String decimalSeparator;
        private String timeZone;
        private String conditionLanguage;
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private List<String> supportedCharacters;
        private String comment;
        private boolean clearComment;
        private Integer baseYear;
        private Boolean fieldRefByShortName;
        private List<String> addLocales;
        private List<String> removeLocales;
        private List<String> roles;
        private boolean clearRoles;
        private List<String> removeAnnotations;

        public Builder decimalSeparator(String v) { this.decimalSeparator = v; return this; }
        public Builder timeZone(String v) { this.timeZone = v; return this; }
        public Builder conditionLanguage(String v) { this.conditionLanguage = v; return this; }
        public Builder labels(Map<String, String> v) { this.labels = v; return this; }
        public Builder annotations(Map<String, String> v) { this.annotations = v; return this; }
        public Builder supportedCharacters(List<String> v) { this.supportedCharacters = v; return this; }
        public Builder comment(String v) { this.comment = v; return this; }
        public Builder clearComment(boolean v) { this.clearComment = v; return this; }
        public Builder baseYear(Integer v) { this.baseYear = v; return this; }
        public Builder fieldRefByShortName(Boolean v) { this.fieldRefByShortName = v; return this; }
        public Builder addLocales(List<String> v) { this.addLocales = v; return this; }
        public Builder removeLocales(List<String> v) { this.removeLocales = v; return this; }
        public Builder roles(List<String> v) { this.roles = v; return this; }
        public Builder clearRoles(boolean v) { this.clearRoles = v; return this; }
        public Builder removeAnnotations(List<String> v) { this.removeAnnotations = v; return this; }

        public ConfigModification build() {
            return new ConfigModification(decimalSeparator, timeZone, conditionLanguage, labels, annotations,
                    supportedCharacters, comment, clearComment, baseYear, fieldRefByShortName,
                    addLocales, removeLocales, roles, clearRoles, removeAnnotations);
        }
    }
}
