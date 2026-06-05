package io.github.mbackschat.a12.dm.rulekit.write;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;

/**
 * The F6 DM-JSON write path for <b>rules</b> (D9: the public, polyglot write surface — no internal
 * builders). Owns the {@code Rule} node shape; the generic tree surgery lives in {@link DmJson} (the
 * dsl-free shared half the future modelkit reuses). Re-binding the result through {@code ModelLoader}
 * re-runs {@code checkConsistency}, so a bad write fails loudly.
 */
public final class ModelWriter {

    private static final ObjectMapper MAPPER = DmJson.MAPPER;

    private ModelWriter() {
    }

    /** Returns {@code modelJson} with {@code rule} (a built {@link Rule}, with its real messages) added to its target group. */
    public static String addRule(String modelJson, Rule rule) {
        return DmJson.insertIntoGroup(modelJson, rule.group().path(),
                ruleNode(rule.group().path() + "/" + rule.name(), rule.name(), rule.errorEntityRelPath(),
                        rule.code(), rule.render(), rule.severity().name(), rule.messages()));
    }

    /**
     * Returns {@code modelJson} with a rule added to {@code groupPath} from its raw slots — the
     * polyglot/CLI authoring path (a condition already rendered to DSL text + real localized messages).
     * The caller is expected to have validated it first ({@code RuleValidator.check}).
     */
    public static String addRule(String modelJson, String groupPath, String name, String errorEntityRelPath,
            String errorCode, String errorCondition, String severity, List<LocalizedText> messages) {
        return DmJson.insertIntoGroup(modelJson, groupPath,
                ruleNode(groupPath + "/" + name, name, errorEntityRelPath, errorCode, errorCondition, severity, messages));
    }

    /**
     * Returns {@code modelJson} with the {@code errorCondition} of the rule at {@code ruleFullName}
     * replaced by {@code newConditionText} (F8 #3 write-back). Only the condition slot changes.
     */
    public static String replaceCondition(String modelJson, String ruleFullName, String newConditionText) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(modelJson);
            ObjectNode rule = DmJson.findElement(root, "Rule", ruleFullName);
            if (rule == null) {
                throw new IllegalArgumentException("no rule at path: " + ruleFullName);
            }
            ((ObjectNode) rule.path("Rule")).put("errorCondition", newConditionText);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /** Build a {@code Rule} element node from its slots (the one place that knows the DM-JSON rule shape). */
    private static ObjectNode ruleNode(String id, String name, String errorEntityRelPath, String errorCode,
            String errorCondition, String severity, List<LocalizedText> messages) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Rule");
        node.put("id", id);
        node.put("name", name);

        ObjectNode body = node.putObject("Rule");
        body.put("errorEntityRelPath", errorEntityRelPath);
        body.put("errorCode", errorCode);
        body.put("errorCondition", errorCondition);
        body.put("severity", severity);

        ArrayNode messageArray = body.putArray("errorMessage");
        for (LocalizedText text : messages) {
            ObjectNode message = messageArray.addObject();
            message.put("locale", text.locale());
            message.put("text", text.text());
        }
        return node;
    }
}
