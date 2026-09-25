package io.github.mbackschat.a12.dm.rulekit.validate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle;

/**
 * Ground-truth rule check (PROJECT-DESIGN §11, line 3). Injects a {@link DraftRule} into the model as
 * DM-JSON — the public, polyglot path (no internal {@code betterbuilders}, per D9/N8) — then runs
 * the real kernel's {@code checkConsistency} (a parse/convert pass: no compile, no execute) and
 * collects the reported problems.
 *
 * <p>This is the <b>kernel's verdict only</b>: it accepts a structurally valid rule even when its
 * <em>meaning</em> is wrong — most notably a back-to-front {@code when(...)} polarity, which is a
 * perfectly valid condition. To also catch those valid-but-suspicious shapes, run
 * {@code lint.RuleDiagnostics.diagnose(model, rule)} — it layers the AST lint ({@code RK_*} polarity /
 * negative-in-iteration / gotcha findings) on top of this kernel check.
 */
public final class RuleValidator {

    private final ObjectMapper mapper = JsonMapper.builder()
            // DM-JSON is parsed the way A12 parses it (`ObjectMapperProvider.createPreconfiguredObjectMapper`): a
            // number keeps the authored literal's exact scale, and a comment is legal. A DEFAULT mapper builds a
            // DoubleNode (0.50 -> 0.5) and refuses a comment outright. API-FINDINGS 103 / IF228.
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private final DiagnosticEnricher enricher = new DiagnosticEnricher(OperatorCatalog.load());

    /** Check {@code rule} against the model given as DM-JSON text (self-contained model). */
    public ValidationResult check(String modelJson, DraftRule rule) {
        return check(modelJson, rule, ReferenceResolvers.selfContained());
    }

    /** Check {@code computation} against the model given as DM-JSON text (self-contained model). */
    public ValidationResult check(String modelJson, DraftComputation computation) {
        return check(modelJson, computation, ReferenceResolvers.selfContained());
    }

    /**
     * Check {@code rule} against a model whose {@code include}d / typedef-imported models are resolved by
     * {@code resolver} (F-RES) — the multi-file-application path (use {@link ReferenceResolvers#directory}).
     */
    public ValidationResult check(String modelJson, DraftRule rule, ModelResolver resolver) {
        ValidationResult result = checkDraft(modelJson, rule.groupPath(), rule.name(), "rule",
                () -> inject(modelJson, rule), resolver);
        return RawLengthDiagnostic.enrich(result, modelJson, rule, resolver);
    }

    /** Check {@code computation} against a model whose references are resolved by {@code resolver} (F-RES). */
    public ValidationResult check(String modelJson, DraftComputation computation,
            ModelResolver resolver) {
        return checkDraft(modelJson, computation.groupPath(), computation.name(), "computation",
                () -> inject(modelJson, computation), resolver);
    }

    /**
     * Inject-and-check a draft, but <b>never throw</b>. The kernel raises an {@link IllegalArgumentException}
     * during conversion (inside {@code checkConsistency}) for a foreseeable authoring collision — most often a
     * child name already taken — <em>before</em> it can report it as a structured problem, and {@link #inject}
     * itself throws when the target group is missing. Either would otherwise escape the calling verb as a raw
     * Java stack trace instead of a diagnostic (the diagnostic-contract leak this method closes for every
     * caller — {@code rule add}, {@code computation add}, {@code rule check}, patterns, model eval). So:
     * pre-check the two known cases into clean {@code RK_*} rejections, and wrap the kernel pass in a catch-all
     * that turns any residual conversion error into a rejection too.
     */
    private ValidationResult checkDraft(String modelJson, String groupPath, String name, String kind,
            Supplier<String> injector, ModelResolver resolver) {
        String fullName = groupPath + "/" + name;
        Optional<Diagnostic> precheck = addPrecheck(modelJson, groupPath, name, kind);
        if (precheck.isPresent()) {
            // Rejected BEFORE the oracle was consulted, so no receipt: a caller must not publish kernel provenance
            // for a group that simply does not exist.
            return ValidationResult.beforeTheGate(List.of(precheck.get()));
        }
        try {
            return checkInjected(injector.get(), fullName, resolver);
        } catch (RuntimeException e) {
            String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // The kernel threw during conversion, so it reached no verdict — again no receipt.
            return ValidationResult.beforeTheGate(List.of(Diagnostic.precheck(Optional.empty(),
                    "the kernel could not process the candidate " + kind + " '" + fullName + "': " + why,
                    Diagnostic.Where.path(fullName),
                    Optional.of("review the " + kind + " against the model's structure"
                            + " (the kernel rejected it during conversion)"),
                    Optional.empty())));
        }
    }

