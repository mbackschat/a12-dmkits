package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * T4 — the shared {@code roles}-merge algorithm (was duplicated verbatim between {@code config modify} and
 * the {@code apply} {@code CONFIG_MODIFY} op): keep the existing roles (order preserved), append the new
 * ones, dedupe.
 */
class RolesMergeTest {

    @Test
    void keepsExistingDedupesAndPreservesOrder() {
        assertEquals("a,b", RolesMerge.merge(null, List.of("a", "b")), "from nothing");
        assertEquals("a,b", RolesMerge.merge("  ", List.of("a", "b")), "blank existing");
        assertEquals("x,a,b", RolesMerge.merge("x", List.of("a", "b")), "existing first, then the new");
        assertEquals("x,a", RolesMerge.merge("x, a ", List.of("a")), "existing is split+trimmed, the re-add dedupes");
        assertEquals("x", RolesMerge.merge("x", List.of()), "no new roles leaves the set as-is");
    }
}
