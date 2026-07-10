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

    @Test
    void enumAgreementUsesEnumEquality() {
        // a small 2-enum model — subscription has only one enum field
        String json = io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("policy").locale("en_US")
                .rootGroup("Policy", p -> p
                        .enumField("Tier", e -> e.value("BASIC").value("PREMIUM"))
                        .enumField("DeclaredTier", e -> e.value("BASIC").value("PREMIUM")))
                .build().toJson();
        BoundModel pm = BoundModel.ofJson(json);
        var tier = pm.enumOf("/Policy/Tier");
        var declared = pm.enumOf("/Policy/DeclaredTier");
        var hand = Dsl.allFieldsFilled(tier.field(), declared.field()).and(tier.is(declared))
                .or(Dsl.noFieldFilled(tier.field(), declared.field()));
        assertEquals(new DslRenderer().render(hand), new DslRenderer().render(tier.agreesWith(declared)));
    }

    @Test
    void temporalAgreementUsesTemporalEquality() {
        // D8: agreesWith existed on string/number/enum but not the temporal refs. It uses each ref's
        // own (date/time/date-time) equality underneath, so the null-safe idiom composes there too.
        String json = io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.named("schedule").locale("en_US")
                .rootGroup("Schedule", s -> s
                        .dateField("Start", d -> { }).dateField("Confirmed", d -> { })
                        .timeField("Open", t -> { }).timeField("Requested", t -> { })
                        .dateTimeField("Planned", dt -> { }).dateTimeField("Actual", dt -> { }))
                .build().toJson();
        BoundModel sm = BoundModel.ofJson(json);
        DslRenderer r = new DslRenderer();

        var startD = sm.date("/Schedule/Start");
        var confD = sm.date("/Schedule/Confirmed");
        var handD = Dsl.allFieldsFilled(startD.field(), confD.field()).and(startD.eq(confD))
                .or(Dsl.noFieldFilled(startD.field(), confD.field()));
        assertEquals(r.render(handD), r.render(startD.agreesWith(confD)));

        var openT = sm.time("/Schedule/Open");
        var reqT = sm.time("/Schedule/Requested");
        var handT = Dsl.allFieldsFilled(openT.field(), reqT.field()).and(openT.eq(reqT))
                .or(Dsl.noFieldFilled(openT.field(), reqT.field()));
        assertEquals(r.render(handT), r.render(openT.agreesWith(reqT)));

        var planDt = sm.dateTime("/Schedule/Planned");
        var actDt = sm.dateTime("/Schedule/Actual");
        var handDt = Dsl.allFieldsFilled(planDt.field(), actDt.field()).and(planDt.eq(actDt))
                .or(Dsl.noFieldFilled(planDt.field(), actDt.field()));
        assertEquals(r.render(handDt), r.render(planDt.agreesWith(actDt)));
    }
}
