package io.github.mbackschat.a12.rulekit.lint;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Rule;
import io.github.mbackschat.a12.rulekit.dsl.Severity;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * F4a unified surface — {@code RuleDiagnostics.diagnose} runs every origin on one rule and returns a
 * single {@link ValidationResult}: KERNEL ground truth (span-pointed), LINT warnings, and gotcha
 * INFO. This is the call an LLM/editor makes to learn everything about a rule at once.
 */
class RuleDiagnosticsTest {

    private static final BoundModel m = BoundModel.ofJson(Fixtures.subscriptionJson());

    private final RuleDiagnostics doctor = new RuleDiagnostics();

    private static boolean has(ValidationResult r, DiagnosticSource source, String code) {
        return r.diagnostics().stream().anyMatch(d ->
                d.source() == source && d.code().filter(code::equals).isPresent());
    }

    @Test
    void unifiesKernelErrorAndLintPreWarningForTheSameRule() {
        // Iterating rule with a bare unguarded negative: kernel REJECTS it, and the lint pre-warned.
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeePresentRule")
                .errorField(m.num("/Subscription/Addons/MonthlyFee"))
                .when(m.field("/Subscription/Addons/MonthlyFee").notFilled())
                .code("FEE").severity(Severity.ERROR)
                .message(en("Fee required."), de("Gebühr erforderlich."))
                .build();

        ValidationResult r = doctor.diagnose(m, rule);

        assertFalse(r.ok(), "the kernel rejects an unguarded negative under iteration");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.source() == DiagnosticSource.KERNEL),
                () -> "expected a KERNEL diagnostic, got: " + r.diagnostics());
        assertTrue(has(r, DiagnosticSource.LINT, "RK_NEG_CONDITION_IN_ITERATION"),
                () -> "the lint should have pre-warned, got: " + r.diagnostics());
    }

    @Test
    void surfacesLintAndGotchaOnAKernelValidRule() {
        // FieldFilled(Tier) is kernel-valid but inverts requiredness, and FieldFilled carries a gotcha.
        Rule rule = m.rule(m.group("/Subscription"))
                .name("TierRequiredRule")
                .errorField(m.field("/Subscription/Tier"))
                .when(m.field("/Subscription/Tier").filled())
                .code("TIER").severity(Severity.ERROR)
                .message(en("Tier required."), de("Stufe erforderlich."))
                .build();

        ValidationResult r = doctor.diagnose(m, rule);

        assertTrue(r.ok(), "the kernel accepts FieldFilled(Tier)");
        assertTrue(has(r, DiagnosticSource.LINT, "RK_INVERTED_REQUIREDNESS"),
                () -> "expected the inverted-requiredness lint, got: " + r.diagnostics());
        assertTrue(has(r, DiagnosticSource.LINT, "RK_GOTCHA"),
                () -> "expected a FieldFilled gotcha, got: " + r.diagnostics());
        // one structured array carries all three origins, JSON-ready
        assertTrue(r.toJson().contains("\"source\""), r.toJson());
    }
}
