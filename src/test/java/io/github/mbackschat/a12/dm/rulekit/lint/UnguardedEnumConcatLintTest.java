package io.github.mbackschat.a12.dm.rulekit.lint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * An enumeration part of a concatenation contributes nothing when empty, exactly as a text part does (KERNEL-FINDINGS
 * KF530), so the unguarded-concatenation lint names it too, and stays quiet once it is guarded.
 */
class UnguardedEnumConcatLintTest {

    private static final BoundModel ORDER = Fixtures.order();

    private static List<String> flagged(Condition condition) {
        return new Linter().lint(condition).stream()
                .filter(d -> d.code().filter("RK_UNGUARDED_STRING_CONCAT"::equals).isPresent())
                .map(d -> d.summary().contains("Priority") && d.summary().contains("RushLevel") ? "both" : d.summary())
                .toList();
    }

    @Test
    void anUnguardedEnumerationPartIsFlagged() {
        Condition c = ORDER.str("/Order/CustomerName").eq(
                ORDER.enumOf("/Order/Priority").plus(ORDER.enumOf("/Order/RushLevel")));
        assertEquals(List.of("both"), flagged(c));
    }

    @Test
    void aGuardedEnumerationPartIsNot() {
        Condition c = ORDER.allFieldsFilled("/Order/Priority", "/Order/RushLevel").and(ORDER.str("/Order/CustomerName")
                .eq(ORDER.enumOf("/Order/Priority").plus(ORDER.enumOf("/Order/RushLevel"))));
        assertEquals(List.of(), flagged(c));
    }
}
