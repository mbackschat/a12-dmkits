package io.github.mbackschat.a12.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mbackschat.a12.rulekit.Fixtures;
import io.github.mbackschat.a12.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * Shared harness for the kernel-law lock tests — the executable counterpart of the
 * {@code KERNEL-FINDINGS.md} entries. Each {@code …LawsTest} in this package embeds a condition as a
 * rule's {@code errorCondition} and asserts the real kernel accepts it / rejects it with a named
 * {@code MVK_*} code, so a kernel upgrade that changes a verified behavior fails loudly.
 */
final class KernelLaws {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String SUBSCRIPTION = Fixtures.subscriptionJson();

    private KernelLaws() {
    }

    /** Asserts the kernel accepts {@code condition} for the given (group, errorField). */
    static void accepts(String group, String errorField, String condition) {
        ValidationResult r = check(SUBSCRIPTION, group, errorField, condition);
        assertTrue(r.ok(), () -> "expected acceptance of '" + condition + "', got: " + r.problems());
    }

    /** Asserts the kernel rejects {@code condition} reporting {@code expectedCode}. */
    static void rejects(String group, String errorField, String condition, String expectedCode) {
        ValidationResult r = check(SUBSCRIPTION, group, errorField, condition);
        assertFalse(r.ok(), () -> "expected rejection of '" + condition + "'");
        assertTrue(r.problems().stream().anyMatch(p -> p.mvkCode().filter(expectedCode::equals).isPresent()),
                () -> "expected " + expectedCode + " for '" + condition + "', got: " + r.problems());
    }

    /** Asserts the kernel rejects {@code condition} (code unspecified — e.g. parse errors). */
    static void rejects(String group, String errorField, String condition) {
        ValidationResult r = check(SUBSCRIPTION, group, errorField, condition);
        assertFalse(r.ok(), () -> "expected rejection of '" + condition + "'");
    }

    private static ValidationResult check(String json, String group, String errorField, String condition) {
        return VALIDATOR.check(json, new DraftRule(group, "ProbeRule", errorField, condition, "PROBE", "ERROR"));
    }
}
