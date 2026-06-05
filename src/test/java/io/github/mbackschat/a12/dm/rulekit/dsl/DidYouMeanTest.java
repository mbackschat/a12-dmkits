package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * F4a "did you mean" — model-index-backed nearest-name suggestions on the fail-fast handle errors,
 * so an LLM that fumbles a path or an enum value gets pointed at the right one.
 */
class DidYouMeanTest {

    private static final BoundModel m = Fixtures.subscription();

    @Test
    void aPathTypoSuggestsTheNearestPath() {
        var ex = assertThrows(IllegalArgumentException.class, () -> m.field("/Subscription/Teir"));
        assertTrue(ex.getMessage().contains("did you mean"), ex.getMessage());
        assertTrue(ex.getMessage().contains("/Subscription/Tier"), ex.getMessage());
    }

    @Test
    void anEnumValueTypoSuggestsTheNearestValue() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> m.enumOf("/Subscription/Tier").is("PROO"));
        assertTrue(ex.getMessage().contains("did you mean"), ex.getMessage());
        assertTrue(ex.getMessage().contains("PRO"), ex.getMessage());
    }

    @Test
    void anUnrelatedNameGivesNoSuggestion() {
        // Far from any path → no misleading "did you mean".
        var ex = assertThrows(IllegalArgumentException.class, () -> m.field("/Totally/Unrelated/Xyz"));
        assertFalse(ex.getMessage().contains("did you mean"), ex.getMessage());
    }
}
