package io.github.mbackschat.a12.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.rulekit.Fixtures;

/**
 * F8 #2 — the reviewable diff of a modify-by-re-express. Renders before/after, flags whether they
 * differ, and carries lossiness notes (study finding 8: disclose what re-rendering drops).
 */
class RenderedDiffTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void reportsAChangeBetweenTwoConditions() {
        Condition before = m.num("/Subscription/Addons/MonthlyFee").gt(1000);
        Condition after = before.and(m.num("/Subscription/Addons/MonthlyFee").filled());

        RenderedDiff diff = RenderedDiff.between(before, after);

        assertTrue(diff.changed());
        assertNotEquals(diff.before(), diff.after());
        assertTrue(diff.unified().contains("- ") && diff.unified().contains("+ "), diff.unified());
    }

    @Test
    void reportsNoChangeForAnIdenticalRe_render() {
        Condition c = m.num("/Subscription/Addons/MonthlyFee").gt(1000);
        RenderedDiff diff = RenderedDiff.between(c, c);
        assertFalse(diff.changed());
    }

    @Test
    void carriesLossinessNotesIntoTheJson() {
        RenderedDiff diff = RenderedDiff.between("[X] > 1", "[X] > 2", List.of("1 comment dropped"));
        assertTrue(diff.changed());
        assertTrue(diff.toJson().contains("1 comment dropped"), diff.toJson());
        assertTrue(diff.unified().contains("! 1 comment dropped"), diff.unified());
    }
}
