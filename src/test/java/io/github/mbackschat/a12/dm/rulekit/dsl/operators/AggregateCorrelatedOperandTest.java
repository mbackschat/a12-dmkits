package io.github.mbackschat.a12.dm.rulekit.dsl.operators;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldRef;

/**
 * The {@code $} outer-iteration correlation is part of an aggregate operand's <b>identity</b>: {@code $path}
 * reads the OUTER row and {@code path} the current one, so the two are different operands and may share one
 * slot list — pairing them inside a {@code Having} filter is what the correlation exists for. The entity-list
 * duplicate gate keys on the rendered path, which is the one part of a {@link FieldRef} that does not carry
 * {@code correlated}.
 *
 * <p>{@code ListAggregate.checkNoDuplicates} is the shared root for the whole field-list family, so the pairing
 * is proved through {@code Sum} (admitted via {@code ListAggregate}) and through {@code NumberOfFilledFields}
 * (via {@code CountAggregate}). {@code FirstFilledValue} keeps its own richer entity-selector identity, which
 * carries the flag already — {@code AggregateDuplicateWildcardTest} is the sibling covering the other way an
 * operand escapes this gate, a wildcard.
 */
class AggregateCorrelatedOperandTest {

    private static final BoundModel m = Fixtures.subscription();

    private final FieldRef baseFee = m.field("/Subscription/Billing/BaseFee");
    private final FieldRef effFee = m.field("/Subscription/Billing/EffectiveFee");

    @Test
    void aCorrelatedOperandIsNotADuplicateOfTheSamePathReadInTheCurrentRow() {
        assertDoesNotThrow(() -> Dsl.sum(baseFee.outer(), baseFee));
        // order-independent: the identity travels with the operand, not with its slot
        assertDoesNotThrow(() -> Dsl.sum(baseFee, baseFee.outer()));
        assertDoesNotThrow(() -> Dsl.numberOfFilledFields(baseFee.outer(), baseFee));
    }

    @Test
    void twoReferencesOfOneCorrelationToOnePathStayADuplicate() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.sum(baseFee.outer(), baseFee.outer()));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.numberOfFilledFields(baseFee.outer(), baseFee.outer()));
    }

    @Test
    void theUncorrelatedRepeatIsStillRefusedAndDistinctPathsAreStillAccepted() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.sum(baseFee, baseFee));
        assertDoesNotThrow(() -> Dsl.sum(baseFee, effFee));
    }
}
