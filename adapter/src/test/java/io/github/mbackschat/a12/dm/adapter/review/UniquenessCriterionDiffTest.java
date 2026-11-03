package io.github.mbackschat.a12.dm.adapter.review;

import static io.github.mbackschat.a12.dm.adapter.UniquenessCriteriaJson.criterion;
import static io.github.mbackschat.a12.dm.adapter.UniquenessCriteriaJson.withCriteria;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * A removed field that a document uniqueness criterion of the base names is a removed referenced element, as for a
 * rule reader (KERNEL-FINDINGS KF671). The head drops the criterion too, since A12 refuses a criterion over a missing
 * field, so only the base can show the referrer. Own domain (insurance policies).
 */
class UniquenessCriterionDiffTest {

    @Test
    void removingAFieldACriterionNamedIsHighImpactAndAnUnnamedOneIsNot() {
        String base = withCriteria(DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required())
                        .field("Holder", FieldKind.STRING)
                        .field("Product", FieldKind.STRING))
                .build().toJson(), criterion("PolicyKey", "Taken", "/Policy/PolicyNo"));
        String head = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p.field("Product", FieldKind.STRING))
                .build().toJson();

        ModelDiff diff = ModelDiff.between(base, head);
        Map<String, String> actual = new LinkedHashMap<>();
        for (String removed : new String[] {"/Policy/PolicyNo", "/Policy/Holder"}) {
            ModelDiff.ElementChange change = diff.changes().stream().filter(c -> c.id().equals(removed)).findFirst()
                    .orElseThrow(() -> new AssertionError("no change for " + removed + " in " + diff.changes()));
            actual.put(removed, change.risk() + " " + change.reasons().contains("REFERENCED_ELEMENT_REMOVED"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/Policy/PolicyNo", "HIGH true");
        expected.put("/Policy/Holder", "MEDIUM false");
        assertEquals(expected, actual);
    }
}
