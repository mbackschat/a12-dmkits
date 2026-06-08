package io.github.mbackschat.a12.dm.adapter;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity;

/**
 * ReadResult of the {@code $} outer-iteration correlation in its real-world shape: a <b>field-list
 * quantifier with a {@code Having} filter</b> (e.g. {@code AtLeastOneFieldFilled} over the flatten of
 * {@code Slots*} then {@code Start}, with a {@code Having} clause)
 * whose filter carries a {@code CurrentRepetition($group)} self-exclusion and date {@code $}
 * comparisons — the "no two periods overlap" template that dominates the workspace corpus. This
 * exercises (a) routing a filtered quantifier through the wildcard-scope path (previously the filter
 * was dropped), (b) the {@code CurrentRepetition($)} marker, and (c) date {@code $} threading — all
 * round-tripping render → parse → convert → render with the markers intact.
 */
class OuterCorrelationQuantifierReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** A schedule with repeatable Slots, each a Start/End date — the smallest period-overlap shape. */
    private static String scheduleJson() {
        return DocumentModelDraft.named("schedule").locale("en_US")
                .rootGroup("Schedule", s -> s
                        .group("Slots", g -> g
                                .repeatable(50)
                                .dateField("Start", d -> d.format("yyyy-MM-dd"))
                                .dateField("End", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    @Test
    void filteredQuantifierWithSelfExclusionAndDateCorrelationRoundTrips() {
        BoundModel m = BoundModel.ofJson(scheduleJson());
        // "some OTHER slot starts within this slot's [Start, End]" — the overlap check: a quantifier over
        // the flattened Slots, filtered by a CurrentRepetition($) self-exclusion AND a date $ comparison.
        Condition overlap = Dsl.atLeastOneFieldFilled(
                m.flatten("/Schedule/Slots*/Start").having(
                        Dsl.currentRepetition(m.group("/Schedule/Slots"))
                                .ne(Dsl.currentRepetition(m.group("/Schedule/Slots"), true))
                                .and(m.date("/Schedule/Slots/Start")
                                        .onOrBefore(m.date("/Schedule/Slots/Start").outer()))));
        Condition authored = m.field("/Schedule/Slots/Start").filled().and(overlap);

        Rule rule = m.rule(m.group("/Schedule/Slots")).name("Overlap")
                .errorField(m.field("/Schedule/Slots/Start")).when(authored)
                .code("C").severity(Severity.ERROR).message(en("x")).build();

        Condition converted = converted(KernelAdapter.of(m.add(rule)).read("/Schedule/Slots/Overlap"));
        String rendered = RENDER.render(converted);
        assertEquals(RENDER.render(authored), rendered, "the filtered quantifier + $ markers must round-trip");
        assertTrue(rendered.contains("($"), () -> "CurrentRepetition($) must survive: " + rendered);
        assertTrue(rendered.contains("[$"), () -> "the date $ ref must survive: " + rendered);
    }

    @Test
    void outerRefInsideAPresencePredicateRoundTrips() {
        BoundModel m = BoundModel.ofJson(scheduleJson());
        // a $ correlation inside a NoFieldFilled presence predicate (the discount-overlap tail shape):
        // keep the OTHER slots (self-excluded) for which the current slot has neither bound set.
        Condition authored = m.field("/Schedule/Slots/Start").filled().and(
                Dsl.atLeastOneFieldFilled(m.flatten("/Schedule/Slots*/Start").having(
                        Dsl.currentRepetition(m.group("/Schedule/Slots"))
                                .ne(Dsl.currentRepetition(m.group("/Schedule/Slots"), true))
                                .and(Dsl.noFieldFilled(
                                        m.field("/Schedule/Slots/Start").asOuter(),
                                        m.field("/Schedule/Slots/End").asOuter())))));

        Rule rule = m.rule(m.group("/Schedule/Slots")).name("PresenceCorr")
                .errorField(m.field("/Schedule/Slots/Start")).when(authored)
                .code("C").severity(Severity.ERROR).message(en("x")).build();

        Condition converted = converted(KernelAdapter.of(m.add(rule)).read("/Schedule/Slots/PresenceCorr"));
        String rendered = RENDER.render(converted);
        assertEquals(RENDER.render(authored), rendered, "the $ inside NoFieldFilled must round-trip");
        assertTrue(rendered.contains("NoFieldFilled($"),
                () -> "the $ on a presence-predicate operand must survive read: " + rendered);
    }

    /** Filters (outer, with a repeatable Allowed/Code list) + Responses (inner) — for the value-list $ case. */
    private static String surveyJson() {
        return DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", s -> s
                        .group("Filters", g -> g.repeatable(20)
                                .numberField("Total", n -> { })
                                .group("Allowed", a -> a.repeatable(20).stringField("Code", c -> { })))
                        .group("Responses", g -> g.repeatable(50)
                                .stringField("Tag", c -> { })
                                .numberField("Score", n -> { })))
                .build().toJson();
    }

    @Test
    void outerWildcardInAnEntityValuedValueListRoundTrips() {
        BoundModel m = BoundModel.ofJson(surveyJson());
        // a $-correlated wildcard value-list operand (the Order_DM residual shape): each response's Tag
        // checked against the current (outer) Filter's Allowed codes — value side is $Filters/Allowed*/Code.
        Condition authored = m.field("/Survey/Filters/Total").filled().and(
                m.num("/Survey/Filters/Total").ne(
                        Dsl.sum(m.flatten("/Survey/Responses*/Score").having(
                                Dsl.atLeastOneFieldValueIncludedInValueList(
                                        java.util.List.of(m.flatten("/Survey/Filters/Allowed*/Code").asOuter()),
                                        m.field("/Survey/Responses/Tag"))))));

        Rule rule = m.rule(m.group("/Survey/Filters")).name("ValueListCorr")
                .errorField(m.field("/Survey/Filters/Total")).when(authored)
                .code("C").severity(Severity.ERROR).message(en("x")).build();

        Condition converted = converted(KernelAdapter.of(m.add(rule)).read("/Survey/Filters/ValueListCorr"));
        String rendered = RENDER.render(converted);
        assertEquals(RENDER.render(authored), rendered, "the $ on a value-list operand must round-trip");
        assertTrue(rendered.contains("$") && rendered.contains("Allowed*/Code"),
                () -> "the $ wildcard value-list operand must survive read: " + rendered);
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
