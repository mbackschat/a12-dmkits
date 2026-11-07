package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;

/**
 * The shared G45 metadata setter cascade for standalone {@code meta <ref>} and transactional
 * {@code apply} {@code META_MODIFY}. Common metadata has one post-creation write implementation and one
 * vocabulary; element-specific modifiers do not duplicate it.
 */
public record MetaModification(
        Map<String, String> labels,
        Map<String, String> annotations,
        List<String> removeAnnotations,
        Map<String, String> internalDescriptions,
        Map<String, String> externalDescriptions,
        List<String> removeInternalDescriptions,
        List<String> removeExternalDescriptions,
        Optional<String> changeNote,
        boolean removeChangeNote) {

    /** The {@code META_MODIFY} directive keys, excluding its polymorphic {@code ref} locator. */
    public static final Set<String> KEYS = Set.of(
            "label", "annotation", "remove-annotation",
            "internal-description", "external-description",
            "remove-internal-description", "remove-external-description",
            "change-note", "remove-change-note");

    public MetaModification {
        labels = copyMap(labels);
        annotations = copyMap(annotations);
        removeAnnotations = copyList(removeAnnotations);
        internalDescriptions = copyMap(internalDescriptions);
        externalDescriptions = copyMap(externalDescriptions);
        removeInternalDescriptions = copyList(removeInternalDescriptions);
        removeExternalDescriptions = copyList(removeExternalDescriptions);
        changeNote = changeNote == null ? Optional.empty() : changeNote;
    }

    /** The public construction path; an unset directive is empty. */
    public static Builder builder() {
        return new Builder();
    }

    /** Parse the canonical metadata operation-record vocabulary used by {@code apply}. */
    public static MetaModification fromJson(JsonNode op) {
        return builder()
                .labels(textMap(op, "label"))
                .annotations(textMap(op, "annotation"))
                .removeAnnotations(stringValues(op, "remove-annotation"))
                .internalDescriptions(textMap(op, "internal-description"))
                .externalDescriptions(textMap(op, "external-description"))
                .removeInternalDescriptions(stringValues(op, "remove-internal-description"))
                .removeExternalDescriptions(stringValues(op, "remove-external-description"))
                .changeNote(op.hasNonNull("change-note") ? op.get("change-note").asText() : null)
                .removeChangeNote(op.path("remove-change-note").asBoolean(false))
                .build();
    }

    /** True when the operation has no directive and must be refused rather than staged as a no-op. */
    public boolean isEmpty() {
        return labels.isEmpty() && annotations.isEmpty() && removeAnnotations.isEmpty()
                && internalDescriptions.isEmpty() && externalDescriptions.isEmpty()
                && removeInternalDescriptions.isEmpty() && removeExternalDescriptions.isEmpty()
                && changeNote.isEmpty() && !removeChangeNote;
    }

    /**
     * Apply this operation to {@code ref}. Label writes merge with existing locales; all other maps upsert
     * individual entries. The caller runs existence/labelability safety checks before invoking this method.
     */
    public void applyTo(String ref, ModelDocument current, EditSession session, ObjectNode changed) {
        changed.put("ref", ref);
        if (!labels.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(current.labels(ref).orElseThrow());
            merged.putAll(labels);
            session.setLabel(ref, merged);
            labels.forEach((locale, text) -> changed.put("label." + locale, text));
        }
        annotations.forEach((name, value) -> {
            session.setAnnotation(ref, name, value);
            changed.put("annotation." + name, value);
        });
        removeAnnotations.forEach(name -> {
            session.removeAnnotation(ref, name);
            changed.putNull("annotation." + name);
        });
        internalDescriptions.forEach((locale, text) -> {
            session.setInternalDescription(ref, locale, text);
            changed.put("internalDescription." + locale, text);
        });
        externalDescriptions.forEach((locale, text) -> {
            session.setExternalDescription(ref, locale, text);
            changed.put("externalDescription." + locale, text);
        });
        removeInternalDescriptions.forEach(locale -> {
            session.removeInternalDescription(ref, locale);
            changed.putNull("internalDescription." + locale);
        });
        removeExternalDescriptions.forEach(locale -> {
            session.removeExternalDescription(ref, locale);
            changed.putNull("externalDescription." + locale);
        });
        if (removeChangeNote) {
            session.removeChangeNote(ref);
            changed.putNull("changeNote");
        }
        changeNote.ifPresent(note -> {
            session.setChangeNote(ref, note);
            changed.put("changeNote", note);
        });
    }

    /** Builds a {@link MetaModification}; each setter replaces that directive. */
    public static final class Builder {
        private Map<String, String> labels;
        private Map<String, String> annotations;
        private List<String> removeAnnotations;
        private Map<String, String> internalDescriptions;
        private Map<String, String> externalDescriptions;
        private List<String> removeInternalDescriptions;
        private List<String> removeExternalDescriptions;
        private String changeNote;
        private boolean removeChangeNote;

        private Builder() {
        }

        public Builder labels(Map<String, String> v) { labels = v; return this; }
        public Builder annotations(Map<String, String> v) { annotations = v; return this; }
        public Builder removeAnnotations(List<String> v) { removeAnnotations = v; return this; }
        public Builder internalDescriptions(Map<String, String> v) { internalDescriptions = v; return this; }
        public Builder externalDescriptions(Map<String, String> v) { externalDescriptions = v; return this; }
        public Builder removeInternalDescriptions(List<String> v) { removeInternalDescriptions = v; return this; }
        public Builder removeExternalDescriptions(List<String> v) { removeExternalDescriptions = v; return this; }
        public Builder changeNote(String v) { changeNote = v; return this; }
        public Builder removeChangeNote(boolean v) { removeChangeNote = v; return this; }

        public MetaModification build() {
            return new MetaModification(labels, annotations, removeAnnotations, internalDescriptions,
                    externalDescriptions, removeInternalDescriptions, removeExternalDescriptions,
                    Optional.ofNullable(changeNote), removeChangeNote);
        }
    }

    private static Map<String, String> copyMap(Map<String, String> values) {
        return values == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static List<String> copyList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static Map<String, String> textMap(JsonNode op, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        if (op.hasNonNull(key)) {
            op.get(key).fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
        }
        return out;
    }

    /** A repeatable op key accepts an array or one scalar, matching the rest of {@code apply}. */
    private static List<String> stringValues(JsonNode op, String key) {
        List<String> out = new ArrayList<>();
        JsonNode value = op.get(key);
        if (value != null && value.isArray()) {
            value.forEach(item -> out.add(item.asText()));
        } else if (value != null && !value.isNull()) {
            out.add(value.asText());
        }
        return out;
    }
}
