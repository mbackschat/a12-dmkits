package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A rule built without {@code severity(...)} — the over-rejection the 2026-09-12 audit found, closed.
 *
 * <p>{@code Rule.build()} used to hard-fail {@code RK_INCOMPLETE_RULE} on an unset severity, and the reason
 * given was that A12 requires the member. It does not: an ABSENT {@code severity} in stored DM-JSON is
 * accepted by {@code checkConsistency}, and no spelling of absent/null/empty fires
 * {@code MVK_SEVERITY_MISSING} ([KERNEL-FINDINGS KF291], {@code validate.laws.RuleCodeAndSeverityLawsTest}).
 * So the refusal was a client-side check with no measured basis — the one direction this project forbids.
 *
 * <p><b>The fix is the sibling surface's answer, not a relaxation into nullability.</b> The CLI's published
 * {@code rule add} schema has always had {@code severity} optional with "default ERROR", so the typed builder
 * defaults it the same way: {@code Rule.severity()} stays non-null, nothing downstream changes, and the two
 * authoring surfaces agree. Omitting the member from the emitted model was the other option and is rejected
 * for now because A12's RUNTIME meaning for an absent severity is unmeasured — writing a shape whose meaning
 * nobody has measured is the weaker choice for an authoring surface. Re-open if that is ever measured.
 *
 * <p>The READ path needed checking too and has nothing to break: {@code Rule} is an authoring object built
 * only through its {@code Builder}, and the inbound side carries a rule's severity as a plain {@code String}
 * ({@code modelkit.ElementDraft.RuleDraft}) with no requirement on it, so no legal stored model was ever
 * unreadable through this kit.
 */
class RuleSeverityDefaultTest {

    private static BoundModel m;
    private static String modelJson;

    @BeforeAll
    static void bind() throws Exception {
        try (InputStream in = RuleSeverityDefaultTest.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            assertNotNull(in);
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        m = BoundModel.of(ModelIndex.ofSelfContained(modelJson));
    }

    @Test
    void anUnsetSeverityDefaultsToErrorAndTheKernelTakesTheRule() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeFloorRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("FEE_FLOOR")
                .message(en("The monthly fee must be positive."), de("Die Monatsgebühr muss positiv sein."))
                .build();

        assertEquals(Severity.ERROR, rule.severity(), "an unset severity takes the CLI's published default");
        ValidationResult vr = new RuleValidator().check(modelJson, rule.draft());
        assertTrue(vr.ok(), () -> "the kernel should accept the defaulted rule, got: " + vr.diagnostics());
    }

    /** …and an explicit severity is still the author's, so the default is a fallback and not an override. */
    @Test
    void anExplicitSeverityIsUntouched() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeAdvisoryRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("FEE_ADVISORY")
                .severity(Severity.INFO)
                .message(en("The monthly fee looks low."), de("Die Monatsgebühr wirkt niedrig."))
                .build();

        assertEquals(Severity.INFO, rule.severity());
    }
}
