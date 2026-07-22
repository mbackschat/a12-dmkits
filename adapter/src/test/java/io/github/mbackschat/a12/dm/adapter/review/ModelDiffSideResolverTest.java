package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementKind;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/** Each model state binds against its own dependency snapshot. */
class ModelDiffSideResolverTest {

    @Test
    void resolvesBaseAndHeadIndependently() {
        ModelResolver baseResolver = reference -> "pricing-v1".equals(reference) ? provider(reference) : null;
        ModelResolver headResolver = reference -> "pricing-v2".equals(reference) ? provider(reference) : null;

        ModelDiff diff = ModelDiff.between(store("pricing-v1"), store("pricing-v2"),
                baseResolver, headResolver);

        ElementChange include = diff.changes().stream()
                .filter(change -> change.element() == ElementKind.INCLUDE)
                .findFirst().orElseThrow(() -> new AssertionError("missing include retarget: " + diff.changes()));
        assertEquals("pricing-v1", delta(include, "reference").before());
        assertEquals("pricing-v2", delta(include, "reference").after());
    }

    private static String store(String pricingId) {
        return DocumentModelDraft.named("store").locale("en_US")
                .include("pricing", pricingId)
                .rootGroup("Store", store -> {
                    store.field("Sku", FieldKind.STRING);
                    store.mount("Prices", "pricing");
                })
                .build().toJson();
    }

    private static String provider(String id) {
        return DocumentModelDraft.named(id).locale("en_US")
                .rootGroup("Pricing", pricing -> pricing.field("Amount", FieldKind.NUMBER))
                .build().toJson();
    }

    private static ModelDiff.Delta delta(ElementChange change, String aspect) {
        return change.deltas().stream().filter(candidate -> candidate.aspect().equals(aspect)).findFirst()
                .orElseThrow(() -> new AssertionError("missing " + aspect + " delta: " + change.deltas()));
    }
}
