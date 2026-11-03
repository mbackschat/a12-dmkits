package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/** Locks one index expansion plus one checked adapter preparation per side of a model diff. */
class ModelDiffLoadingTest {

    @Test
    void eachSideUsesOneSharedCheckedPreparation() {
        String included = DocumentModelDraft.named("tariff").locale("en_US")
                .rootGroup("Tariff", group -> group.field("Rate", FieldKind.NUMBER))
                .build()
                .toJson();
        ModelResolver editingResolver = id -> "tariff".equals(id) ? included : null;
        EditSession session = EditSession.read(DocumentModelDraft.named("quote").locale("en_US")
                .rootGroup("Quote", group -> group.field("Reference", FieldKind.STRING))
                .build()
                .toJson(), editingResolver);
        session.mount("/Quote", "CurrentTariff", "tariff");
        var resolver = new CountingResolver(included);

        ModelDiff.between(session.toJson(), session.toJson(), resolver);

        assertEquals(4, resolver.calls(),
                "each side needs one core index expansion plus one checked kernel preparation, never a preflight gate too");
    }

    private static final class CountingResolver implements ModelResolver {
        private final String included;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingResolver(String included) {
            this.included = included;
        }

        @Override
        public String resolve(String modelId) {
            calls.incrementAndGet();
            return "tariff".equals(modelId) ? included : null;
        }

        private int calls() {
            return calls.get();
        }
    }
}
