package io.github.mbackschat.a12.dm.rulekit.write;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;


import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/**
 * The F6 DM-JSON write path for <b>rules</b> (D9: the public, polyglot write surface — no internal
 * builders). Owns the {@code Rule} node shape; the generic tree surgery lives in {@link DmJson} (the
 * dsl-free, rulekit-private half). Re-binding the result through {@code ModelLoader} re-runs
 * {@code checkConsistency}, so a bad write fails loudly.
 */
public final class ModelWriter {

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

    private ModelWriter() {
    }

    /**
     * Returns {@code modelJson} with {@code rule} added to its target group. The rule is rendered in the destination
     * model's declared condition language; the raw-slot overload remains verbatim.
     */
    public static String addRule(String modelJson, Rule rule) {
        return insertTyped(modelJson, rule.group().path().value(), language ->
                ruleNode(rule.group().path() + "/" + rule.name(), rule.name(), rule.errorEntityRelPath(),
                        rule.code(), rule.render(language), rule.severity().name(), rule.messages()));
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
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            replaceConditionIn(root, ruleFullName, newConditionText);
            return write(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /**
     * Typed condition replacement rendered in the destination model's declared condition language. The raw-text
     * overload remains the verbatim CLI/spec path.
     */
    public static String replaceCondition(String modelJson, String ruleFullName, Condition newCondition) {
        Objects.requireNonNull(newCondition, "newCondition");
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            String groupPath = ruleFullName.substring(0, ruleFullName.lastIndexOf('/'));
            String rendered = new DslRenderer(conditionLanguage(root), groupPath).render(newCondition);
            replaceConditionIn(root, ruleFullName, rendered);
            return write(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    /**
     * Returns {@code modelJson} with the rule's {@code severity} slot set to {@code severity} (the enum name),
     * leaving its condition and messages untouched.
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
     * {@code messages}, leaving its condition and severity untouched — the in-place message edit.
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

    /**
     * Returns {@code modelJson} with {@code computation} added to its own DECLARING group, which A12 lets differ
     * from the computed field's parent. Typed fragments render in the destination model's declared condition
     * language, relative to that same declaring group.
     *
     * <p>Unvalidated: this is the raw writer, so it inserts wherever {@link Computation#groupPath()} points and
     * checks nothing. {@link io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel#add(Computation)} is the gated
     * path — it re-binds and re-validates.
     */
    public static String addComputation(String modelJson, Computation computation) {
        return insertTyped(modelJson, computation.groupPath(), language ->
                computationNode(computation.draft(language), computation.messages()));
    }

    /**
     * Adds a complete typed program population with one tree parse and one serialization.
     *
     * <p>Group element arrays are cached by path during the append, so a large population does not repeatedly
     * traverse the model tree either. Callers that need consistency validation should use
     * {@link io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel#addAll(List, List)}.
     */
    public static String addAll(
            String modelJson,
            List<Rule> rules,
            List<Computation> computations) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(computations, "computations");
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            ConditionLanguage language = conditionLanguage(root);
            Map<String, ArrayNode> elementsByGroup = new HashMap<>();
            for (Rule rule : rules) {
                Objects.requireNonNull(rule, "rule");
                elements(root, elementsByGroup, rule.group().path().value()).add(
                        ruleNode(
                                rule.group().path() + "/" + rule.name(),
                                rule.name(),
                                rule.errorEntityRelPath(),
                                rule.code(),
                                rule.render(language),
                                rule.severity().name(),
                                rule.messages()));
            }
            for (Computation computation : computations) {
                Objects.requireNonNull(computation, "computation");
                elements(root, elementsByGroup, computation.groupPath()).add(
                        computationNode(computation.draft(language), computation.messages()));
            }
            return write(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    private static ArrayNode elements(
            ObjectNode root,
            Map<String, ArrayNode> elementsByGroup,
            String groupPath) {
        return elementsByGroup.computeIfAbsent(groupPath, path -> {
            ArrayNode elements = DmJson.groupElements(root, path);
            if (elements == null) {
                throw PrecheckException.notFound(RkCode.NO_SUCH_GROUP, path);
            }
            return elements;
        });
    }

    /**
     * Parse once so choosing the model-owned language and appending the rendered element cannot observe different
     * source snapshots. Raw-slot writers bypass this helper because their text is already the stored representation.
     */
    private static String insertTyped(
            String modelJson,
            String groupPath,
            Function<ConditionLanguage, ObjectNode> element) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(modelJson);
            ArrayNode elements = DmJson.groupElements(root, groupPath);
            if (elements == null) {
                throw new IllegalArgumentException("no group at path: " + groupPath);
            }
            elements.add(element.apply(conditionLanguage(root)));
            return write(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    private static ConditionLanguage conditionLanguage(JsonNode root) {
        String code = root.path(DmKeys.CONTENT)
                .path(DmKeys.MODEL_CONFIG)
                .path(DmKeys.CONDITION_LANGUAGE)
                .path(DmKeys.CODE)
                .asText("en_US");
        return "de_DE".equals(code) ? ConditionLanguage.DE : ConditionLanguage.EN;
    }

    private static void replaceConditionIn(ObjectNode root, String ruleFullName, String newConditionText) {
        ObjectNode rule = DmJson.findElement(root, "Rule", ruleFullName);
        if (rule == null) {
            throw PrecheckException.notFound(RkCode.NO_SUCH_RULE, ruleFullName);
        }
        ObjectNode body = (ObjectNode) rule.path("Rule");
        String oldText = body.path("errorCondition").asText("");
        body.put("errorCondition", Comments.preserveLeading(oldText, newConditionText));
    }

    private static String write(ObjectNode root) throws IOException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
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
        return preserveComputationMetadata(modelJson, computationFullName,
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
     * computation's leading {@code ;;} comments (on its common precondition and its first operation) <b>and its
     * element-level metadata</b> (annotations, internalDescription, externalDescription) are preserved by
     * default, the computation peer of {@link #replaceCondition} (which mutates in place and so never loses them).
     */
    public static String replaceComputation(String modelJson, String computationFullName, Computation computation) {
        return preserveComputationMetadata(modelJson, computationFullName,
                addComputation(removeComputation(modelJson, computationFullName), computation));
    }

    /** The top-level slots {@link #computationNode} reconstructs; every OTHER slot on the old element is metadata
     *  ({@code annotations}/{@code internalDescription}/…) the rebuild would drop, so it is carried over. */
    private static final Set<String> REBUILT_COMPUTATION_SLOTS = Set.of("type", "id", "name", "Computation");

    /**
     * Carry the old computation's leading {@code ;;} comments AND its element-level metadata onto the rebuilt one.
     * {@code replaceComputation} is remove-old + add-rebuilt, and {@link #computationNode} reconstructs only
     * {@code type}/{@code id}/{@code name} and the {@code Computation} body — so without this, the element's
     * comments (on its common precondition and first operation) and its metadata (annotations,
     * internalDescription, externalDescription) would be lost. Best-effort: any parse hiccup leaves the rebuilt
     * model as-is.
     */
    private static String preserveComputationMetadata(String oldModel, String fullName, String newModel) {
        try {
            ObjectNode oldComp = DmJson.findElement(MAPPER.readTree(oldModel), "Computation", fullName);
            ObjectNode newRoot = (ObjectNode) MAPPER.readTree(newModel);
            ObjectNode newComp = DmJson.findElement(newRoot, "Computation", fullName);
            if (oldComp == null || newComp == null) {
                return newModel;
            }
            carryElementMetadata(oldComp, newComp);
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

    /** Copy every non-rebuilt top-level slot from the old element node onto the rebuilt one (annotations,
     *  internalDescription, externalDescription, and any future element-level metadata) — leaving the rebuilt
     *  {@code type}/{@code id}/{@code name}/{@code Computation} authoritative. */
    private static void carryElementMetadata(ObjectNode oldElement, ObjectNode newElement) {
        oldElement.fields().forEachRemaining(e -> {
            if (!REBUILT_COMPUTATION_SLOTS.contains(e.getKey()) && !newElement.has(e.getKey())) {
                newElement.set(e.getKey(), e.getValue());
            }
        });
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
        if (!c.errorCodesToSuppress().isEmpty()) {
            ArrayNode suppress = body.putArray("errorCodesToSuppress");
            c.errorCodesToSuppress().forEach(suppress::add);
        }
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
            if (alternative.toleranceRangeOp() != null) {
                alt.put("toleranceRangeOp", alternative.toleranceRangeOp().wire());
            }
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
