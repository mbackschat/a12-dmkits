package io.github.mbackschat.a12.dm.adapter.review;

import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/** One parsed/bound side of a model diff, shared by all element-family projectors. */
record ModelDiffState(ModelDocument document, ModelDocument.AuthoredState authored,
        BoundModel model, KernelAdapter adapter, Map<String, Set<String>> referrers, Map<String, String> changeNotes) {

    static ModelDiffState load(String json, ModelResolver resolver) {
        ModelResolver effective = resolver == null ? ReferenceResolvers.selfContained() : resolver;
        var model = BoundModel.ofJsonLenient(json, effective);
        var document = ModelDocument.read(json);
        var adapter = KernelAdapter.checkAndOpen(model);
        var authored = document.authoredState();
        return new ModelDiffState(document, authored, model, adapter, adapter.referrersByEntity(),
                ChangeNotes.of(document, authored));
    }
}
