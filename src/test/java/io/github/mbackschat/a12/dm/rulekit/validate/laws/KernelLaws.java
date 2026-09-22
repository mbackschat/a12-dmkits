package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.ModelDrafts;
import io.github.mbackschat.a12.dm.rulekit.dsl.Calculation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
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

    /**
     * The Kernel judged the declaration and stated its verdict in PROSE, carrying no class key.
     *
     * <p><b>Codelessness alone does not say which tier refused</b>, and collapsing the two into
     * {@link #NOT_BUILDABLE} published the wrong claim: a line break inside a declared enumeration value draws
     * {@code "Field with path '/Policy/Tier': Only values without line breaks are allowed."}, which is a
     * verdict about the declaration, and reading it as "A12 never judged this" is the opposite of the truth.
     * The discriminator is the build-failure summary our own adapter writes
     * ({@code KernelModelPreparation}), which is the same one {@code cli/…/schema/diagnostic.schema.json}
     * already names to separate these two tiers.
     *
     * <p>A row landing here is a finding in its own right: the class is reachable but unnamed, so a portable
     * checker either owns a code for it or stays silent — it can never cite A12's.
     */
    static final String REFUSED_CODELESS = "REFUSED (codeless)";

    /** The summary our adapter writes when {@code checkConsistency} threw instead of reporting. */
    private static final String COULD_NOT_BUILD = "the kernel could not build this model:";

    /**
     * The Kernel refused the input by RAISING past every typed channel — a bare {@code RuntimeException}
     * that is neither an input refusal nor a consistency exception.
     *
     * <p>It is a distinct observable outcome and it names a known class rather than a harness slip: the
     * Kernel rejects some invalid models this way, and left to escape each one reaches a caller as an
     * internal-defect envelope, asserting the opposite of the truth (KERNEL-FINDINGS KF259). A row landing
     * here is therefore a finding about A12's reporting, not a passing row.
     */
    static final String RAISED_UNTYPED = "RAISED (untyped)";

    /**
     * The Kernel's own class key, which every coded notification carries as the terminal {@code [KEY]}.
     *
     * <p><b>Matching {@code MVK_*} instead was a defect, and it published the wrong claim.</b> A12's keys are
     * not all spelled that way — {@code NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN} and
     * {@code INVALID_GROUP} carry no prefix — so a prefix match dropped them and the row fell through to
     * {@link #NOT_BUILDABLE}, which says A12 reached no verdict about the declaration. It had reached one.
     * Reading the key off the notification cannot miss a class this way, because it keys on the Kernel's
     * format rather than on a family of names someone listed.
     */
    private static final Pattern CLASS_KEY =
            Pattern.compile(".*\\[(\\w+)]$");

    private KernelLaws() {
    }

    /**
     * What A12 says about a whole stored model — {@link #ADMITTED}, {@link #UNREADABLE},
     * {@link #NOT_BUILDABLE}, or the distinct classes it reported, joined by commas in first-seen order.
     *
     * <p><b>Four verdicts, not two, and the ones past ADMITTED are what matter.</b> A shape A12's reader
     * REFUSES makes the model unreadable, so A12 never judges it at all. Reporting an {@code MVK_*} finding
     * there presents the reader's own opinion as A12's, and reporting nothing presents an unbuildable model as
     * clean, so the tiers must be told apart rather than collapsed into "invalid". The first three arrive on
     * different exception types; {@link #REFUSED_CODELESS} is separated from {@link #NOT_BUILDABLE} by our own
     * build-failure summary, since both arrive as a consistency rejection carrying no class key.
     */
    static String verdictOf(String modelJson) {
        try {
            io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(modelJson);
            return ADMITTED;
        } catch (io.github.mbackschat.a12.dm.core.ModelInputException unreadable) {
            return UNREADABLE;
        } catch (io.github.mbackschat.a12.dm.core.ModelConsistencyException rejected) {
            // Codeless prose is dropped deliberately: one malformed name also re-reports the path
            // validator's sentence, unkeyed, once per element beneath the offending path, so the classes are
            // the law and the multiplicity is a property of the fixture's shape.
            String classes = rejected.notifications().stream()
                    .map(n -> n.message().trim())
                    .map(CLASS_KEY::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .distinct()
                    .collect(Collectors.joining(","));
            if (!classes.isEmpty()) {
                return classes;
            }
            // Two different tiers arrive codeless and they are not the same claim: the Kernel refusing to
            // BUILD the model from inside checkConsistency, which is no verdict about its declarations, and
            // the Kernel stating a real verdict in prose. Our own build-failure summary separates them.
            return rejected.notifications().stream().anyMatch(n -> n.message().contains(COULD_NOT_BUILD))
                    ? NOT_BUILDABLE
                    : REFUSED_CODELESS;
        } catch (RuntimeException raised) {
            // Last, so a typed refusal can never land here: only a throw that escaped every channel does.
            return RAISED_UNTYPED;
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

    // ---- typed twins ---------------------------------------------------------------------------------
    //
    // Every fixture-bound entry point above, taking a Condition instead of its rendered text. They exist
    // because the String form was the ONLY form here, which is the whole reason the accept/reject tier is
    // written in raw strings: a law that wanted to author typed had to abandon the convenience entry point
    // and rebuild a model (DEV-API-GAPS DG59, measured 2026-09-22 — 453 call sites across these nine names).
    // Each delegates through render(group, …), so a migrated row sends the kernel byte-identical text and a
    // String row that stays is a row whose subject really is the concrete syntax, which is the distinction
    // DG59 wants to become checkable. Fixtures.subscription()/order()/project()/logistics() bind the models
    // these methods assert against, so a caller authors against the same model the assertion uses.

    /** As {@link #accepts(String, String, String)}, authored typed. */
    static void accepts(String group, String errorField, Condition condition) {
        accepts(group, errorField, render(group, condition));
    }

    /** As {@link #rejects(String, String, String, String)}, authored typed. */
    static void rejects(String group, String errorField, Condition condition, String expectedCode) {
        rejects(group, errorField, render(group, condition), expectedCode);
    }

    /** As {@link #rejects(String, String, String)}, authored typed. */
    static void rejects(String group, String errorField, Condition condition) {
        rejects(group, errorField, render(group, condition));
    }

    /** As {@link #acceptsOrder}, authored typed. */
    static void acceptsOrder(String group, String errorField, Condition condition) {
        acceptsOrder(group, errorField, render(group, condition));
    }

    /** As {@link #rejectsOrder}, authored typed. */
    static void rejectsOrder(String group, String errorField, Condition condition, String expectedCode) {
        rejectsOrder(group, errorField, render(group, condition), expectedCode);
    }

    /** As {@link #rejectsOrderInsteadOf}, authored typed. */
    static void rejectsOrderInsteadOf(String group, String errorField, Condition condition,
            String expectedCode, String preemptedCode) {
        rejectsOrderInsteadOf(group, errorField, render(group, condition), expectedCode, preemptedCode);
    }

    /** As {@link #acceptsLogistics}, authored typed. */
    static void acceptsLogistics(String group, String errorField, Condition condition) {
        acceptsLogistics(group, errorField, render(group, condition));
    }

    /** As {@link #rejectsLogistics}, authored typed. */
    static void rejectsLogistics(String group, String errorField, Condition condition, String expectedCode) {
        rejectsLogistics(group, errorField, render(group, condition), expectedCode);
    }

    /** As {@link #acceptsProject}, authored typed. */
    static void acceptsProject(String group, String errorField, Condition condition) {
        acceptsProject(group, errorField, render(group, condition));
    }

    /** As {@link #rejectsProject}, authored typed. */
    static void rejectsProject(String group, String errorField, Condition condition, String expectedCode) {
        rejectsProject(group, errorField, render(group, condition), expectedCode);
    }

    /** As {@link #acceptsModel(String, String, String, String)}, authored typed. */
    static void acceptsModel(String json, String group, String errorField, Condition condition) {
        acceptsModel(json, group, errorField, render(group, condition));
    }

    /** As {@link #rejectsModel(String, String, String, String)}, authored typed. */
    static void rejectsModel(String json, String group, String errorField, Condition condition) {
        rejectsModel(json, group, errorField, render(group, condition));
    }

    /** As {@link #rejectsModel(String, String, String, String, String)}, authored typed. */
    static void rejectsModel(String json, String group, String errorField, Condition condition,
            String expectedCode) {
        rejectsModel(json, group, errorField, render(group, condition), expectedCode);
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

    /**
     * The typed-rule twin of {@link #rejectsModelInsteadOf(String, String, String, String, String, String)},
     * for a fixture whose error field is a kind the string-condition variant cannot name. The exclusivity is
     * the point: a law claiming one gate fires <em>instead of</em> another is untested by an {@code anyMatch},
     * which passes whether or not the pre-empted gate co-fired.
     */
    static void rejectsModelInsteadOf(String json, Rule rule, String expectedCode, String preemptedCode) {
        assertRejectsInsteadOf(VALIDATOR.check(json, rule.draft()), rule.render(), expectedCode, preemptedCode);
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
        return computationVerdict(json, computation.draft());
    }

    /**
     * As above, on a DRAFT written directly — for a law whose subject is a shape the typed builder REFUSES.
     * Going through {@code Computation.Builder} there would measure our own gate instead of the kernel's,
     * which is the whole question such a law asks.
     */
    static String computationVerdict(String json, DraftComputation draft) {
        ValidationResult r = VALIDATOR.check(json, draft);
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

    /** {@link #orderVerdict} on the Project fixture, whose two nested repeatable levels the others lack. */
    static String projectVerdict(String group, String errorField, String condition) {
        return verdictModel(PROJECT, group, errorField, condition);
    }

    /** {@link #orderVerdict} on a caller-supplied model, for a table whose carrier the fixtures cannot supply. */
    static String verdictModel(String json, String group, String errorField, String condition) {
        ValidationResult r = check(json, group, errorField, condition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(","));
    }

    /**
     * The TYPED entry point — the same verdict for a {@link Condition} built with the kit, rendered relative
     * to {@code group}. Prefer it: the path is written once, checked at the call site, and the row shows the
     * spelling a real consumer would ship.
     *
     * <p><b>Why this exists at all, and why its absence mattered.</b> Until 2026-09-22 a law that wanted to
     * author typed had to hand-roll {@code new DslRenderer(ConditionLanguage.EN, group).render(…)} at every
     * site, so the string overload was strictly cheaper and the estate drifted to text — which is how a law's
     * rows stopped resembling anything a consumer writes. The kit's own harness making the typed path the
     * expensive one is the friction, not the laws that took the cheap one.
     *
     * <p><b>A string row is still legitimate, for two reasons and no others.</b> The typed surface may ERASE
     * the shape — a wrong-kind operand the kit refuses statically because the kernel refuses it, where the row
     * exists to measure that the kernel does. Or the law's subject may be concrete SYNTAX that has no node —
     * a brace depth, a malformed token, a literal form — where an AST cannot carry the variable under test.
     * Say which one, at the row or at the helper that builds it.
     */
    static String verdictModel(String json, String group, String errorField, Condition condition) {
        return verdictModel(json, group, errorField, render(group, condition));
    }

    /**
     * The condition TEXT a typed condition reaches the kernel as, relative to {@code group}.
     *
     * <p>Published so a law that needs to see the text — one whose subject is the rendered spelling, or one
     * proving its own rows carry distinct conditions — does not have to build a renderer to get it. That is
     * the same affordance argument as the {@code Condition} overloads themselves: the guard forbids a private
     * renderer, so every legitimate reason to want one belongs here.
     */
    static String render(String group, Condition condition) {
        return new DslRenderer(ConditionLanguage.EN, group).render(condition);
    }

    /**
     * The condition TEXT with ABSOLUTE paths — the spelling a renderer built with no base group produces.
     *
     * <p>Published because it is what most of the estate actually wants: of the 250 law files that build their
     * own renderer, 210 build the NO-ARG one (measured 2026-09-22). Sending them to {@link #render(String,
     * Condition)} would relativize every path, which is a rewrite of the condition rather than of the spelling
     * that produces it — and a law's text is frequently its subject. This overload lets those files drop their
     * private renderer with byte-identical output, which is the only migration that is genuinely free.
     */
    static String render(Condition condition) {
        return new DslRenderer(ConditionLanguage.EN).render(condition);
    }

    /**
     * The same, for a CALCULATION — a computation's operation rather than a rule's condition.
     *
     * <p>Both arms need it: a law that measures an operation's text was hand-rolling a renderer for exactly the
     * reason a law measuring a condition's was, and leaving one arm out would have sent those files back to
     * {@code new DslRenderer()} the moment they needed it.
     */
    static String render(Calculation calculation) {
        return new DslRenderer(ConditionLanguage.EN).render(calculation);
    }

    /** As {@link #render(Calculation)}, relative to {@code group}. */
    static String render(String group, Calculation calculation) {
        return new DslRenderer(ConditionLanguage.EN, group).render(calculation);
    }

    /**
     * The typed twin of {@link #checkModel} — the raw {@link ValidationResult}, for a law that must see every
     * co-firing class rather than a joined verdict string.
     */
    static ValidationResult checkModel(String json, String group, String errorField, Condition condition) {
        return check(json, group, errorField,
                new DslRenderer(ConditionLanguage.EN, group).render(condition));
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
    /**
     * The same, on a condition the kernel REFUSES — where the control [messageVerdict] asserts cannot hold.
     *
     * One law needs this and only this: A12 answers a rule whose condition does not parse with an extra
     * class when the message text contains a `$`, because it then needs the condition to resolve the
     * parameters. The refused condition is the measurement's subject rather than its confound, so the
     * control has to be the plain-message row of the same table instead of a precondition — which is why
     * this entry point exists rather than a flag on the one above.
     */
    static String messageVerdictOnARefusedCondition(String json, String group, String errorField,
            String condition, String locale, String messageText) {
        ValidationResult verdict = VALIDATOR.check(json, message(group, errorField, condition, locale, messageText));
        return verdict.ok() ? ADMITTED
                : verdict.diagnostics().stream().map(d -> d.code().orElse("(no code: " + d.summary() + ")"))
                        .collect(Collectors.joining(","));
    }

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
