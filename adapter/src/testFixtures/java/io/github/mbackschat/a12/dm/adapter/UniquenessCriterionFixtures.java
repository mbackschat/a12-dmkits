package io.github.mbackschat.a12.dm.adapter;

import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion;

/** Shorthand for the tests that put document uniqueness criteria on a model (KERNEL-FINDINGS KF671). */
public final class UniquenessCriterionFixtures {

    private UniquenessCriterionFixtures() {
    }

    /** One criterion over [fields], whose only error text is [text] for {@code en_US}. */
    public static UniquenessCriterion criterion(String name, String text, String... fields) {
        return new UniquenessCriterion(name, List.of(fields), Map.of("en_US", text));
    }

    /** [json] with these criteria added after any it already has. */
    public static String withCriteria(String json, UniquenessCriterion... criteria) {
        ModelDocument model = ModelDocument.read(json);
        for (UniquenessCriterion criterion : criteria) {
            model.addUniquenessCriterion(criterion);
        }
        return model.toJson();
    }
}
