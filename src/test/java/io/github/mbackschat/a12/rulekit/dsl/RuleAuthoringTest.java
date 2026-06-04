package io.github.mbackschat.a12.rulekit.dsl;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.groupFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.model.ModelIndex;
import io.github.mbackschat.a12.rulekit.model.ModelLoader;
import io.github.mbackschat.a12.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.rulekit.validate.ValidationResult;

/**
 * The {@code Rule} authoring object (F2a, study §4.1): one typed build replaces five hand-written
 * strings — the rule lives in a group by construction, {@code errorEntityRelPath} is computed from
 * the error-field handle, and the error-field-referenced rule (incl. the verified
 * enclosing-group-counts semantics) is pre-checked at {@code build()} instead of surfacing as
 * {@code MVK_ERROR_FIELD_NOT_REFERENCED} later.
 */
class RuleAuthoringTest {

    private static BoundModel m;
    private static String modelJson;

    @BeforeAll
    static void bind() throws Exception {
        try (InputStream in = RuleAuthoringTest.class.getClassLoader()
                .getResourceAsStream("models/subscription.dm.json")) {
            assertNotNull(in);
            modelJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Reader reader = fixture("models/subscription.dm.json")) {
            m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));
        }
    }

    @Test
    void buildsARuleWithComputedErrorEntityRelPath() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeePositiveRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("FEE_POSITIVE")
                .severity(Severity.ERROR)
                .message(en("The monthly fee must be positive."), de("Die Monatsgebühr muss positiv sein."))
                .build();

        assertEquals("../MonthlyFee", rule.errorEntityRelPath());
        assertEquals(
                "FieldFilled(MonthlyFee) And [MonthlyFee] <= 0",   // fork #9: paths relative to the rule's group
                rule.render());

        ValidationResult vr = new RuleValidator().check(modelJson, rule.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the authored rule, got: " + vr.diagnostics());
    }

    @Test
    void computesDeeperRelPathWhenTheRuleSitsHigher() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        Rule rule = m.rule(m.group("/Subscription"))
                .name("FeeRuleAtRoot")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("FEE_POSITIVE")
                .severity(Severity.WARNING)
                .message(en("Fee should be positive."), de("Die Gebühr sollte positiv sein."))
                .build();

        assertEquals("../Addons/MonthlyFee", rule.errorEntityRelPath());
        ValidationResult vr = new RuleValidator().check(modelJson, rule.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the deeper relPath, got: " + vr.diagnostics());
    }

    @Test
    void acceptsAnEnclosingGroupReferenceAsErrorFieldReference() {
        // Verified kernel semantics: GroupFilled(Group) counts as referencing the error field.
        Rule rule = m.rule(m.group("/Subscription"))
                .name("AddonsPresentRule")
                .errorField(m.num("/Subscription/Addons/MonthlyFee"))
                .when(groupFilled(m.group("/Subscription/Addons")))
                .code("ADDONS_PRESENT")
                .severity(Severity.ERROR)
                .message(en("Add-ons must not be present."), de("Zusatzoptionen dürfen nicht vorhanden sein."))
                .build();

        ValidationResult vr = new RuleValidator().check(modelJson, rule.draft());
        assertTrue(vr.ok(), () -> "enclosing group reference should satisfy the kernel, got: " + vr.diagnostics());
    }

    @Test
    void rejectsAnUnreferencedErrorFieldAtBuildTime() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("BadRule")
                .errorField(m.str("/Subscription/Addons/Name"))     // error field: Name
                .when(fee.filled().and(fee.le(0)))                  // condition references only MonthlyFee
                .code("BAD")
                .severity(Severity.ERROR)
                .message(en("nope"), de("nein"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("error field"),
                () -> "message should name the problem: " + ex.getMessage());
    }

    @Test
    void rejectsAnErrorFieldOutsideTheRuleGroup() {
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("OutsideRule")
                .errorField(m.str("/Subscription/Billing/InvoiceEmail"))
                .when(m.str("/Subscription/Billing/InvoiceEmail").filled())
                .code("OUTSIDE")
                .severity(Severity.ERROR)
                .message(en("nope"), de("nein"));
        assertThrows(IllegalStateException.class, builder::build);
    }

    @Test
    void requiresAMessageForEveryDeclaredLocale() {
        // The model declares en_US + de_DE (header locales are arbitrary — en/de are not special).
        // Missing one is MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING at the kernel — pre-checked here.
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("EnOnlyRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("EN_ONLY")
                .severity(Severity.ERROR)
                .message(en("English only."));
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("de_DE"), ex::getMessage);
    }

    @Test
    void rejectsMessagesForUndeclaredLocales() {
        // Stricter than the kernel (which silently ignores undeclared-locale texts).
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("FrenchRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("FR")
                .severity(Severity.ERROR)
                .message(en("ok"), de("ok"), Dsl.text("fr_FR", "d'accord"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("fr_FR"), ex::getMessage);
    }

    @Test
    void requiresAtLeastOneMessage() {
        // The kernel rejects message-less rules (MVK_ERROR_MESSAGE_MISSING) — pre-checked here.
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("NoMessageRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("NO_MSG")
                .severity(Severity.ERROR);
        assertThrows(IllegalStateException.class, builder::build);
    }

    private static Reader fixture(String classpathLocation) {
        InputStream in = RuleAuthoringTest.class.getClassLoader().getResourceAsStream(classpathLocation);
        assertNotNull(in, "fixture not found: " + classpathLocation);
        return new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