    /**
     * The foreseeable add-time collisions, pre-checked from the model JSON so the kernel never throws on them:
     * the target group must exist ({@code RK_NO_SUCH_GROUP}), and the new name must be free among the group's
     * children ({@code RK_NAME_EXISTS} — a field/group/rule/computation already owns it).
     */
    private Optional<Diagnostic> addPrecheck(String modelJson, String groupPath, String name, String kind) {
        ArrayNode elements;
        JsonNode rootGroups;
        try {
            rootGroups = mapper.readTree(modelJson)
                    .path("content").path("modelRoot").path("rootGroups");
            elements = elementsOf(rootGroups, groupPath);
        } catch (IOException e) {
            return Optional.empty();   // unreadable model — let the kernel path be the one to report it
        }
        if (elements == null) {
            // the path may name an include MOUNT (a Group with a modelAlias, whose children come from the
            // include, not stored `elements`) — elementsOf can't see those, so distinguish it from a genuinely
            // absent group: a mount IS there, you just can't anchor a rule/computation's error field inside an
            // included sub-model (probe 04 — the misleading "no group at path" when the group is a mount).
            JsonNode group = groupNodeAt(rootGroups, groupPath);
            String alias = group == null ? null : group.path("Group").path("modelAlias").asText(null);
            if (alias != null && !alias.isBlank()) {
                return Optional.of(Diagnostic.precheck(Optional.of("RK_MOUNT_NOT_EDITABLE"),
                        // "error field" is the RULE's word for it; a computation has a computed field, and the
                        // declaring `group` key made this branch directly reachable for one.
                        "'" + groupPath + "' is an include mount (alias '" + alias + "'), not an editable group — a "
                                + kind + " cannot be declared inside an included sub-model",
                        Diagnostic.Where.path(groupPath),
                        Optional.of("anchor the " + kind + " on a NATIVE field/group of THIS model, and reference the "
                                + "included field by its absolute path inside the condition (e.g. " + groupPath + "/<field>)"),
                        Optional.empty()));
            }
            return Optional.of(Diagnostic.precheck(Optional.of(RkCode.NO_SUCH_GROUP.code()),
                    "no group at path '" + groupPath + "' to add the " + kind + " to",
                    Diagnostic.Where.path(groupPath),
                    Optional.of(RkCode.NO_SUCH_GROUP.fix()), Optional.empty()));
        }
        for (JsonNode element : elements) {
            if (name.equals(element.path("name").asText(null))) {
                String existing = element.path("type").asText("element").toLowerCase(Locale.ROOT);
                return Optional.of(Diagnostic.precheck(Optional.of(RkCode.NAME_EXISTS.code()),
                        "a " + existing + " named '" + name + "' already exists in '" + groupPath
                                + "' — cannot add the " + kind,
                        Diagnostic.Where.path(groupPath + "/" + name),
                        Optional.of("choose a name/code not already used in '" + groupPath
                                + "', or remove the existing '" + name + "' first"),
                        Optional.empty()));
            }
        }
        return Optional.empty();
    }

