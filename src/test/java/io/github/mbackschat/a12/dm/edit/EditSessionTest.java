package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.DmJson;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * M9 — structure edits (modelkit) and rule edits (rulekit) compose in ONE read → edit → write-back
 * flow. {@link EditSession} is the orchestration layer above the two sibling kits (which cannot see each
 * other): it threads the same DM-JSON through both. The kernel gate runs once at {@link
 * EditSession#validate()} (and again on each rule add, which re-binds the whole model).
 */
class EditSessionTest {

    @Test
    void addsAFieldThenARuleThatReferencesIt() {
        // the rule's error field is the field the structure step just added — so the model is only
        // kernel-valid if both edits composed over the same document.
        String result = EditSession.read(Fixtures.subscriptionJson())
                .addField("/Subscription/Addons", "Discount", FieldKind.NUMBER)
                .addRule(m -> m.rule(m.group("/Subscription/Addons"))
                        .name("DiscountNotNegative")
                        .errorField(m.num("/Subscription/Addons/Discount"))
                        .when(m.num("/Subscription/Addons/Discount").filled()
                                .and(m.num("/Subscription/Addons/Discount").lt(0)))
                        .code("DISCOUNT_NEGATIVE")
                        .severity(Severity.ERROR)
                        .message(Dsl.en("Discount must not be negative."),
                                Dsl.de("Rabatt darf nicht negativ sein."))
                        .build())
                .validate()
                .toJson();

        assertTrue(ModelDocument.read(result).fieldPaths().contains("/Subscription/Addons/Discount"));
        JsonNode tree = readTree(result);
        assertNotNull(DmJson.findElement(tree, "Rule", "/Subscription/Addons/DiscountNotNegative"),
                "the rule node is missing from the written-back model");
    }

    private static JsonNode readTree(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void deletesAStructureGroupAndWritesBack() {
        String result = EditSession.read(Fixtures.subscriptionJson())
                .removeGroup("/Subscription/Billing")
                .validate()
                .toJson();

        assertFalse(ModelDocument.read(result).groupPaths().contains("/Subscription/Billing"));
    }
}
