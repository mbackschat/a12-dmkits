package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.context.ComputationExplanation;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.context.RuleExplanation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * MODEL-REVIEW — the comprehension engine (a library primitive, MODEL-REVIEW-SPEC §4), the peer of
 * {@link ModelDiff}. Assembles a single model's comprehension view by <b>reusing</b> the existing reads —
 * no reinvention:
 * <ul>
 *   <li><b>identity</b> (J1, from {@link ModelDocument});</li>
 *   <li>the <b>structure</b> outline (J2, via {@link ModelContext#describe()} → {@link ModelCard});</li>
 *   <li>the unreferenced (dead-field) set (J4, via the whole-model reference inverse
 *       {@link KernelAdapter#referrersByEntity} — built once, {@code O(R+C+F)}, not the per-field
 *       {@code whereUsed} recomputation that made a report {@code O(F·(R+C))} and stall on huge models);</li>
 *   <li>the <b>glossed rules/computations catalog</b> (J3, the centerpiece) — every rule/computation in plain
 *       language: the tool-computed {@code gloss} ({@link ModelContext#explain}) + the fixed {@code polarity}
 *       (a rule fires on the VIOLATION) + the verbatim stored {@code messages} (the modeller's own text), so a
 *       reviewer sees what the condition DOES next to what it CLAIMS (the Hybrid-gloss drift signal).</li>
 * </ul>
 *
 * <p>Tool-computed and deterministic (the trust principle); tolerant of conditions rulekit reads as
 * {@code Opaque} (reported {@code explained:false} with the kernel-canonical text, never dropped).
 */
public record ModelReport(Identity identity, ModelCard structure, Usage usage,
        List<RuleEntry> rules, List<ComputationEntry> computations) {

    /** The fixed polarity fact — an A12 rule condition is TRUE on the violation, not the requirement. */
    public static final Polarity VIOLATION_POLARITY = new Polarity("violation",
            "An A12 rule condition is TRUE when the rule is VIOLATED — it models the state to reject, not the "
                    + "requirement. Read it as the forbidden case; the message states the requirement positively.");

    /** The model's header facts — its identity card (J1). */
    public record Identity(String id, String modelType, String modelVersion, List<String> locales) {}

    /** The fields no rule or computation reads — the dead-field audit headline. (The full field→referrers map is `model usage`.) */
    public record Usage(List<String> unreferenced) {}

    /** Why a rule fires — constant across ALL rules, so the report carries it ONCE at the top (not per rule). */
    public record Polarity(String firesOn, String note) {}

    /** A localized stored message (the modeller's own NL — the verbatim half of the Hybrid gloss). */
    public record Message(String locale, String text) {}

    /** One rule, glossed for comprehension. {@code gloss} is null when {@code explained} is false (Opaque). Polarity is
     *  the model-level constant {@link #VIOLATION_POLARITY}, not repeated here. */
    public record RuleEntry(String rule, String scope, boolean explained, String gloss, String canonicalText,
            String severity, String code, String errorField, List<Message> messages) {}

    /**
     * One computation, glossed for comprehension. {@code gloss}/{@code computedField} null when Opaque. The
     * {@code commonPrecondition} (nullable — the gate over the whole computation) and the {@code alternatives}
     * (each a {@code (precondition?, operation, toleranceRangeOp?)} — the preconditioned or
     * single-unconditional formulae) mirror
     * {@code computation read}'s shape exactly, so the two surfaces agree; both are absent/empty for an Opaque
     * computation.
     */
    public record ComputationEntry(String computation, String scope, boolean explained, String computedField,
            String gloss, String canonicalText, String commonPrecondition,
            List<DraftComputation.Alternative> alternatives, List<Message> messages) {}

    /** Assemble the comprehension view of {@code modelJson} (a self-contained, kernel-loadable model). */
    public static ModelReport of(String modelJson) {
        return of(modelJson, BoundModel.ofJson(modelJson));
    }

    /**
     * Assemble the comprehension view, resolving {@code include}s/imports through a <b>kernel-free</b>
     * {@link io.github.mbackschat.a12.dm.core.ModelResolver} — the abstraction the rest of the dmkits surface
     * uses (a {@code id → DM-JSON} SAM), so a consumer need not import {@code com.mgmtp.*}. {@code null} = none.
     */
    public static ModelReport of(String modelJson, io.github.mbackschat.a12.dm.core.ModelResolver resolver) {
        return of(modelJson, resolver == null ? BoundModel.ofJson(modelJson) : BoundModel.ofJson(modelJson, resolver));
    }

    /** The shared assembly over an already-bound model — the public overloads differ only in how the
     *  {@link BoundModel} was resolved. */
    private static ModelReport of(String modelJson, BoundModel model) {
        return assemble(modelJson, model, KernelAdapter.of(model));
    }

    /**
     * The assembly over a caller-provided {@link KernelAdapter} — a package-private seam so the Set RP perf
     * lock can build the adapter itself and read its {@link KernelAdapter#referenceParseCount()} after
     * assembly, pinning that one report parses each rule/computation at most once (never once per field).
     */
    static ModelReport assemble(String modelJson, BoundModel model, KernelAdapter adapter) {
        ModelDocument doc = ModelDocument.read(modelJson);
        Identity identity = new Identity(doc.id(), doc.modelType(), doc.modelVersion(), doc.locales());
        ModelContext context = new ModelContext(model);
        ModelCard structure = context.describe();
        return new ModelReport(identity, structure, usage(adapter, structure),
                ruleCatalog(adapter, context), computationCatalog(adapter, context));
    }

    /** The dead-field set — served from the whole-model reference inverse ({@link KernelAdapter#referrersByEntity},
     *  built once), so it is {@code O(R+C+F)} instead of re-parsing every rule/computation per field. A field is
     *  unreferenced iff no rule or computation references it, i.e. iff it is not a key in the inverse. */
    private static Usage usage(KernelAdapter adapter, ModelCard card) {
        Map<String, Set<String>> referrers = adapter.referrersByEntity();
        List<String> unreferenced = new ArrayList<>();
        for (ModelCard.Field field : card.fields()) {
            if (!referrers.containsKey(field.path())) {
                unreferenced.add(field.path());
            }
        }
        return new Usage(List.copyOf(unreferenced));
    }

    private static List<RuleEntry> ruleCatalog(KernelAdapter adapter, ModelContext context) {
        List<RuleEntry> out = new ArrayList<>();
        for (String name : adapter.ruleFullNames()) {
            ReadResult read = adapter.read(name);
            String gloss = null;
            String canonical;
            boolean explained = read instanceof ReadResult.Converted;
            if (read instanceof ReadResult.Converted converted) {
                RuleExplanation ex = context.explain(converted.condition());
                gloss = ex.gloss();
                canonical = ex.text();
            } else {
                canonical = ((ReadResult.Opaque) read).canonicalText();
            }
            out.add(new RuleEntry(name, scopeOf(name), explained, gloss, canonical,
                    adapter.ruleSeverity(name), adapter.ruleErrorCode(name),
                    adapter.ruleErrorField(name).orElse(null), messages(adapter.ruleMessages(name))));
        }
        return out;
    }

    private static List<ComputationEntry> computationCatalog(KernelAdapter adapter, ModelContext context) {
        List<ComputationEntry> out = new ArrayList<>();
        for (String name : adapter.computationFullNames()) {
            ReadComputation read = adapter.readComputation(name);
            if (read instanceof ReadComputation.Converted converted) {
                ComputationExplanation ex = context.explainComputation(converted.computation());
                // the (precondition?, operation) formulae — the SAME DraftComputation `computation read`
                // emits, so the report and the per-computation read agree on when a field is filled
                DraftComputation draft = converted.computation().draft();
                out.add(new ComputationEntry(name, scopeOf(name), true, ex.computedField(), ex.gloss(), null,
                        draft.commonPrecondition(), draft.alternatives(), computationMessages(ex.messages())));
            } else {
                out.add(new ComputationEntry(name, scopeOf(name), false, null, null,
                        ((ReadComputation.Opaque) read).canonicalText(), null, List.of(), List.of()));
            }
        }
        return out;
    }

    /** The element's scope group — its containing group (the path with the trailing element name removed). */
    private static String scopeOf(String fullName) {
        int slash = fullName.lastIndexOf('/');
        return slash > 0 ? fullName.substring(0, slash) : fullName;
    }

    private static List<Message> messages(List<LocalizedText> stored) {
        List<Message> out = new ArrayList<>();
        for (LocalizedText t : stored) {
            out.add(new Message(t.locale(), t.text()));
        }
        return out;
    }

    private static List<Message> computationMessages(List<ComputationExplanation.Message> stored) {
        List<Message> out = new ArrayList<>();
        for (ComputationExplanation.Message t : stored) {
            out.add(new Message(t.locale(), t.text()));
        }
        return out;
    }
}
