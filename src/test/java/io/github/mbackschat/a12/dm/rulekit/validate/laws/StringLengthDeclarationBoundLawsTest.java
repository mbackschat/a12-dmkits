package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.StringFieldBuilder;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The largest String length A12 admits as a declaration (KERNEL-FINDINGS KF547): the 99,999 units its technical
 * input guard enforces on every stored value, so a declared bound above it could never be reached anyway. A12
 * answers a bound above it with a sentence and no class.
 *
 * <p>Own domain (a support ticket's free-text description).
 */
class StringLengthDeclarationBoundLawsTest {

    private static final String REFUSAL =
            "Field with path '/Ticket/Description': Only values smaller than or equal to 99999 are allowed.";

    private static String ticket(UnaryOperator<StringFieldBuilder> bounds) {
        return DocumentModelDraft.named("ticket-length").locale("en_US")
                .rootGroup("Ticket", t -> t.stringField("Description", bounds::apply))
                .build().toJson();
    }

    /** A12's sentences for the ticket with [bounds], none when it admits the model. */
    private static List<String> refusals(UnaryOperator<StringFieldBuilder> bounds) {
        try {
            BoundModel.ofJson(ticket(bounds));
            return List.of();
        } catch (ModelConsistencyException refused) {
            return refused.notifications().stream().map(n -> n.message().trim()).toList();
        }
    }

    @Test
    void aMaximumUpToTheTechnicalLimitIsAdmitted() {
        assertEquals(List.of(), refusals(s -> s.maxLength(99_999)));
        assertEquals(List.of(), refusals(s -> s.minLength(99_999)));
    }

    @Test
    void aBoundAboveTheTechnicalLimitIsRefusedWithoutAClass() {
        assertEquals(List.of(REFUSAL), refusals(s -> s.maxLength(100_000)));
        assertEquals(List.of(REFUSAL), refusals(s -> s.maxLength(1_000_000)));
        assertEquals(List.of(REFUSAL), refusals(s -> s.minLength(100_000)));
        // One sentence per field, whichever members exceed it.
        assertEquals(List.of(REFUSAL), refusals(s -> s.minLength(100_000).maxLength(100_000)));
    }
}
