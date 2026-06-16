package io.github.mbackschat.a12.dm.rulekit.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * DG3 — one structured error vocabulary. The write/edit not-found paths speak the same coded
 * {@link Diagnostic} (carried by {@link PrecheckException}) as validate/lint, so a caller catches one
 * error model regardless of layer — not a bare {@link IllegalArgumentException} with a scrape-only message.
 */
class ErrorVocabularyTest {

    // a WRITABLE binding (carries source JSON) — removeRule/replaceCondition go through ModelWriter
    private static final BoundModel m = BoundModel.ofJson(Fixtures.subscriptionJson());

    @Test
    void removeMissingRuleCarriesTheCodedDiagnostic() {
        PrecheckException ex = assertThrows(PrecheckException.class, () -> m.removeRule("/Subscription/Nope"));
        assertEquals("RK_NO_SUCH_RULE", ex.diagnostic().code().orElseThrow());
        assertEquals("/Subscription/Nope", ex.diagnostic().where().path().orElseThrow());
    }

    @Test
    void removeMissingComputationCarriesTheCodedDiagnostic() {
        PrecheckException ex = assertThrows(PrecheckException.class, () -> m.removeComputation("/Subscription/Nope"));
        assertEquals("RK_NO_SUCH_COMPUTATION", ex.diagnostic().code().orElseThrow());
    }

    @Test
    void replaceConditionOnMissingRuleCarriesTheCodedDiagnostic() {
        Condition c = m.num("/Subscription/Addons/MonthlyFee").gt(0);
        PrecheckException ex = assertThrows(PrecheckException.class,
                () -> m.replaceCondition("/Subscription/Nope", c));
        assertEquals("RK_NO_SUCH_RULE", ex.diagnostic().code().orElseThrow());
    }
}
