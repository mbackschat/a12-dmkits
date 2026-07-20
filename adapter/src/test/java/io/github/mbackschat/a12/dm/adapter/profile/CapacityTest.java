package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.Capacity.Cause.DECLARED;
import static io.github.mbackschat.a12.dm.adapter.profile.Capacity.Cause.OVERFLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CapacityTest {

    @Test
    void boundedCapacityRejectsNegativeValues() {
        var rejection = assertThrows(
                IllegalArgumentException.class,
                () -> new Capacity.Bounded(-1));

        assertEquals("capacity must be non-negative: -1", rejection.getMessage());
    }

    @Test
    void boundedArithmeticRetainsExactLongValues() {
        var six = new Capacity.Bounded(6);
        var seven = new Capacity.Bounded(7);

        assertEquals(new Capacity.Bounded(42), six.multiply(seven));
        assertEquals(seven, six.max(seven));
        assertEquals(
                new Capacity.Bounded(Long.MAX_VALUE),
                new Capacity.Bounded(Long.MAX_VALUE).multiply(new Capacity.Bounded(1)));
        assertEquals(
                new Capacity.Bounded(0),
                new Capacity.Bounded(Long.MAX_VALUE).multiply(new Capacity.Bounded(0)));
    }

    @Test
    void multiplicationOverflowBecomesAnExplicitCapacityState() {
        var overflow = new Capacity.Unbounded(OVERFLOW);

        assertEquals(
                overflow,
                new Capacity.Bounded(Long.MAX_VALUE).multiply(new Capacity.Bounded(2)));
        assertEquals(
                overflow,
                new Capacity.Bounded(2).multiply(new Capacity.Bounded(Long.MAX_VALUE)));
        assertEquals(overflow, overflow.max(new Capacity.Bounded(Long.MAX_VALUE)));
        assertEquals(overflow, new Capacity.Bounded(Long.MAX_VALUE).max(overflow));
    }

    @Test
    void declaredUnboundednessCannotBeErasedByZeroOrAZeroMaximum() {
        var unbounded = new Capacity.Unbounded(DECLARED);
        var zero = new Capacity.Bounded(0);

        assertEquals(unbounded, unbounded.multiply(zero));
        assertEquals(unbounded, zero.multiply(unbounded));
        assertEquals(unbounded, unbounded.max(zero));
        assertEquals(unbounded, zero.max(unbounded));
    }

    @Test
    void declaredUnboundednessTakesPrecedenceOverAnOverflowState() {
        var declared = new Capacity.Unbounded(DECLARED);
        var overflow = new Capacity.Unbounded(OVERFLOW);

        assertEquals(declared, declared.multiply(overflow));
        assertEquals(declared, overflow.multiply(declared));
        assertEquals(declared, declared.max(overflow));
        assertEquals(declared, overflow.max(declared));
    }
}
