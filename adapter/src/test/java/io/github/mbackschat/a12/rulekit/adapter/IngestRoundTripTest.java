package io.github.mbackschat.a12.rulekit.adapter;

import static io.github.mbackschat.a12.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.rulekit.dsl.Condition;
import io.github.mbackschat.a12.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.rulekit.dsl.Rule;
import io.github.mbackschat.a12.rulekit.dsl.Severity;

/**
 * F10 first slice — the read direction round-trips. For each authored condition: rulekit renders it,
 * the kernel parses that text, and the converter rebuilds a rulekit {@link Condition} — which must
 * render identically (render → parse → convert → render is the identity for the covered constructs).
 * Plus the fail-fast contract: an unconverted construct raises {@link UnsupportedConstructException}.
 */
class IngestRoundTripTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    private static BoundModel base() {
        return BoundModel.ofJson(read("models/subscription.dm.json"));
    }

    @Test
    void roundTripsTheFirstSliceConstructs() {
        BoundModel m = base();
        NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");

        // (rule full name → the condition it was authored from)
        var cases = new java.util.LinkedHashMap<String, Condition>();
        BoundModel withRules = m
                .add(rule(m, "/Subscription", "FilledRule", m.field("/Subscription/Tier"),
                        m.field("/Subscription/Tier").filled(), cases))
                .add(rule(m, "/Subscription", "NotFilledRule", m.field("/Subscription/Tier"),
                        m.field("/Subscription/Tier").notFilled(), cases))
                .add(rule(m, "/Subscription/Addons", "FeeCapRule", fee.field(),
                        fee.gt(1000), cases))
                .add(rule(m, "/Subscription/Addons", "FeeRangeRule", fee.field(),
                        fee.filled().and(fee.gt(0)), cases))
                .add(rule(m, "/Subscription", "TierEnumRule", m.field("/Subscription/Tier"),
                        m.enumOf("/Subscription/Tier").is("PRO"), cases))
                .add(rule(m, "/Subscription/Addons", "NameRule", m.field("/Subscription/Addons/Name"),
                        m.str("/Subscription/Addons/Name").eq("Premium"), cases));

        KernelAdapter adapter = KernelAdapter.of(withRules);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(adapter.ingest(fullName)),
                        () -> "ingest round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsQuantifiersGroupsAndPatterns() {
        BoundModel m = base();
        var cases = new java.util.LinkedHashMap<String, Condition>();
        BoundModel withRules = m
                .add(rule(m, "/Subscription/Billing", "AllFeesRule", m.field("/Subscription/Billing/BaseFee"),
                        m.allFieldsFilled("/Subscription/Billing/BaseFee", "/Subscription/Billing/EffectiveFee"), cases))
                .add(rule(m, "/Subscription/Billing", "OneFeeRule", m.field("/Subscription/Billing/BaseFee"),
                        m.notExactlyOneFieldFilled("/Subscription/Billing/BaseFee", "/Subscription/Billing/EffectiveFee"), cases))
                .add(rule(m, "/Subscription", "BillingPresentRule", m.field("/Subscription/Billing/InvoiceEmail"),
                        io.github.mbackschat.a12.rulekit.dsl.Dsl.groupFilled(m.group("/Subscription/Billing")), cases))
                .add(rule(m, "/Subscription/Billing", "EmailPatternRule", m.field("/Subscription/Billing/InvoiceEmail"),
                        m.str("/Subscription/Billing/InvoiceEmail").matches("[^@]+@[^@]+"), cases))
                .add(rule(m, "/Subscription/Billing", "EmailNoPatternRule", m.field("/Subscription/Billing/InvoiceEmail"),
                        m.str("/Subscription/Billing/InvoiceEmail").violates("@@"), cases));

        KernelAdapter adapter = KernelAdapter.of(withRules);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(adapter.ingest(fullName)),
                        () -> "ingest round-trip mismatch for " + fullName));
    }

    @Test
    void failsFastOnAnUnconvertedConstruct() {
        BoundModel m = base();
        var ignore = new java.util.LinkedHashMap<String, Condition>();
        // A value-list membership condition is not in the first slice → fail-fast, never silent loss.
        BoundModel withRule = m.add(rule(m, "/Subscription/Addons", "NameListRule",
                m.field("/Subscription/Addons/Name"),
                m.str("/Subscription/Addons/Name").in("Gold", "Silver"), ignore));
        KernelAdapter adapter = KernelAdapter.of(withRule);

        UnsupportedConstructException ex = assertThrows(UnsupportedConstructException.class,
                () -> adapter.ingest("/Subscription/Addons/NameListRule"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("kernel node:"),
                () -> "the fail-fast message should name the unconverted construct: " + ex.getMessage());
    }

    private static Rule rule(BoundModel m, String group, String name,
            io.github.mbackschat.a12.rulekit.dsl.FieldRef errorField, Condition cond,
            java.util.Map<String, Condition> sink) {
        Rule r = m.rule(m.group(group)).name(name).errorField(errorField).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x"), de("y")).build();
        sink.put(group + "/" + name, cond);
        return r;
    }

    private static String read(String path) {
        try (InputStream in = IngestRoundTripTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
