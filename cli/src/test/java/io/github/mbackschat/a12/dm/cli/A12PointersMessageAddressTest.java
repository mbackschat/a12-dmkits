package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.cli.interp.A12Pointers;
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;

/**
 * <b>The published rendering of a message address is decided from the POINTER, never from its text.</b>
 *
 * <p>This is the guard for a defect that a string-based converter cannot avoid. dmtool must print canonical A12
 * for an address that names one cell and the interpreter's house form for one that does not — and the house form
 * omits the terminal repetition, which is exactly where A12's two message sentinels live. So the house string of
 * an <em>unresolved</em> address is indistinguishable from the house string of a perfectly ordinary
 * <em>resolved</em> one, and re-reading it answers the wrong question confidently.
 *
 * <p>{@link #anUnresolvedAddressIsNotPromotedByItsOwnHouseString()} is the case that fails against a try-parse
 * converter: {@code ("/Plan/Demand", [1, -5])} renders {@code /Plan[1]/Demand}, which parses cleanly as an exact
 * pointer and renders back as {@code Plan/Demand} — an address that matched <em>no</em> row, published as the
 * ordinary first row.
 */
class A12PointersMessageAddressTest {

    @Test
    void anExactAddressIsPublishedAsCanonicalA12() {
        assertEquals("Order/Items[2]/Count", A12Pointers.canonical(address("/Order/Items/Count", 1, 2, 1)),
                "every level resolved to one row, so A12 has a canonical string for it");
        assertEquals("Order/Items[0]", A12Pointers.canonical(address("/Order/Items", 1, 0)),
                "a terminal 0 is A12's RepetitionsV2 slot on an exact pointer, not a wildcard — and canonical "
                        + "form omits only [1], so the 0 is written");
    }

    /**
     * The discriminating case. The assertion is deliberately written as "not the exact reading" rather than only
     * as "the house form": what makes this a defect is the promotion, not the spelling.
     */
    @Test
    void anUnresolvedAddressIsNotPromotedByItsOwnHouseString() {
        A12PartiallyKnownMultiPointer unresolved = address("/Plan/Demand", 1, -5);
        String published = A12Pointers.canonical(unresolved);

        assertEquals("/Plan[1]/Demand", published,
                "A12 defines no pointer string for an unresolved address, so it passes through as the house form");
        assertEquals("Plan/Demand", canonicalOf(published),
                "the premise of this test: that house string IS readable as an exact pointer. A try-parse "
                        + "converter would therefore publish 'Plan/Demand' — the FIRST row — for an address that "
                        + "resolved to no row at all, which is worse than losing the -5: it invents a cell");
    }

    @Test
    void aWildcardAboveTheLastLevelIsAlsoUnresolved() {
        assertEquals("/Order[0]/Items", A12Pointers.canonical(address("/Order/Items", 0, 2)),
                "a 0 above the last level names all rows, which A12 gives no exact-pointer string");
    }

    @Test
    void aListOfAddressesIsRenderedElementByElement() {
        assertEquals(
                List.of("Order/Items[2]/Count", "/Plan[1]/Demand"),
                A12Pointers.canonical(List.of(address("/Order/Items/Count", 1, 2, 1), address("/Plan/Demand", 1, -5))),
                "referenced/fillToFix may mix resolved and unresolved addresses in one list");
    }

    /** What a try-parse converter would have produced — kept explicit so the premise above is not assumed. */
    private static String canonicalOf(String houseString) {
        return A12Pointers.canonicalCell(houseString);
    }

    private static A12PartiallyKnownMultiPointer address(String fullName, int... repetitions) {
        return A12PartiallyKnownMultiPointer.Companion.of(fullName,
                java.util.Arrays.stream(repetitions).boxed().toList());
    }
}
