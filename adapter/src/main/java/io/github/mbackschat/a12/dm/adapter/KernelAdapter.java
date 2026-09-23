package io.github.mbackschat.a12.dm.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.mgmtp.a12.kernel.core.parsetree.a12internal.api.IParseTreeEntity;
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
import com.mgmtp.a12.kernel.md.model.a12internal.DocumentModel;
import com.mgmtp.a12.kernel.md.model.a12internal.services.DocumentModelService;
import com.mgmtp.a12.kernel.md.model.a12internal.services.ElementContainer;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.internal.service.mapping.IRuleFromComputation;
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
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Verification;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import com.mgmtp.a12.kernel.md.document.api.IDocument;
import com.mgmtp.a12.kernel.md.document.api.services.IDocumentFactory;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2;
import com.mgmtp.a12.kernel.md.facade.DocumentRtServiceFactory;
import com.mgmtp.a12.kernel.md.facade.DocumentServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelResolver;
import com.mgmtp.a12.kernel.md.rt.api.DocumentProcessingConfig;
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
 * Phase-2 kernel-adapter bootstrap (PROJECT-DESIGN D15 / N8 tier 3). Wraps the kernel's {@code a12internal}
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
 * {@code IRule} is to look one up among the model's existing rules). The converter visitor (F10, the
 * {@link ReadResult.Converted}/{@link ReadResult.Opaque} read) and {@code IProblem → Diagnostic} mapping
 * are built on this wiring (format / referenced-entities / validity).
 *
 * <p><b>Read + diff, not write-back (cohesion):</b> this adapter <em>reads</em> foreign rules
 * ({@link #read}/{@link #readAll}/{@link #readModel}) and <em>renders</em> a diff ({@link #diff}/
 * {@link #diffComputation}) over the kernel — it deliberately does not persist changes. The
 * read&rarr;modify&rarr;write-back loop closes on {@link BoundModel} / {@code EditSession} (the
 * modelkit/edit write surface): read a rule here, re-express + emit it there. Keeping mutation off this
 * internal read tier is the N8 boundary, not an omission.
 */
public final class KernelAdapter {

    /** Discards problems — the bootstrap reads validity from return values; IProblem→Diagnostic is F10. */
    private static final IProblemReporter SILENT = problem -> { };
    /** The header id is the first {@code "id"} in the DM-JSON (the document-model id passed to the runtime). */
    private static final Pattern HEADER_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private final IMVK_Service mvk;
    private final ElementContainer elements;
    private final BoundModel model;
    /** The gate receipt: null for the deliberately unchecked {@link #of} path, else how the gate was arbitrated. */
    private final Verification consistencyGate;
    private ICalculationService calc;   // lazily built (from the MVK service, which is its config) — only computations need it
    private long referenceParses;       // observability seam: count of kernel condition-parses (getDirectlyReferencedEntities)
    private Map<String, IRule> ruleIndex;                    // lazily built name→rule index (kills the per-lookup O(R) scan)
    private Map<String, ICalculationRule> calcIndex;         // lazily built name→computation index (the ICalculationRule peer)

    private KernelAdapter(IMVK_Service mvk, BoundModel model, Verification consistencyGate) {
        this.mvk = mvk;
        this.model = model;
        this.consistencyGate = consistencyGate;
        IEC iec = mvk.getIEC();
        if (!(iec instanceof ElementContainer container)) {
            throw new IllegalStateException(
                    "expected the MVK service's IEC to be an ElementContainer, got " + iec.getClass());
        }
        this.elements = container;
    }

    /** Open an adapter over a rulekit {@link BoundModel} (via its DM-JSON — the public boundary). */
    public static KernelAdapter of(BoundModel model) {
        ModelResolver resolver =
                model.resolver() != null ? model.resolver() : ReferenceResolvers.selfContained();
        // Deliberately unchecked (a candidate-rule inspection may pass a lenient binding), so NO gate receipt —
        // any consistency evidence this adapter's consumer publishes must come from a path that actually gated.
        return new KernelAdapter(buildService(model.toJson(), resolver, ConsistencyGate.SKIP), model, null);
    }

    /**
     * Run the consistency gate and open an adapter over the same kernel preparation. The supplied binding may
     * be lenient; it must retain source JSON and its resolver. This is the shared large-model path for consumers
     * that need both a {@link BoundModel} projection and kernel reads without deserializing the kernel model twice.
     */
    public static KernelAdapter checkAndOpen(BoundModel model) {
        ModelResolver resolver =
                model.resolver() != null ? model.resolver() : ReferenceResolvers.selfContained();
        return new KernelAdapter(buildService(model.toJson(), resolver, ConsistencyGate.REQUIRED), model,
                Backends.verification());
    }

    /**
     * The consistency gate's <b>receipt</b> — empty for the deliberately unchecked {@link #of(BoundModel)} path,
     * else how {@link #checkAndOpen} arbitrated it. The adapter twin of
     * {@link BoundModel#consistencyGate()}: two factories of the same type run different gates, so the receipt
     * (never the factory name, and never a per-verb table) is what a consumer publishes evidence from.
     */
    public Optional<Verification> consistencyGate() {
        return Optional.ofNullable(consistencyGate);
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
    public static KernelAdapter ofJson(String modelJson, ModelResolver resolver) {
        return checkAndOpen(BoundModel.ofJsonLenient(modelJson, resolver));
    }

    /** Deserialize + expand once, optionally check, convert to internal, and bootstrap the MVK service. */
    private static IMVK_Service buildService(
            String modelJson,
            ModelResolver resolver,
            ConsistencyGate gate) {
        KernelModelPreparation.ExpandedModel expanded = KernelModelPreparation.expand(modelJson, resolver);
        if (gate == ConsistencyGate.REQUIRED) {
            expanded.requireConsistent();
        }
        DocumentModel intern = ExternalDocumentModelSerializerService.getInternDocumentModel(expanded.model());
        return new DocumentModelService().getMvkServiceForModel(intern, expanded.variant(), SILENT);
    }

    private enum ConsistencyGate {
        SKIP,
        REQUIRED
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
        referenceParses++;   // one kernel condition-parse — the count the single-pass usage-inverse contract locks
        Set<IEntity> refs = mvk.getDirectlyReferencedEntities(rule, SILENT);
        if (refs != null) {                                  // ParserService returns null on a parse failure
            for (IEntity entity : refs) {
                paths.add(entity.getFullName());
            }
        }
        return paths;
    }

    /**
     * Observability seam (Set RP): the running count of kernel condition-parses
     * ({@code getDirectlyReferencedEntities}) — the expensive per-rule/per-computation reference read. Lets a
     * test <b>deterministically</b> lock the single-pass {@code O(R+C)} contract of {@link #referrersByEntity}
     * (each rule and computation parsed at most once per whole-model audit) instead of relying on wall-clock
     * timing — the guard against re-introducing the {@code O(F·(R+C))} per-field recomputation.
     */
    public long referenceParseCount() {
        return referenceParses;
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
    public Set<String> whereUsed(String entityFullName) {
        LinkedHashSet<String> rules = new LinkedHashSet<>();
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
    public Set<String> whereUsedInComputations(String entityFullName) {
        LinkedHashSet<String> comps = new LinkedHashSet<>();
        for (ICalculationRule cr : elements.getAllCalculations()) {
            if (referencedEntities(cr.getRule()).contains(entityFullName)) {
                comps.add(cr.getRule().getFullName());
            }
        }
        return comps;
    }

    /**
     * The whole-model <b>reference inverse</b>, built in a SINGLE pass: every field/group full path → the full
     * names of the validation rules and computations that <em>directly</em> reference it (the per-entity union
     * of {@link #whereUsed} and {@link #whereUsedInComputations}). This is the {@code O(R+C)} foundation a
     * model-wide audit needs — a report's dead-field set and {@code model usage} would otherwise call the
     * per-field {@code whereUsed}/{@code whereUsedInComputations} in a loop and re-parse every rule and
     * computation once <em>per field</em> ({@code O(F·(R+C))} kernel parses, the shipped {@code model report}
     * non-termination on huge models). Each rule and computation is parsed exactly once here.
     *
     * <p>An entity with no referrer is absent from the map. <b>That does NOT make "not a key" the same as
     * "unreferenced"</b> — this entry used to say it did: a group operand expands recursively to every field
     * below it, so a field can be read while only its ancestor GROUP is a key. Ask
     * {@link #isReadByAnything}/{@link #referrersReaching} for that question ({@code CG16}).
     * Referrer sets are insertion-ordered — validation rules first (rule order), then computations
     * (computation order) — matching a per-field {@code whereUsed()}+{@code whereUsedInComputations()} union,
     * so a consumer that re-sorts (the {@code TreeSet} callers) sees byte-identical output.
     */
    public Map<String, Set<String>> referrersByEntity() {
        Map<String, Set<String>> byEntity = new LinkedHashMap<>();
        for (String ruleName : ruleFullNames()) {            // validation rules only (computations excluded)
            for (String entity : referencedFields(ruleName)) {
                byEntity.computeIfAbsent(entity, k -> new LinkedHashSet<>()).add(ruleName);
            }
        }
        for (ICalculationRule cr : elements.getAllCalculations()) {
            String computation = cr.getRule().getFullName();
            for (String entity : referencedEntities(cr.getRule())) {
                byEntity.computeIfAbsent(entity, k -> new LinkedHashSet<>()).add(computation);
            }
        }
        return byEntity;
    }

    /**
     * The rules and computations that <b>READ</b> {@code entityPath} — directly, <em>or through an ancestor
     * GROUP operand</em>. The <b>reachability</b> peer of {@link #referrersByEntity}'s exactness.
     *
     * <p><b>Why both exist, and why the exact one is not the bug.</b> {@link #whereUsed} is exact by design and
     * says so: a rule referencing an enclosing group is not listed for a field inside it. That is the right
     * answer to <i>"which rules name this entity?"</i>. It is the wrong answer to <i>"does anything read this
     * field?"</i>, because the kernel's {@code ExpandService} expands a group operand <b>recursively</b> to every
     * field below it — so {@code AllFieldsFilled(/Order/Address)} reads {@code /Order/Address/Region/Code} while
     * naming neither. Consumers that asked the reachability question through the exactness primitive reported a
     * live field as {@code unreferenced}, which an agent reads as safe-to-delete ({@code CG16}).
     *
     * <p><b>Ancestors are walked by cutting at each {@code /}, which is segment-correct by construction</b> — the
     * one thing a {@code startsWith} test gets wrong, since {@code "/Order/BillingContact/Email"} starts with
     * {@code "/Order/Billing"} without being inside it. That trap is live one file over, as {@code CG17}'s
     * {@code "/Order/Address".startsWith("/Order/Address/")} over-refusal.
     *
     * <p>Conservative on purpose: it answers "a rule reaches into this group", not "this particular field enters
     * that operator's expansion". For a safety gate that is the correct direction to err, and for a usage report
     * it matches what the kernel actually evaluates.
     *
     * <p><b>Not every {@link #referrersByEntity} consumer wants this</b>, and the two that keep the exact answer
     * do so for a reason worth writing down rather than re-deriving:
     * <ul>
     *   <li>{@code StructureRefactor}'s referrer scans feed {@code changed.rewroteReferences} — a report of which
     *       condition TEXTS a rename/move actually rewrote. A rule naming an ancestor group has no text to
     *       rewrite, so including it would report a change that did not happen; a rule naming a descendant is
     *       already covered, because that scan enumerates the subtree.</li>
     *   <li>{@code ModelDiffState} merely holds the shared map; its consumers decide individually
     *       ({@code FieldDiffProjector} reaches for a field, {@code GroupDiffProjector} for a removed group).</li>
     * </ul>
     *
     * @param byEntity one {@link #referrersByEntity} map, passed in so a model-wide sweep builds it ONCE — the
     *                 {@code O(R+C)} contract this method must not silently turn back into {@code O(F·(R+C))}
     */
    public static Set<String> referrersReaching(Map<String, Set<String>> byEntity,
            String entityPath) {
        Set<String> reaching =
                new LinkedHashSet<>(byEntity.getOrDefault(entityPath, Set.of()));
        // EVERY ancestor level contributes — this unions, it does not select the nearest. A field can be read by
        // one rule naming its parent group and another naming its grandparent, and both are readers.
        // The walk cuts at each '/', which is segment-correct by construction, and `cut > 0` stops before the
        // empty prefix. That also means a "/" key is never consulted; no producer makes one today.
        for (int cut = entityPath.lastIndexOf('/'); cut > 0; cut = entityPath.lastIndexOf('/', cut - 1)) {
            reaching.addAll(byEntity.getOrDefault(entityPath.substring(0, cut), Set.of()));
        }
        return reaching;
    }

    /**
     * Whether ANYTHING reads {@code entityPath} — {@link #referrersReaching} for callers that only need the
     * boolean, short-circuiting on the first hit instead of materializing every referrer of every ancestor.
     * The dead-field sweep asks this once per field, so building F sets to discard all but their emptiness is
     * the kind of cost the {@code O(R+C)} contract above exists to keep out.
     */
    public static boolean isReadByAnything(Map<String, Set<String>> byEntity, String entityPath) {
        if (!byEntity.getOrDefault(entityPath, Set.of()).isEmpty()) {
            return true;
        }
        for (int cut = entityPath.lastIndexOf('/'); cut > 0; cut = entityPath.lastIndexOf('/', cut - 1)) {
            if (!byEntity.getOrDefault(entityPath.substring(0, cut), Set.of()).isEmpty()) {
                return true;
            }
        }
        return false;
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
        mvk.hasValidConditionText(rule(ruleFullName),
                p -> diagnostics.add(KernelDiagnostics.enriched(KernelNotifications.toCore(p), ruleFullName)));
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
                p -> diagnostics.add(KernelDiagnostics.enriched(KernelNotifications.toCore(p), ruleFullName)));
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
            if (parsed == null) {
                // The kernel's parser is SILENT here, so it answers an unparseable stored condition with null
                // rather than an exception, and every caller below dereferences it. Unguarded, the first
                // dereference threw an NPE that opaqueFailureReason then labelled "converter error (…) at
                // ConditionConverter.countEntities" — a MODEL problem reported as a bug in our own converter,
                // the exact mislabel that method exists to prevent. Measured 2026-09-20 on two unrelated
                // laws (a format-illegal extraction, a star on a non-repeatable group), so the null is the
                // parser's general answer to "this text does not fit this model", not one law's quirk.
                return new ReadResult.Opaque(verbatimConditionText(ruleFullName),
                        "the kernel could not parse the stored condition against this model");
            }
            int outerRefs = converter.countOuterRefs(parsed);   // $ correlations the source carries (N8)
            int categoryRefs = converter.countCategoryRefs(parsed);   // [field -> Cat] projections it carries
            int semanticIndexRefs = converter.countSemanticIndexRefs(parsed);   // [field For key] reads
            int wildcardLevels = converter.countWildcardLevels(parsed);   // repetition stars it carries
            Condition converted = converter.convert(parsed);
            converter.verifyOuterPreserved(converted, outerRefs);   // backstop: a dropped $ → Opaque, never silent
            String rendered = new DslRenderer().render(converted);
            converter.verifyCategoryPreserved(rendered, categoryRefs);
            converter.verifySemanticIndexPreserved(rendered, semanticIndexRefs);
            converter.verifyWildcardPreserved(rendered, wildcardLevels);
            String stored = verbatimConditionText(ruleFullName);
            if (dropsALoadBearingGroup(ruleFullName, stored, rendered)) {
                return new ReadResult.Opaque(stored, "F10 converter would silently drop a `{ … }` group the "
                        + "kernel reads as decisive: the stored condition carries "
                        + ConditionConverter.groupCount(stored) + " group(s) and conversion rendered "
                        + ConditionConverter.groupCount(rendered) + ", and the kernel accepts the stored text "
                        + "while refusing the rendering — so the rule is preserved verbatim "
                        + "[kernel node: BRACKET_OP, recorded only on ICompositeOperation]");
            }
            return new ReadResult.Converted(converted);
        } catch (UnsupportedConstructException unconverted) {
            return new ReadResult.Opaque(verbatimConditionText(ruleFullName), unconverted.getMessage());
        // There is deliberately NO `catch (KernelCodeRefusal)` here, and it was tried (DG1001, 2026-09-20).
        // A rulekit gate can only refuse what the converter has already begun rebuilding, and on this path
        // the kernel answers first for every law measured — a format-illegal extraction, a star on a
        // non-repeatable group, and duplicate operands all produced a null parse above, never a refusal. So
        // the arm had no reachable case, and an unreachable arm claiming a behaviour is worse than none.
        // Should a gate ever become stricter than the kernel, the tolerant arm below reports it as
        // "converter error (KernelCodeRefusal): … MVK_*" — which is the honest label for that, because on
        // this path the model has been read by the kernel and OUR gate is the thing that disagreed.
        } catch (RuntimeException unexpected) {
            // Tolerant-by-design: a rule the kernel accepts must never crash whole-DM read. But be
            // HONEST about why it didn't convert — opaqueFailureReason() keeps the benign
            // "unresolved reference" wording for a header/cross-form entity rulekit's content-only
            // index legitimately lacks, and reports anything else as a located "converter error"
            // (class + crash site), never the old blanket "unresolved reference (…): null" that hid
            // a converter bug behind a reference-problem label.
            return new ReadResult.Opaque(verbatimConditionText(ruleFullName), opaqueFailureReason(unexpected));
        }
    }

    /**
     * The honest {@code reason} for a read that failed with an <em>unexpected</em> exception — anything the
     * converter threw that is not an intentional {@link UnsupportedConstructException}. Two sub-cases, never
     * the old blanket {@code "unresolved reference (NullPointerException): null"} that mislabeled a converter
     * bug as a reference problem and discarded the locus:
     *
     * <ol>
     *   <li><b>a genuinely unresolved reference</b> — a header/cross-form entity rulekit's content-only
     *       {@code ModelIndex} legitimately lacks, recognizable by {@code ModelIndex.require}'s stable
     *       {@code "no element at path:"} message: a benign degrade, kept as {@code "unresolved reference …"}
     *       (so the corpus histograms that bucket on that substring keep working).</li>
     *   <li><b>anything else</b> — a converter bug on a rule the kernel accepts: reported as {@code
     *       "converter error (<Class>): <message> at <site>"}, naming the exception class and the first stack
     *       frame inside our own code so the failing site is actionable.</li>
     * </ol>
     */
    static String opaqueFailureReason(RuntimeException e) {
        String message = e.getMessage();
        if (e instanceof IllegalArgumentException && message != null && message.startsWith("no element at path:")) {
            return "unresolved reference (" + e.getClass().getSimpleName() + "): " + message;
        }
        return "converter error (" + e.getClass().getSimpleName() + ")"
                + (message != null ? ": " + message : "") + locus(e);
    }

    /** {@code " at Class.method(File:line)"} for the first frame inside our own packages — the crash locus. */
    private static String locus(Throwable e) {
        for (StackTraceElement f : e.getStackTrace()) {
            if (f.getClassName().startsWith("io.github.mbackschat.a12.dm.")) {
                String cls = f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1);
                return " at " + cls + "." + f.getMethodName() + "(" + f.getFileName() + ":" + f.getLineNumber() + ")";
            }
        }
        return "";
    }

    /**
     * The faithful text to preserve for an {@link ReadResult.Opaque}: the rule's <b>raw stored</b> condition
     * (verbatim), falling back to the kernel canonical form only if it's absent. Raw is required because
     * the kernel's formatter can be lossy — it truncates a condition carrying a {@code $} correlation to
     * its first conjunct (KERNEL-FINDINGS) — so preserving canonical would <em>lose</em> the rule on
     * write-back, the opposite of "preserved verbatim".
     */
    /**
     * The grouping backstop, and the one the converter cannot run for itself. A12 records a {@code { … }}
     * group only on a composite — {@code ParseTreeCreator.exitBRACKET_OP} sets {@code geklammert} under an
     * {@code instanceof ICompositeOperation} guard — so a group around a <em>simple</em> operand never
     * reaches the parse tree we convert, while the checker, which walks the ANTLR contexts instead, still
     * sees it and still decides on it. The measured witness: {@code {Today} == Today} is admitted and
     * {@code Today == Today} draws {@code MVK_INVALID_COMPARE_TO_CONST}, so a converted readback published
     * an illegal replacement for a legal rule and reported itself converted (reported by a12-kernel-lean
     * against dmtool 0.13.0 on 2026-09-08, reproduced at {@code e7ce81063}). The three counters above cannot
     * reach this: they count what the source tree carries, and this is the construct it drops.
     *
     * <p>A missing group is only a SUSPICION, never the finding — {@link DslRenderer} drops a group its own
     * precedence rules make redundant, which is faithful — so the kernel arbitrates, and only a rendering it
     * refuses where it accepted the stored text degrades the read. That keeps the two extra parses off every
     * grouped rule and off every ungrouped one, which is all of them but the rare loss.
     */
    private boolean dropsALoadBearingGroup(String ruleFullName, String stored, String rendered) {
        if (ConditionConverter.groupCount(stored) <= ConditionConverter.groupCount(rendered)) {
            return false;
        }
        return diagnose(ruleFullName, stored).ok() && !diagnose(ruleFullName, rendered).ok();
    }

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
    public Map<String, ReadResult> readAll() {
        Set<String> computations = computationFullNames();
        Map<String, ReadResult> out = new LinkedHashMap<>();
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
    public Set<String> ruleFullNames() {
        Set<String> computations = computationFullNames();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (IRule r : elements.getAllRules()) {
            if (!computations.contains(r.getFullName())) {
                names.add(r.getFullName());
            }
        }
        return names;
    }

    /**
     * Which of the three sets {@code IMandatoryInformation} derives. An enum rather than three positional
     * record components: they are all {@code Set<String>}, so a keyed map makes a swap unrepresentable
     * instead of merely unlikely, and the wire name is single-sourced for anything that serializes them.
     */
    public enum MandatorySet {

        /** {@code getMandatory()} — fields the rule collection makes mandatory outright. */
        MANDATORY("mandatory"),

        /** {@code getMandatoryForRootGroup()} — fields mandatory once their root group is present. */
        MANDATORY_FOR_ROOT_GROUP("mandatoryForRootGroup"),

        /** {@code getMandatoryRootGroups()} — root groups the collection makes mandatory. */
        MANDATORY_ROOT_GROUPS("mandatoryRootGroups");

        private final String wireName;

        MandatorySet(String wireName) {
            this.wireName = wireName;
        }

        /** The kernel's own getter name, minus the {@code get} prefix — what a serialized observation spells. */
        public String wireName() {
            return wireName;
        }
    }

    /**
     * The kernel's <b>mandatory-information derivation</b> over this model's rule collection — read-only, no
     * document, no evaluation ({@code IRuleCollectionMetaInformation.getMandatoryInformationFromRules}). The
     * kernel derives mandatoriness from rule CONDITIONS: {@code FieldNotFilled(/A/a)} makes {@code /A/a}
     * mandatory, {@code GroupNotFilled(/A)} makes {@code /A} a mandatory root group, and a starred form makes
     * neither, because it does not hold for every repetition.
     *
     * <p><b>The rule collection is the kernel's own, unfiltered.</b> {@code getAllRules()} includes the
     * generated self-validation of every computation, and {@link #readAll} filters those out for its own
     * purpose. Filtering here would insert an editorial choice of ours into a kernel measurement, so the
     * collection is passed as the kernel keeps it.
     *
     * <p><b>Sorted, because the kernel returns unordered sets.</b> {@code IMandatoryInformation} exposes
     * {@code Set<String>}, which carries no order contract, so a consumer pinning an observation by hash
     * would be pinning a hash-bucket layout. Sorting loses nothing that was ever guaranteed.
     *
     * <p>Problems the derivation reports are dropped: its precondition is that every rule's condition text is
     * valid, which the consistency gate has already established for any model opened through {@link #checkAndOpen}.
     */
    public Map<MandatorySet, Set<String>> mandatoryInformation() {
        var info = new ServiceFactory().getRuleCollectionMetaInformation(mvk)
                .getMandatoryInformationFromRules(elements.getAllRules(), SILENT);
        var out = new java.util.EnumMap<MandatorySet, Set<String>>(MandatorySet.class);
        out.put(MandatorySet.MANDATORY, sorted(info.getMandatory()));
        out.put(MandatorySet.MANDATORY_FOR_ROOT_GROUP, sorted(info.getMandatoryForRootGroup()));
        out.put(MandatorySet.MANDATORY_ROOT_GROUPS, sorted(info.getMandatoryRootGroups()));
        return Collections.unmodifiableMap(out);
    }

    private static Set<String> sorted(Set<String> names) {
        return Collections.unmodifiableSet(new java.util.TreeSet<>(names));
    }

    /**
     * The kernel's <b>dynamic-enumeration derivation</b> over this model's rule collection, with its problems
     * KEPT: the field→allowed-values map the kernel extracts, and every diagnostic it reported while doing so
     * ({@code IRuleCollectionMetaInformation.getDynamicEnumerationStringValues}).
     *
     * <p><b>Why this exists as a separate method rather than a flag on {@link #mandatoryInformation()}.</b>
     * {@code DynamicEnumerationService} — the kernel's dyn-enum checker, whose {@code DynEnumErrorType} declares
     * twelve {@code MVK_DYNENUM_*}/{@code MVK_ERROR_*} classes — is CONSTRUCTED at two sites in the 30.8.1
     * checkout: this facade method, and {@code ServiceFactory.getDynamicEnumerationService(…)}, which has no
     * in-kernel caller at all and exists to hand a per-rule {@code checkDynamicEnumerationRule} to external
     * consumers. Neither site sits on a {@code DocumentModelConsistencyRule}, so no consistency-gate route can
     * report any of those twelve however the model is shaped. (Enumerated here, 2026-09-08, over the checkout's
     * tracked sources with delombok trees excluded, after a12-kernel-lean reported it as one caller: their
     * substantive point holds and the count does not.) The mandatory-information sibling reports
     * PARSER problems through a different mechanism, and a rule may have a perfectly valid condition while
     * still not being a well-formed dynamic enumeration — so the two derivations' diagnostics are neither the
     * same set nor obtainable from one call. Reported to us by a12-kernel-lean, 2026-09-07.
     *
     * <p>The rule collection is the kernel's own and unfiltered, for the same reason
     * {@link #mandatoryInformation()} gives: filtering would insert an editorial choice of ours into a kernel
     * measurement. Diagnostics are attributed to the rule path only where the kernel's own message names one;
     * it usually does not, so the attribution is the model.
     */
    public DynamicEnumerations dynamicEnumerations() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        Map<String, List<String>> values =
                new ServiceFactory().getRuleCollectionMetaInformation(mvk)
                        .getDynamicEnumerationStringValues(new ArrayList<>(elements.getAllRules()),
                                // null lets Diagnostic.fromKernel attribute from the kernel's OWN message,
                                // which names the offending rule when it knows one. Passing a model-level
                                // path would overwrite that with something less specific.
                                problem -> diagnostics.add(KernelDiagnostics.enriched(
                                        KernelNotifications.toCore(problem), null)));
        Map<String, List<String>> sortedValues = new java.util.TreeMap<>();
        values.forEach((field, allowed) -> sortedValues.put(field, List.copyOf(allowed)));
        return new DynamicEnumerations(Collections.unmodifiableMap(sortedValues),
                List.copyOf(diagnostics));
    }

    /**
     * The dyn-enum derivation's verdict on <b>one</b> rule — "is this rule a well-formed dynamic
     * enumeration?", which is the question a consumer actually has, and the only shape in which the answer is
     * attributable.
     *
     * <p><b>Read {@link #dynamicEnumerations()}'s diagnostics with this in mind: the derivation checks EVERY
     * rule and reports one problem for each that is not a dynamic enumeration in form.</b> An ordinary
     * single-leaf rule draws {@code MVK_DYNENUM_INVALID_GATE} and an ordinary conjunction
     * {@code MVK_DYNENUM_INVALID_CALCOP} (measured), so on a model of ordinary rules the collection-wide call
     * returns roughly one diagnostic per rule and none of them is a defect. They mean "not a dynamic
     * enumeration", not "malformed" — which is why this channel is a measurement surface and neither
     * {@code model check} nor {@code rule check} reports it.
     */
    public ValidationResult dynamicEnumerationDiagnostics(String ruleFullName) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        new ServiceFactory().getRuleCollectionMetaInformation(mvk)
                .getDynamicEnumerationStringValues(new ArrayList<>(List.of(rule(ruleFullName))),
                        problem -> diagnostics.add(KernelDiagnostics.enriched(
                                KernelNotifications.toCore(problem), ruleFullName)));
        return ValidationResult.of(diagnostics);
    }

    /**
     * The <b>rule-driven model changes</b> a rule collection specifies — a repeatability a rule narrows below
     * the declared one — reported as the problems that derivation raises rather than as the changes themselves,
     * which nothing here consumes ({@code IRuleCollectionMetaInformation.getChangesAsSpecifiedByRules}).
     *
     * <p><b>This is the other half of the reachability gap, and it is NOT behind
     * {@link #mandatoryInformation()}'s reporter.</b> That method's only problem source is the rule parser
     * ({@code parseBedingungExpand} → {@code MVKParserProblemManager.transferProblems}); the two
     * rule-collection restriction classes — {@code MVK_MAX_GROUP_RESTRICTION_TO_ONE} and its H-model sibling
     * {@code MVK_MAX_LFDNR_RESTRICTION_TO_ONE} — are raised by {@code CheckerFuerKontextEinschraenkung} /
     * {@code CheckerFuerLfdNrEinschraenkung}, which only THIS facade method calls. Measured 2026-09-07: a
     * collecting reporter on {@code getMandatoryInformationFromRules} returns nothing on any of THIRTEEN probed
     * consistency-clean models, each carrying one or two spliced rules, because a condition that parsed for the consistency gate parses here too —
     * so the original "problems already known impossible" comment is right about that method and says nothing
     * about this one. Reported to us as one ~14-class gap by a12-kernel-lean, 2026-09-07; it is two gaps
     * behind two different methods, twelve classes in {@link #dynamicEnumerations()} and these here.
     *
     * <p>The A12 arm ({@code CheckerFuerKontextEinschraenkung}) fires when a rule's condition is a BARE
     * comparison of {@code NumberOfFilledGroups} over a single starred group against a number constant and the
     * implied maximum works out to exactly 1 — {@code > 1}, or {@code >= 2}. A restriction to 2 or more is a
     * legal narrowing and reports nothing. The {@code LFDNR} arm is H-model-only and therefore out of reach
     * from an A12 document model, which is a property of the kernel's own branch, not of this method.
     */
    public List<Diagnostic> ruleDrivenModelChangeProblems() {
        List<Diagnostic> diagnostics = new ArrayList<>();
        new ServiceFactory().getRuleCollectionMetaInformation(mvk)
                .getChangesAsSpecifiedByRules(elements.getAllRules(),
                        problem -> diagnostics.add(KernelDiagnostics.enriched(
                                KernelNotifications.toCore(problem), null)));
        return List.copyOf(diagnostics);
    }

    /**
     * The dyn-enum derivation's two channels. {@code valuesByField} maps a dynamic-enumeration field's full
     * name to the constant values the kernel extracted for it, sorted by field so a consumer pinning the shape
     * is not pinning a hash-bucket layout; {@code diagnostics} is every problem the derivation reported,
     * enriched through the same catalog-join {@code model check} uses.
     */
    public record DynamicEnumerations(Map<String, List<String>> valuesByField,
            List<Diagnostic> diagnostics) {
    }

    /**
     * The error messages stored on a validation rule (locale → text), <b>raw and uninterpolated</b> — read
     * straight from the model, no runtime evaluation, so it is native-capable. Surfaced by {@code rule read}
     * (FEATURE-GAPS G10) so an agent/BA can audit a rule's wording, message length, and locale coverage
     * without reaching for the heavier runtime-eval verbs. The interpolated, runtime message is the
     * separate (and harder) {@code validateFull} path.
     */
    public List<LocalizedText> ruleMessages(String ruleFullName) {
        return recoverMessages(rule(ruleFullName));
    }

    /**
     * A rule's stored <b>severity</b> ({@code ERROR}/{@code WARNING}/{@code INFO}), read straight from the
     * model — native-capable, no runtime eval. Surfaced by {@code rule read} (probe 11) so an agent can
     * confirm a severity it set with {@code rule add --severity} through the sanctioned structured surface,
     * not just {@code condition}/{@code messages}; the kernel default is {@code ERROR}.
     */
    public String ruleSeverity(String ruleFullName) {
        return rule(ruleFullName).getSeverityType().name();
    }

    /**
     * A rule's stored <b>error code</b> ({@code IRule.getErrorCode()}) — the code an agent set with
     * {@code rule add}'s {@code code}, distinct from the rule's full name. Read straight from the model
     * (native-capable). Surfaced by {@code rule read} (slot parity vs {@code IRule}) so the code is
     * confirmable through the structured surface.
     */
    public String ruleErrorCode(String ruleFullName) {
        return rule(ruleFullName).getErrorCode();
    }

    /**
     * A rule's <b>error entity</b> — the field (or group) the error attaches to, by full path
     * ({@code IRule.getErrorEntity().getFullName()}). The rule's iteration anchor; surfaced by
     * {@code rule read} (slot parity vs {@code IRule}) so an agent can confirm where a rule binds without
     * re-deriving it. Empty when the rule carries no error entity.
     */
    public Optional<String> ruleErrorField(String ruleFullName) {
        IEntity entity = rule(ruleFullName).getErrorEntity();
        return entity == null ? Optional.empty() : Optional.ofNullable(entity.getFullName());
    }

    // ── Computations (the calculated-field read path; the ReadComputation peer of read) ───────

    /**
     * The full names of every <b>computation</b> in the model — the discriminator that partitions
     * {@link #ruleFullNames()} into true validation rules and computation rules. A computation is stored
     * as a rule (so it appears in {@code getAllRules()} too), but its "condition" is the kernel-generated
     * self-validation of a calculated field — semantically a computation, read via
     * {@link #readComputation}, not {@link #read}.
     */
    public Set<String> computationFullNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (ICalculationRule cr : elements.getAllCalculations()) {
            names.add(cr.getRule().getFullName());
        }
        return names;
    }

    /**
     * Every computation full name → its calculated field's absolute path, in model order. This reads
     * {@link ICalculationRule#getCalculatedField()} directly from the existing computation index: it does
     * not parse conditions, extract/convert computation bodies, or expand the model again. Consequently an
     * opaque {@link ReadComputation} remains present with its authoritative target.
     */
    public Map<String, String> computationTargetFields() {
        Map<String, String> targets = new LinkedHashMap<>();
        calcIndex().forEach((name, computation) ->
                targets.put(name, computation.getCalculatedField().getFullName()));
        return Collections.unmodifiableMap(targets);
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

    /** A12's owner-level suppression metadata, independent of formula AST conversion. */
    public List<String> computationErrorCodesToSuppress(String computationFullName) {
        return errorCodesToSuppress(calculationRule(computationFullName));
    }

    /** Look up a computation's {@code ICalculationRule} by full name (read-only — the {@link #rule} peer).
     *  Served from the {@link #calcIndex} so repeated computation reads stay {@code O(C)}, not {@code O(C²)}. */
    private ICalculationRule calculationRule(String computationFullName) {
        ICalculationRule cr = calcIndex().get(computationFullName);
        if (cr == null) {
            throw PrecheckException.notFound(RkCode.NO_SUCH_COMPUTATION, computationFullName);
        }
        return cr;
    }

    /** The name→computation index over every {@code ICalculationRule} in the model, built once — the
     *  {@link #ruleIndex} peer. First occurrence wins, matching the old linear scan. */
    private Map<String, ICalculationRule> calcIndex() {
        if (calcIndex == null) {
            Map<String, ICalculationRule> index = new LinkedHashMap<>();
            for (ICalculationRule cr : elements.getAllCalculations()) {
                index.putIfAbsent(cr.getRule().getFullName(), cr);
            }
            calcIndex = index;
        }
        return calcIndex;
    }

    /**
     * ReadResult every computation in the model, keyed by full name (insertion-ordered) — the computation
     * peer of {@link #readAll()}; never throws on an unmodeled computation (those become Opaque).
     */
    public Map<String, ReadComputation> readAllComputations() {
        Map<String, ReadComputation> out = new LinkedHashMap<>();
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
            List<String> errorCodesToSuppress = errorCodesToSuppress(cr);
            Optional<ICalculationInfo> info = calcService().extractCalculationInfo(cr, SILENT);
            if (info.isEmpty()) {
                return new ReadComputation.Opaque(verbatim, "the kernel could not extract calculation info");
            }
            ConditionConverter converter = new ConditionConverter(model);
            int categoryRefs = sumOverSlots(info.get(), converter::countCategoryRefs);
            int wildcardLevels = sumOverSlots(info.get(), converter::countWildcardLevels);
            Computation computation =
                    converter.convertComputation(info.get(), recoverMessages(cr.getRule()), errorCodesToSuppress);
            // backstops: a dropped [field -> Cat] projection or star → Opaque, never a silently different operation
            String rendered = renderedSlots(computation);
            converter.verifyCategoryPreserved(rendered, categoryRefs);
            converter.verifyWildcardPreserved(rendered, wildcardLevels);
            // …and the grouping backstop {@link #dropsALoadBearingGroup} states, at the sibling site the sweep
            // for it found: a computation's precondition is an ordinary condition, so the kernel drops its
            // group on a simple operand exactly as it does for a rule, and `computation read` published the
            // unbraced slot as converted. This site takes the suspicion WITHOUT the kernel arbitration the
            // rule path gets, because no arbiter is available here: the stored text the kernel hands back for
            // a computation is its joined form, which is itself lossy (it truncates at a `$` correlation), so
            // re-parsing it would compare two texts that are not the same candidate. The cost of arbitrating
            // conservatively is that a precedence-redundant group degrades a computation to verbatim rather
            // than typed; the corpus read-coverage suites are the measurement that this does not happen on a
            // real model, and preserving the authored text is never the wrong answer, only the weaker one.
            if (ConditionConverter.groupCount(verbatim) > ConditionConverter.groupCount(rendered)) {
                return new ReadComputation.Opaque(verbatim, "F10 converter would silently drop a `{ … }` group "
                        + "the kernel reads as decisive: the stored computation carries "
                        + ConditionConverter.groupCount(verbatim) + " group(s) and conversion rendered "
                        + ConditionConverter.groupCount(rendered) + " — so the computation is preserved "
                        + "verbatim [kernel node: BRACKET_OP, recorded only on ICompositeOperation]");
            }
            return new ReadComputation.Converted(computation);
        } catch (UnsupportedConstructException unconverted) {
            return new ReadComputation.Opaque(verbatim, unconverted.getMessage());
        // No refusal arm here either, for the reason {@link #read} states and on the same measurement: the
        // kernel's calculation-info extraction is this path's first answer, and it returned empty for the
        // same corruptions before any rulekit factory ran.
        } catch (RuntimeException unexpected) {
            return new ReadComputation.Opaque(verbatim, opaqueFailureReason(unexpected));
        }
    }

    /** {@code count} summed over a computation's source AST — its common precondition plus every
     *  alternative's precondition and value expression. */
    private static int sumOverSlots(ICalculationInfo info, ToIntFunction<IParseTreeEntity> count) {
        int sum = info.getCommonPrecondition().map(count::applyAsInt).orElse(0);
        for (var partial : info.getPartialCalculations()) {
            if (partial.getLeft() != null) {
                sum += count.applyAsInt(partial.getLeft());
            }
            sum += count.applyAsInt(partial.getRight());
        }
        return sum;
    }

    /** Every rendered slot of a converted computation, concatenated — the text the category backstop counts. */
    private static String renderedSlots(Computation computation) {
        var draft = computation.draft();
        StringBuilder out = new StringBuilder();
        if (draft.commonPrecondition() != null) {
            out.append(draft.commonPrecondition()).append('\n');
        }
        for (var alternative : draft.alternatives()) {
            if (alternative.precondition() != null) {
                out.append(alternative.precondition()).append('\n');
            }
            out.append(alternative.operation()).append('\n');
        }
        return out.toString();
    }

    private static List<String> errorCodesToSuppress(ICalculationRule calculation) {
        if (!(calculation.getRule() instanceof IRuleFromComputation source)) {
            return List.of();
        }
        return List.copyOf(source.getOriginalComputation().getErrorCodesToSuppress());
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
        List<String> lossiness = new java.util.ArrayList<>();
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
                List.of());
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
            alternative.toleranceRangeOp().ifPresent(
                    tolerance -> text.append(" [toleranceRangeOp=").append(tolerance.wire()).append(']'));
        }
        if (!computation.errorCodesToSuppress().isEmpty()) {
            text.append("\nerrorCodesToSuppress=").append(computation.errorCodesToSuppress());
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
        return toEvalResult(runtime.rt().validateFull(runtime.document(), config(locale)));
    }

    /**
     * The <b>form-engine validation flow</b> over a document instance: {@code compute()} →
     * {@code applyTo(doc)} → {@code validateFull(doc)}. The kernel keeps validation and computation separate
     * (KERNEL-FINDINGS "validateFull does NOT run computations"); this applies the computed values back —
     * changes set, errored/cleared emptied, the kernel's own {@code applyTo} — <em>before</em> validating, so a
     * required-but-computed field is filled by its computation before the required check runs (no false
     * {@code mandatoryField}). The kernel's compose path (the differential oracle for {@code model eval}), the
     * peer of the interpreter's {@link DmInterpreter#applyComputations} + {@code validate} that dmtool runs.
     * Uses the modern {@code applyTo(DocumentV2)}: {@link DocumentV2} is immutable, so {@code applyTo} returns a
     * NEW document (the applied one) rather than mutating in place, and that returned instance is validated.
     */
    public EvalResult evaluateApplied(Map<String, Object> fields, Locale locale) {
        DocRuntime runtime = prepare(fields);
        DocumentProcessingConfig config = config(locale);
        DocumentV2 applied = runtime.rt().compute(runtime.document(), config).applyTo(runtime.document());
        return toEvalResult(runtime.rt().validateFull(applied, config));
    }

    /** Map a kernel {@link IDocumentValidationResult} to the JDK-only {@link EvalResult} (shared by both paths). */
    private static EvalResult toEvalResult(IDocumentValidationResult result) {
        List<EvalMessage> messages = new ArrayList<>();
        for (IMessage m : result.getMessages()) {
            messages.add(new EvalMessage(m.getErrorCode(), m.getRulePath(),
                    fieldPointer(m.getErrorFieldPointer()), m.getSeverity().name(),
                    m.getMessageType().name(), m.getErrorText(),
                    m.getReferencedFieldsPointers().stream().map(KernelAdapter::fieldPointer).toList(),
                    m.getRefOmissionErrorResponsiblePointers().stream().map(KernelAdapter::fieldPointer).toList()));
        }
        return new EvalResult(messages);
    }

    /** Boundary conversion (N8): a kernel field pointer → JDK-only {@link FieldPointer} (path + indices). */
    private static FieldPointer fieldPointer(PartiallyKnownDocumentMultiPointer p) {
        return new FieldPointer(p.fullName(), p.repetitionIndexes());
    }

    /** The kernel's sentinel {@code rulePath} for a formal check (vs. an author rule, whose path starts with {@code /}). */
    private static final String FORMAL_CHECK = "formalePruefung";

    /**
     * Test <b>one rule</b> against a document instance (FEATURE-GAPS) — the scoped, suppression-aware
     * polarity check over {@link #evaluate}. Returns a three-way {@link RuleTestOutcome.Verdict}: the rule
     * <b>FIRED</b> (a message carries its path), it <b>PASSED</b> (evaluated, no message), or it was
     * <b>SUPPRESSED</b> — never evaluated because a field it references is formally invalid (the kernel skips
     * a rule whose operand is "unknown"; KERNEL-FINDINGS, {@code FormalErrorLawsTest}). The distinction
     * matters: reporting a suppressed rule as a pass would mislead, so when not fired we check whether any of
     * the rule's {@linkplain #referencedFields referenced fields} drew a formal-check error and, if so, name them.
     */
    public RuleTestOutcome testRule(String ruleFullName, Map<String, Object> fields, Locale locale) {
        return testRule(ruleFullName, fields, locale, false);
    }

    /**
     * {@link #testRule(String, Map, Locale)} with an explicit choice of validation flow: {@code applyComputations}
     * true runs the form-engine {@link #evaluateApplied compute → apply → validate} (the {@code rule eval}
     * default — so a rule over a computed field sees the computed value); false runs the bare {@link #evaluate
     * validateFull} (the raw differential's flow). The verdict logic is identical; only the message source differs.
     */
    public RuleTestOutcome testRule(String ruleFullName, Map<String, Object> fields, Locale locale, boolean applyComputations) {
        Set<String> referenced = referencedFields(ruleFullName);
        List<EvalMessage> messages = (applyComputations ? evaluateApplied(fields, locale) : evaluate(fields, locale)).messages();
        Optional<EvalMessage> firedMessage = messages.stream()
                .filter(m -> m.rulePath().filter(ruleFullName::equals).isPresent()).findFirst();
        if (firedMessage.isPresent()) {
            return new RuleTestOutcome(ruleFullName, RuleTestOutcome.Verdict.FIRED, List.of(), firedMessage);
        }
        List<RuleTestOutcome.Suppression> suppressions = new ArrayList<>();
        for (EvalMessage m : messages) {
            if (isFormalCheck(m)) {
                for (String entity : referenced) {
                    // CONTAINMENT, not equality: `referenced` holds what the condition NAMES, which for a group
                    // operand is the GROUP, while a formal-check message necessarily names the FIELD whose stored
                    // value would not parse. Those strings can never be equal, so an exact scan found nothing and
                    // the verdict fell through to PASSED — for a rule the kernel never evaluated, which is the one
                    // outcome this method exists to prevent (CG14).
                    if (EntityPaths.isInside(m.field().path(), entity)) {
                        suppressions.add(new RuleTestOutcome.Suppression(m.field().path(), m.errorCode()));
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
     * Run the kernel's runtime <b>computation</b> over a document instance and report each computed field's
     * outcome — the peer of {@link #evaluate} (does-this-field-COMPUTE, against the same document model and
     * the same nested {@code fields} representation). Read-only: {@code compute} returns a <em>result</em>
     * (the kernel never mutates the input; applying it back is a separate, opt-in step), so this reports
     * what each computed field <em>becomes</em> — a value, cleared (computed nothing — e.g. an empty
     * operand, see {@code DateRangeComputationLawsTest}), or an error. These are the three <b>change-projection</b>
     * channels of the fuller five-channel result (KERNEL-FINDINGS §11 five-channel supplement). Powers the {@code compute-doc} verb
     * and lets a computation judge verify computation-equivalence by EXECUTION.
     */
    public ComputeResult compute(Map<String, Object> fields, Locale locale) {
        DocRuntime runtime = prepare(fields);
        IDocumentComputationResult result = runtime.rt().compute(runtime.document(), config(locale));
        List<ComputedField> computed = new ArrayList<>();
        // Carry each instance's repetitions through: the kernel exposes the row identity on every channel, and
        // dropping it here is what made a repeated computed field's rows indistinguishable downstream.
        result.getComputedFieldInstancesWithChanges()
                .forEach(c -> computed.add(
                        ComputedField.of(c.getPath(), "value", c.getStringValue(), c.getRepetitions())));
        result.getComputedFieldInstancesWithErrors()
                .forEach(c -> computed.add(
                        ComputedField.of(c.getPath(), "error", c.getStringValue(), c.getRepetitions())));
        result.getClearedFieldInstances()
                .forEach(c -> computed.add(
                        ComputedField.of(c.getPath(), "cleared", Optional.empty(), c.getRepetitions())));
        return new ComputeResult(computed);
    }

    /** The kernel runtime service + the built (immutable {@link DocumentV2}) instance, shared by {@link #evaluate}/{@link #compute}. */
    private record DocRuntime(IDocumentRtService rt, DocumentV2 document) {
    }

    /** The modern per-call processing config: just the locale (no custom factories, real current date) — the
     *  behavioral twin of the retired {@code validateFull(IDocument, Locale)} form, whose builder defaults are
     *  null factories + empty additional-info + no clock override. */
    private static DocumentProcessingConfig config(Locale locale) {
        return DocumentProcessingConfig.builder(locale).build();
    }

    /** Build the runtime service + the document instance from the nested {@code fields} tree (model-aware walk). */
    private DocRuntime prepare(Map<String, Object> fields) {
        ModelResolver resolver =
                model.resolver() != null ? model.resolver() : ReferenceResolvers.selfContained();
        IDocumentModel expanded = loadExpanded(model.toJson(), resolver);
        // The structural index (group paths / repeatability / field-ness) is kernel-free (decoupling Stage 2);
        // it re-expands the authored JSON and is parity-locked to the kernel's expansion (ModelIndexParityTest).
        ModelIndex index = ModelIndex.of(model.toJson(), resolver);
        IDocumentModelResolver modelResolver = id -> expanded;
        DocumentServiceFactory dsf = new DocumentServiceFactory(modelResolver);
        IDocumentRtService rt = new DocumentRtServiceFactory(modelResolver).createDocumentRtService(dynamicConfig());
        IDocumentFactory df = dsf.createDocumentFactory();

        IDocument document = df.createDocument(modelId(model.toJson()));
        Set<String> groupPaths = index.groupPaths().collect(Collectors.toCollection(HashSet::new));
        buildInstances(fields, "", new int[0], index, groupPaths, df, document);
        // Convert the built V1 instance to the immutable DocumentV2 the modern rt-service consumes (once, per prepare).
        return new DocRuntime(rt, dsf.createDocumentV1V2Converter().ofDocumentV1(document));
    }

    /**
     * Deserialize → expand(resolver) → the consistency gate, returning the expanded kernel model the runtime
     * build needs. This is the backend's own copy of the old {@code ModelLoader.load} (which is now the
     * kernel-free {@code loadAndCheck} gate): the runtime-eval path needs the expanded {@code IDocumentModel}
     * object, which is a kernel type the gate no longer hands back. Keeps the {@code ERROR}-throws behaviour
     * exactly (behaviour-neutral for dmtool).
     */
    private static IDocumentModel loadExpanded(String modelJson, ModelResolver resolver) {
        return KernelModelPreparation.expand(modelJson, resolver).requireConsistent();
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
            public Optional<ILabelProvider> getLabelProvider() { return Optional.of(LABEL_OR_NAME); }
            public Optional<IDocumentModelIdProvider> getModelIdProvider() { return Optional.empty(); }
        };
    }

    /**
     * The {@code $field$} display-name resolver: a field's model label for the locale when defined (and
     * non-blank), else the field NAME. The kernel REQUIRES the caller to supply this (the javadoc on
     * {@link ILabelProvider} is explicit); with none configured the kernel falls back to a path+index
     * debug string ({@code /Order/Quantity/1.1}) — a leak in the same class as the pointer {@code toString}
     * we already render cleanly. Wiring it makes the kernel match the interpreter's
     * {@code ModelDef.labelOf} (both yield the name for our label-free corpus). KERNEL-FINDINGS §3.
     */
    private static final ILabelProvider LABEL_OR_NAME = (field, locale) -> {
        String label = field.getLabel().get(locale);
        return Optional.of(label != null && !label.isBlank() ? label : field.getName());
    };

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

    /** Look up a tool-API {@code IRule} among the model's existing rules by full name (read-only — N8). Served
     *  from the {@link #ruleIndex} so a report's per-rule catalog reads stay {@code O(R)}, not {@code O(R²)}. */
    private IRule rule(String fullName) {
        IRule r = ruleIndex().get(fullName);
        if (r == null) {
            throw new IllegalArgumentException("no rule in model at: " + fullName);
        }
        return r;
    }

    /** The name→rule index over every rule in the model, built once — replaces the per-lookup linear scan
     *  over {@code getAllRules()} that made a report's rule catalog + usage {@code O(R²)}. First occurrence
     *  wins, matching the old {@code findFirst()} scan. */
    private Map<String, IRule> ruleIndex() {
        if (ruleIndex == null) {
            Map<String, IRule> index = new LinkedHashMap<>();
            for (IRule r : elements.getAllRules()) {
                index.putIfAbsent(r.getFullName(), r);
            }
            ruleIndex = index;
        }
        return ruleIndex;
    }
}
