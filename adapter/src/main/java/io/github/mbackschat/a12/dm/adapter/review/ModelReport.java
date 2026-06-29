package io.github.mbackschat.a12.dm.adapter.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * MODEL-REVIEW — the comprehension engine (a library primitive, MODEL-REVIEW-SPEC §4), the peer of
 * {@link ModelDiff}. Assembles a single model's comprehension view by <b>reusing</b> the existing reads —
 * no reinvention: <b>identity</b> (J1, from {@link ModelDocument}), the <b>structure</b> outline (J2, via
 * {@link ModelContext#describe()} → {@link ModelCard}), and the field→referrers <b>usage</b> map + the
 * unreferenced set (J4, via {@link KernelAdapter#whereUsed}). The glossed rules/computations catalog (J3) is
 * added by the next slice.
 *
 * <p>Tool-computed and deterministic (the trust principle): every fact is read from the model, never narrated.
 */
public record ModelReport(Identity identity, ModelCard structure, Usage usage) {

    /** The model's header facts — its identity card (J1). */
    public record Identity(String id, String modelType, String modelVersion, List<String> locales) {}

    /** field path → the rule/computation full names that reference it; {@code unreferenced} = the fields with none. */
    public record Usage(Map<String, List<String>> referrers, List<String> unreferenced) {}

    /** Assemble the comprehension view of {@code modelJson} (kernel-loadable). */
    public static ModelReport of(String modelJson) {
        BoundModel model = BoundModel.ofJson(modelJson);
        ModelDocument doc = ModelDocument.read(modelJson);
        Identity identity = new Identity(doc.id(), doc.modelType(), doc.modelVersion(), doc.locales());
        ModelCard structure = new ModelContext(model).describe();
        return new ModelReport(identity, structure, usage(model, structure));
    }

    /** Reuses the same {@code getDirectlyReferencedEntities} primitive as {@code where-used} / {@code model usage}. */
    private static Usage usage(BoundModel model, ModelCard card) {
        KernelAdapter adapter = KernelAdapter.of(model);
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
}
