package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * Custom-field-type validity (item 10, KERNEL-FINDINGS): {@code Valid(field, "Type")} /
 * {@code Invalid(field, "Type")} — the kernel's {@code IPredefinedTypeValidityCondition}, distinct from
 * the date-construct {@code Valid(Date(...))} overload. The type name is opaque at model-check time (the
 * kernel accepts any string), so this is verified at the {@code checkConsistency} tier only.
 */
class CustomTypeValidityConditionTest {

    private static final BoundModel m = Fixtures.subscription();
    private static final String EMAIL = "/Subscription/Billing/InvoiceEmail";

    @Test
    void rendersCustomTypeValidityBilingually() {
        Condition valid = Dsl.validType(m.field(EMAIL), "EMail");
        assertEquals("Valid(/Subscription/Billing/InvoiceEmail, \"EMail\")", new DslRenderer().render(valid));
        assertEquals("Gueltig(/Subscription/Billing/InvoiceEmail, \"EMail\")",
                new DslRenderer(ConditionLanguage.DE).render(valid));

        Condition invalid = Dsl.invalidType(m.field(EMAIL), "EMail");
        assertEquals("Invalid(/Subscription/Billing/InvoiceEmail, \"EMail\")", new DslRenderer().render(invalid));
        assertEquals("Ungueltig(/Subscription/Billing/InvoiceEmail, \"EMail\")",
                new DslRenderer(ConditionLanguage.DE).render(invalid));
    }

    @Test
    void rejectsANonStringNonEnumField() {
        // operand 1 must be STRING or ENUMERATION (kernel rule) — a number field is caught at the handle
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.validType(m.field("/Subscription/Billing/BaseFee"), "EMail"));
    }

    @Test
    void rejectsABlankTypeName() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.validType(m.field(EMAIL), "  "));
    }

    @Test
    void theKernelAcceptsACustomTypeValidityRule() {
        // F4: the real kernel's checkConsistency accepts the rule (the type name is not validated here)
        BoundModel mw = BoundModel.ofJson(Fixtures.subscriptionJson());
        Rule rule = mw.rule(mw.group("/Subscription/Billing"))
                .name("EmailFormatRule")
                .errorField(mw.field(EMAIL))
                .when(mw.field(EMAIL).filled().and(Dsl.invalidType(mw.field(EMAIL), "EMail")))
                .code("EMAIL_FORMAT").severity(Severity.ERROR)
                .message(Dsl.en("Invoice email is not a valid address."),
                        Dsl.de("Rechnungs-E-Mail ist keine gültige Adresse.")).build();
        assertTrue(new RuleValidator().check(mw.toJson(), rule.draft()).ok(),
                () -> "the kernel should accept Valid/Invalid(field, \"Type\"): "
                        + new RuleValidator().check(mw.toJson(), rule.draft()).diagnostics());
    }
}