    private ValidationResult checkInjected(String injected, String rulePath,
            ModelResolver resolver) {
        Optional<ConsistencyOracle> oracle = Backends.consistencyOracle();
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (oracle.isEmpty()) {
            // Degraded (STRUCTURAL_ONLY, KERNEL-DECOUPLING-PROPOSAL §2c): the authoritative kernel gate did
            // not run, so no MVK verdict is fabricated — the structural prechecks above already ran, and this
            // one ENVIRONMENT advisory makes the posture explicit. ok() stays "no ERROR among the checks that
            // ran"; verification() is what keeps it from ever reading as kernel-green.
            diagnostics.add(noKernelBackend(rulePath));
            return ValidationResult.of(diagnostics, Verification.STRUCTURAL_ONLY);
        }
        for (Notification n : oracle.get().checkConsistency(injected, resolver)) {
            diagnostics.add(enricher.enrich(Diagnostic.fromKernel(n, rulePath)));
        }
        return ValidationResult.of(diagnostics, Verification.KERNEL_CONFIRMED);
    }

    /** The single {@code RK_NO_KERNEL_BACKEND} advisory (INFO/ENVIRONMENT — never gates {@code ok}). */
    private static Diagnostic noKernelBackend(String rulePath) {
        return new Diagnostic(
                RkCode.NO_KERNEL_BACKEND.severity(),
                RkCode.NO_KERNEL_BACKEND.source(),
                Optional.of(RkCode.NO_KERNEL_BACKEND.code()),
                RkCode.NO_KERNEL_BACKEND.meaning(),
                Diagnostic.Where.path(rulePath),
                Optional.of(RkCode.NO_KERNEL_BACKEND.fix()),
                Optional.empty());
    }

    /**
     * Inject {@code rule} into the model as DM-JSON and return the new model text — the same step
     * {@link #check} runs before validating, exposed for callers that need the rule-bearing model
     * <em>without</em> persisting it: e.g. evaluating a CANDIDATE rule against a document instance
     * ({@code eval-doc --condition}), where the draft must be present in the model the kernel runs but is
     * never written back. Supplied messages are checked; omitted locales receive a placeholder so the
     * message-required check does not mask the authored candidate.
     */
    public String inject(String modelJson, DraftRule rule) {
        return injectInto(modelJson, rule.groupPath(), (root, elements) ->
                elements.add(ruleNode(rule, localeCodes(root))));
    }

    private String inject(String modelJson, DraftComputation computation) {
        return injectInto(modelJson, computation.groupPath(), (root, elements) ->
                elements.add(computationNode(computation, localeCodes(root))));
    }

