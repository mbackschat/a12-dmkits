package io.github.mbackschat.a12.dm.rulekit.write;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.DmJson;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * The F6 DM-JSON write path for <b>rules</b> (D9: the public, polyglot write surface — no internal
 * builders). Owns the {@code Rule} node shape; the generic tree surgery lives in {@link DmJson} (the
 * dsl-free shared half in {@code dm.core} that modelkit also reuses). Re-binding the result through
 * {@code ModelLoader} re-runs {@code checkConsistency}, so a bad write fails loudly.
 */
public final class ModelWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * replaced by {@code newConditionText} (F8 #3 write-back). Only the condition slot changes. The
     * old condition's <b>leading {@code ;;} comment is preserved by default</b> ({@link Comments#preserveLeading})
     * — a re-expression that drops the comment from its AST still keeps the explanatory note, unless the
     * new text supplies its own.
     */
    public static String replaceCondition(String modelJson, String ruleFullName, String newConditionText) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(modelJson);
            ObjectNode rule = DmJson.findElement(root, "Rule", ruleFullName);
            if (rule == null) {
                throw new IllegalArgumentException("no rule at path: " + ruleFullName);
            }
            ObjectNode body = (ObjectNode) rule.path("Rule");
            String oldText = body.path("errorCondition").asText("");
            body.put("errorCondition", Comments.preserveLeading(oldText, newConditionText));
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /** Returns {@code modelJson} with {@code computation} (a built {@link Computation}) added to its computed field's group (F6). */
    public static String addComputation(String modelJson, Computation computation) {
        return DmJson.insertIntoGroup(modelJson, computation.groupPath(),
                computationNode(computation.draft(), computation.messages()));
    }

    /**
     * Returns {@code modelJson} with a computation added from its raw slots ({@link DraftComputation}) plus
     * real localized messages — the polyglot/CLI authoring path (the {@code addRule}-from-slots peer). The
     * caller is expected to have validated it first ({@code RuleValidator.check}).
     */
    public static String addComputation(String modelJson, DraftComputation computation, List<LocalizedText> messages) {
        return DmJson.insertIntoGroup(modelJson, computation.groupPath(), computationNode(computation, messages));
    }

    /**
     * Returns {@code modelJson} with the computation at {@code computationFullName} replaced by a re-expressed
     * one given as raw slots + messages (the slot-based {@link #replaceComputation(String, String, Computation)}
     * peer for the CLI) — remove the old element, add the re-expressed one.
     */
    public static String replaceComputation(String modelJson, String computationFullName,
            DraftComputation computation, List<LocalizedText> messages) {
        return addComputation(removeComputation(modelJson, computationFullName), computation, messages);
    }

    /** Returns {@code modelJson} with the Rule element at {@code ruleFullName} removed from its group. */
    public static String removeRule(String modelJson, String ruleFullName) {
        return removeElement(modelJson, "Rule", ruleFullName);
    }

    /** Returns {@code modelJson} with the Computation element at {@code computationFullName} removed from its group. */
    public static String removeComputation(String modelJson, String computationFullName) {
        return removeElement(modelJson, "Computation", computationFullName);
    }

    /**
     * Returns {@code modelJson} with the computation at {@code computationFullName} replaced by
     * {@code computation} (F8 #3 write-back) — remove the old element, add the re-expressed one.
     */
    public static String replaceComputation(String modelJson, String computationFullName, Computation computation) {
        return addComputation(removeComputation(modelJson, computationFullName), computation);
    }

    /** Remove the element of {@code type} at {@code fullName} from its group (the shared half of removeRule/removeComputation). */
    private static String removeElement(String modelJson, String type, String fullName) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            String groupPath = fullName.substring(0, fullName.lastIndexOf('/'));
            ArrayNode elements = DmJson.groupElements(root, groupPath);
            if (elements == null) {
                throw new IllegalArgumentException("no group at path: " + groupPath);
            }
            int idx = -1;
            for (int i = 0; i < elements.size(); i++) {
                JsonNode el = elements.get(i);
                if (type.equals(el.path("type").asText()) && fullName.equals(el.path("id").asText())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                throw new IllegalArgumentException("no " + type.toLowerCase(java.util.Locale.ROOT) + " at path: " + fullName);
            }
            elements.remove(idx);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /** Build a {@code Computation} element node from its rendered slots + real messages (the DM-JSON computation shape). */
    private static ObjectNode computationNode(DraftComputation c, List<LocalizedText> messages) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "Computation");
        node.put("id", c.groupPath() + "/" + c.name());
        node.put("name", c.name());

        ObjectNode body = node.putObject("Computation");
        body.put("computedFieldRelPath", c.computedFieldRelPath());
        if (c.commonPrecondition() != null) {
            body.put("commonPrecondition", c.commonPrecondition());
        }
        ArrayNode alternatives = body.putArray("computationAlternatives");
        for (DraftComputation.Alternative alternative : c.alternatives()) {
            ObjectNode alt = alternatives.addObject();
            if (alternative.precondition() != null) {
                alt.put("precondition", alternative.precondition());
            }
            alt.put("operation", alternative.operation());
        }

        ArrayNode messageArray = body.putArray("errorMessage");
        for (LocalizedText text : messages) {
            ObjectNode message = messageArray.addObject();
            message.put("locale", text.locale());
            message.put("text", text.text());
        }
        return node;
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
