package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Null-safe field agreement — {@code a.agreesWith(b)} = "both filled and equal, OR both empty". The
 * single most repeated idiom in real field-mirroring rules (study §3.4a/§3.6); shipping it as a ref
 * combinator collapses {@code (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b)} to one call.
 */
class FieldAgreementTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void stringAgreementRendersTheNullSafeEqualityIdiom() {
        String name = "/Subscription/Addons/Name";
        String email = "/Subscription/Billing/InvoiceEmail";
        assertEquals(
                "(AllFieldsFilled(" + name + ", " + email + ") And [" + name + "] == [" + email + "])"
                        + " Or NoFieldFilled(" + name + ", " + email + ")",
                new DslRenderer().render(m.str(name).agreesWith(m.str(email))));
    }

    @Test
    void agreementEqualsTheHandComposedForm() {
        var a = m.str("/Subscription/Addons/Name");
        var b = m.str("/Subscription/Billing/InvoiceEmail");
        var hand = Dsl.allFieldsFilled(a.field(), b.field()).and(a.eq(b))
                .or(Dsl.noFieldFilled(a.field(), b.field()));
        assertEquals(new DslRenderer().render(hand), new DslRenderer().render(a.agreesWith(b)));
    }

    @Test
    void numberAgreementUsesNumericEquality() {
        var fee = m.num("/Subscription/Addons/MonthlyFee");   // scale 2
        var base = m.num("/Subscription/Billing/BaseFee");    // scale 2 — eq(ref) gate satisfied
        var hand = Dsl.allFieldsFilled(fee.field(), base.field()).and(fee.eq(base))
                .or(Dsl.noFieldFilled(fee.field(), base.field()));
        assertEquals(new DslRenderer().render(hand), new DslRenderer().render(fee.agreesWith(base)));
    }
}
