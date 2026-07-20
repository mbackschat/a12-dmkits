package io.github.mbackschat.a12.dm.cli.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;

/** Immutable path index for the optional metadata facets of {@code model report --tree}. */
final class ModelTreeMetadataIndex {

    private final Map<String, Metadata> byPath;
    private final int traversalVisits;

    private ModelTreeMetadataIndex(Map<String, Metadata> byPath, int traversalVisits) {
        this.byPath = Collections.unmodifiableMap(new LinkedHashMap<>(byPath));
        this.traversalVisits = traversalVisits;
    }

    static ModelTreeMetadataIndex from(JsonNode expandedRoot) {
        Map<String, Metadata> byPath = new LinkedHashMap<>();
        int visits = collect(rootGroups(expandedRoot), byPath);
        return new ModelTreeMetadataIndex(byPath, visits);
    }

    Metadata metadata(String path) {
        Metadata metadata = byPath.get(path);
        if (metadata == null) {
            throw new IllegalArgumentException("no group or field metadata at path: " + path);
        }
        return metadata;
    }

    int indexedElementCount() {
        return byPath.size();
    }

    int traversalVisitCount() {
        return traversalVisits;
    }

    private static int collect(JsonNode elements, Map<String, Metadata> byPath) {
        int visits = 0;
        for (JsonNode element : elements) {
            ElementType type = ElementType.fromWire(element.path(DmKeys.TYPE).asText(null));
            if (type != ElementType.GROUP && type != ElementType.FIELD) {
                continue;
            }
            visits++;
            String path = element.path(DmKeys.ID).asText();
            byPath.putIfAbsent(path, Metadata.from(element, type));
            if (type == ElementType.GROUP) {
                visits += collect(element.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS), byPath);
            }
        }
        return visits;
    }

    private static Map<String, String> localized(JsonNode element, String key) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode entry : element.path(key)) {
            values.put(entry.path(DmKeys.LOCALE).asText(), entry.path(DmKeys.TEXT).asText());
        }
        return immutable(values);
    }

    private static Map<String, String> annotations(JsonNode element) {
        Map<String, String> values = new LinkedHashMap<>();
        for (JsonNode annotation : element.path(DmKeys.ANNOTATIONS)) {
            values.put(annotation.path(DmKeys.NAME).asText(), annotation.path(DmKeys.VALUE).asText());
        }
        return immutable(values);
    }

    private static Map<String, String> immutable(Map<String, String> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static JsonNode rootGroups(JsonNode root) {
        return root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS);
    }

    static final class Metadata {
        private final Map<String, String> internal;
        private final Map<String, String> external;
        private final Map<String, String> helper;
        private final Map<String, String> annotations;

        private Metadata(JsonNode element, ElementType type) {
            this.internal = localized(element, DmKeys.INTERNAL_DESCRIPTION);
            this.external = localized(element, DmKeys.EXTERNAL_DESCRIPTION);
            this.helper = type == ElementType.FIELD
                    ? localized(element.path(ElementType.FIELD.wireName()), "helperText")
                    : Map.of();
            this.annotations = ModelTreeMetadataIndex.annotations(element);
        }

        private static Metadata from(JsonNode element, ElementType type) {
            return new Metadata(element, type);
        }

        Map<String, String> internal() {
            return internal;
        }

        Map<String, String> external() {
            return external;
        }

        Map<String, String> helper() {
            return helper;
        }

        Map<String, String> annotations() {
            return annotations;
        }
    }
}
