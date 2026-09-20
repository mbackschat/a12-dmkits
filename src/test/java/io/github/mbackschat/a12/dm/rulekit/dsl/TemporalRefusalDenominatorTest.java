package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The temporal extraction gate is held to the COMPLETE {@link ValueKind} denominator rather than to a
 * hand-listed subset of it. A client-side refusal may never be broader than the kernel rows behind it, and
 * the way that bound is lost is not a wrong entry — it is a kind nobody classified, which an allowlist
 * silently refuses and a hand-copied denylist silently admits.
 *
 * <p>So the refusal set is <b>derived</b> here as the complement of the two kinds of exemption, and compared
 * with what {@link TemporalOperands} actually enforces. Adding a {@code ValueKind}, or widening the
 * production set, fails until the new kind is put in a bucket — and a bucket is only earned by a measured
 * row, which {@code validate.laws.DateExtractionLawsTest} settles at the kernel.
 */
class TemporalRefusalDenominatorTest {

    /**
     * Read by the family, so never refused. Which of them answers which extraction is decided by the
     * source's DECLARED FORMAT and not by its kind (KERNEL-FINDINGS KF211), which is why a gate holding
     * only a {@link Calculation} cannot narrow this further and must leave the choice to the kernel.
     */
    private static final Set<ValueKind> ADMITTED =
            EnumSet.of(ValueKind.DATE, ValueKind.DATE_FRAGMENT, ValueKind.TIME, ValueKind.DATE_TIME);

    /**
     * Carried by no authorable A12 field — {@link ValueKind#UNKNOWN} is what an unresolved type definition
     * classifies to — so no kernel row can be measured for it and refusing it would state a rule nothing
     * establishes. Under-reporting is the safe direction: the kernel still gets asked.
     */
    private static final Set<ValueKind> DELEGATED = EnumSet.of(ValueKind.UNKNOWN);

    /** The refusal set the gate is entitled to, derived rather than restated. */
    private static Set<ValueKind> refusable() {
        Set<ValueKind> refusable = EnumSet.allOf(ValueKind.class);
        refusable.removeAll(ADMITTED);
        refusable.removeAll(DELEGATED);
        return refusable;
    }

    @Test
    void everyValueKindIsClassifiedExactlyOnce() {
        Set<ValueKind> overlap = EnumSet.copyOf(ADMITTED);
        overlap.retainAll(DELEGATED);
        assertEquals(EnumSet.noneOf(ValueKind.class), overlap, "a kind may sit in only one bucket");

        Set<ValueKind> union = EnumSet.copyOf(refusable());
        union.addAll(ADMITTED);
        union.addAll(DELEGATED);
        assertEquals(EnumSet.allOf(ValueKind.class), union, "every ValueKind must be classified");
    }

    /**
     * Each carrier is PROBED over the whole denominator rather than compared against the gate's own
     * constant: a set that is right but never consulted reads identically to one that is enforced, and only
     * the observed behaviour tells them apart.
     */
    @Test
    void eachCarrierRefusesExactlyTheDerivedSet() {
        assertEquals(refusable(), refusedBy(kind -> new DateExtraction(DateExtraction.Unit.YEAR, kind)),
                "YearFromDate");
        assertEquals(refusable(), refusedBy(kind -> new DateTimePart(DateTimePart.Unit.DATE, kind)),
                "DateFromDateTime");
    }

    /**
     * Which kinds a carrier refuses, and on what stated basis. The code is asserted alongside the set
     * because a refusal that throws for its own reason would otherwise pass for the measured one.
     */
    private static Set<ValueKind> refusedBy(java.util.function.Consumer<Calculation> carrier) {
        Set<ValueKind> refused = EnumSet.noneOf(ValueKind.class);
        for (ValueKind kind : ValueKind.values()) {
            Calculation source = new FieldRef("/Probe/" + kind, kind).value();
            try {
                carrier.accept(source);
            } catch (IllegalArgumentException refusal) {
                refused.add(kind);
                assertEquals("MVK_NO_DATE", citedCode(refusal), kind.name());
            }
        }
        return refused;
    }

    /** The kernel code a refusal cites, which the message carries in trailing parentheses. */
    private static String citedCode(IllegalArgumentException refusal) {
        String message = refusal.getMessage();
        int open = message.lastIndexOf('(');
        return open < 0 ? message : message.substring(open + 1, message.length() - 1);
    }
}
