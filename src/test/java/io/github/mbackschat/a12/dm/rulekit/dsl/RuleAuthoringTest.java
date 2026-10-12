package io.github.mbackschat.a12.dm.rulekit.dsl;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.groupFilled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * The {@code Rule} authoring object (F2a, study §4.1): one typed build replaces five hand-written
 * strings — the rule lives in a group by construction, and {@code errorEntityRelPath} is computed
 * from the error-field handle. Completeness and structural-placement mistakes hard-fail at
 * {@code build()}; the error-field-referenced check (which mirrors a kernel judgment) is deferred to
 * the live kernel (F11, {@code Rule#deferredChecks()}) rather than hard-failing on a heuristic.
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
        m = BoundModel.of(ModelIndex.ofSelfContained(modelJson));
    }

    @Test
    void malformedReservedNamesRemainDelegatedToTheKernel() {
        for (String name : java.util.List.of("vk_vk.Bad", "vk_vk" + "x".repeat(96))) {
            var fee = m.num("/Subscription/Addons/MonthlyFee");
            var built = m.rule(m.group("/Subscription/Addons")).name(name)
                    .errorField(fee).when(fee.filled()).code("FORMAL_NAME")
                    .message(en("x"), de("x")).build();
            assertEquals(name, built.name());
        }
    }

    @Test
    void buildsARuleWithAnInterpolatedMessageTheKernelAccepts() {
        // F2a: a $Field.value$ placeholder in the message — typed via Dsl.fieldValue (no-asterisk by
        // construction); the field is referenced in the condition (the kernel's other requirement).
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        String token = Dsl.fieldValue(fee.field());
        assertEquals("$/Subscription/Addons/MonthlyFee.value$", token);
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("FeeCapRule")
                .errorField(fee)
                .when(fee.filled().and(fee.gt(1000)))
                .code("FEE_CAP")
                .severity(Severity.ERROR)
                .message(en("The fee " + token + " exceeds the cap."),
                        de("Die Gebühr " + token + " überschreitet das Limit."))
                .build();

        ValidationResult vr = new RuleValidator().check(modelJson, rule.draft());
        assertTrue(vr.ok(), () -> "kernel should accept the interpolated message, got: " + vr.diagnostics());
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
    void defersAnUnreferencedErrorFieldToTheKernel() {
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        // F11: the error-field-referenced check mirrors a kernel judgment (the covers() heuristic only
        // approximates it), so build() no longer hard-fails — it is deferred to the live kernel.
        Rule rule = m.rule(m.group("/Subscription/Addons"))
                .name("BadRule")
                .errorField(m.str("/Subscription/Addons/Name"))     // error field: Name
                .when(fee.filled().and(fee.le(0)))                  // condition references only MonthlyFee
                .code("BAD")
                .severity(Severity.ERROR)
                .message(en("nope"), de("nein"))
                .build();
        var deferred = rule.deferredChecks();
        assertEquals(1, deferred.size(), () -> "expected the deferred error-field check, got: " + deferred);
        assertTrue(deferred.get(0).summary().contains("error field"),
                () -> "the deferred check should name the problem: " + deferred.get(0).summary());
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
    void rejectsAReservedVkVkRuleName() {
        // The kernel reserves names beginning vk_vk, any letter case (MVK_RULE_NAME_INVALID;
        // KERNEL-FINDINGS "Reserved rule-name prefix") — pre-checked with the code named.
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("VK_VK_FeeRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("RESERVED")
                .severity(Severity.ERROR)
                .message(en("x"), de("x"));
        PrecheckException ex = assertThrows(PrecheckException.class, builder::build);
        assertEquals(java.util.Optional.of("MVK_RULE_NAME_INVALID"), ex.diagnostic().code());
    }

    @Test
    void rejectsDuplicateMessageLocales() {
        // The kernel rejects two messages for one locale (MVK_LANGUAGE_DUPLICATE) — pre-checked,
        // where the old behavior let the coverage check silently pass duplicates through.
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
        var builder = m.rule(m.group("/Subscription/Addons"))
                .name("DupLocaleRule")
                .errorField(fee)
                .when(fee.filled().and(fee.le(0)))
                .code("DUP")
                .severity(Severity.ERROR)
                .message(en("first"), en("second"), de("ok"));
        IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(ex.getMessage().contains("MVK_LANGUAGE_DUPLICATE"), ex::getMessage);
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

}
