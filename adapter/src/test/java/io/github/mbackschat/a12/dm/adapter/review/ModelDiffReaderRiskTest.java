package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.ElementChange;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/** MR9 Set 3 — removals and retypes account for existing rule/computation readers in one inverse map. */
class ModelDiffReaderRiskTest {

    private static final String OBSERVED = "/Policy/Observed";
    private static final String RULE = "/Policy/ObservedRequired";

    @Test
    void removingAReferencedFieldIsHighImpactEvenWhenItsReaderIsRemovedToo() {
        String base = model(FieldKind.NUMBER);
        EditSession head = EditSession.read(base);
        head.rule(RULE).remove();
        head.removeField(OBSERVED).validate();

        ElementChange removed = change(ModelDiff.between(base, head.toJson()), OBSERVED);
        assertEquals(ModelDiff.Risk.HIGH, removed.risk());
        assertTrue(removed.reasons().contains("REFERENCED_ELEMENT_REMOVED"), () -> removed.reasons().toString());
    }

    @Test
    void retypingAReferencedFieldIsHighImpact() {
        ElementChange retyped = change(ModelDiff.between(model(FieldKind.NUMBER), model(FieldKind.STRING)), OBSERVED);

        assertEquals(ModelDiff.Risk.HIGH, retyped.risk());
        assertTrue(retyped.reasons().contains("KIND_CHANGED"), () -> retyped.reasons().toString());
        assertTrue(retyped.reasons().contains("RETYPE_AFFECTS_READERS"), () -> retyped.reasons().toString());
    }

    private static String model(FieldKind kind) {
        String base = DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", policy -> policy.field("Observed", kind))
                .build().toJson();
        EditSession session = EditSession.read(base);
        session.addRule(bound -> bound.rule(bound.group("/Policy")).name("ObservedRequired")
                .errorField(bound.field(OBSERVED)).when(bound.field(OBSERVED).notFilled())
                .code("OBSERVED_REQUIRED").severity(Severity.ERROR)
                .message(new LocalizedText("en_US", "Observed is required")).build());
        return session.validate().toJson();
    }

    private static ElementChange change(ModelDiff diff, String id) {
        return diff.changes().stream().filter(candidate -> candidate.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no change for " + id + " in " + diff.changes()));
    }
}
