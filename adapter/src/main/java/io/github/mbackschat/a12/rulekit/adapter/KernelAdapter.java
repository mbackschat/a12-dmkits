package io.github.mbackschat.a12.rulekit.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICondition;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IEC;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IEntity;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblemReporter;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.IMVK_Service;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.a12internal.DocumentModel;
import com.mgmtp.a12.kernel.md.model.a12internal.services.DocumentModelService;
import com.mgmtp.a12.kernel.md.model.a12internal.services.ElementContainer;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.kernel.md.serializer.model.internal.service.ExternalDocumentModelSerializerService;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.rulekit.model.ReferenceResolvers;

/**
 * Phase-2 kernel-adapter bootstrap (proposal D15 / N8 tier 3). Wraps the kernel's {@code a12internal}
 * <em>read</em> surface for rules already in a model — the foreign-rule path that ingest (F10) and
 * exact whereUsed (F9a) build on. The bootstrap chain, verified against kernel {@code 30.8.1} and
 * staying entirely in the {@code a12internal.api} tier:
 *
 * <pre>
 *   public IDocumentModel ──getInternDocumentModel──▶ internal DocumentModel
 *     ──new DocumentModelService().getMvkServiceForModel──▶ IMVK_Service
 *     ──(ElementContainer) getIEC().getAllRules() · match getFullName()──▶ ado.IRule
 *     ──parse / format / getDirectlyReferencedEntities / hasValidConditionText──▶ …
 * </pre>
 *
 * <p><b>Boundary conversion (N8):</b> every public method takes/returns only rulekit or JDK types —
 * the kernel {@code IMVK_Service}/{@code ICondition}/{@code IRule} never escape this class.
 * <b>Read-only:</b> no kernel {@code …Impl} is constructed or mutated (the only way to obtain a tool
 * {@code IRule} is to look one up among the model's existing rules). The converter visitor (F10) and
 * {@code IProblem → Diagnostic} mapping are the next increments; this slice proves the wiring with
 * format / referenced-entities / validity.
 */
public final class KernelAdapter {

    /** Discards problems — the bootstrap reads validity from return values; IProblem→Diagnostic is F10. */
    private static final IProblemReporter SILENT = problem -> { };
    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    private final IMVK_Service mvk;
    private final ElementContainer elements;
    private final BoundModel model;

    private KernelAdapter(IMVK_Service mvk, BoundModel model) {
        this.mvk = mvk;
        this.model = model;
        IEC iec = mvk.getIEC();
        if (!(iec instanceof ElementContainer container)) {
            throw new IllegalStateException(
                    "expected the MVK service's IEC to be an ElementContainer, got " + iec.getClass());
        }
        this.elements = container;
    }

    /** Open an adapter over a rulekit {@link BoundModel} (via its DM-JSON — the public boundary). */
    public static KernelAdapter of(BoundModel model) {
        return new KernelAdapter(buildService(model.toJson()), model);
    }

    /** Open an adapter over a model given as DM-JSON: deserialize + expand, then bootstrap the MVK service. */
    public static KernelAdapter ofJson(String modelJson) {
        return new KernelAdapter(buildService(modelJson), BoundModel.ofJson(modelJson));
    }

