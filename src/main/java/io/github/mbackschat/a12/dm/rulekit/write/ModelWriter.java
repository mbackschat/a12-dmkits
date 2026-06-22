package io.github.mbackschat.a12.dm.rulekit.write;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
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
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

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
                throw PrecheckException.notFound(RkCode.NO_SUCH_RULE, ruleFullName);
            }
            ObjectNode body = (ObjectNode) rule.path("Rule");
            String oldText = body.path("errorCondition").asText("");
            body.put("errorCondition", Comments.preserveLeading(oldText, newConditionText));
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /**
     * Returns {@code modelJson} with the rule's {@code severity} slot set to {@code severity} (the enum name),
     * leaving its condition and messages untouched — the in-place severity edit (FEATURE-GAPS §3.7).
     */
    public static String replaceSeverity(String modelJson, String ruleFullName, String severity) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(modelJson);
            ObjectNode rule = DmJson.findElement(root, "Rule", ruleFullName);
            if (rule == null) {
                throw PrecheckException.notFound(RkCode.NO_SUCH_RULE, ruleFullName);
            }
            ((ObjectNode) rule.path("Rule")).put("severity", severity);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /**
     * Returns {@code modelJson} with the rule's per-locale {@code errorMessage} array replaced by
     * {@code messages}, leaving its condition and severity untouched — the in-place message edit (§3.7).
     */
    public static String replaceMessages(String modelJson, String ruleFullName, List<LocalizedText> messages) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(modelJson);
            ObjectNode rule = DmJson.findElement(root, "Rule", ruleFullName);
            if (rule == null) {
                throw PrecheckException.notFound(RkCode.NO_SUCH_RULE, ruleFullName);
            }
            ArrayNode messageArray = ((ObjectNode) rule.path("Rule")).putArray("errorMessage");
            for (LocalizedText text : messages) {
                messageArray.addObject().put("locale", text.locale()).put("text", text.text());
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /**
     * Read the rule's current per-locale messages straight from the DM-JSON — a <b>raw</b> read with NO kernel
     * bind, so it tolerates a <em>transiently-invalid</em> model. The {@code apply} message-merge calls this
     * mid-sequence (e.g. right after an {@code add-locale} op, before the rule's new-locale message is merged in),
     * where a validating read ({@code KernelAdapter.ofJson(...).ruleMessages}) would wrongly throw
     * MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING — apply defers consistency to the terminal gate (probe Issue B).
     * Empty when the rule (or its {@code errorMessage}) is absent.
     */
    public static List<LocalizedText> readMessages(String modelJson, String ruleFullName) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            ObjectNode rule = DmJson.findElement(root, "Rule", ruleFullName);
            List<LocalizedText> out = new ArrayList<>();
            if (rule != null) {
                for (JsonNode m : rule.path("Rule").path("errorMessage")) {
                    out.add(new LocalizedText(m.path("locale").asText(), m.path("text").asText()));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read the model DM-JSON", e);
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
        return preserveComputationComments(modelJson, computationFullName,
                addComputation(removeComputation(modelJson, computationFullName), computation, messages));
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
     * {@code computation} (F8 #3 write-back) — remove the old element, add the re-expressed one. The old
     * computation's leading {@code ;;} comments (on its common precondition and its first operation) are
     * preserved by default, the computation peer of {@link #replaceCondition}.
     */
    public static String replaceComputation(String modelJson, String computationFullName, Computation computation) {
        return preserveComputationComments(modelJson, computationFullName,
                addComputation(removeComputation(modelJson, computationFullName), computation));
    }

    /**
     * Carry the old computation's leading {@code ;;} comments onto the rebuilt one — the common
     * precondition and the first operation (the slots a whole-computation comment lands on; per-alternative
     * matching across a re-expression is positional and out of scope). Best-effort: any parse hiccup leaves
     * the rebuilt model as-is.
     */
    private static String preserveComputationComments(String oldModel, String fullName, String newModel) {
        try {
            ObjectNode oldComp = DmJson.findElement(MAPPER.readTree(oldModel), "Computation", fullName);
            ObjectNode newRoot = (ObjectNode) MAPPER.readTree(newModel);
            ObjectNode newComp = DmJson.findElement(newRoot, "Computation", fullName);
            if (oldComp == null || newComp == null) {
                return newModel;
            }
            JsonNode oldBody = oldComp.path("Computation");
            ObjectNode newBody = (ObjectNode) newComp.path("Computation");
            carryLeading(oldBody, newBody, "commonPrecondition");
            JsonNode oldAlts = oldBody.path("computationAlternatives");
            JsonNode newAlts = newBody.path("computationAlternatives");
            if (oldAlts.isArray() && !oldAlts.isEmpty() && newAlts.isArray() && !newAlts.isEmpty()) {
                carryLeading(oldAlts.get(0), (ObjectNode) newAlts.get(0), "operation");
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(newRoot);
        } catch (IOException e) {
            return newModel;
        }
    }

    /** Preserve the leading {@code ;;} comment of {@code slot} from {@code oldNode} onto {@code newNode}. */
    private static void carryLeading(JsonNode oldNode, ObjectNode newNode, String slot) {
        if (oldNode.hasNonNull(slot) && newNode.hasNonNull(slot)) {
            newNode.put(slot, Comments.preserveLeading(oldNode.get(slot).asText(), newNode.get(slot).asText()));
        }
    }

    /** Remove the element of {@code type} at {@code fullName} from its group (the shared half of removeRule/removeComputation). */
    private static String removeElement(String modelJson, String type, String fullName) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            String groupPath = fullName.substring(0, fullName.lastIndexOf('/'));
            ArrayNode elements = DmJson.groupElements(root, groupPath);
            if (elements == null) {
                throw PrecheckException.notFound(RkCode.NO_SUCH_GROUP, groupPath);
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
                throw PrecheckException.notFound(
                        "Computation".equals(type) ? RkCode.NO_SUCH_COMPUTATION : RkCode.NO_SUCH_RULE, fullName);
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
