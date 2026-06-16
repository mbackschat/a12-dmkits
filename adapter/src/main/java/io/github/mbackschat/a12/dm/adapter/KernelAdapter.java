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
import io.github.mbackschat.a12.dm.rulekit.dsl.Comments;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.RenderedDiff;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.KernelDiagnostics;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelLoader;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.mgmtp.a12.kernel.md.document.api.IDocument;
import com.mgmtp.a12.kernel.md.document.api.services.IDocumentFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentRtServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelResolver;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentComputationResult;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentDynamicServiceConfig;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentModelIdProvider;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentRtService;
import com.mgmtp.a12.kernel.md.rt.api.IDocumentValidationResult;
import com.mgmtp.a12.kernel.md.rt.api.ILabelProvider;
import com.mgmtp.a12.kernel.md.rt.api.IMessage;
import com.mgmtp.a12.kernel.md.rt.api.IModelCode;
import com.mgmtp.a12.kernel.md.rt.api.IModelCodeCache;

/**
 * Phase-2 kernel-adapter bootstrap (proposal D15 / N8 tier 3). Wraps the kernel's {@code a12internal}
 * <em>read</em> surface for rules already in a model — the foreign-rule path that read (F10) and
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
    /** The header id is the first {@code "id"} in the DM-JSON (the document-model id passed to the runtime). */
    private static final Pattern HEADER_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

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
     * so a rule referencing an included model's field reads instead of failing to load.
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

    /**
     * The kernel's canonical condition text re-emitted in {@code language}'s keywords (EN↔DE) — a parse +
     * format + translate round-trip through the kernel's own {@code formatAndTranslateConditionText}. The
     * translate peer of {@link #canonicalConditionText(String)}: {@code And}↔{@code Und} and friends switch
     * language while field paths stay put, so a team can normalize a model into the other dialect with the
     * engine as the authority (complementing rulekit's {@code DslRenderer(DE)}). Computations share this path
     * (a computation is also one of the model's {@link #rule}s). Like the plain format round-trip it is lossy
     * for a {@code $} correlation (KERNEL-FINDINGS) — a display/normalization read, not a write-back.
     *
     * <p>Takes rulekit's {@link ConditionLanguage}, not a raw {@code Locale}, by design (N8 boundary): the
     * kernel recognizes <em>only</em> {@code Locale.US} and {@code Locale.GERMANY} as condition languages and
     * <b>silently falls back to German</b> for any other locale (KERNEL-FINDINGS) — mapping here makes that
     * footgun unrepresentable.
     */
    public String canonicalConditionText(String ruleFullName, ConditionLanguage language) {
        Locale kernelLocale = language == ConditionLanguage.DE ? Locale.GERMANY : Locale.US;
        return mvk.formatAndTranslateConditionText(rule(ruleFullName), kernelLocale, SILENT);
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
     * ReadResult (F10): parse a rule's stored condition and convert it into rulekit's typed AST — the
     * read direction of the bidirectional loop. Returns a sealed {@link ReadResult}: {@code Converted}
     * (an ordinary rulekit {@link Condition}, carrying the D14 invariant as if hand-authored) or, for
     * a not-yet-modeled construct, {@code Opaque} (the rule preserved verbatim with a reason — never
     * lost, never reported as corrupt). Tolerant by design: a valid rule the kernel accepts never
     * throws here (the converter's strict {@link UnsupportedConstructException} is caught and mapped
     * to {@code Opaque}). The kernel transport AST never escapes.
     */
    public ReadResult read(String ruleFullName) {
        try {
            ConditionConverter converter = new ConditionConverter(model);
            ICondition parsed = parseCondition(ruleFullName);
            int outerRefs = converter.countOuterRefs(parsed);   // $ correlations the source carries (N8)
            Condition converted = converter.convert(parsed);
            converter.verifyOuterPreserved(converted, outerRefs);   // backstop: a dropped $ → Opaque, never silent
            return new ReadResult.Converted(converted);
        } catch (UnsupportedConstructException unconverted) {
            return new ReadResult.Opaque(verbatimConditionText(ruleFullName), unconverted.getMessage());
        } catch (RuntimeException unexpected) {
            // Tolerant-by-design: a rule the kernel accepts must never crash whole-DM read. A
            // reference the converter can't resolve in rulekit's content-only index (e.g. a header
            // entity like /header/id, or a path form rulekit doesn't model) degrades to Opaque —
            // preserved verbatim, never lost — rather than escaping as an exception.
            return new ReadResult.Opaque(verbatimConditionText(ruleFullName),
                    "unresolved reference (" + unexpected.getClass().getSimpleName() + "): "
                            + unexpected.getMessage());
        }
    }

    /**
     * The faithful text to preserve for an {@link ReadResult.Opaque}: the rule's <b>raw stored</b> condition
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
     * ReadResult every <em>validation rule</em> in the model — the publishing-safe whole-DM entry point.
     * Returns each rule's outcome keyed by full name (insertion-ordered); never throws on an unmodeled
     * construct (those become {@link ReadResult.Opaque}). The caller sees exactly which rules are typed and
     * which are preserved opaque.
     *
     * <p>Computations are <em>excluded</em>: a computation is stored as a rule (so it appears in the
     * kernel's {@code getAllRules()}), but its condition is the generated self-validation of a calculated
     * field — a computation, not a hand-authored rule. Computations read via
     * {@link #readAllComputations}; {@link #readModel} unifies both into one disjoint view.
     */
    public java.util.Map<String, ReadResult> readAll() {
        java.util.Set<String> computations = computationFullNames();
        java.util.Map<String, ReadResult> out = new java.util.LinkedHashMap<>();
        for (IRule r : elements.getAllRules()) {
            if (!computations.contains(r.getFullName())) {   // skip computation rules — see readAllComputations
                out.put(r.getFullName(), read(r.getFullName()));
            }
        }
        return out;
    }

    /**
     * The full names of every <em>validation rule</em> in the model — cheap (no conversion); for resolving
     * a {@code --rule} argument. Computations are excluded (see {@link #computationFullNames}); a
     * {@code --rule} that names a computation should be rejected so it isn't read as a rule.
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

    // ── Computations (the calculated-field read path; the ReadComputation peer of read) ───────

    /**
     * The full names of every <b>computation</b> in the model — the discriminator that partitions
     * {@link #ruleFullNames()} into true validation rules and computation rules. A computation is stored
     * as a rule (so it appears in {@code getAllRules()} too), but its "condition" is the kernel-generated
     * self-validation of a calculated field — semantically a computation, read via
     * {@link #readComputation}, not {@link #read}.
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
     * ReadResult one computation (F10, the calculated-field direction): convert the kernel's computation —
     * calculated field, common precondition, and {@code (precondition, operation)} alternatives — into a
     * typed rulekit {@link Computation}, carrying the recovered error messages so it survives the full
     * read → modify → write-back cycle. Tolerant: a not-yet-modeled computation degrades to
     * {@link ReadComputation.Opaque} rather than throwing.
     */
    public ReadComputation readComputation(String computationFullName) {
        return readOne(calculationRule(computationFullName));
    }

    /** Look up a computation's {@code ICalculationRule} by full name (read-only — the {@link #rule} peer). */
    private ICalculationRule calculationRule(String computationFullName) {
        for (ICalculationRule cr : elements.getAllCalculations()) {
            if (computationFullName.equals(cr.getRule().getFullName())) {
                return cr;
            }
        }
        throw PrecheckException.notFound(RkCode.NO_SUCH_COMPUTATION, computationFullName);
    }

    /**
     * ReadResult every computation in the model, keyed by full name (insertion-ordered) — the computation
     * peer of {@link #readAll()}; never throws on an unmodeled computation (those become Opaque).
     */
    public java.util.Map<String, ReadComputation> readAllComputations() {
        java.util.Map<String, ReadComputation> out = new java.util.LinkedHashMap<>();
        for (ICalculationRule cr : elements.getAllCalculations()) {
            out.put(cr.getRule().getFullName(), readOne(cr));
        }
        return out;
    }

    /**
     * The unified whole-model read (F10): every <em>validation rule</em> ({@link #readAll}) and every
     * <em>computation</em> ({@link #readAllComputations}) of the model as one {@link ModelRead} — two
     * disjoint maps, no double-counting (a computation appears under {@code computations}, never under
     * {@code rules}). The single entry point for round-tripping a foreign model completely; tolerant, so
     * it never throws on an unmodeled construct (those stay {@code Opaque}).
     */
    public ModelRead readModel() {
        return new ModelRead(readAll(), readAllComputations());
    }

    private ReadComputation readOne(ICalculationRule cr) {
        String verbatim = verbatimComputationText(cr);
        try {
            java.util.Optional<ICalculationInfo> info = calcService().extractCalculationInfo(cr, SILENT);
            if (info.isEmpty()) {
                return new ReadComputation.Opaque(verbatim, "the kernel could not extract calculation info");
            }
            Computation computation = new ConditionConverter(model)
                    .convertComputation(info.get(), recoverMessages(cr.getRule()));
            return new ReadComputation.Converted(computation);
        } catch (UnsupportedConstructException unconverted) {
            return new ReadComputation.Opaque(verbatim, unconverted.getMessage());
        } catch (RuntimeException unexpected) {
            return new ReadComputation.Opaque(verbatim, "unresolved reference ("
                    + unexpected.getClass().getSimpleName() + "): " + unexpected.getMessage());
        }
    }

    /** The verbatim stored text to preserve for an opaque computation — a computation is stored as a rule,
     *  so this is its raw {@code errorConditionText} (the {@link #verbatimConditionText} analogue). */
    private static String verbatimComputationText(ICalculationRule cr) {
        String raw = cr.getRule().getErrorConditionText();
        return raw == null ? "" : raw;
    }

    /** Recover a computation's error messages (the kernel requires them) as rulekit {@link LocalizedText}. */
    private static List<LocalizedText> recoverMessages(IRule rule) {
        List<LocalizedText> messages = new ArrayList<>();
        rule.getErrorMessages().forEach((locale, text) -> messages.add(new LocalizedText(locale.toString(), text)));
        return messages;
    }

    /**
     * Modify-by-re-express diff (F8 #2): render the rule's current condition (as read) against a
     * re-expressed {@code modified} one, disclosing lossiness up front (study finding 8). The
     * before/after are both rulekit renderings (symmetric — the diff is the intended change, not
     * cosmetics), each carrying the source's <b>leading comment</b> verbatim because write-back
     * preserves it ({@link Comments#preserveLeading}, F8 #3). The lossiness then notes only what
     * re-rendering genuinely drops — <em>inline/trailing</em> comments beyond the leading one. The
     * caller reviews this before writing back.
     *
     * @throws IllegalStateException if the rule's current condition didn't read cleanly (it is
     *         {@code Opaque}) — you can't re-express an AST you don't have.
     */
    public RenderedDiff diff(String ruleFullName, Condition modified) {
        if (!(read(ruleFullName) instanceof ReadResult.Converted current)) {
            throw new IllegalStateException("cannot diff a rule whose condition did not read cleanly: "
                    + ruleFullName);
        }
        IRule rule = rule(ruleFullName);
        java.util.List<String> lossiness = new java.util.ArrayList<>();
        String raw = rule.getErrorConditionText();
        String before = render(current.condition());
        String after = render(modified);
        if (raw != null) {
            // the leading comment survives write-back, so show it on both sides (context, not a change);
            // anything still commented beyond the leading block is what re-rendering drops.
            before = Comments.preserveLeading(raw, before);
            after = Comments.preserveLeading(raw, after);
            String beyondLeading = raw.substring(Comments.leadingCommentBlock(raw).length());
            String commentFree = java.util.Objects.toString(mvk.stripComments(rule), "");
            if (!beyondLeading.strip().equals(commentFree.strip())) {
                lossiness.add("inline/trailing comments are not preserved by re-rendering"
                        + " (the leading comment is carried over)");
            }
        }
        return RenderedDiff.between(before, after, lossiness);
    }

    /** The raw stored condition text of {@code ruleFullName} — concrete syntax (comments + formatting) intact. */
    public String conditionText(String ruleFullName) {
        return rule(ruleFullName).getErrorConditionText();
    }

    /**
     * Modify-by-re-express diff for a <b>computation</b> (F8 #2, the {@link #diff} peer): render the
     * computation's current shape (as read) against a re-expressed {@code modified} one, so the caller
     * reviews the intended change before writing it back. Both sides are rulekit renderings of the whole
     * computation — computed field, common precondition, and each {@code (precondition, operation)}
     * alternative — so the diff is the change, not cosmetics.
     *
     * @throws IllegalStateException if the computation didn't read cleanly (it is {@code Opaque}) — you
     *         can't re-express a shape you don't have.
     */
    public RenderedDiff diffComputation(String computationFullName, Computation modified) {
        if (!(readComputation(computationFullName) instanceof ReadComputation.Converted current)) {
            throw new IllegalStateException("cannot diff a computation that did not read cleanly: "
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

    // ---- runtime evaluation (does a rule FIRE on a document instance?) — the eval-doc seam -----------

    /**
     * Run the kernel's runtime validation over a document <b>instance</b> (actual field data) and report
     * every message that fired — the runtime counterpart of the static {@link #diagnose} (does-this-rule
     * <em>fire on data</em>, not is-this-rule <em>accepted</em>). The WHOLE model is evaluated; narrow to
     * one rule by matching {@link EvalResult#firedCodes()} against its code. Powers the {@code eval-doc}
     * verb and lets a judge decide rule equivalence by EXECUTION against the kernel, not by reasoning.
     *
     * <p>{@code fields} is the nested document tree (boundary = JDK types only): a <b>repeatable</b> group
     * is a {@link List} of row {@link Map}s, a <b>non-repeatable</b> group a nested {@link Map}, a
     * <b>field</b> its text value ({@code null}/empty string = present-but-unfilled; an <em>absent</em> key
     * = no instance). Repetition indices (one 1-based index per path segment) are <em>derived</em> from the
     * structure, never authored.
     *
     * @throws IllegalArgumentException if a key doesn't resolve to a field/group at its path, or its JSON
     *         shape contradicts the model (an array for a non-repeatable group, an object for a field, …)
     */
    public EvalResult evaluate(Map<String, Object> fields, Locale locale) {
        DocRuntime runtime = prepare(fields);
        IDocumentValidationResult result = runtime.rt().validateFull(runtime.document(), locale);
        List<EvalMessage> messages = new ArrayList<>();
        for (IMessage m : result.getMessages()) {
            messages.add(new EvalMessage(m.getErrorCode(), m.getRulePath(),
                    String.valueOf(m.getErrorFieldPointer()), m.getSeverity().name(),
                    m.getMessageType().name(), m.getErrorText()));
        }
        return new EvalResult(messages);
    }

    /** The kernel's sentinel {@code rulePath} for a formal check (vs. an author rule, whose path starts with {@code /}). */
    private static final String FORMAL_CHECK = "formalePruefung";

    /**
     * Test <b>one rule</b> against a document instance (FEATURE-GAPS §3.3) — the scoped, suppression-aware
     * polarity check over {@link #evaluate}. Returns a three-way {@link RuleTestOutcome.Verdict}: the rule
     * <b>FIRED</b> (a message carries its path), it <b>PASSED</b> (evaluated, no message), or it was
     * <b>SUPPRESSED</b> — never evaluated because a field it references is formally invalid (the kernel skips
     * a rule whose operand is "unknown"; KERNEL-FINDINGS, {@code FormalErrorLawsTest}). The distinction
     * matters: reporting a suppressed rule as a pass would mislead, so when not fired we check whether any of
     * the rule's {@linkplain #referencedFields referenced fields} drew a formal-check error and, if so, name them.
     */
    public RuleTestOutcome testRule(String ruleFullName, Map<String, Object> fields, Locale locale) {
        Set<String> referenced = referencedFields(ruleFullName);
        List<EvalMessage> messages = evaluate(fields, locale).messages();
        Optional<EvalMessage> firedMessage = messages.stream()
                .filter(m -> m.rulePath().filter(ruleFullName::equals).isPresent()).findFirst();
        if (firedMessage.isPresent()) {
            return new RuleTestOutcome(ruleFullName, RuleTestOutcome.Verdict.FIRED, List.of(),
                    Optional.of(firedMessage.get().message()));
        }
        List<RuleTestOutcome.Suppression> suppressions = new ArrayList<>();
        for (EvalMessage m : messages) {
            if (isFormalCheck(m)) {
                for (String field : referenced) {
                    if (pointerHitsField(m.fieldPointer(), field)) {
                        suppressions.add(new RuleTestOutcome.Suppression(field, m.errorCode()));
                    }
                }
            }
        }
        RuleTestOutcome.Verdict verdict = suppressions.isEmpty()
                ? RuleTestOutcome.Verdict.PASSED
                : RuleTestOutcome.Verdict.SUPPRESSED;
        return new RuleTestOutcome(ruleFullName, verdict, suppressions, Optional.empty());
    }

    /** A formal/structural check (not an author rule): no {@code rulePath}, or one that isn't an absolute slash-path. */
    private static boolean isFormalCheck(EvalMessage m) {
        return m.rulePath().map(p -> !p.startsWith("/")).orElse(true);
    }

    /**
     * Whether a kernel field-instance pointer (e.g. {@code [/Order/UnitPrice, [1]]}) targets {@code fieldPath} —
     * a containment match bounded so a field is not mistaken for a longer sibling that shares its prefix
     * ({@code /Order/Unit} must not hit {@code /Order/UnitPrice}).
     */
    private static boolean pointerHitsField(String pointer, String fieldPath) {
        int at = pointer.indexOf(fieldPath);
        if (at < 0) {
            return false;
        }
        int after = at + fieldPath.length();
        return after >= pointer.length() || !isPathChar(pointer.charAt(after));
    }

    private static boolean isPathChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '_';
    }

    /**
     * Run the kernel's runtime <b>computation</b> over a document instance and report each computed field's
     * outcome — the peer of {@link #evaluate} (does-this-field-COMPUTE, against the same document model and
     * the same nested {@code fields} representation). Read-only: {@code compute} returns a <em>result</em>
     * (the kernel never mutates the input; applying it back is a separate, opt-in step), so this reports
     * what each computed field <em>becomes</em> — a value, cleared (computed nothing — e.g. an empty
     * operand, see {@code DateRangeComputationLawsTest}), or an error. Powers the {@code compute-doc} verb
     * and lets a computation judge verify computation-equivalence by EXECUTION.
     */
    public ComputeResult compute(Map<String, Object> fields, Locale locale) {
        DocRuntime runtime = prepare(fields);
        IDocumentComputationResult result = runtime.rt().compute(runtime.document(), locale);
        List<ComputedField> computed = new ArrayList<>();
        result.getComputedFieldInstancesWithChanges()
                .forEach(c -> computed.add(new ComputedField(c.getPath(), "value", c.getStringValue())));
        result.getComputedFieldInstancesWithErrors()
                .forEach(c -> computed.add(new ComputedField(c.getPath(), "error", c.getStringValue())));
        result.getClearedFieldInstances()
                .forEach(c -> computed.add(new ComputedField(c.getPath(), "cleared", Optional.empty())));
        return new ComputeResult(computed);
    }

    /** The kernel runtime service + the built document instance, shared by {@link #evaluate}/{@link #compute}. */
    private record DocRuntime(IDocumentRtService rt, IDocument document) {
    }

    /** Build the runtime service + the document instance from the nested {@code fields} tree (model-aware walk). */
    private DocRuntime prepare(Map<String, Object> fields) {
        IDocumentModelReferenceResolver resolver =
                model.resolver() != null ? model.resolver() : ReferenceResolvers.selfContained();
        IDocumentModel expanded;
        try {
            expanded = ModelLoader.load(new StringReader(model.toJson()), resolver);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load the model for evaluation", e);
        }
        ModelIndex index = ModelIndex.of(expanded);
        IDocumentModelResolver modelResolver = id -> expanded;
        IDocumentRtService rt = new DocumentRtServiceFactory(modelResolver).createDocumentRtService(dynamicConfig());
        IDocumentFactory df = new DocumentServiceFactory(modelResolver).createDocumentFactory();

        IDocument document = df.createDocument(modelId(model.toJson()));
        Set<String> groupPaths = index.groupPaths().collect(Collectors.toCollection(HashSet::new));
        buildInstances(fields, "", new int[0], index, groupPaths, df, document);
        return new DocRuntime(rt, document);
    }

    /**
     * Recursively turn the nested document tree into kernel entity instances, deriving one 1-based
     * repetition index per path segment (a group's index = its array position, or 1 if non-repeating; a
     * field's own index is always 1; a parent is created before its children). Validates every path
     * against the model so a typo or a shape mismatch fails fast rather than yielding a wrong evaluation.
     */
    private static void buildInstances(Map<String, Object> node, String parentPath, int[] reps,
            ModelIndex index, Set<String> groupPaths, IDocumentFactory df, IDocument document) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String childPath = parentPath + "/" + e.getKey();
            Object value = e.getValue();
            if (value instanceof List<?> rows) {
                if (!groupPaths.contains(childPath) || !index.isRepeating(childPath)) {
                    throw new IllegalArgumentException("'" + childPath + "' is given as a JSON array (rows) but is not"
                            + " a repeatable group in this model");
                }
                int r = 0;
                for (Object row : rows) {
                    int[] rowReps = append(reps, ++r);
                    document.addEntityInstance(df.createGroupInstance(childPath, rowReps));
                    buildInstances(asMap(row, childPath), childPath, rowReps, index, groupPaths, df, document);
                }
            } else if (value instanceof Map<?, ?>) {
                if (!groupPaths.contains(childPath)) {
                    throw new IllegalArgumentException("'" + childPath + "' is given as a JSON object (a group) but is"
                            + " not a group in this model");
                }
                if (index.isRepeating(childPath)) {
                    throw new IllegalArgumentException("'" + childPath + "' is a repeatable group — give it as a JSON"
                            + " array of rows, not a single object");
                }
                int[] childReps = append(reps, 1);
                document.addEntityInstance(df.createGroupInstance(childPath, childReps));
                buildInstances(asMap(value, childPath), childPath, childReps, index, groupPaths, df, document);
            } else {
                if (!index.isField(childPath)) {
                    throw new IllegalArgumentException("'" + childPath + "' is not a field in this model — check the"
                            + " path and the document structure (a group is a nested object/array, a field is a value)");
                }
                int[] fieldReps = append(reps, 1);
                if (value == null || (value instanceof String s && s.isEmpty())) {
                    document.addEntityInstance(df.createFieldInstance(childPath, fieldReps));   // present, unfilled
                } else {
                    document.addEntityInstance(df.createFieldInstance(childPath, fieldReps, String.valueOf(value)));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String path) {
        if (!(o instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("'" + path + "' must be a JSON object (group), got: " + o);
        }
        return (Map<String, Object>) m;
    }

    private static int[] append(int[] base, int v) {
        int[] out = Arrays.copyOf(base, base.length + 1);
        out[base.length] = v;
        return out;
    }

    /** The in-memory dynamic runtime config (no precompiled model code) — lifted from the F-LAW oracle. */
    private static IDocumentDynamicServiceConfig dynamicConfig() {
        IModelCodeCache cache = new IModelCodeCache() {
            private final Map<String, IModelCode> codes = new HashMap<>();
            public IModelCode getModelCode(String id) { return codes.get(id); }
            public void addModelCode(String id, IModelCode code) { codes.put(id, code); }
        };
        return new IDocumentDynamicServiceConfig() {
            public IModelCodeCache getCache() { return cache; }
            public Optional<String> getVariant() { return Optional.empty(); }
            public Optional<ILabelProvider> getLabelProvider() { return Optional.empty(); }
            public Optional<IDocumentModelIdProvider> getModelIdProvider() { return Optional.empty(); }
        };
    }

    private static String modelId(String modelJson) {
        Matcher m = HEADER_ID.matcher(modelJson);
        if (m.find()) {
            return m.group(1);   // the header id is the first "id" in the document
        }
        throw new IllegalArgumentException("no header id in model JSON");
    }

    /** Parse a rule's own stored condition into the kernel transport AST (adapter-internal, for F10 read). */
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
