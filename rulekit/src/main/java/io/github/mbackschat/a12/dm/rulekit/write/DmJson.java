package io.github.mbackschat.a12.dm.rulekit.write;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;

/**
 * Generic, <b>dsl-free</b> DM-JSON element surgery — {@link ModelWriter}'s tree-level write primitives.
 * Knows only the DM-JSON element shape ({@code type}/{@code id}/{@code <Type>.elements}), nothing about
 * rules. Rulekit-private since Set W: a Jackson-typed signature may not cross a published kit boundary, so
 * the formerly shared {@code core.DmJson} left the public surface and each internal consumer carries its
 * slice of the traversal (siblings: {@code modelkit.DmNodes}, {@code schemakit.Carriage}).
 */
final class DmJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // DM-JSON is parsed the way A12 parses it (`ObjectMapperProvider.createPreconfiguredObjectMapper`):
            // a number keeps the authored literal's exact scale, and a comment is legal. A DEFAULT mapper builds a
            // DoubleNode, so `0.50` is already `0.5` before anything reads it — and because this surface writes the
            // tree back, an unrelated edit then rewrites every number in the model it passes through (measured
            // 2026-07-28; INTERPRETER-FINDINGS IF228). Behavioural guard, not a shared constant, keeps these in
            // step: `cli … NumberSpecScaleFidelityTest` + `cli … CommentedModelSourceTest`.
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private DmJson() {
    }

    /** Return {@code modelJson} with {@code element} appended to the {@code elements} of the group at {@code groupPath} (pretty-printed). */
    static String insertIntoGroup(String modelJson, String groupPath, ObjectNode element) {
        try {
            ObjectNode root = (ObjectNode) FloatTokens.keep(MAPPER.readTree(modelJson));
            ArrayNode elements = groupElements(root, groupPath);
            if (elements == null) {
                throw new IllegalArgumentException("no group at path: " + groupPath);
            }
            elements.add(element);
            // pretty-print so the persisted model stays human-readable and produces reviewable diffs
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(FloatTokens.forWrite(root));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /** The {@code elements} array of the group at the absolute {@code groupPath}, or {@code null}. */
    static ArrayNode groupElements(JsonNode root, String groupPath) {
        return elementsOf(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), groupPath);
    }

    /** Depth-first search for an element of {@code type} (e.g. {@code "Rule"}) by its {@code id}, or {@code null}. */
    static ObjectNode findElement(JsonNode root, String type, String id) {
        return searchElements(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), type, id);
    }

    /** True iff {@code node}'s {@code "type"} discriminator names a Group element. */
    private static boolean isGroup(JsonNode node) {
        return ElementType.GROUP.wireName().equals(node.path(DmKeys.TYPE).asText());
    }

    private static ObjectNode searchElements(JsonNode elements, String type, String id) {
        if (!elements.isArray()) {
            return null;
        }
        for (JsonNode node : elements) {
            if (type.equals(node.path(DmKeys.TYPE).asText()) && id.equals(node.path(DmKeys.ID).asText())) {
                return (ObjectNode) node;
            }
            if (isGroup(node)) {
                ObjectNode found = searchElements(
                        node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS), type, id);
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
            if (!isGroup(node)) {
                continue;
            }
            JsonNode elements = node.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS);
            if (groupPath.equals(node.path(DmKeys.ID).asText())) {
                if (elements.isArray()) {
                    return (ArrayNode) elements;
                }
                // a childless group may serialize WITHOUT its (empty) `elements` array — the kernel omits it;
                // treat the id-match as an empty container and materialize the array so callers can append.
                if (node.path(ElementType.GROUP.wireName()) instanceof ObjectNode group) {
                    return group.putArray(DmKeys.ELEMENTS);
                }
                return null;
            }
            ArrayNode nested = elementsOf(elements, groupPath);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
