package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DiagnosticSource;

/**
 * F4a span-pointing — link a KERNEL diagnostic to the AST node that produced it. Intersecting the
 * code's catalog candidate operators with the operators the rule uses pins {@code where.operator}
 * (previously empty for KERNEL) and makes the catalog-join operator-exact when unambiguous.
 */
class SpanResolverTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String FEE = "/Subscription/Addons/MonthlyFee";

    private final SpanResolver resolver = new SpanResolver();

    private static Diagnostic kernel(String code) {
        return new Diagnostic(Severity.ERROR, DiagnosticSource.KERNEL, Optional.of(code),
                code + " (from the kernel)",
                new Diagnostic.Where("/Subscription/Addons/Probe", Optional.empty()),
                Optional.empty(), Optional.empty());
    }

    @Test
    void pinsTheOperatorAndSpanWhenExactlyOneUsedOperatorCanProduceTheCode() {
        // The rule uses only LessThan; MVK_INVALID_TYPE_FOR_COMPARISON has four ordering-op candidates.
        Condition c = m.num(FEE).lt(0);

        Diagnostic refined = resolver.resolve(List.of(kernel("MVK_INVALID_TYPE_FOR_COMPARISON")), c).get(0);

        assertEquals(Optional.of("LessThan"), refined.where().operator());
        assertTrue(refined.fix().isPresent(), "fix should be re-derived from the pinned operator");
        // the single comparison node is the whole condition → its span covers the rendered text
        assertTrue(refined.where().span().isPresent(), "span should be attached for the single pinned node");
        assertEquals(0, refined.where().span().get().start());
        assertEquals(new io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer().render(c).length(),
                refined.where().span().get().end());

        String json = new io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult(false, List.of(refined)).toJson();
        assertTrue(json.contains("\"span\""), json);
    }

    @Test
    void leavesItRepresentativeWhenSeveralUsedOperatorsAreCandidates() {
        // Uses both LessThan and GreaterThan → ambiguous → keep the representative (no pin).
        Condition c = m.num(FEE).lt(0).or(m.num(FEE).gt(100));

        Diagnostic refined = resolver.resolve(List.of(kernel("MVK_INVALID_TYPE_FOR_COMPARISON")), c).get(0);

        assertTrue(refined.where().operator().isEmpty(), "ambiguous → where.operator stays empty");
    }

    @Test
    void leavesNonKernelDiagnosticsUntouched() {
        Condition c = m.num(FEE).lt(0);
        Diagnostic lint = new Diagnostic(Severity.WARNING, DiagnosticSource.LINT, Optional.of("RK_X"),
                "x", new Diagnostic.Where("r", Optional.empty()), Optional.empty(), Optional.empty());

        assertSame(lint, resolver.resolve(List.of(lint), c).get(0));
    }
}
