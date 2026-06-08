package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.condition.ICondition;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IEC;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IEntity;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblemReporter;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.IMVK_Service;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.ICalculationService;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.IBaseCalculationService.ICalculationRule;
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.serviceparams.ICalculationInfo;
import com.mgmtp.a12.kernel.core.facade.a12internal.ServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.a12internal.DocumentModel;
import com.mgmtp.a12.kernel.md.model.a12internal.services.DocumentModelService;
import com.mgmtp.a12.kernel.md.model.a12internal.services.ElementContainer;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.kernel.md.serializer.model.internal.service.ExternalDocumentModelSerializerService;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

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
    private ICalculationService calc;   // lazily built (from the MVK service, which is its config) — only computations need it

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
        IDocumentModelReferenceResolver resolver =
                model.resolver() != null ? model.resolver() : ReferenceResolvers.selfContained();
        return new KernelAdapter(buildService(model.toJson(), resolver), model);
    }

    /** Open an adapter over a model given as DM-JSON: deserialize + expand, then bootstrap the MVK service. */
    public static KernelAdapter ofJson(String modelJson) {
        return ofJson(modelJson, ReferenceResolvers.selfContained());
    }

    /**
     * Open an adapter over a model whose includes / type-def imports are resolved by {@code resolver}
     * (F-RES) — e.g. {@link ReferenceResolvers#directory} for a multi-file application. Both the
     * MVK-service bootstrap (here) and the {@link BoundModel} index expand against the same resolver,
     * so a rule referencing an included model's field ingests instead of failing to load.
     */
    public static KernelAdapter ofJson(String modelJson, IDocumentModelReferenceResolver resolver) {
        return new KernelAdapter(buildService(modelJson, resolver), BoundModel.ofJson(modelJson, resolver));
    }

    /** Deserialize + expand the DM-JSON (public path), convert to internal, and bootstrap the MVK service. */
    private static IMVK_Service buildService(String modelJson, IDocumentModelReferenceResolver resolver) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();
        try {
            IDocumentModel pub = serializer.deserialize(new StringReader(modelJson));
            service.expand(pub, resolver);
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
        return referencedEntities(rule(ruleFullName));
    }

    /** The kernel's directly-referenced entities for any rule element, as full-name paths (null-tolerant). */
    private Set<String> referencedEntities(IRule rule) {
        Set<String> paths = new TreeSet<>();
        Set<IEntity> refs = mvk.getDirectlyReferencedEntities(rule, SILENT);
        if (refs != null) {                                  // ParserService returns null on a parse failure
            for (IEntity entity : refs) {
                paths.add(entity.getFullName());
            }
        }
        return paths;
    }

    /**
     * The exact <b>whereUsed</b> (F9a): the full names of every <em>validation rule</em> whose condition
     * <em>directly</em> references the entity at {@code entityFullName} (a field or group, as a resolved
     * absolute path) — the inverse of {@link #referencedFields}, built from the kernel's own
     * {@code getDirectlyReferencedEntities} (no converter coverage needed). Insertion-ordered by rule.
     * "Direct" is exact: a rule that references an enclosing <em>group</em> is not listed for a field
     * inside it (it references the group, which {@code whereUsed("/…/group")} would surface).
     *
     * <p>This is validation rules only — a computation that reads the entity is surfaced by
     * {@link #whereUsedInComputations} instead, so the two element kinds stay attributed correctly
     * (a computation is also a rule in the kernel's {@code getAllRules()}, but a different thing).
     */
    public java.util.Set<String> whereUsed(String entityFullName) {
        java.util.LinkedHashSet<String> rules = new java.util.LinkedHashSet<>();
        for (String ruleName : ruleFullNames()) {            // ruleFullNames() already excludes computations
            if (referencedFields(ruleName).contains(entityFullName)) {
                rules.add(ruleName);
            }
        }
        return rules;
    }

    /**
     * The <b>computation</b> peer of {@link #whereUsed}: the full names of every computation that
     * <em>directly</em> references {@code entityFullName} — in its calculated field, common precondition,
     * an alternative's precondition, or an operation (the kernel's {@code getDirectlyReferencedEntities}
     * over the computation rule captures all of these, operands included). A field read only inside a
     * computation's operation — invisible to {@link #whereUsed} — shows up here. Insertion-ordered.
     */
    public java.util.Set<String> whereUsedInComputations(String entityFullName) {
        java.util.LinkedHashSet<String> comps = new java.util.LinkedHashSet<>();
        for (ICalculationRule cr : elements.getAllCalculations()) {
            if (referencedEntities(cr.getRule()).contains(entityFullName)) {
                comps.add(cr.getRule().getFullName());
            }
        }
        return comps;
    }

    /**
     * The entities a computation <em>directly</em> references — the {@link #referencedFields} peer for a
     * computation (the kernel's own answer over the computation rule: calculated field + every operand and
     * precondition field). The seed of {@link #whereUsedInComputations}.
     */
    public Set<String> referencedFieldsOfComputation(String computationFullName) {
        return referencedEntities(calculationRule(computationFullName).getRule());
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
     * The structured form of {@link #hasValidCondition}: the kernel's problems for the rule's <b>stored</b>
     * condition, mapped to enriched core {@link Diagnostic}s (the IProblem→Diagnostic wiring, F4a). A
     * collecting reporter captures the kernel {@code IProblem}s (each is a {@code RankedNotification}) and
     * {@link KernelDiagnostics} runs the same catalog-join the whole-model {@code RuleValidator} uses. On a
     * loaded model a stored condition is normally ERROR-free (the model wouldn't have loaded otherwise),
     * so this mainly surfaces lower-severity notes — for a <em>proposed</em> condition use the overload.
     */
    public ValidationResult diagnose(String ruleFullName) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        mvk.hasValidConditionText(rule(ruleFullName), p -> diagnostics.add(KernelDiagnostics.enriched(p, ruleFullName)));
        return ValidationResult.of(diagnostics);
    }

    /**
     * Validate a <b>candidate</b> condition for {@code ruleFullName} <em>without mutating the model</em> —
     * the F8 modify-loop's pre-write check. The kernel parses {@code candidateConditionText} in the rule's
     * context ({@code parseConditionNoExpand}) and its problems become enriched {@link Diagnostic}s, so the
     * caller gets a structured verdict (what's wrong + the curated fix) instead of an exception. This is the
     * loaded-model fast lane complementing the whole-model {@code RuleValidator.check}.
     */
    public ValidationResult diagnose(String ruleFullName, String candidateConditionText) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        mvk.parseConditionNoExpand(rule(ruleFullName), candidateConditionText,
                p -> diagnostics.add(KernelDiagnostics.enriched(p, ruleFullName)));
        return ValidationResult.of(diagnostics);
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
            ConditionConverter converter = new ConditionConverter(model);
            ICondition parsed = parseCondition(ruleFullName);
            int outerRefs = converter.countOuterRefs(parsed);   // $ correlations the source carries (N8)
            Condition converted = converter.convert(parsed);
            converter.verifyOuterPreserved(converted, outerRefs);   // backstop: a dropped $ → Opaque, never silent
            return new Ingest.Converted(converted);
        } catch (UnsupportedConstructException unconverted) {
            return new Ingest.Opaque(verbatimConditionText(ruleFullName), unconverted.getMessage());
        } catch (RuntimeException unexpected) {
            // Tolerant-by-design: a rule the kernel accepts must never crash whole-DM ingest. A
            // reference the converter can't resolve in rulekit's content-only index (e.g. a header
            // entity like /header/id, or a path form rulekit doesn't model) degrades to Opaque —
            // preserved verbatim, never lost — rather than escaping as an exception.
            return new Ingest.Opaque(verbatimConditionText(ruleFullName),
                    "unresolved reference (" + unexpected.getClass().getSimpleName() + "): "
                            + unexpected.getMessage());
        }
    }

    /**
     * The faithful text to preserve for an {@link Ingest.Opaque}: the rule's <b>raw stored</b> condition
     * (verbatim), falling back to the kernel canonical form only if it's absent. Raw is required because
     * the kernel's formatter can be lossy — it truncates a condition carrying a {@code $} correlation to
     * its first conjunct (KERNEL-FINDINGS) — so preserving canonical would <em>lose</em> the rule on
     * write-back, the opposite of "preserved verbatim".
     */
    private String verbatimConditionText(String ruleFullName) {
        String raw = rule(ruleFullName).getErrorConditionText();
        return raw != null && !raw.isBlank() ? raw : canonicalConditionText(ruleFullName);
    }

    /**
     * Ingest every <em>validation rule</em> in the model — the publishing-safe whole-DM entry point.
     * Returns each rule's outcome keyed by full name (insertion-ordered); never throws on an unmodeled
     * construct (those become {@link Ingest.Opaque}). The caller sees exactly which rules are typed and
     * which are preserved opaque.
     *
     * <p>Computations are <em>excluded</em>: a computation is stored as a rule (so it appears in the
     * kernel's {@code getAllRules()}), but its condition is the generated self-validation of a calculated
     * field — a computation, not a hand-authored rule. Computations ingest via
     * {@link #ingestAllComputations}; {@link #ingestModel} unifies both into one disjoint view.
     */
    public java.util.Map<String, Ingest> ingestAll() {
        java.util.Set<String> computations = computationFullNames();
        java.util.Map<String, Ingest> out = new java.util.LinkedHashMap<>();
        for (IRule r : elements.getAllRules()) {
            if (!computations.contains(r.getFullName())) {   // skip computation rules — see ingestAllComputations
                out.put(r.getFullName(), ingest(r.getFullName()));
            }
        }
        return out;
    }

    /**
     * The full names of every <em>validation rule</em> in the model — cheap (no conversion); for resolving
     * a {@code --rule} argument. Computations are excluded (see {@link #computationFullNames}); a
     * {@code --rule} that names a computation should be rejected so it isn't ingested as a rule.
     */
    public java.util.Set<String> ruleFullNames() {
        java.util.Set<String> computations = computationFullNames();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (IRule r : elements.getAllRules()) {
            if (!computations.contains(r.getFullName())) {
                names.add(r.getFullName());
            }
        }
        return names;
    }

    // ── Computations (the calculated-field read path; the IngestedComputation peer of ingest) ───────

    /**
     * The full names of every <b>computation</b> in the model — the discriminator that partitions
     * {@link #ruleFullNames()} into true validation rules and computation rules. A computation is stored
     * as a rule (so it appears in {@code getAllRules()} too), but its "condition" is the kernel-generated
     * self-validation of a calculated field — semantically a computation, ingested via
     * {@link #ingestComputation}, not {@link #ingest}.
     */
    public java.util.Set<String> computationFullNames() {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        for (ICalculationRule cr : elements.getAllCalculations()) {
            names.add(cr.getRule().getFullName());
        }
        return names;
    }

    /** The kernel's calculation service, built lazily from the MVK service (which is also its config). */
    private ICalculationService calcService() {
        if (calc == null) {
            calc = new ServiceFactory().getCalculationService(mvk);
        }
        return calc;
    }

    /**
     * Ingest one computation (F10, the calculated-field direction): convert the kernel's computation —
     * calculated field, common precondition, and {@code (precondition, operation)} alternatives — into a
     * typed rulekit {@link Computation}, carrying the recovered error messages so it survives the full
     * ingest → modify → write-back cycle. Tolerant: a not-yet-modeled computation degrades to
     * {@link IngestedComputation.Opaque} rather than throwing.
     */
    public IngestedComputation ingestComputation(String computationFullName) {
        return ingestOne(calculationRule(computationFullName));
    }

    /** Look up a computation's {@code ICalculationRule} by full name (read-only — the {@link #rule} peer). */
    private ICalculationRule calculationRule(String computationFullName) {
        for (ICalculationRule cr : elements.getAllCalculations()) {
            if (computationFullName.equals(cr.getRule().getFullName())) {
                return cr;
            }
        }
        throw new IllegalArgumentException("no computation in model at: " + computationFullName);
    }

    /**
     * Ingest every computation in the model, keyed by full name (insertion-ordered) — the computation
     * peer of {@link #ingestAll()}; never throws on an unmodeled computation (those become Opaque).
     */
    public java.util.Map<String, IngestedComputation> ingestAllComputations() {
        java.util.Map<String, IngestedComputation> out = new java.util.LinkedHashMap<>();
        for (ICalculationRule cr : elements.getAllCalculations()) {
            out.put(cr.getRule().getFullName(), ingestOne(cr));
        }
        return out;
    }

    /**
     * The unified whole-model read (F10): every <em>validation rule</em> ({@link #ingestAll}) and every
     * <em>computation</em> ({@link #ingestAllComputations}) of the model as one {@link ModelIngest} — two
     * disjoint maps, no double-counting (a computation appears under {@code computations}, never under
     * {@code rules}). The single entry point for round-tripping a foreign model completely; tolerant, so
     * it never throws on an unmodeled construct (those stay {@code Opaque}).
     */
    public ModelIngest ingestModel() {
        return new ModelIngest(ingestAll(), ingestAllComputations());
    }

    private IngestedComputation ingestOne(ICalculationRule cr) {
        try {
            java.util.Optional<ICalculationInfo> info = calcService().extractCalculationInfo(cr, SILENT);
            if (info.isEmpty()) {
                return new IngestedComputation.Opaque("the kernel could not extract calculation info");
            }
            Computation computation = new ConditionConverter(model)
                    .convertComputation(info.get(), recoverMessages(cr.getRule()));
            return new IngestedComputation.Converted(computation);
        } catch (UnsupportedConstructException unconverted) {
            return new IngestedComputation.Opaque(unconverted.getMessage());
        } catch (RuntimeException unexpected) {
            return new IngestedComputation.Opaque("unresolved reference (" + unexpected.getClass().getSimpleName()
                    + "): " + unexpected.getMessage());
        }
    }

    /** Recover a computation's error messages (the kernel requires them) as rulekit {@link LocalizedText}. */
    private static List<LocalizedText> recoverMessages(IRule rule) {
        List<LocalizedText> messages = new ArrayList<>();
        rule.getErrorMessages().forEach((locale, text) -> messages.add(new LocalizedText(locale.toString(), text)));
        return messages;
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

    /**
     * Modify-by-re-express diff for a <b>computation</b> (F8 #2, the {@link #diff} peer): render the
     * computation's current shape (as ingested) against a re-expressed {@code modified} one, so the caller
     * reviews the intended change before writing it back. Both sides are rulekit renderings of the whole
     * computation — computed field, common precondition, and each {@code (precondition, operation)}
     * alternative — so the diff is the change, not cosmetics.
     *
     * @throws IllegalStateException if the computation didn't ingest cleanly (it is {@code Opaque}) — you
     *         can't re-express a shape you don't have.
     */
    public RenderedDiff diffComputation(String computationFullName, Computation modified) {
        if (!(ingestComputation(computationFullName) instanceof IngestedComputation.Converted current)) {
            throw new IllegalStateException("cannot diff a computation that did not ingest cleanly: "
                    + computationFullName);
        }
        return RenderedDiff.between(renderComputation(current.computation()), renderComputation(modified),
                java.util.List.of());
    }

    private static String render(Condition condition) {
        return new DslRenderer(ConditionLanguage.EN).render(condition);
    }

    /** A canonical multi-line rendering of a whole computation — the before/after unit for {@link #diffComputation}. */
    private static String renderComputation(Computation computation) {
        DslRenderer renderer = new DslRenderer(ConditionLanguage.EN);
        StringBuilder text = new StringBuilder("computes ").append(computation.computedField().path());
        computation.commonPrecondition().ifPresent(p -> text.append("\nwhen ").append(renderer.render(p)));
        for (Computation.Alternative alternative : computation.alternatives()) {
            text.append('\n');
            alternative.precondition().ifPresentOrElse(
                    p -> text.append("when ").append(renderer.render(p)).append(" => "),
                    () -> text.append("always => "));
            text.append(renderer.render(alternative.operation()));
        }
        return text.toString();
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
