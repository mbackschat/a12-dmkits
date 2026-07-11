package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.Severity;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;

/**
 * F4a PRECHECK origin — a build-time pre-check failure carries a structured {@link Diagnostic} (the
 * same LSP shape as KERNEL/LINT), not just a message, while staying an {@code IllegalStateException}
 * so the typed builder keeps its fail-fast contract.
 */
class PrecheckDiagnosticTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void anErrorFieldNotReferencedIsDeferred_notAHardBuildFailure() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        // F11: this no longer throws — the error-field-referenced check mirrors a kernel judgment, so
        // it is deferred to the live kernel rather than hard-failing build() on the covers() heuristic.
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("BadRule")
                .errorField(m.str("/Subscription/Addons/Name"))   // error field Name, condition references only fee
                .when(fee.filled().and(fee.le(0)))
                .code("BAD").severity(io.github.mbackschat.a12.dm.rulekit.dsl.Severity.ERROR)
                .message(en("nope"), de("nein"))
                .build();

        var deferred = rule.deferredChecks();
        assertEquals(1, deferred.size(), () -> "expected one deferred check, got: " + deferred);

        Diagnostic d = deferred.get(0);
        assertEquals(DiagnosticSource.PRECHECK, d.source());
        assertEquals(Severity.WARNING, d.severity(), "deferred kernel-semantic prechecks are advisory, not ERROR");
        assertEquals(java.util.Optional.of("MVK_ERROR_FIELD_NOT_REFERENCED"), d.code());
        assertEquals("/Subscription/Addons/BadRule", d.where().rule());
        assertTrue(d.fix().isPresent(), "a deferred PRECHECK diagnostic should carry an actionable fix");
        assertTrue(d.summary().contains("error field"), d.summary());
    }

    @Test
    void anIncompleteRuleReportsRkIncompleteRule() {
        var builder = m.rule(m.group("/Subscription/Addons")).name("NoFieldRule");

        PrecheckException ex = assertThrows(PrecheckException.class, builder::build);
        assertEquals(java.util.Optional.of("RK_INCOMPLETE_RULE"), ex.diagnostic().code());
        assertEquals(DiagnosticSource.PRECHECK, ex.diagnostic().source());
    }
}
