package io.github.mbackschat.a12.dm.adapter.review;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/** One parsed/bound side of a model diff, shared by all element-family projectors. */
record ModelDiffState(ModelDocument document, BoundModel model, KernelAdapter adapter) {

    static ModelDiffState of(BoundModel model) {
        return new ModelDiffState(ModelDocument.read(model.toJson()), model, KernelAdapter.of(model));
    }
}
