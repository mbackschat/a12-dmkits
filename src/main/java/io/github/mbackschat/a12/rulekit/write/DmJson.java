package io.github.mbackschat.a12.rulekit.write;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Generic, <b>dsl-free</b> DM-JSON element surgery — the shared model-core write primitives. The rule
 * layer ({@link ModelWriter}) builds on these, and the future structure layer (modelkit, fork #8 /
 * ARCHITECTURE §5) will reuse the same primitives to insert/find Field/Group/TypeDef nodes. Knows only
 * the DM-JSON element shape ({@code type}/{@code id}/{@code <Type>.elements}), nothing about rules.
 */
public final class DmJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private DmJson() {
    }

    /** Return {@code modelJson} with {@code element} appended to the {@code elements} of the group at {@code groupPath} (pretty-printed). */
    public static String insertIntoGroup(String modelJson, String groupPath, ObjectNode element) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            ArrayNode elements = groupElements(root, groupPath);
            if (elements == null) {
                throw new IllegalArgumentException("no group at path: " + groupPath);
            }
            elements.add(element);
            // pretty-print so the persisted model stays human-readable and produces reviewable diffs
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /** The {@code elements} array of the group at the absolute {@code groupPath}, or {@code null}. */
    public static ArrayNode groupElements(JsonNode root, String groupPath) {
        return elementsOf(root.path("content").path("modelRoot").path("rootGroups"), groupPath);
    }

    /** Depth-first search for an element of {@code type} (e.g. {@code "Rule"}) by its {@code id}, or {@code null}. */
    public static ObjectNode findElement(JsonNode root, String type, String id) {
        return searchElements(root.path("content").path("modelRoot").path("rootGroups"), type, id);
    }

    private static ObjectNode searchElements(JsonNode elements, String type, String id) {
        if (!elements.isArray()) {
            return null;
        }
        for (JsonNode node : elements) {
            String nodeType = node.path("type").asText();
            if (type.equals(nodeType) && id.equals(node.path("id").asText())) {
                return (ObjectNode) node;
            }
            if ("Group".equals(nodeType)) {
                ObjectNode found = searchElements(node.path("Group").path("elements"), type, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ArrayNode elementsOf(JsonNode groups, String groupPath) {
        if (!groups.isArray()) {
            return null;
        }
        for (JsonNode node : groups) {
            if (!"Group".equals(node.path("type").asText())) {
                continue;
            }
            JsonNode elements = node.path("Group").path("elements");
            if (groupPath.equals(node.path("id").asText()) && elements.isArray()) {
                return (ArrayNode) elements;
            }
            ArrayNode nested = elementsOf(elements, groupPath);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
