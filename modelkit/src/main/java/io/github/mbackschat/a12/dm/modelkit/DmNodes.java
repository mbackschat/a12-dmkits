package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;
import io.github.mbackschat.a12.dm.core.ModelInputException;

/**
 * Modelkit-private DM-JSON node tests + group-elements traversal. Since Set W a Jackson-typed signature may
 * not cross a published kit boundary, so the formerly shared {@code core.DmJson}/{@code ElementType.matches}
 * forms are no longer public API — each internal consumer carries its slice of the traversal (siblings:
 * {@code rulekit.write.DmJson}, {@code schemakit.Carriage}), composed from the public wire vocabulary
 * ({@link DmKeys}, {@link ElementType#wireName()}).
 */
final class DmNodes {

    private DmNodes() {
    }

    /** True iff {@code node}'s {@code "type"} discriminator names {@code type}. */
    static boolean is(ElementType type, JsonNode node) {
        return type.wireName().equals(node.path(DmKeys.TYPE).asText());
    }

    /**
     * A Group element's body, read without changing the document. Kernel 31.1.1 reads a missing or {@code null} body
     * as an empty group on every carrier, and builds no model from any other non-object body ([KERNEL-FINDINGS KF3],
     * {@code adapter.WorkspaceIncludeStructureKernelTest}); a reading must not respell the author's text, so the empty
     * group is a detached node.
     */
    static ObjectNode groupBody(JsonNode group) {
        JsonNode body = group.path(ElementType.GROUP.wireName());
        if (body instanceof ObjectNode object) {
            return object;
        }
        requireNoValue(group, body);
        return JsonNodeFactory.instance.objectNode();
    }

    /** {@link #groupBody}, for an edit: the empty group A12 reads is written out, so the edit lands in the document. */
    static ObjectNode editableGroupBody(ObjectNode group) {
        JsonNode body = group.path(ElementType.GROUP.wireName());
        if (body instanceof ObjectNode object) {
            return object;
        }
        requireNoValue(group, body);
        return group.putObject(ElementType.GROUP.wireName());
    }

    private static void requireNoValue(JsonNode group, JsonNode body) {
        if (!body.isMissingNode() && !body.isNull()) {
            throw new ModelInputException("the group " + group.path(DmKeys.ID).asText() + " has a Group body that is"
                    + " not an object, and A12 builds no model from it; write {\"elements\": [...]}, or {} for an empty"
                    + " group");
        }
    }

    /** The {@code elements} array of the group at the absolute {@code groupPath}, or {@code null}. */
    static ArrayNode groupElements(JsonNode root, String groupPath) {
        return elementsOf(root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS), groupPath);
    }

    private static ArrayNode elementsOf(JsonNode groups, String groupPath) {
        if (!groups.isArray()) {
            return null;
        }
        for (JsonNode node : groups) {
            if (!is(ElementType.GROUP, node)) {
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
