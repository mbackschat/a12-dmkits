package io.github.mbackschat.a12.rulekit.lint;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Rule;
import io.github.mbackschat.a12.rulekit.dsl.Severity;
import io.github.mbackschat.a12.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.rulekit.validate.DiagnosticSource;
import io.github.mbackschat.a12.rulekit.validate.KernelVersion;
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

    @Test
    void deferredErrorFieldCheckIsSuppressedWhenTheKernelAlreadyRules() {
        // F11: build() no longer hard-fails an unreferenced error field; the kernel rules on it, and
        // the deferred PRECHECK advisory is suppressed so the same code isn't double-reported.
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("BadRule")
                .errorField(m.str("/Subscription/Addons/Name"))   // error field Name, condition references only fee
                .when(m.num("/Subscription/Addons/MonthlyFee").filled())
                .code("BAD").severity(Severity.ERROR)
                .message(en("nope"), de("nein"))
                .build();

        ValidationResult r = doctor.diagnose(m, rule);

        assertFalse(r.ok(), "the kernel rejects an unreferenced error field");
        long withCode = r.diagnostics().stream()
                .filter(d -> d.code().filter("MVK_ERROR_FIELD_NOT_REFERENCED"::equals).isPresent())
                .count();
        assertEquals(1, withCode,
                () -> "the deferred precheck must be deduped against the kernel verdict: " + r.diagnostics());
        assertTrue(has(r, DiagnosticSource.KERNEL, "MVK_ERROR_FIELD_NOT_REFERENCED"),
                () -> "the kernel is the authority on this code: " + r.diagnostics());
    }

    @Test
    void reportsKernelVersionSkewOnceAsInfoWithoutGatingOk() {
        // F11: a doctor running against a kernel newer than the verified floor surfaces one advisory.
        RuleDiagnostics skewed = new RuleDiagnostics(new KernelVersion(Optional.of("99.9.9"), "30.8.1"));
        Rule rule = m.rule(m.group("/Subscription"))
                .name("TierRequiredRule")
                .errorField(m.field("/Subscription/Tier"))
                .when(m.field("/Subscription/Tier").filled())
                .code("TIER").severity(Severity.ERROR)
                .message(en("Tier required."), de("Stufe erforderlich."))
                .build();

        ValidationResult first = skewed.diagnose(m, rule);
        assertTrue(first.ok(), "the version advisory is INFO — it must not gate ok");
        assertTrue(has(first, DiagnosticSource.ENVIRONMENT, "RK_KERNEL_VERSION_SKEW"),
                () -> "expected the kernel-version skew advisory, got: " + first.diagnostics());

        // once per instance — a second diagnose on the same doctor doesn't repeat it
        ValidationResult second = skewed.diagnose(m, rule);
        assertFalse(has(second, DiagnosticSource.ENVIRONMENT, "RK_KERNEL_VERSION_SKEW"),
                () -> "the advisory should fire once per instance, got: " + second.diagnostics());
    }
}
