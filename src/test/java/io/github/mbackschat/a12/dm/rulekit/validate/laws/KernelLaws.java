package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * Shared harness for the kernel-law lock tests — the executable counterpart of the
 * {@code KERNEL-FINDINGS.md} entries. Each {@code …LawsTest} in this package embeds a condition as a
 * rule's {@code errorCondition} and asserts the real kernel accepts it / rejects it with a named
 * {@code MVK_*} code, so a kernel upgrade that changes a verified behavior fails loudly.
 */
final class KernelLaws {

    private static final RuleValidator VALIDATOR = new RuleValidator();
    private static final String SUBSCRIPTION = Fixtures.subscriptionJson();
    private static final String ORDER = Fixtures.orderJson();
    private static final String LOGISTICS = ModelDrafts.logistics().toJson();
    private static final String PROJECT = Fixtures.projectJson();

    private KernelLaws() {
    }

    /** Asserts the kernel accepts {@code condition} for the given (group, errorField). */
    static void accepts(String group, String errorField, String condition) {
        assertAccepts(SUBSCRIPTION, group, errorField, condition);
    }

    /** Asserts the kernel rejects {@code condition} reporting {@code expectedCode}. */
    static void rejects(String group, String errorField, String condition, String expectedCode) {
        assertRejects(SUBSCRIPTION, group, errorField, condition, expectedCode);
    }

    /** Asserts the kernel rejects {@code condition} (code unspecified — e.g. parse errors). */
    static void rejects(String group, String errorField, String condition) {
        ValidationResult r = check(SUBSCRIPTION, group, errorField, condition);
        assertFalse(r.ok(), () -> "expected rejection of '" + condition + "'");
    }

    /** Order-fixture variants (for rules needing distinct number scales etc.). */
    static void acceptsOrder(String group, String errorField, String condition) {
        assertAccepts(ORDER, group, errorField, condition);
    }

    static void rejectsOrder(String group, String errorField, String condition, String expectedCode) {
        assertRejects(ORDER, group, errorField, condition, expectedCode);
    }

    /** Logistics-fixture variant (two index-keyed repeatable groups) — authored via modelkit, not JSON. */
    static void acceptsLogistics(String group, String errorField, String condition) {
        assertAccepts(LOGISTICS, group, errorField, condition);
    }

    static void rejectsLogistics(String group, String errorField, String condition, String expectedCode) {
        assertRejects(LOGISTICS, group, errorField, condition, expectedCode);
    }

    /** Project-fixture variants (nested repeatable groups Milestones ▸ Tasks) — for §10 wildcard-path laws. */
    static void acceptsProject(String group, String errorField, String condition) {
        assertAccepts(PROJECT, group, errorField, condition);
    }

    static void rejectsProject(String group, String errorField, String condition, String expectedCode) {
        assertRejects(PROJECT, group, errorField, condition, expectedCode);
    }

    /** Generic variants for an arbitrary modelkit-authored draft (its JSON passed in). */
    static void acceptsModel(String json, String group, String errorField, String condition) {
        assertAccepts(json, group, errorField, condition);
    }

    /** Typed-rule variant: every path and the rendered condition derive from the rulekit object. */
    static void acceptsModel(String json, Rule rule) {
        assertAccepts(json, rule);
    }

    /** Rejection on a caller-supplied model without pinning a code — for kernel checks that carry no MVK code. */
    static void rejectsModel(String json, String group, String errorField, String condition) {
        ValidationResult r = check(json, group, errorField, condition);
        assertFalse(r.ok(), () -> "expected rejection of '" + condition + "'");
    }

    static void rejectsModel(String json, String group, String errorField, String condition, String expectedCode) {
        assertRejects(json, group, errorField, condition, expectedCode);
    }

    /** Typed-rule variant: every path and the rendered condition derive from the rulekit object. */
    static void rejectsModel(String json, Rule rule, String expectedCode) {
        assertRejects(json, rule, expectedCode);
    }

    private static void assertAccepts(String json, String group, String errorField, String condition) {
        ValidationResult r = check(json, group, errorField, condition);
        assertTrue(r.ok(), () -> "expected acceptance of '" + condition + "', got: " + r.diagnostics());
    }

    private static void assertAccepts(String json, Rule rule) {
        ValidationResult r = VALIDATOR.check(json, rule.draft());
        assertTrue(r.ok(), () -> "expected acceptance of '" + rule.render() + "', got: " + r.diagnostics());
    }

    private static void assertRejects(String json, String group, String errorField, String condition, String code) {
        ValidationResult r = check(json, group, errorField, condition);
        assertFalse(r.ok(), () -> "expected rejection of '" + condition + "'");
        assertTrue(r.diagnostics().stream().anyMatch(p -> p.code().filter(code::equals).isPresent()),
                () -> "expected " + code + " for '" + condition + "', got: " + r.diagnostics());
    }

    private static void assertRejects(String json, Rule rule, String code) {
        ValidationResult r = VALIDATOR.check(json, rule.draft());
        assertFalse(r.ok(), () -> "expected rejection of '" + rule.render() + "'");
        assertTrue(r.diagnostics().stream().anyMatch(p -> p.code().filter(code::equals).isPresent()),
                () -> "expected " + code + " for '" + rule.render() + "', got: " + r.diagnostics());
    }

    private static ValidationResult check(String json, String group, String errorField, String condition) {
        return VALIDATOR.check(json, new DraftRule(group, "ProbeRule", errorField, condition, "PROBE", "ERROR"));
    }
}
