package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledValue;

class FirstFilledValueDuplicatePrecedenceTest {

    private static final BoundModel MODEL = BoundModel.ofJson(DocumentModelDraft.named("claim").locale("en_US")
            .rootGroup("Claim", claim -> claim
                    .stringField("Fallback", field -> { })
                    .group("Primary", primary -> primary
                            .field("First", FieldKind.STRING)
                            .group("Detail", detail -> detail.field("Second", FieldKind.STRING)))
                    .group("Alternatives", alternatives -> alternatives.repeatable(10)
                            .field("Value", FieldKind.STRING)))
            .build().toJson());

    @Test
    void theFirstCompletedExactDuplicateSelectsTheWitness() {
        FirstFilledOperand primary = MODEL.firstFilledGroup("/Claim/Primary");
        FirstFilledOperand first = MODEL.field("/Claim/Primary/First").firstFilledOperand();

        assertEquals("FirstFilledValue: '/Claim/Primary/First' is listed more than once"
                + " (MVK_DUPLICATE_PARAM1)", assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(primary, List.of(first, first, primary))).getMessage());
        assertEquals("FirstFilledValue: '/Claim/Primary' is listed more than once"
                + " (MVK_DUPLICATE_PARAM1)", assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(primary, List.of(primary, first, first))).getMessage());
    }

    @Test
    void anyExactGroupDuplicatePrecedesStrictOverlap() {
        FirstFilledOperand primary = MODEL.firstFilledGroup("/Claim/Primary");
        FirstFilledOperand detail = MODEL.firstFilledGroup("/Claim/Primary/Detail");
        String expected = "FirstFilledValue: '/Claim/Primary' is listed more than once"
                + " (MVK_DUPLICATE_PARAM1)";

        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(primary, List.of(detail, primary))).getMessage());
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(detail, List.of(primary, primary))).getMessage());
    }

    @Test
    void strictOverlapRemainsTheFallbackWhenNoExactDuplicateExists() {
        FirstFilledOperand primary = MODEL.firstFilledGroup("/Claim/Primary");
        FirstFilledOperand detail = MODEL.firstFilledGroup("/Claim/Primary/Detail");

        assertEquals("FirstFilledValue: '/Claim/Primary/Detail' is already included through '/Claim/Primary'"
                + " (MVK_DUPLICATE_PARAM2)", assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(primary, List.of(detail))).getMessage());
    }

    @Test
    void wildcardedGroupOccurrencesRemainDistinctAuthoredSlots() {
        FirstFilledOperand alternatives = MODEL.firstFilledGroup("/Claim/Alternatives*");

        assertEquals("FirstFilledValue(/Claim/Alternatives*, /Claim/Alternatives*) == \"x\"",
                new DslRenderer().render(Dsl.firstFilledString(alternatives, alternatives).eq("x")));
    }

    @Test
    void scalarSingletonKeepsItsIndependentArityDiagnostic() {
        FirstFilledOperand first = MODEL.field("/Claim/Primary/First").firstFilledOperand();

        assertEquals("FirstFilledValue needs at least two scalar fields or one repetition list"
                + " (MVK_PARAMSIZE_INVALIDN)", assertThrows(IllegalArgumentException.class,
                () -> new FirstFilledValue(first, List.of())).getMessage());
    }
}
