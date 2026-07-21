package io.github.mbackschat.a12.dm.adapter.review;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/** One parsed/bound side of a model diff, shared by all element-family projectors. */
record ModelDiffState(ModelDocument document, ModelDocument.AuthoredState authored,
        BoundModel model, KernelAdapter adapter) {

    static ModelDiffState of(BoundModel model) {
        var document = ModelDocument.read(model.toJson());
        return new ModelDiffState(document, document.authoredState(), model, KernelAdapter.of(model));
    }
}
