package io.github.mbackschat.a12.rulekit.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.TreeSet;

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

    private KernelAdapter(IMVK_Service mvk) {
        this.mvk = mvk;
        IEC iec = mvk.getIEC();
        if (!(iec instanceof ElementContainer container)) {
            throw new IllegalStateException(
                    "expected the MVK service's IEC to be an ElementContainer, got " + iec.getClass());
        }
        this.elements = container;
    }

    /** Open an adapter over a rulekit {@link BoundModel} (via its DM-JSON — the public boundary). */
    public static KernelAdapter of(BoundModel model) {
        return ofJson(model.toJson());
    }

    /** Open an adapter over a model given as DM-JSON: deserialize + expand, then bootstrap the MVK service. */
    public static KernelAdapter ofJson(String modelJson) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();
        try {
            IDocumentModel pub = serializer.deserialize(new StringReader(modelJson));
            service.expand(pub, ReferenceResolvers.selfContained());
            String variant = service.extractUsedVariants(pub).stream().findFirst().orElse("");
            DocumentModel intern = ExternalDocumentModelSerializerService.getInternDocumentModel(pub);
            IMVK_Service mvk = new DocumentModelService().getMvkServiceForModel(intern, variant, SILENT);
            return new KernelAdapter(mvk);
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

    /** Look up a tool-API {@code IRule} among the model's existing rules by full name (read-only — N8). */
    private IRule rule(String fullName) {
        return elements.getAllRules().stream()
                .filter(r -> fullName.equals(r.getFullName()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no rule in model at: " + fullName));
    }
}
