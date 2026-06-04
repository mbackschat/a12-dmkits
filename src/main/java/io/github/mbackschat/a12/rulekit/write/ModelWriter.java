package io.github.mbackschat.a12.rulekit.write;

import java.io.IOException;
import java.io.UncheckedIOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.rulekit.dsl.Rule;

/**
 * The F6 DM-JSON write path (D9: the public, polyglot write surface — no internal builders): inject
 * a whole {@code Rule} element, with its <em>real</em> localized messages, into a model's DM-JSON by
 * Jackson tree surgery. The kernel's write gate ({@code BasicConsistencyCheck}, instrument 4) is the
 * output contract; rulekit's authoring builders already satisfy its rule subset, and re-binding the
 * result through {@code ModelLoader} re-runs {@code checkConsistency} so a bad write fails loudly.
 */
public final class ModelWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelWriter() {
    }

    /** Returns {@code modelJson} with {@code rule} added to its target group's elements. */
    public static String addRule(String modelJson, Rule rule) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            JsonNode rootGroups = root.path("content").path("modelRoot").path("rootGroups");
            ArrayNode elements = elementsOf(rootGroups, rule.group().path());
            if (elements == null) {
                throw new IllegalArgumentException("no group at path: " + rule.group().path());
            }
            elements.add(ruleNode(rule));
            // pretty-print so the persisted model stays human-readable and produces reviewable diffs
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /**
     * Returns {@code modelJson} with the {@code errorCondition} of the rule at {@code ruleFullName}
     * replaced by {@code newConditionText} (F8 #3 write-back). Only the condition slot changes —
     * messages, error field, code, and severity are left exactly as authored. Re-binding the result
     * re-runs {@code checkConsistency}, so a write that breaks the rule fails loudly.
     */
    public static String replaceCondition(String modelJson, String ruleFullName, String newConditionText) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            JsonNode rootGroups = root.path("content").path("modelRoot").path("rootGroups");
            ObjectNode rule = findRule(rootGroups, ruleFullName);
            if (rule == null) {
                throw new IllegalArgumentException("no rule at path: " + ruleFullName);
            }
            ((ObjectNode) rule.path("Rule")).put("errorCondition", newConditionText);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /** Depth-first search for a {@code Rule} element by its id (full path), returning its node. */
    private static ObjectNode findRule(JsonNode elements, String ruleFullName) {
        if (!elements.isArray()) {
            return null;
        }
        for (JsonNode node : elements) {
            String type = node.path("type").asText();
            if ("Rule".equals(type) && ruleFullName.equals(node.path("id").asText())) {
                return (ObjectNode) node;
            }
            if ("Group".equals(type)) {
                ObjectNode found = findRule(node.path("Group").path("elements"), ruleFullName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static ObjectNode ruleNode(Rule rule) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Rule");
        node.put("id", rule.group().path() + "/" + rule.name());
        node.put("name", rule.name());

        ObjectNode body = node.putObject("Rule");
        body.put("errorEntityRelPath", rule.errorEntityRelPath());
        body.put("errorCode", rule.code());
        body.put("errorCondition", rule.render());
        body.put("severity", rule.severity().name());

        ArrayNode messages = body.putArray("errorMessage");
        for (LocalizedText text : rule.messages()) {
            ObjectNode message = messages.addObject();
            message.put("locale", text.locale());
            message.put("text", text.text());
        }
        return node;
    }

    /** Depth-first search for a group node by its id (absolute path), returning its elements array. */
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
