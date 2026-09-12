package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
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

    /** A12's READER refused the bytes, so no declaration was ever examined — not a verdict about the model. */
    static final String UNREADABLE = "UNREADABLE";

    /** Read, but the Kernel could not BUILD the model, so it reports that and no consistency verdict. */
    static final String NOT_BUILDABLE = "NOT BUILDABLE";

    /** The Kernel's own codes, lifted from the notification stream rather than matched against a guess. */
    private static final java.util.regex.Pattern MVK = java.util.regex.Pattern.compile("MVK_[A-Z0-9_]+");

    private KernelLaws() {
    }

    /**
     * What A12 says about a whole stored model — {@link #ADMITTED}, {@link #UNREADABLE},
     * {@link #NOT_BUILDABLE}, or its distinct {@code MVK_*} codes joined by commas in first-seen order.
     *
     * <p><b>Three verdicts, not two, and the third is the one that matters.</b> A shape A12's reader REFUSES
     * makes the model unreadable, so A12 never judges it at all. Reporting an {@code MVK_*} finding there
     * presents the reader's own opinion as A12's, and reporting nothing presents an unbuildable model as
     * clean, so the tiers must be told apart rather than collapsed into "invalid". They arrive on different
     * exception types, which is what makes the distinction mechanical rather than a reading of messages.
     */
    static String verdictOf(String modelJson) {
        try {
            io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(modelJson);
            return ADMITTED;
        } catch (io.github.mbackschat.a12.dm.core.ModelInputException unreadable) {
            return UNREADABLE;
        } catch (io.github.mbackschat.a12.dm.core.ModelConsistencyException rejected) {
            String said = rejected.notifications().stream()
                    .map(n -> n.message())
                    .collect(Collectors.joining(" "));
            java.util.regex.Matcher codes = MVK.matcher(said);
            java.util.List<String> found = new java.util.ArrayList<>();
            while (codes.find()) {
                if (!found.contains(codes.group())) {
                    found.add(codes.group());
                }
            }
            // A rejection carrying no MVK_ code is the Kernel refusing to BUILD the model from inside
            // checkConsistency — still not a verdict about its declarations, and still not an input refusal.
            return found.isEmpty() ? NOT_BUILDABLE : String.join(",", found);
        }
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

    /**
     * As {@link #rejectsOrder}, and additionally that {@code preemptedCode} is <b>absent</b> — for a law whose
     * claim is that one gate fires <em>instead of</em> another.
     *
     * <p>Needed because the ordinary assertion is an {@code anyMatch} over the diagnostics: it proves the expected
     * code is present and says nothing about what else is. An exclusivity law asserted that way passes whether or
     * not the gate it claims is pre-empted also fired, so it does not test its own statement — the presence-shaped
     * blind spot, inside the law tier that exists to be property-shaped.
     */
    static void rejectsOrderInsteadOf(String group, String errorField, String condition,
            String expectedCode, String preemptedCode) {
        assertRejectsInsteadOf(check(ORDER, group, errorField, condition), condition, expectedCode, preemptedCode);
    }

    private static void assertRejectsInsteadOf(ValidationResult r, String condition,
            String expectedCode, String preemptedCode) {
        assertFalse(r.ok(), () -> "expected rejection of '" + condition + "'");
        assertTrue(r.diagnostics().stream().anyMatch(p -> p.code().filter(expectedCode::equals).isPresent()),
                () -> "expected " + expectedCode + " for '" + condition + "', got: " + r.diagnostics());
        assertFalse(r.diagnostics().stream().anyMatch(p -> p.code().filter(preemptedCode::equals).isPresent()),
                () -> preemptedCode + " must be PRE-EMPTED for '" + condition + "' — if both fire, the ordering "
                        + "claim is not what was measured: " + r.diagnostics());
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

    /** As {@link #rejectsOrderInsteadOf}, on a caller-supplied model — same exclusivity reasoning. */
    static void rejectsModelInsteadOf(String json, String group, String errorField, String condition,
            String expectedCode, String preemptedCode) {
        assertRejectsInsteadOf(check(json, group, errorField, condition), condition, expectedCode, preemptedCode);
    }

    /** Typed-rule variant: every path and the rendered condition derive from the rulekit object. */
    static void rejectsModel(String json, Rule rule, String expectedCode) {
        assertRejects(json, rule, expectedCode);
    }

    /** Typed-computation variant: the target and the rendered operation derive from the rulekit object. */
    static void acceptsModel(String json, Computation computation) {
        ValidationResult r = VALIDATOR.check(json, computation.draft());
        assertTrue(r.ok(), () -> "expected acceptance of computation '" + computation.name() + "', got: "
                + r.diagnostics());
    }

    static void rejectsModel(String json, Computation computation, String expectedCode) {
        ValidationResult r = VALIDATOR.check(json, computation.draft());
        assertFalse(r.ok(), () -> "expected rejection of computation '" + computation.name() + "'");
        assertTrue(r.diagnostics().stream().anyMatch(p -> p.code().filter(expectedCode::equals).isPresent()),
                () -> "expected " + expectedCode + " for computation '" + computation.name() + "', got: "
                        + r.diagnostics());
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

    /**
     * The raw {@link ValidationResult} for a caller-supplied model — for a law whose claim is a TABLE of verdicts
     * across many conditions. Asserting row-by-row aborts at the first divergence and hides the rest of the matrix,
     * which is exactly what you need to see when a kernel upgrade shifts a gate; such a law collects verdicts with
     * this and asserts the whole table once.
     */
    static ValidationResult checkModel(String json, String group, String errorField, String condition) {
        return check(json, group, errorField, condition);
    }

    private static ValidationResult check(String json, String group, String errorField, String condition) {
        return VALIDATOR.check(json, new DraftRule(group, "ProbeRule", errorField, condition, "PROBE", "ERROR"));
    }

    /**
     * The verdict word every law in this package reads when the kernel admits the candidate — shared with
     * {@link #verdictOf}'s tiers so two axes cannot spell the same outcome differently.
     */
    static final String ADMITTED = "ADMITTED";

    /**
     * The kernel's verdict on one COMPUTATION — {@link #ADMITTED} or the comma-joined classes it reported —
     * for a law whose claim is a table of computation verdicts. The complete class list matters here rather
     * than a single expected code, because the claim such a table usually carries is <em>which gate reports
     * first</em>, and a one-code assertion cannot see a co-firing second gate.
     */
    static String computationVerdict(String json, Computation computation) {
        ValidationResult r = VALIDATOR.check(json, computation.draft());
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(","));
    }

    /**
     * The RULE twin of {@link #computationVerdict}, on the shared Order fixture — {@link #ADMITTED} or the
     * comma-joined classes. A table law reaches for this whenever its claim is <em>which gate reports</em>
     * rather than merely <em>that one does</em>: a one-code assertion cannot distinguish a refusal attributed
     * to the gate under test from the same refusal arriving from a different gate that happens to co-fire.
     */
    static String orderVerdict(String group, String errorField, String condition) {
        return verdictModel(ORDER, group, errorField, condition);
    }

    /** {@link #orderVerdict} on a caller-supplied model, for a table whose carrier the fixtures cannot supply. */
    static String verdictModel(String json, String group, String errorField, String condition) {
        ValidationResult r = check(json, group, errorField, condition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(","));
    }

    /**
     * The kernel's verdict on ONE error-message TEXT — {@link #ADMITTED} or the comma-joined classes it
     * reported — with the condition held fixed. The message axis needs its own entry point because
     * {@link #check} varies the condition and always supplies a placeholder text.
     *
     * <p><b>The plain-message control is the point, not politeness.</b> A malformed condition is refused
     * before the error-text checker ever runs, and that refusal is indistinguishable from a verdict on the
     * message. Nine rows of the probe behind [KF198] were exactly that mistake, so this asserts the same
     * condition is admitted with a plain text before it reads the row.
     */
    static String messageVerdict(String json, String group, String errorField, String condition,
            String locale, String messageText) {
        ValidationResult control = VALIDATOR.check(json, message(group, errorField, condition, locale, "plain"));
        assertTrue(control.ok(), () -> "the CONDITION is refused, so this row measures nothing about the "
                + "message: " + control.diagnostics());
        ValidationResult verdict = VALIDATOR.check(json, message(group, errorField, condition, locale, messageText));
        // The summary rides a CODELESS diagnostic because on this surface codelessness is itself a
        // measurable property: the template's supplementary-plane refusal carries no class and is identified
        // only by its text, so a bare "(no code)" would make two different refusals indistinguishable.
        return verdict.ok() ? ADMITTED
                : verdict.diagnostics().stream().map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                        .collect(Collectors.joining(","));
    }

    private static DraftRule message(String group, String errorField, String condition,
            String locale, String text) {
        return DraftRule.builder()
                .groupPath(group).name("ProbeRule").errorEntityRelPath(errorField)
                .errorCondition(condition).errorCode("PROBE").severity("ERROR")
                .messages(java.util.List.of(new LocalizedText(locale, text)))
                .build();
    }
}
