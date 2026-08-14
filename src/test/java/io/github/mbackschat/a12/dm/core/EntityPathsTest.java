package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The path-containment relation, pinned at the two ends where the obvious spelling is wrong — <b>both of which
 * have shipped here as defects, three days apart, in opposite directions</b>:
 *
 * <ul>
 *   <li>A bare {@code path.startsWith(ancestor)} crosses a segment boundary, making
 *       {@code /Order/BillingContact/Email} "inside" {@code /Order/Billing}. That over-reported a reference that
 *       does not exist ({@code CG16}).</li>
 *   <li>Adding the slash — {@code path.startsWith(ancestor + "/")} — fixes that end and breaks the other: a group
 *       is then not inside <em>itself</em>, which is how {@code group extract} came to refuse a rule referencing
 *       the very group being extracted ({@code CG17}).</li>
 * </ul>
 *
 * <p>Two predicates exist precisely because those are the two answers a caller can want, and the whole defect
 * class is picking the wrong one. Naming them is the fix; this file is the oracle for both.
 */
class EntityPathsTest {

    @Test
    void aPathIsInsideItself() {
        assertTrue(EntityPaths.isInside("/Order/Shipping", "/Order/Shipping"),
                "CG17: the subtree ROOT counts as inside its own subtree — a rule referencing the group being "
                        + "extracted does not escape it, and refusing that named a problem no author can fix");
        assertFalse(EntityPaths.isStrictlyInside("/Order/Shipping", "/Order/Shipping"),
                "…and the strict relation is the one that excludes it, which is why both exist");
    }

    @Test
    void aDescendantIsInsideAtEveryDepth() {
        for (String descendant : List.of("/Order/Shipping/Street", "/Order/Shipping/Region/Code")) {
            assertTrue(EntityPaths.isInside(descendant, "/Order/Shipping"), descendant);
            assertTrue(EntityPaths.isStrictlyInside(descendant, "/Order/Shipping"), descendant);
        }
    }

    /** <b>The CG16 row.</b> The prefix is a prefix of the STRING and not of the PATH. */
    @Test
    void aSegmentBoundarySiblingIsNotInside() {
        assertTrue("/Order/BillingContact/Email".startsWith("/Order/Billing"),
                "the premise: this IS a string prefix, which is why the naive spelling passes review");
        assertFalse(EntityPaths.isInside("/Order/BillingContact/Email", "/Order/Billing"),
                "CG16: comparison happens at a path-segment boundary, so a longer sibling name is not containment");
        assertFalse(EntityPaths.isStrictlyInside("/Order/BillingContact/Email", "/Order/Billing"));
    }

    @Test
    void containmentIsDirectional() {
        assertFalse(EntityPaths.isInside("/Order/Shipping", "/Order/Shipping/Street"),
                "an ancestor is not inside its own descendant");
        assertFalse(EntityPaths.isInside("/Order/Billing/Street", "/Order/Shipping"),
                "unrelated branches are incomparable");
    }

    /**
     * The two predicates differ in exactly one case. Stating it as a law means a future change cannot make them
     * disagree anywhere else without failing here.
     */
    @Test
    void theStrictRelationDiffersOnlyOnEquality() {
        List<String> paths = List.of("/Order", "/Order/Shipping", "/Order/Shipping/Street",
                "/Order/ShippingNotes", "/Order/Billing", "/OrderArchive");
        for (String path : paths) {
            for (String ancestor : paths) {
                assertEquals(path.equals(ancestor) || EntityPaths.isStrictlyInside(path, ancestor),
                        EntityPaths.isInside(path, ancestor),
                        () -> "isInside must be equality OR strict containment: " + path + " vs " + ancestor);
            }
        }
    }

    /**
     * {@code "/"} is a caller-level "whole model" sentinel, NOT an entity path — the root group is {@code /Order}.
     * Pinned so the behaviour is a decision rather than an accident: a caller that means "everything" tests for
     * the sentinel itself (as {@code ModelTreeData} does) instead of expecting this relation to absorb it.
     */
    @Test
    void theRootSentinelIsNotTreatedAsAnAncestor() {
        assertFalse(EntityPaths.isInside("/Order/Shipping", "/"),
                "'/' is not an entity path; a whole-model scope is the caller's sentinel, not a containment fact");
    }
}