    private String injectInto(String modelJson, String groupPath,
            java.util.function.BiConsumer<ObjectNode, ArrayNode> adder) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(modelJson);
            JsonNode rootGroups = root.path("content").path("modelRoot").path("rootGroups");
            ArrayNode elements = elementsOf(rootGroups, groupPath);
            if (elements == null) {
                throw new IllegalArgumentException("no group at path: " + groupPath);
            }
            adder.accept(root, elements);
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read/write the model DM-JSON", e);
        }
    }

    private List<String> localeCodes(JsonNode root) {
        List<String> codes = new ArrayList<>();
        for (JsonNode locale : root.path("header").path("locales")) {
            String code = locale.path("code").asText(null);
            if (code != null && !code.isBlank()) {
                codes.add(code);
            }
        }
        return codes.isEmpty() ? List.of("en_US") : codes;
    }

    /**
     * DFS for a group NODE by its id (absolute path) — unlike {@link #elementsOf}, returns the node itself and
     * does NOT require it to carry an {@code elements} array, so an include-mount group (whose children come
     * from the include, not stored elements) is found too. Used to tell a mount apart from an absent group.
     */
    private JsonNode groupNodeAt(JsonNode groups, String groupPath) {
        if (!groups.isArray()) {
            return null;
        }
        for (JsonNode node : groups) {
            if (!"Group".equals(node.path("type").asText())) {
                continue;
            }
            if (groupPath.equals(node.path("id").asText())) {
                return node;
            }
            JsonNode found = groupNodeAt(node.path("Group").path("elements"), groupPath);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Depth-first search for a group node by its id (absolute path), returning its elements array. */
    private ArrayNode elementsOf(JsonNode groups, String groupPath) {
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

    private ObjectNode ruleNode(DraftRule rule, List<String> localeCodes) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "Rule");
        node.put("id", rule.groupPath() + "/" + rule.name());
        node.put("name", rule.name());

        ObjectNode body = node.putObject("Rule");
        body.put("errorEntityRelPath", rule.errorEntityRelPath());
        body.put("errorCode", rule.errorCode());
        body.put("errorCondition", rule.errorCondition());
        body.put("severity", rule.severity());

        addMessages(body, localeCodes, rule.messages(), rule.name());
        return node;
    }

    /**
     * The locales a caller asked about that the model does not declare — refused rather than injected.
     *
     * <p><b>Measured basis for refusing rather than passing through</b> (2026-08-24, one model declaring
     * {@code en_US} + {@code de_DE}): the identical {@code "See $Nonexistent$"} text is rejected
     * {@code INVALID_ENTITY} in {@code en_US} and accepted with no diagnostic at all in {@code fr_FR}. The
     * kernel's error-text checker runs per DECLARED locale, so an undeclared one is never examined — and a
     * check that answers {@code valid:true} about a text nothing looked at is the one answer this route must
     * not give. Passing it through with an advisory was the alternative and is worse: it discloses the hole
     * instead of closing it.
     */
    public List<String> undeclaredMessageLocales(String modelJson, DraftRule rule) {
        if (rule.messages().isEmpty()) {
            return List.of();
        }
        List<String> declared = declaredLocales(modelJson);
        return rule.messages().stream().map(LocalizedText::locale).distinct()
                .filter(locale -> !declared.contains(locale)).toList();
    }

    /** The locales the model's header declares — the set an error text is actually checked for. */
    public List<String> declaredLocales(String modelJson) {
        try {
            return localeCodes(mapper.readTree(modelJson));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ObjectNode computationNode(DraftComputation computation, List<String> localeCodes) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "Computation");
        node.put("id", computation.groupPath() + "/" + computation.name());
        node.put("name", computation.name());

        ObjectNode body = node.putObject("Computation");
        body.put("computedFieldRelPath", computation.computedFieldRelPath());
        // Right after the target and only when not Exact — where and when A12's ComputationSerializer writes it.
        if (computation.roundingMode() != RoundingMode.EXACT) {
            body.put("roundingMode", computation.roundingMode().wire());
        }
        if (!computation.errorCodesToSuppress().isEmpty()) {
            ArrayNode suppress = body.putArray("errorCodesToSuppress");
            computation.errorCodesToSuppress().forEach(suppress::add);
        }
        if (computation.commonPrecondition() != null) {
            body.put("commonPrecondition", computation.commonPrecondition());
        }
        ArrayNode alternatives = body.putArray("computationAlternatives");
        for (DraftComputation.Alternative alternative : computation.alternatives()) {
            ObjectNode alt = alternatives.addObject();
            if (alternative.precondition() != null) {
                alt.put("precondition", alternative.precondition());
            }
            alt.put("operation", alternative.operation());
            if (alternative.toleranceRangeOp() != null) {
                alt.put("toleranceRangeOp", alternative.toleranceRangeOp().wire());
            }
        }

        addMessages(body, localeCodes, computation.messages(), computation.name());
        return node;
    }

    private static void addMessages(ObjectNode body, List<String> localeCodes,
            List<LocalizedText> authored, String fallback) {
        // MVK_ERROR_MESSAGE_MISSING must not mask a partial candidate, but supplied text must reach the checker.
        Map<String, String> supplied = new LinkedHashMap<>();
        authored.forEach(m -> supplied.put(m.locale(), m.text()));
        ArrayNode messages = body.putArray("errorMessage");
        for (String code : localeCodes) {
            ObjectNode message = messages.addObject();
            message.put("locale", code);
            message.put("text", supplied.getOrDefault(code, fallback));
        }
    }
}
