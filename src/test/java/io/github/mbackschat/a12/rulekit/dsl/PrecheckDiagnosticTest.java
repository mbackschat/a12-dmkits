package io.github.mbackschat.a12.rulekit.dsl;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.rulekit.validate.PrecheckException;

/**
 * F4a PRECHECK origin — a build-time pre-check failure carries a structured {@link Diagnostic} (the
 * same LSP shape as KERNEL/LINT), not just a message, while staying an {@code IllegalStateException}
 * so the typed builder keeps its fail-fast contract.
 */
class PrecheckDiagnosticTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void anErrorFieldNotReferencedFailsWithAStructuredPrecheckDiagnostic() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("BadRule")
                .errorField(m.str("/Subscription/Addons/Name"))   // error field Name, condition references only fee
                .when(fee.filled().and(fee.le(0)))
                .code("BAD").severity(io.github.mbackschat.a12.rulekit.dsl.Severity.ERROR)
                .message(en("nope"), de("nein"));

        PrecheckException ex = assertThrows(PrecheckException.class, builder::build);
        // still fail-fast: an IllegalStateException, so existing callers are unaffected
        assertInstanceOf(IllegalStateException.class, ex);

        Diagnostic d = ex.diagnostic();
        assertEquals(DiagnosticSource.PRECHECK, d.source());
        assertEquals(Severity.ERROR, d.severity());
        assertEquals(java.util.Optional.of("MVK_ERROR_FIELD_NOT_REFERENCED"), d.code());
        assertEquals("/Subscription/Addons/BadRule", d.where().rule());
        assertTrue(d.fix().isPresent(), "a PRECHECK diagnostic should carry an actionable fix");
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
