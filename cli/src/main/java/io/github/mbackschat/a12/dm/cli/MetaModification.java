package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        List<String> removeExternalDescriptions) {

    /** The {@code META_MODIFY} directive keys, excluding its polymorphic {@code ref} locator. */
    public static final Set<String> KEYS = Set.of(
            "label", "annotation", "remove-annotation",
            "internal-description", "external-description",
            "remove-internal-description", "remove-external-description");

    public MetaModification {
        labels = copyMap(labels);
        annotations = copyMap(annotations);
        removeAnnotations = copyList(removeAnnotations);
        internalDescriptions = copyMap(internalDescriptions);
        externalDescriptions = copyMap(externalDescriptions);
        removeInternalDescriptions = copyList(removeInternalDescriptions);
        removeExternalDescriptions = copyList(removeExternalDescriptions);
    }

    /** Parse the canonical metadata operation-record vocabulary used by {@code apply}. */
    public static MetaModification fromJson(JsonNode op) {
        return new MetaModification(
                textMap(op, "label"),
                textMap(op, "annotation"),
                stringValues(op, "remove-annotation"),
                textMap(op, "internal-description"),
                textMap(op, "external-description"),
                stringValues(op, "remove-internal-description"),
                stringValues(op, "remove-external-description"));
    }

    /** True when the operation has no directive and must be refused rather than staged as a no-op. */
    public boolean isEmpty() {
        return labels.isEmpty() && annotations.isEmpty() && removeAnnotations.isEmpty()
                && internalDescriptions.isEmpty() && externalDescriptions.isEmpty()
                && removeInternalDescriptions.isEmpty() && removeExternalDescriptions.isEmpty();
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
