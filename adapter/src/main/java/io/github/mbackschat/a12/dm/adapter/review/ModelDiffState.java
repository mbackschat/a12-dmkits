package io.github.mbackschat.a12.dm.adapter.review;

import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/** One parsed/bound side of a model diff, shared by all element-family projectors. */
record ModelDiffState(ModelDocument document, ModelDocument.AuthoredState authored,
        BoundModel model, KernelAdapter adapter, Map<String, Set<String>> referrers) {

    static ModelDiffState of(String json, BoundModel model) {
        var document = ModelDocument.read(json);
        var adapter = KernelAdapter.of(model);
        return new ModelDiffState(document, document.authoredState(), model, adapter, adapter.referrersByEntity());
    }
}