    /** Deserialize + expand the DM-JSON (public path), convert to internal, and bootstrap the MVK service. */
    private static IMVK_Service buildService(String modelJson) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();
        try {
            IDocumentModel pub = serializer.deserialize(new StringReader(modelJson));
            service.expand(pub, ReferenceResolvers.selfContained());
            String variant = service.extractUsedVariants(pub).stream().findFirst().orElse("");
            DocumentModel intern = ExternalDocumentModelSerializerService.getInternDocumentModel(pub);
            return new DocumentModelService().getMvkServiceForModel(intern, variant, SILENT);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to deserialize the model DM-JSON", e);
        }
    }

    /**
     * The fields/groups a rule's condition references, as resolved absolute paths — the kernel's own
     * answer ({@code getDirectlyReferencedEntities}), the exact form of F9a's whereUsed (no converter
     * needed, so it lands with the bootstrap).
     */
    public Set<String> referencedFields(String ruleFullName) {
        Set<String> paths = new TreeSet<>();
        for (IEntity entity : mvk.getDirectlyReferencedEntities(rule(ruleFullName), SILENT)) {
            paths.add(entity.getFullName());
        }
        return paths;
    }

    /** The kernel's canonical text for a rule's condition (a parse + format round-trip through the kernel). */
    public String canonicalConditionText(String ruleFullName) {
        return mvk.formatConditionText(rule(ruleFullName), SILENT);
    }

    /** Whether the kernel accepts the rule's condition — the per-rule fast lane (no whole-model check). */
    public boolean hasValidCondition(String ruleFullName) {
        return mvk.hasValidConditionText(rule(ruleFullName), SILENT);
    }

    /**
     * Ingest (F10): parse a rule's stored condition and convert it into rulekit's typed AST — the
     * read direction of the bidirectional loop. Returns a sealed {@link Ingest}: {@code Converted}
     * (an ordinary rulekit {@link Condition}, carrying the D14 invariant as if hand-authored) or, for
     * a not-yet-modeled construct, {@code Opaque} (the rule preserved verbatim with a reason — never
     * lost, never reported as corrupt). Tolerant by design: a valid rule the kernel accepts never
     * throws here (the converter's strict {@link UnsupportedConstructException} is caught and mapped
     * to {@code Opaque}). The kernel transport AST never escapes.
     */
    public Ingest ingest(String ruleFullName) {
        try {
            return new Ingest.Converted(new ConditionConverter(model).convert(parseCondition(ruleFullName)));
        } catch (UnsupportedConstructException unconverted) {
            return new Ingest.Opaque(canonicalConditionText(ruleFullName), unconverted.getMessage());
        }
    }

    /**
     * Ingest every rule in the model — the publishing-safe whole-DM entry point. Returns each rule's
     * outcome keyed by full name (insertion-ordered); never throws on an unmodeled construct (those
     * become {@link Ingest.Opaque}). The caller sees exactly which rules are typed and which are
     * preserved opaque.
     */
    public java.util.Map<String, Ingest> ingestAll() {
        java.util.Map<String, Ingest> out = new java.util.LinkedHashMap<>();
        for (IRule r : elements.getAllRules()) {
            out.put(r.getFullName(), ingest(r.getFullName()));
        }
        return out;
    }

    /**
     * Modify-by-re-express diff (F8 #2): render the rule's current condition (as ingested) against a
     * re-expressed {@code modified} one, disclosing lossiness up front (study finding 8). The
     * before/after are both rulekit renderings (symmetric — the diff is the intended change, not
     * cosmetics); the lossiness notes what re-rendering a foreign source drops — chiefly comments,
     * detected via the kernel's {@code stripComments}. The caller reviews this before writing back.
     *
     * @throws IllegalStateException if the rule's current condition didn't ingest cleanly (it is
     *         {@code Opaque}) — you can't re-express an AST you don't have.
     */
    public RenderedDiff diff(String ruleFullName, Condition modified) {
        if (!(ingest(ruleFullName) instanceof Ingest.Converted current)) {
            throw new IllegalStateException("cannot diff a rule whose condition did not ingest cleanly: "
                    + ruleFullName);
        }
        IRule rule = rule(ruleFullName);
        java.util.List<String> lossiness = new java.util.ArrayList<>();
        String raw = rule.getErrorConditionText();
        if (raw != null && !raw.equals(mvk.stripComments(rule))) {
            lossiness.add("the source rule's comments/formatting are not preserved by re-rendering");
        }
        return RenderedDiff.between(render(current.condition()), render(modified), lossiness);
    }

    private static String render(Condition condition) {
        return new DslRenderer(ConditionLanguage.EN).render(condition);
    }

    /** Parse a rule's own stored condition into the kernel transport AST (adapter-internal, for F10 ingest). */
    ICondition parseCondition(String ruleFullName) {
        return mvk.parseBedingungNoExpand(rule(ruleFullName), SILENT);
    }

    /** Look up a tool-API {@code IRule} among the model's existing rules by full name (read-only — N8). */
    private IRule rule(String fullName) {
        return elements.getAllRules().stream()
                .filter(r -> fullName.equals(r.getFullName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no rule in model at: " + fullName));
    }
}
