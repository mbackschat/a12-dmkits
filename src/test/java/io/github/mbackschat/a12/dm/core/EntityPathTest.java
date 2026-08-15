package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link EntityPath} — the containment relation as a <b>representation</b> rather than a discipline (DG37).
 *
 * <p><b>What this type changes.</b> {@code EntityPaths} already centralized the relation, and a census fails the
 * build on a re-introduced hand-spelling. That is correct and it holds, but it is a rule a future call site must
 * remember to obey; the census exists precisely because that memory is unreliable, and it has a blind spot it
 * admits to — a naive {@code startsWith(ancestor)} with no separator is indistinguishable from any other string
 * prefix test. A path parsed once into segments has no text-prefix operation available to get wrong, so a call
 * site holding an {@code EntityPath} cannot spell either historical defect at all.
 *
 * <p><b>Why the parse is deliberately permissive.</b> This slice changes the representation, not the contract.
 * {@code EntityPaths} never rejected anything — it compared strings — so a validating parse here would be a new
 * client-side refusal with no measured basis, and it would fire on the READ path too. {@link #theSplitIsFaithful}
 * pins the three inputs where a convenient split would silently diverge from the relation being replaced.
 */
class EntityPathTest {

    // ── the two defects that produced this type, now unrepresentable rather than merely avoided ─────

    /** CG16: a string prefix crosses a segment boundary; a path prefix does not. */
    @Test
    void aSiblingWhoseNameExtendsTheAncestorsIsNotInsideIt() {
        EntityPath email = EntityPath.of("/Order/BillingContact/Email");
        EntityPath billing = EntityPath.of("/Order/Billing");

        assertFalse(email.isStrictlyInside(billing), "'BillingContact' is not the segment 'Billing'");
        assertFalse(email.isInside(billing), "…and the inclusive relation must not rescue it either");
    }

    /** CG17: the other end — appending the separator excludes a subtree root from its own subtree. */
    @Test
    void aPathIsInsideItselfButNotStrictlyInsideItself() {
        EntityPath shipping = EntityPath.of("/Order/Shipping");

        assertTrue(shipping.isInside(shipping), "a group is within its own subtree — the safety-gate question");
        assertFalse(shipping.isStrictlyInside(shipping), "…but it is not one of its own descendants");
    }

    @Test
    void anOrdinaryDescendantIsInsideBothWays() {
        EntityPath street = EntityPath.of("/Order/Shipping/Street");
        EntityPath shipping = EntityPath.of("/Order/Shipping");

        assertTrue(street.isStrictlyInside(shipping));
        assertTrue(street.isInside(shipping));
        assertFalse(shipping.isStrictlyInside(street), "the relation is directional");
    }

    @Test
    void aDeeperDescendantIsStillInside() {
        assertTrue(EntityPath.of("/A/B/C/D").isStrictlyInside(EntityPath.of("/A/B")));
    }

    @Test
    void disjointBranchesAreUnrelated() {
        assertFalse(EntityPath.of("/Order/Shipping/Street").isInside(EntityPath.of("/Order/Billing")));
        assertFalse(EntityPath.of("/Order/Billing").isInside(EntityPath.of("/Order/Shipping/Street")));
    }

    // ── behaviour preservation, which is this slice's whole contract ────────────────────────────────

    /**
     * The three inputs where a convenient split diverges from the string relation this type replaces.
     *
     * <p>{@code "".split("/")} and {@code "/".split("/")} both yield a single empty segment in Java, which would
     * make {@code "/"} behave like {@code ""} and report every absolute path as inside it — where the string
     * relation reported {@code false}, because {@code "/Order"} does not start with {@code "//"}. Keeping the
     * trailing empty segments is what preserves it, and the sentinel matters: {@code EntityPaths}' own contract
     * notes that {@code "/"} reaches it only as a caller-level "whole model" marker.
     */
    @Test
    void theSplitIsFaithful() {
        assertEquals(List.of("", "Order"), EntityPath.of("/Order").segments());
        assertEquals(List.of("", ""), EntityPath.of("/").segments(), "the root sentinel keeps both empties");
        assertEquals(List.of(""), EntityPath.of("").segments());
        assertEquals(List.of("", "Order", ""), EntityPath.of("/Order/").segments(), "a trailing slash is a segment");

        assertFalse(EntityPath.of("/Order").isStrictlyInside(EntityPath.of("/")),
                "the string relation said false here ('/Order' does not start with '//') — preserve it");
        assertTrue(EntityPath.of("/Order").isStrictlyInside(EntityPath.of("")),
                "…and true here ('/Order' starts with '/') — preserve that too");
    }

    /**
     * The exhaustive equivalence, run over a cross-product rather than sampled: for every ordered pair of these
     * inputs the new relation must agree with the exact expression it replaces. This is what makes converting
     * 26 call sites a representation change instead of a behaviour change.
     */
    @Test
    void everyPairAgreesWithTheStringRelationItReplaces() {
        List<String> inputs = Arrays.asList(
                "", "/", "/Order", "/Order/", "/Order/Billing", "/Order/BillingContact",
                "/Order/BillingContact/Email", "/Order/Shipping", "/Order/Shipping/Street",
                "/A/B/C/D", "/Items*", "/Items*/Amount", "//Order");

        for (String path : inputs) {
            for (String ancestor : inputs) {
                boolean expectedStrict = path.startsWith(ancestor + "/");
                boolean expectedInclusive = path.equals(ancestor) || expectedStrict;

                assertEquals(expectedStrict,
                        EntityPath.of(path).isStrictlyInside(EntityPath.of(ancestor)),
                        () -> "isStrictlyInside(\"" + path + "\", \"" + ancestor + "\")");
                assertEquals(expectedInclusive,
                        EntityPath.of(path).isInside(EntityPath.of(ancestor)),
                        () -> "isInside(\"" + path + "\", \"" + ancestor + "\")");
            }
        }
    }

    /** A starred operand path is an ordinary path here — the type refuses nothing the relation used to accept. */
    @Test
    void aStarredSegmentIsAnOrdinarySegment() {
        assertTrue(EntityPath.of("/Items*/Amount").isStrictlyInside(EntityPath.of("/Items*")));
        assertFalse(EntityPath.of("/Items*/Amount").isStrictlyInside(EntityPath.of("/Items")),
                "'Items*' and 'Items' are different segments, exactly as the string relation had it");
    }

    @Test
    void theCanonicalStringRoundTrips() {
        for (String path : List.of("/Order", "/Order/Shipping/Street", "/", "", "/Items*/Amount")) {
            assertEquals(path, EntityPath.of(path).value(), "value() must return what of() was given");
        }
    }

    @Test
    void equalityIsByValueSoPathsWorkAsMapKeysAndInSets() {
        assertEquals(EntityPath.of("/Order/Shipping"), EntityPath.of("/Order/Shipping"));
        assertEquals(EntityPath.of("/Order/Shipping").hashCode(), EntityPath.of("/Order/Shipping").hashCode());
    }
}
