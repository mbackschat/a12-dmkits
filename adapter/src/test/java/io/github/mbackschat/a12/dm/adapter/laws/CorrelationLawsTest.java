package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * Behavioral law — the <b>{@code $} outer-iteration correlation</b> (KERNEL-SEMANTICS §9, D13). The
 * structural tests prove a {@code $} rule round-trips; this proves it <em>means</em> the right thing on
 * data. Two facets, the second the load-bearing one:
 *
 * <ul>
 *   <li>a filter that keeps the rows whose value equals the <em>outer</em> row's value
 *       ({@code [Items/Count] == [$Items/Count]}) genuinely correlates inner-to-outer: with a
 *       {@code CurrentRepetition} self-exclusion it fires exactly on the rows that have a <em>different</em>
 *       row sharing their value, and nowhere when all values are distinct;</li>
 *   <li><b>the {@code CurrentRepetition(G) != CurrentRepetition($G)} self-exclusion is load-bearing</b>:
 *       drop it and every row trivially matches <em>itself</em> (inner rep == outer rep), so the rule
 *       fires on every row even when all values are distinct — the classic correlation bug the
 *       self-exclusion exists to prevent.</li>
 * </ul>
 *
 * <p>Conditions are authored through the typed DSL (so the {@code $}/{@code CurrentRepetition($)} syntax
 * is exactly what the kernel accepts) and evaluated over a real multi-row document via {@link RuntimeLaws}.
 */
class CorrelationLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final BoundModel M = BoundModel.ofJson(ORDER);
    
    private static final String SCHEDULE = schedule();
    private static final BoundModel SCHED = BoundModel.ofJson(SCHEDULE);

    /** A schedule with repeatable Slots, each a Start date — for the chronological-order (predecessor) law. */
    private static String schedule() {
        return DocumentModelDraft.named("schedule").locale("en_US")
                .rootGroup("Schedule", s -> s
                        .group("Slots", g -> g.repeatable(50)
                                .dateField("Start", d -> d.format("yyyy-MM-dd"))))
                .build().toJson();
    }

    /** "another Item row shares this row's Count" — correlated, self-excluded via CurrentRepetition($). */
    private static String duplicateCountWithSelfExclusion() {
        // FieldFilled(Count) guards the iteration (the error field is in the repeatable Items — else
        // MVK_NEG_CONDITION_IN_ITERATION: the rule could fire on an unfilled row).
        Condition c = M.field("/Order/Items/Count").filled().and(
                Dsl.atLeastOneFieldFilled(M.flatten("/Order/Items*/Count").having(
                        Dsl.currentRepetition(M.group("/Order/Items"))
                                .ne(Dsl.currentRepetition(M.group("/Order/Items"), true))
                                .and(M.num("/Order/Items/Count").eq(M.num("/Order/Items/Count").outer())))));
        return RuntimeLaws.render(c);
    }

    /** Same, but WITHOUT the self-exclusion — every row matches itself. */
    private static String duplicateCountNoSelfExclusion() {
        Condition c = M.field("/Order/Items/Count").filled().and(
                Dsl.atLeastOneFieldFilled(M.flatten("/Order/Items*/Count").having(
                        M.num("/Order/Items/Count").eq(M.num("/Order/Items/Count").outer()))));
        return RuntimeLaws.render(c);
    }

    /** Items rows with the given Count values; Sku is the (unique) index field, auto A, B, C, … */
    private static RuntimeLaws.Doc counts(String... values) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Order", 1).field("/Order/CustomerName", "Acme", 1, 1);
        for (int i = 0; i < values.length; i++) {
            int r = i + 1;
            d.group("/Order/Items", 1, r).field("/Order/Items/Sku", String.valueOf((char) ('A' + i)), 1, r, 1)
                    .field("/Order/Items/Count", values[i], 1, r, 1);
        }
        return d;
    }

    @Test
    void outerCorrelationDetectsACrossRowMatchOnTheRightRows() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "Corr", "../Items/Count", "CORR",
                duplicateCountWithSelfExclusion());

        // Counts 5, 5, 9 → rows 1 and 2 each have a *different* row sharing their Count → fire on both
        List<RuntimeLaws.Msg> fired = RuntimeLaws.messages(model, counts("5", "5", "9")).stream()
                .filter(m -> m.code().equals("CORR")).toList();
        assertEquals(2, fired.size(), () -> "fires on each row with a correlated duplicate, got: " + fired);
        assertTrue(fired.stream().anyMatch(m -> m.errorField().contains("[1, 1, 1]")), () -> "row 1: " + fired);
        assertTrue(fired.stream().anyMatch(m -> m.errorField().contains("[1, 2, 1]")), () -> "row 2: " + fired);

        // all distinct → no row has a different row sharing its Count → fires nowhere
        assertFalse(RuntimeLaws.fires(model, "CORR", counts("5", "6", "9")),
                "with the self-exclusion, distinct values correlate to nothing");
    }

    /**
     * "A slot is out of order if an EARLIER slot starts on or after it" — the predecessor pattern from the
     * kernel BA doc's reviewed "holidays not in chronological order" example, transferred to a schedule
     * domain: {@code MaxValue} over the flatten of {@code Slots*} then {@code Start}, with a
     * {@code Having CurrentRepetition(Slots) < CurrentRepetition($Slots)} predecessor filter, {@code >= [Start]}.
     */
    private static String chronologicalOrderViolation() {
        Condition c = SCHED.field("/Schedule/Slots/Start").filled().and(
                Dsl.latestOf(SCHED.flatten("/Schedule/Slots*/Start").having(
                        Dsl.currentRepetition(SCHED.group("/Schedule/Slots"))
                                .lt(Dsl.currentRepetition(SCHED.group("/Schedule/Slots"), true))))
                        .onOrAfter(SCHED.date("/Schedule/Slots/Start")));
        return RuntimeLaws.render(c);
    }

    /** Schedule with the given Slot Start dates (ISO), one row each. */
    private static RuntimeLaws.Doc slots(String... isoDates) {
        RuntimeLaws.Doc d = RuntimeLaws.doc().group("/Schedule", 1);
        for (int i = 0; i < isoDates.length; i++) {
            int r = i + 1;
            d.group("/Schedule/Slots", 1, r).field("/Schedule/Slots/Start", isoDates[i], 1, r, 1);
        }
        return d;
    }

    @Test
    void chronologicalOrderViaPredecessorsFiresOnTheOutOfOrderRow() {
        String model = RuntimeLaws.withRule(SCHEDULE, "/Schedule", "Chrono", "../Slots/Start", "CHRONO",
                chronologicalOrderViolation());

        // in chronological order → no slot has an earlier slot starting on/after it → fires nowhere
        assertFalse(RuntimeLaws.fires(model, "CHRONO", slots("2024-01-01", "2024-01-02", "2024-01-03")),
                "ascending dates correlate to no out-of-order predecessor");

        // slot 3 starts 2024-01-02, but its predecessor (slot 2) starts 2024-01-03 → out of order, fires on slot 3
        List<RuntimeLaws.Msg> fired = RuntimeLaws.messages(model, slots("2024-01-01", "2024-01-03", "2024-01-02"))
                .stream().filter(m -> m.code().equals("CHRONO")).toList();
        assertEquals(1, fired.size(), () -> "fires only on the out-of-order row, got: " + fired);
        assertTrue(fired.get(0).errorField().contains("[1, 3, 1]"), () -> "the third slot: " + fired);
    }

    @Test
    void theCurrentRepetitionSelfExclusionIsLoadBearing() {
        String guarded = RuntimeLaws.withRule(ORDER, "/Order", "Corr", "../Items/Count", "CORR",
                duplicateCountWithSelfExclusion());
        String unguarded = RuntimeLaws.withRule(ORDER, "/Order", "Corr", "../Items/Count", "CORR",
                duplicateCountNoSelfExclusion());

        // distinct Counts: guarded fires nowhere; UNGUARDED fires on every row (each matches itself)
        assertFalse(RuntimeLaws.fires(guarded, "CORR", counts("5", "6", "9")),
                "guarded: distinct values fire nowhere");
        assertEquals(3, RuntimeLaws.messages(unguarded, counts("5", "6", "9")).stream()
                        .filter(m -> m.code().equals("CORR")).count(),
                "unguarded: every row trivially matches itself via the $ ref — the bug the self-exclusion prevents");
    }
}
