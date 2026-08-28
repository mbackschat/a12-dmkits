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
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * F10 read tail — {@code NumberOfDifferentValues} over a <b>custom field type</b> and over a
 * <b>date fragment</b>, the two kinds outside the simple set and the plain date trio.
 *
 * <p><b>Why the read side needs its own file.</b> {@code ConditionConverter} builds the same
 * {@code CountAggregate} record on the way IN, so a kind the compact constructor refuses makes a legal kernel
 * model <em>unreadable</em>, not merely unauthorable — the sharper half of an over-rejection, and the half an
 * authoring test cannot see. {@code TemporalCountAggregateReadTest} is the sibling holding the date trio.
 *
 * <p>Own domain: an archive reconciling where a holding is shelved and when it was last accounted for.
 */
class CustomAndFragmentCountAggregateReadTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    // Built ONCE for the class, not per case: this file shares :adapter:test with timing-sensitive growth
    // floors, and per-case kernel work inflates their medians (CLAUDE.md, "A new test must not perturb
    // timing-sensitive floors that share its task").
    private static final String ARCHIVE_JSON = DocumentModelDraft.named("archive").locale("en_US")
            .rootGroup("Holding", h -> h
                    .field("Reference", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING)
                    .customField("ShelfCode", f -> f.type("SelectionCode"))
                    .customField("StoreCode", f -> f.type("SelectionCode"))
                    .dateFragmentField("AcquiredYear", f -> f.fragmentFormat("yyyy"))
                    .dateFragmentField("AuditedYear", f -> f.fragmentFormat("yyyy")))
            .build().toJson();

    @Test
    void roundTripsACountOfDistinctCustomTypedCodes() {
        assertRoundTrips("ShelfCode", "StoreCode", "DistinctShelvingRule");
    }

    @Test
    void roundTripsACountOfDistinctYearFragments() {
        assertRoundTrips("AcquiredYear", "AuditedYear", "DistinctYearRule");
    }

    @Test
    void bothFormsAreKernelValid() {
        assertKernelValid("NumberOfDifferentValues(ShelfCode, StoreCode) > 1");
        assertKernelValid("NumberOfDifferentValues(AcquiredYear, AuditedYear) > 1");
    }

    private static void assertRoundTrips(String first, String second, String ruleName) {
        BoundModel m = BoundModel.ofJson(ARCHIVE_JSON);
        Condition cond = Dsl.fieldFilled(m.field("/Holding/Reference"))
                .and(Dsl.numberOfDifferentValues(
                        m.field("/Holding/" + first), m.field("/Holding/" + second)).gt(1));

        KernelAdapter adapter = KernelAdapter.of(m.add(rule(m, ruleName, cond)));
        assertEquals(RENDER.render(cond), RENDER.render(converted(adapter.read("/Holding/" + ruleName))));
    }

    private static void assertKernelValid(String aggregate) {
        ValidationResult r = new RuleValidator().check(ARCHIVE_JSON,
                new DraftRule("/Holding", "ProbeRule", "../Reference",
                        "FieldFilled(Reference) And " + aggregate, "C", "ERROR"));
        assertTrue(r.ok(), () -> "expected kernel acceptance of '" + aggregate + "', got: " + r.diagnostics());
    }

    private static Rule rule(BoundModel m, String name, Condition cond) {
        return m.rule(m.group("/Holding")).name(name).errorField(m.field("/Holding/Reference")).when(cond)
                .code("C").severity(Severity.ERROR).message(en("x")).build();
    }

    private static Condition converted(ReadResult result) {
        return assertInstanceOf(ReadResult.Converted.class, result,
                () -> "expected a converted read, got: " + result).condition();
    }
}
