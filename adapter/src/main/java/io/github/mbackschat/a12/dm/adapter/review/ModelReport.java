package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ReadComputation;
import io.github.mbackschat.a12.dm.adapter.ReadResult;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.context.ComputationExplanation;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.context.RuleExplanation;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;

/**
 * MODEL-REVIEW — the comprehension engine (a library primitive, MODEL-REVIEW-SPEC §4), the peer of
 * {@link ModelDiff}. Assembles a single model's comprehension view by <b>reusing</b> the existing reads —
 * no reinvention:
 * <ul>
 *   <li><b>identity</b> (J1, from {@link ModelDocument});</li>
 *   <li>the <b>structure</b> outline (J2, via {@link ModelContext#describe()} → {@link ModelCard});</li>
 *   <li>the field→referrers <b>usage</b> map + the unreferenced set (J4, via {@link KernelAdapter#whereUsed});</li>
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

    /** field path → the rule/computation full names that reference it; {@code unreferenced} = the fields with none. */
    public record Usage(Map<String, List<String>> referrers, List<String> unreferenced) {}

    /** Why a rule fires (constant across rules — the trap a reviewer must hold). */
    public record Polarity(String firesOn, String note) {}

    /** A localized stored message (the modeller's own NL — the verbatim half of the Hybrid gloss). */
    public record Message(String locale, String text) {}

    /** One rule, glossed for comprehension. {@code gloss} is null when {@code explained} is false (Opaque). */
    public record RuleEntry(String rule, String scope, boolean explained, String gloss, String canonicalText,
            Polarity polarity, String severity, String code, String errorField, List<Message> messages) {}

    /** One computation, glossed for comprehension. {@code gloss}/{@code computedField} null when Opaque. */
    public record ComputationEntry(String computation, String scope, boolean explained, String computedField,
            String gloss, String canonicalText, List<Message> messages) {}

    /** Assemble the comprehension view of {@code modelJson} (kernel-loadable). */
    public static ModelReport of(String modelJson) {
        BoundModel model = BoundModel.ofJson(modelJson);
        ModelDocument doc = ModelDocument.read(modelJson);
        Identity identity = new Identity(doc.id(), doc.modelType(), doc.modelVersion(), doc.locales());
        ModelContext context = new ModelContext(model);
        ModelCard structure = context.describe();
        KernelAdapter adapter = KernelAdapter.of(model);
        return new ModelReport(identity, structure, usage(adapter, structure),
                ruleCatalog(adapter, context), computationCatalog(adapter, context));
    }

    /** Reuses the same {@code getDirectlyReferencedEntities} primitive as {@code where-used} / {@code model usage}. */
    private static Usage usage(KernelAdapter adapter, ModelCard card) {
        Map<String, List<String>> referrers = new LinkedHashMap<>();
        List<String> unreferenced = new ArrayList<>();
        for (ModelCard.Field field : card.fields()) {
            String path = field.path();
            TreeSet<String> refs = new TreeSet<>(adapter.whereUsed(path));
            refs.addAll(adapter.whereUsedInComputations(path));
            referrers.put(path, List.copyOf(refs));
            if (refs.isEmpty()) {
                unreferenced.add(path);
            }
        }
        return new Usage(referrers, List.copyOf(unreferenced));
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
            out.add(new RuleEntry(name, scopeOf(name), explained, gloss, canonical, VIOLATION_POLARITY,
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
                out.add(new ComputationEntry(name, scopeOf(name), true, ex.computedField(), ex.gloss(), null,
                        computationMessages(ex.messages())));
            } else {
                out.add(new ComputationEntry(name, scopeOf(name), false, null, null,
                        ((ReadComputation.Opaque) read).canonicalText(), List.of()));
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
