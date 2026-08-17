package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * The entity-list duplicate gate <b>skips wildcarded operands</b> (SPEC-2026-07-22-07). Outside HModel the
 * kernel's {@code checkDuplicatesInDirectRefs} continues past {@code ref.hasWildCards()}: a plain or filtered
 * repetition list may occupy several authored aggregate slots and is expanded and consumed again at each
 * position. Only repeated <em>direct</em> (non-wildcard) fields still draw {@code MVK_DUPLICATE_PARAM1}.
 *
 * <p>The field-list aggregates share {@code ListAggregate.checkNoDuplicates}; {@code FirstFilledValue} owns the
 * corresponding richer entity-selector check because it also admits group, category, and semantic-index shapes.
 * The matrix therefore proves the wildcard rule through both admission roots for {@code Sum}, {@code MaxValue},
 * {@code MinValue}, {@code FirstFilledValue}, {@code NumberOfFilledFields}, and
 * {@code NumberOfDifferentValues}.
 */
class AggregateDuplicateWildcardTest {

    private static final BoundModel m = Fixtures.subscription();

    private final FieldListRef fee = m.flatten("/Subscription/Addons*/MonthlyFee");   // number list
    private final FieldListRef name = m.flatten("/Subscription/Addons*/Name");        // string list
    private final FieldRef baseFee = m.field("/Subscription/Billing/BaseFee");
    private final FieldRef effFee = m.field("/Subscription/Billing/EffectiveFee");

    @Test
    void repeatedPlainWildcardIsAcceptedAcrossTheAggregateFamily() {
        // The rendered form keeps both occurrences — the kernel expands and consumes each slot independently.
        assertEquals("Sum(/Subscription/Addons*/MonthlyFee, /Subscription/Addons*/MonthlyFee) > 100.00",
                new DslRenderer().render(Dsl.sum(fee, fee).gt("100.00")));
        assertDoesNotThrow(() -> Dsl.maxValue(fee, fee));
        assertDoesNotThrow(() -> Dsl.minValue(fee, fee));
        assertDoesNotThrow(() -> Dsl.firstFilledNumber(fee, fee));
        assertDoesNotThrow(() -> Dsl.numberOfFilledFields(fee, fee));
        assertDoesNotThrow(() -> Dsl.numberOfDifferentValues(name, name));
    }

    @Test
    void repeatedFilteredWildcardAndMixedPlainFilteredAreAccepted() {
        FieldListRef feeFiltered =
                m.flatten("/Subscription/Addons*/MonthlyFee").having(m.num("/Subscription/Addons/MonthlyFee").gt(0));
        // two filtered stars over the same base, and a plain star plus a filtered star over the same base
        assertDoesNotThrow(() -> Dsl.sum(feeFiltered, feeFiltered));
        assertDoesNotThrow(() -> Dsl.sum(fee, feeFiltered));
    }

    @Test
    void repeatedDirectFieldsAreStillRejected() {
        // adjacent repeated direct field (existing behavior retained)
        assertThrows(IllegalArgumentException.class, () -> Dsl.sum(baseFee, baseFee));
        // a NON-ADJACENT repeated direct field separated by a wildcard is still a duplicate
        assertThrows(IllegalArgumentException.class, () -> Dsl.sum(baseFee, fee, baseFee));
        // a distinct direct field beside the repeated wildcard is fine
        assertDoesNotThrow(() -> Dsl.sum(baseFee, effFee, fee, fee));
    }
}
