package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.DateExpr;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * A Date computation authored entirely through typed handles: {@link GroupEditor#addComputation} takes the
 * {@code DateRef} target and both builders take the {@link DateExpr} operation, as they already did for numbers
 * and text. Without them every Date computation ended in {@code field(...)} and {@code expression()}, the
 * friction the 2026-10-13 first-filled differentials ran into.
 *
 * <p>Own domain (an insurance claim's reported and confirmed dates).
 */
class GroupEditorDateComputationTest {

    private static final String CLAIM = DocumentModelDraft.named("claim-dates").locale("en_US")
            .rootGroup("Claim", c -> c
                    .dateField("Reported", f -> f.format("yyyy-MM-dd"))
                    .dateField("Confirmed", f -> f.format("yyyy-MM-dd"))
                    .dateField("FirstKnown", f -> f.format("dd.MM.yyyy")))
            .build().toJson();

    @Test
    void anUnconditionalDateAlternativeTakesTheExpression() {
        GroupEditor claim = EditSession.read(CLAIM).group("/Claim");
        DateExpr first = Dsl.firstFilledDate(claim.date("Reported"), claim.date("Confirmed"));

        Computation built = claim.computation(claim.date("FirstKnown"), computation -> computation
                .name("FirstKnownComp").calc(first)
                .message(Dsl.en("The first known claim date.")).build());

        assertEquals("FirstFilledValue(/Claim/Reported, /Claim/Confirmed)", operationOf(built));
    }

    @Test
    void aPreconditionedDateAlternativeTakesTheExpression() {
        GroupEditor claim = EditSession.read(CLAIM).group("/Claim");
        DateExpr first = Dsl.firstFilledDate(claim.date("Reported"), claim.date("Confirmed"));

        Computation built = claim.computation(claim.date("FirstKnown"), computation -> computation
                .name("FirstKnownComp").when(claim.date("Reported").filled()).calc(first)
                .message(Dsl.en("The first known claim date.")).build());

        assertEquals("FirstFilledValue(/Claim/Reported, /Claim/Confirmed)", operationOf(built));
    }

    @Test
    void theEditorAddsADateComputationTheKernelAdmits() {
        EditSession session = EditSession.read(CLAIM);
        GroupEditor claim = session.group("/Claim");

        claim.addComputation(claim.date("FirstKnown"), computation -> computation
                .name("FirstKnownComp").calc(Dsl.firstFilledDate(claim.date("Reported"), claim.date("Confirmed")))
                .message(Dsl.en("The first known claim date.")).build());
        session.validate();

        assertEquals("/Claim/FirstKnownComp", session.computation("/Claim/FirstKnownComp").name());
    }

    private static String operationOf(Computation built) {
        assertEquals("../FirstKnown", built.computedFieldRelPath());
        return new DslRenderer().render(built.alternatives().getFirst().operation());
    }
}
