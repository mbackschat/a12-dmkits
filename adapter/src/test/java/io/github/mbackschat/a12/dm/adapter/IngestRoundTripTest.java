package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * F10 first slice — the read direction round-trips. For each authored condition: rulekit renders it,
 * the kernel parses that text, and the converter rebuilds a rulekit {@link Condition} — which must
 * render identically (render → parse → convert → render is the identity for the covered constructs).
 * Plus the tolerance contract: an unconverted construct is preserved as {@link Ingest.Opaque}, never lost.
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
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
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
                        io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.groupFilled(m.group("/Subscription/Billing")), cases))
                .add(rule(m, "/Subscription/Billing", "EmailPatternRule", m.field("/Subscription/Billing/InvoiceEmail"),
                        m.str("/Subscription/Billing/InvoiceEmail").matches("[^@]+@[^@]+"), cases))
                .add(rule(m, "/Subscription/Billing", "EmailNoPatternRule", m.field("/Subscription/Billing/InvoiceEmail"),
                        m.str("/Subscription/Billing/InvoiceEmail").violates("@@"), cases));

        KernelAdapter adapter = KernelAdapter.of(withRules);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "ingest round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsBooleanConfirmAndInequalityComparisons() {
        // Converter-completeness (surfaced by the kernel-mm ingest-coverage spike): boolean/confirm
        // comparisons and the `!=` direction for string/enum — all already in rulekit's DSL, just not
        // wired into the F10 converter. Real models lean on these heavily (confirm flags everywhere).
        BoundModel m = base();
        var cases = new java.util.LinkedHashMap<String, Condition>();
        BoundModel withRules = m
                .add(rule(m, "/Subscription", "AutoRenewOnRule", m.field("/Subscription/AutoRenew"),
                        m.bool("/Subscription/AutoRenew").isTrue(), cases))
                .add(rule(m, "/Subscription", "AutoRenewOffRule", m.field("/Subscription/AutoRenew"),
                        m.bool("/Subscription/AutoRenew").isFalse(), cases))
                .add(rule(m, "/Subscription", "AutoRenewNotOnRule", m.field("/Subscription/AutoRenew"),
                        m.bool("/Subscription/AutoRenew").isNotTrue(), cases))
                .add(rule(m, "/Subscription", "AutoRenewNotOffRule", m.field("/Subscription/AutoRenew"),
                        m.bool("/Subscription/AutoRenew").isNotFalse(), cases))
                .add(rule(m, "/Subscription", "TermsConfirmedRule", m.field("/Subscription/TermsAccepted"),
                        m.confirm("/Subscription/TermsAccepted").confirmed(), cases))
                .add(rule(m, "/Subscription", "TermsNotConfirmedRule", m.field("/Subscription/TermsAccepted"),
                        m.confirm("/Subscription/TermsAccepted").notConfirmed(), cases))
                .add(rule(m, "/Subscription/Addons", "NameNeRule", m.field("/Subscription/Addons/Name"),
                        m.str("/Subscription/Addons/Name").ne("Banned"), cases))
                .add(rule(m, "/Subscription", "TierNeRule", m.field("/Subscription/Tier"),
                        m.enumOf("/Subscription/Tier").isNot("PRO"), cases));

        KernelAdapter adapter = KernelAdapter.of(withRules);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "comparison round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsValueListMembership() {
        BoundModel m = base();
        var cases = new java.util.LinkedHashMap<String, Condition>();
        BoundModel withRules = m
                .add(rule(m, "/Subscription/Addons", "NameInRule", m.field("/Subscription/Addons/Name"),
                        m.str("/Subscription/Addons/Name").in("Gold", "Silver", "Bronze"), cases))
                .add(rule(m, "/Subscription/Addons", "NameNotInRule", m.field("/Subscription/Addons/Name"),
                        m.str("/Subscription/Addons/Name").notIn("Banned"), cases))
                .add(rule(m, "/Subscription", "TierInRule", m.field("/Subscription/Tier"),
                        m.enumOf("/Subscription/Tier").in("PRO", "ENTERPRISE"), cases))
                .add(rule(m, "/Subscription/Addons", "FeeInRule", m.field("/Subscription/Addons/MonthlyFee"),
                        m.num("/Subscription/Addons/MonthlyFee").in(0, 10, 100), cases));

        KernelAdapter adapter = KernelAdapter.of(withRules);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "value-list round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsIterationAndUniqueness() {
        BoundModel m = base();
        var cases = new java.util.LinkedHashMap<String, Condition>();
        BoundModel withRules = m
                .add(rule(m, "/Subscription/Addons", "RepRule", m.field("/Subscription/Addons/MonthlyFee"),
                        Dsl.currentRepetition(m.group("/Subscription/Addons")).gt(1)
                                .and(m.num("/Subscription/Addons/MonthlyFee").filled()), cases))
                .add(rule(m, "/Subscription", "UniqueNameRule", m.field("/Subscription/Addons/Name"),
                        Dsl.repetitionNotUnique(m.field("/Subscription/Addons/Name")), cases))
                .add(rule(m, "/Subscription/Billing", "FeeDupRule", m.field("/Subscription/Billing/BaseFee"),
                        Dsl.fieldValuesNotUnique(m.field("/Subscription/Billing/BaseFee"),
                                m.field("/Subscription/Billing/EffectiveFee")), cases));

        KernelAdapter adapter = KernelAdapter.of(withRules);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "iteration/uniqueness round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsAggregatesAndHaving() {
        // Aggregates validate in the order model (Items*/Count, Sku); its only declared locale is en_US.
        BoundModel base = BoundModel.ofJson(read("models/order.dm.json"));
        var cases = new java.util.LinkedHashMap<String, Condition>();
        String cust = "/Order/CustomerName";
        Condition sumC = base.field(cust).filled().and(Dsl.sum(base.flatten("/Order/Items*/Count")).gt(100));
        Condition maxC = base.field(cust).filled().and(Dsl.maxValue(base.flatten("/Order/Items*/Count")).gt(50));
        Condition ndvC = base.field(cust).filled()
                .and(Dsl.numberOfDifferentValues(base.flatten("/Order/Items*/Sku")).lt(2));
        Condition havingC = base.field(cust).filled().and(Dsl.sum(
                base.flatten("/Order/Items*/Count").having(base.str("/Order/Items/Sku").eq("A"))).gt(5));
        // field-vs-aggregate: [Quantity] < Sum(Items*/Count Having [Items/Sku] == "RUSH") — the F10 gap lift.
        Condition fieldVsAggC = base.field(cust).filled().and(base.num("/Order/Quantity").lt(
                Dsl.sum(base.flatten("/Order/Items*/Count").having(base.str("/Order/Items/Sku").eq("RUSH")))));
        // aggregate-vs-field (the symmetric direction): Sum(Items*/Count) > [Quantity].
        Condition aggVsFieldC = base.field(cust).filled().and(
                Dsl.sum(base.flatten("/Order/Items*/Count")).gt(base.num("/Order/Quantity").asNumber()));

        BoundModel m = base
                .add(orderRule(base, "SumRule", sumC, cases))
                .add(orderRule(base, "MaxRule", maxC, cases))
                .add(orderRule(base, "NdvRule", ndvC, cases))
                .add(orderRule(base, "HavingRule", havingC, cases))
                .add(orderRule(base, "QuantityCoversRushRule", fieldVsAggC, cases))
                .add(orderRule(base, "SumExceedsQuantityRule", aggVsFieldC, cases));

        KernelAdapter adapter = KernelAdapter.of(m);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "aggregate round-trip mismatch for " + fullName));
    }

    @Test
    void roundTripsDates() {
        BoundModel base = BoundModel.ofJson(read("models/order.dm.json"));
        var cases = new java.util.LinkedHashMap<String, Condition>();
        String cust = "/Order/CustomerName";
        Condition todayC = base.field(cust).filled()
                .and(base.date("/Order/OrderDate").onOrAfter(Dsl.today()));
        Condition beforeC = base.field(cust).filled()
                .and(base.date("/Order/DeliveryDate").before(base.date("/Order/OrderDate")));
        Condition diffC = base.field(cust).filled().and(
                Dsl.differenceInDays(base.date("/Order/OrderDate"), base.date("/Order/DeliveryDate")).gt(7));

        BoundModel m = base
                .add(orderRule(base, "DateTodayRule", todayC, cases))
                .add(orderRule(base, "DateBeforeRule", beforeC, cases))
                .add(orderRule(base, "DateDiffRule", diffC, cases));

        KernelAdapter adapter = KernelAdapter.of(m);
        cases.forEach((fullName, original) ->
                assertEquals(RENDER.render(original), RENDER.render(converted(adapter.ingest(fullName))),
                        () -> "date round-trip mismatch for " + fullName));
    }

    /** A rule in the order model (single declared locale en_US), error field = the referenced CustomerName. */
    private static Rule orderRule(BoundModel m, String name, Condition cond, java.util.Map<String, Condition> sink) {
        Rule r = m.rule(m.group("/Order")).name(name).errorField(m.field("/Order/CustomerName")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
        sink.put("/Order/" + name, cond);
        return r;
    }

    /**
     * A still-unconverted construct — a <b>date {@code $} outer correlation</b>, the un-threaded kind
     * the N8 count-check backstop keeps Opaque (number/string correlations now <em>convert</em> — see
     * {@code OuterCorrelationIngestTest}; the focused backstop is {@code OuterCorrelationBackstopTest}).
     * Needs a repeatable date group, so these two cases use a small timesheet fixture rather than the
     * order model.
     */
    private static final String OPAQUE_CONDITION =
            "FieldFilled(PeriodEnd) And [/Timesheet/PeriodEnd] != "
                    + "MaxValue(Shifts*/ShiftDate Having [$/Timesheet/PeriodEnd] == [Shifts/ShiftDate])";

    @Test
    void preservesAnUnconvertedConstructAsOpaque() {
        // A date $ correlation (un-threaded kind) stays unconverted → preserved opaque, not lost, not corrupt.
        KernelAdapter adapter = KernelAdapter.ofJson(
                timesheetWith(timesheetRule("/Timesheet/OpaqueRule", "OpaqueRule", OPAQUE_CONDITION)));

        Ingest result = adapter.ingest("/Timesheet/OpaqueRule");
        Ingest.Opaque opaque = assertInstanceOf(Ingest.Opaque.class, result,
                () -> "an unconverted construct should ingest as Opaque, got: " + result);
        assertTrue(opaque.reason().toLowerCase().contains("$") || opaque.reason().toLowerCase().contains("outer"),
                () -> "the reason should name the unconverted construct: " + opaque.reason());
        // preserved verbatim: the rule's text survives (re-emit on write-back), $ marker and all
        assertFalse(opaque.canonicalText().isBlank());
        assertTrue(opaque.canonicalText().contains("$"),
                () -> "opaque text should preserve the rule verbatim: " + opaque.canonicalText());
    }

    @Test
    void ingestAllReturnsTypedAndOpaqueOutcomesPerRule() {
        java.util.Map<String, Ingest> all = KernelAdapter.ofJson(timesheetWith(
                timesheetRule("/Timesheet/OkRule", "OkRule", "FieldFilled(PeriodEnd)"),
                timesheetRule("/Timesheet/OpaqueRule", "OpaqueRule", OPAQUE_CONDITION))).ingestAll();
        // a whole model ingests without throwing: supported rules typed, the rest preserved opaque
        assertInstanceOf(Ingest.Converted.class, all.get("/Timesheet/OkRule"));
        assertInstanceOf(Ingest.Opaque.class, all.get("/Timesheet/OpaqueRule"));
    }

    /** A timesheet with a repeatable Shifts group (date field) — the smallest model carrying a date $ correlation. */
    private static String timesheetJson() {
        return DocumentModelDraft.named("timesheet").locale("en_US")
                .rootGroup("Timesheet", t -> t
                        .dateField("PeriodEnd", d -> d.format("yyyy-MM-dd"))
                        .group("Shifts", g -> g
                                .repeatable(50)
                                .dateField("ShiftDate", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    private static String timesheetRule(String id, String name, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{ \"type\": \"Rule\", \"id\": \"" + id + "\", \"name\": \"" + name + "\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../PeriodEnd\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
    }

    /** Inject raw-text rules into the /Timesheet group (the unconverted form can't be built via the typed DSL). */
    private static String timesheetWith(String... rules) {
        String base = timesheetJson();
        int idx = base.indexOf("\"elements\"");
        int br = base.indexOf('[', idx);
        return base.substring(0, br + 1) + "\n" + String.join(",\n", rules) + ",\n" + base.substring(br + 1);
    }

    /** Unwrap a {@link Ingest.Converted}, asserting the ingest converted (not opaque). */
    private static Condition converted(Ingest result) {
        return assertInstanceOf(Ingest.Converted.class, result,
                () -> "expected a converted ingest, got: " + result).condition();
    }

    private static Rule rule(BoundModel m, String group, String name,
            io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef errorField, Condition cond,
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
