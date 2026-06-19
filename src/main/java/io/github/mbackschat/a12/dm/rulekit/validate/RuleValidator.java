package io.github.mbackschat.a12.dm.rulekit.validate;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.model.notification.RankedNotification;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

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

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();
    private final ObjectMapper mapper = new ObjectMapper();
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
    public ValidationResult check(String modelJson, DraftRule rule, IDocumentModelReferenceResolver resolver) {
        return checkInjected(inject(modelJson, rule), rule.groupPath() + "/" + rule.name(), resolver);
    }

    /** Check {@code computation} against a model whose references are resolved by {@code resolver} (F-RES). */
    public ValidationResult check(String modelJson, DraftComputation computation,
            IDocumentModelReferenceResolver resolver) {
        return checkInjected(inject(modelJson, computation),
                computation.groupPath() + "/" + computation.name(), resolver);
    }

    private ValidationResult checkInjected(String injected, String rulePath,
            IDocumentModelReferenceResolver resolver) {

        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();

        List<Diagnostic> diagnostics = new ArrayList<>();
        try {
            IDocumentModel model = serializer.deserialize(new StringReader(injected));
            service.expand(model, resolver);
            service.checkConsistency(model, (RankedNotification n) ->
                    diagnostics.add(enricher.enrich(Diagnostic.fromKernel(n, rulePath))));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to deserialize the rule-injected model", e);
        }
        return ValidationResult.of(diagnostics);
    }

    /**
     * Inject {@code rule} into the model as DM-JSON and return the new model text — the same step
     * {@link #check} runs before validating, exposed for callers that need the rule-bearing model
     * <em>without</em> persisting it: e.g. evaluating a CANDIDATE rule against a document instance
     * ({@code eval-doc --condition}), where the draft must be present in the model the kernel runs but is
     * never written back. A placeholder error message per declared locale is added (so the kernel's
     * message-required check doesn't mask the condition).
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

        // A rule must carry error text (MVK_ERROR_MESSAGE_MISSING) — inject a placeholder per model
        // locale so that requirement doesn't mask the condition diagnostics we actually care about.
        ArrayNode messages = body.putArray("errorMessage");
        for (String code : localeCodes) {
            ObjectNode message = messages.addObject();
            message.put("locale", code);
            message.put("text", rule.name());
        }
        return node;
    }

    private ObjectNode computationNode(DraftComputation computation, List<String> localeCodes) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "Computation");
        node.put("id", computation.groupPath() + "/" + computation.name());
        node.put("name", computation.name());

        ObjectNode body = node.putObject("Computation");
        body.put("computedFieldRelPath", computation.computedFieldRelPath());
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
        }

        // Computations also require error text (MVK_ERROR_MESSAGE_MISSING) — placeholder per locale.
        ArrayNode messages = body.putArray("errorMessage");
        for (String code : localeCodes) {
            ObjectNode message = messages.addObject();
            message.put("locale", code);
            message.put("text", computation.name());
        }
        return node;
    }
}
