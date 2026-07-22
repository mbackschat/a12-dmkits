package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Authoring of the enum field-list value-list quantifiers — union admission over the selected members'
 * domains, category projections as distinct members, exact-duplicate rejection, and the entity-valued
 * base-kind/combined-duplicate gate (KERNEL-FINDINGS §8, SPEC-2026-07-22-05). The order fixture's
 * {@code Priority} (STANDARD/EXPRESS/OVERNIGHT, category SpeedClass = NORMAL/FAST/FAST) and
 * {@code RushLevel} (1/2/3) are two enums with DISJOINT stored domains.
 */
class EnumValueListUnionTest {

    private static final BoundModel m = Fixtures.order();
    private static final String PRIORITY = "/Order/Priority";
    private static final String RUSH = "/Order/RushLevel";
    private static final String QUANTITY = "/Order/Quantity";
    private static final String CUSTOMER = "/Order/CustomerName";

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    @Test
    void unionAdmitsALiteralFromEitherDisjointDomain() {
        // "STANDARD" is a Priority value, "1" a RushLevel value — neither is in BOTH (the old intersection
        // check wrongly rejected this legal rule).
        Condition c = Dsl.atLeastOneFieldValueIncludedInValueList(List.of("STANDARD", "1"),
                m.enumOf(PRIORITY), m.enumOf(RUSH));
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Order/Priority, /Order/RushLevel In \"STANDARD\", \"1\")",
                render(c));
    }

    @Test
    void aLiteralInNeitherDomainIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of("ZZZ"), m.enumOf(PRIORITY), m.enumOf(RUSH)));
    }

    @Test
    void directAndCategoryOnTheSameFieldAreDistinctAndUnioned() {
        // "STANDARD" rides Priority's stored domain, "FAST" rides SpeedClass's category domain — both admitted,
        // and both projections are retained in the rendered condition (bare `Priority -> SpeedClass`).
        Condition c = Dsl.noFieldValueIncludedInValueList(List.of("STANDARD", "FAST"),
                m.enumOf(PRIORITY), m.enumOf(PRIORITY).category("SpeedClass"));
        assertEquals("NoFieldValueIncludedInValueList(/Order/Priority, /Order/Priority -> SpeedClass In \"STANDARD\", \"FAST\")",
                render(c));
    }

    @Test
    void aCategoryProjectionRendersBareInTheQuantifier() {
        Condition c = Dsl.notAllFieldValuesIncludedInValueList(List.of("FAST"),
                m.enumOf(PRIORITY).category("SpeedClass"));
        assertEquals("NotAllFieldValuesIncludedInValueList(/Order/Priority -> SpeedClass In \"FAST\")", render(c));
    }

    @Test
    void anExactDuplicateReferenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of("STANDARD"), m.enumOf(PRIORITY), m.enumOf(PRIORITY)));
        // ...but a direct ref and a category ref on the same field are DISTINCT, so this must NOT throw:
        Dsl.atLeastOneFieldValueIncludedInValueList(List.of("STANDARD"),
                m.enumOf(PRIORITY), m.enumOf(PRIORITY).category("SpeedClass"));
    }

    @Test
    void stringQuantifierAlsoRejectsAnExactDuplicate() {
        // sibling sweep: the kernel applies the same duplicate rule to string field-lists (correction 4)
        assertThrows(IllegalArgumentException.class, () -> Dsl.noFieldValueIncludedInValueList(
                List.of("x"), m.str(CUSTOMER), m.str(CUSTOMER)));
    }

    @Test
    void entityValuedRejectsMixedBaseKinds() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of(m.field(QUANTITY)), m.field(PRIORITY)));   // enum field IN number value list
    }

    @Test
    void entityValuedRejectsACombinedDuplicate() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.atLeastOneFieldValueIncludedInValueList(
                List.of(m.field(PRIORITY)), m.field(PRIORITY)));   // same ref across both sides
    }

    @Test
    void entityValuedAcceptsDisjointEnumsWithoutContainment() {
        // two enums with disjoint domains, one In the other — the kernel does NOT require domain containment.
        Condition c = Dsl.atLeastOneFieldValueIncludedInValueList(List.of(m.field(RUSH)), m.field(PRIORITY));
        assertEquals("AtLeastOneFieldValueIncludedInValueList(/Order/Priority In /Order/RushLevel)", render(c));
    }
}
