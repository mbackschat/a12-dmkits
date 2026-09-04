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

    // ── relativize: the stored-reference direction (the inverse of ModelReader.resolveRelPath) ──────

    /**
     * The two forms A12 actually stores, both pinned as exact text because they are written into DM-JSON.
     *
     * <p>The first is what every computation carried before a declaring group could differ from the computed
     * field's parent — {@code "../" + fieldName} — so it is the byte-identity the default path must keep. The
     * second is the cross-group walk measured to be kernel-accepted in
     * {@code CrossGroupConstantComputeScopeDiffTest}, which is why the general algebra had to produce it rather
     * than an absolute path.
     */
    @Test
    void relativizeProducesTheStoredComputationReferenceForms() {
        assertEquals("../Total",
                EntityPath.of("/Order/TotalComp").relativize(EntityPath.of("/Order/Total")),
                "a computation beside its computed field — the pre-existing '../<Field>' form");
        assertEquals("../../Store/StockLevel",
                EntityPath.of("/Rules/StockComp").relativize(EntityPath.of("/Store/StockLevel")),
                "declared under one root group, computing into another");
        assertEquals("../../Store/StockLevel",
                EntityPath.of("/Probe/Rules/StockComp").relativize(EntityPath.of("/Probe/Store/StockLevel")),
                "…and the same walk between two sibling subtrees");
    }

    /** A target below the base descends without climbing, and an identical path is the empty reference. */
    @Test
    void relativizeDescendsWithoutClimbingAndIsEmptyForItself() {
        assertEquals("B/C", EntityPath.of("/A").relativize(EntityPath.of("/A/B/C")));
        assertEquals("..", EntityPath.of("/A/B").relativize(EntityPath.of("/A")));
        assertEquals("", EntityPath.of("/A/B").relativize(EntityPath.of("/A/B")));
    }

    /**
     * The round trip is NOT total over arbitrary text, and the boundary is pinned rather than assumed away.
     *
     * <p>A target carrying an empty segment relativizes to a reference the resolver reads as the path without
     * it, so relativize+resolve is not the identity there. Both gates that stand in front of the production
     * call sites refuse such a path — {@code ModelPaths.isAbsoluteEntityPath} on the CLI spec side, and A12's
     * own {@code ElementPathUtils.splitAbsPath} contract — which is why the javadoc states the limit instead of
     * claiming a totality the next test measures over well-formed paths only.
     */
    @Test
    void anEmptySegmentIsWhereTheRoundTripStops() {
        assertEquals("../", EntityPath.of("/A/B").relativize(EntityPath.of("/A/")));
        assertEquals("/A", ModelReader.resolveRelPath("/A/B", "../"),
                "the resolver drops the empty segment, so the pair is not an identity");
    }

    /**
     * The contract, executed rather than asserted: over every ordered pair of these paths, resolving the
     * relativized reference against the base must land back on the target. {@code ModelReader.resolveRelPath} is
     * the oracle because it is the code that actually reads a stored {@code computedFieldRelPath} back, so a
     * relativize it cannot invert would write a reference the reader resolves elsewhere.
     *
     * <p>The inputs are deliberately all WELL-FORMED absolute paths — that is the domain the gates admit, and
     * {@link #anEmptySegmentIsWhereTheRoundTripStops} pins what lies outside it.
     */
    @Test
    void everyPairRoundTripsThroughTheResolverThatReadsItBack() {
        for (String base : ROUND_TRIP_INPUTS) {
            for (String target : ROUND_TRIP_INPUTS) {
                String relative = EntityPath.of(base).relativize(EntityPath.of(target));
                assertEquals(target, ModelReader.resolveRelPath(base, relative),
                        () -> "relativize(\"" + base + "\", \"" + target + "\") = \"" + relative + "\"");
            }
        }
    }

    private static final List<String> ROUND_TRIP_INPUTS = Arrays.asList(
            "/Order", "/Order/Total", "/Order/TotalComp", "/Order/Billing/Fee", "/Order/Shipping/Street",
            "/Rules/StockComp", "/Store/StockLevel", "/Probe/Rules/StockComp", "/Probe/Store/StockLevel",
            "/A/B/C/D", "/Items*/Amount");

    // ── resolve: the reading direction, which existed only as a static on the model reader ─────────────

    /**
     * The property that makes the 2026-09-04 defect unspellable: over every ordered pair, {@code resolve} must
     * invert {@code relativize} exactly. Non-vacuous because the two are independent algorithms — one counts a
     * common prefix and emits climbs, the other walks them — so an inverted convention on either side lands
     * somewhere else and this fails.
     *
     * <p>It is asserted here and not through {@code ModelReader.resolveRelPath}, which now DELEGATES to
     * {@code resolve} and so could only agree with it tautologically. The test above keeps the reader's own
     * spelling exercised at the boundary its callers use.
     */
    @Test
    void resolveInvertsRelativizeOverEveryPair() {
        for (String base : ROUND_TRIP_INPUTS) {
            for (String target : ROUND_TRIP_INPUTS) {
                String relative = EntityPath.of(base).relativize(EntityPath.of(target));
                assertEquals(EntityPath.of(target), EntityPath.of(base).resolve(relative),
                        () -> "resolve(\"" + base + "\", \"" + relative + "\") must land back on " + target);
            }
        }
    }

    /**
     * The convention the CLI corrective got backwards, pinned as a sentence a reader can check: the base is the
     * rule ELEMENT, so one {@code ..} lands on its group and the element's own name never survives.
     */
    @Test
    void oneDotDotLandsOnTheGroupBecauseTheBaseIsAnElementInsideIt() {
        assertEquals(EntityPath.of("/Order/CustomerName"),
                EntityPath.of("/Order/AnyRuleName").resolve("../CustomerName"));
        assertEquals(EntityPath.of("/Order/CustomerName"),
                EntityPath.of("/Order/AWhollyDifferentName").resolve("../CustomerName"),
                "the element's name is popped, so it cannot influence the answer");
        assertEquals(EntityPath.of("/Order/Items/Sku"),
                EntityPath.of("/Order/SomeRule").resolve("../Items/Sku"),
                "a reference may descend after the hop — the error field need not be the group's own child");
    }

    /**
     * The rest of the reference vocabulary, at the boundaries {@code relativize} never emits — all of it
     * reachable from author-typed text, which is what the CLI corrective is handed.
     *
     * <p>The over-climb is TOTAL rather than refusing: it clamps at the root level, which is the behaviour
     * {@code ModelIndex.resolvePath}'s callers already had. A caller needing more than that tests the result
     * for the shape it wants — {@code /X} is a root group, and a corrective proposing it for {@code --field}
     * would be proposing a value the gate then refuses.
     */
    @Test
    void theReferenceFormsOutsideRelativizesRangeArePinned() {
        assertEquals(EntityPath.of("/A/B"), EntityPath.of("/A/B").resolve(""),
                "an empty reference is the base itself, mirroring relativize returning \"\" for a pair");
        assertEquals(EntityPath.of("/A/B/C"), EntityPath.of("/A/B").resolve("/A/B/C"),
                "a reference that is already absolute passes through");
        assertEquals(EntityPath.of("/A"), EntityPath.of("/A/B").resolve("../"),
                "an empty tail segment is skipped");
        assertEquals(EntityPath.of("/A/C"), EntityPath.of("/A/B").resolve(".././C"),
                "a `.` step is skipped");
        assertEquals(EntityPath.of("/X"), EntityPath.of("/A/B").resolve("../../../X"),
                "more climbs than the base has segments clamp at the root level rather than failing");
        assertEquals("/X", ModelReader.resolveRelPath("/A/B", "../../../X"),
                "…which is exactly what the reader's callers already got");
    }
}
