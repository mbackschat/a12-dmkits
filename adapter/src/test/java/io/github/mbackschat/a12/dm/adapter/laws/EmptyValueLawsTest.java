package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Behavioral law — <b>non-specified (empty) values</b> (KERNEL-SEMANTICS §2): an empty <b>number</b>
 * field has a default of {@code 0} substituted in a comparison, so {@code [Quantity] < 100} <em>fires</em>
 * on an empty Quantity (0 &lt; 100). The documented mitigation is a {@code FieldFilled} guard.
 *
 * <p><b>Precondition (verified, the subtle part):</b> the substitution applies to an empty field
 * <em>within a filled group row</em> — a row made non-empty by at least one sibling value. An
 * <em>entirely</em> empty row (group instance with no field values) is not evaluated, so the same rule
 * does not fire there. The tests pin both halves so the distinction can't silently regress.
 *
 * <p>The contrasting type behaviours (empty date / string / boolean / enum → <em>not evaluated</em>,
 * empty confirm → {@code False}) are separate laws; the date case additionally needs the date-literal
 * syntax of KERNEL-FINDINGS §6 and is deferred to its own increment.
 */
class EmptyValueLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");
    /** A sibling value that makes the /Order row genuinely filled (so its empty fields are evaluated). */
    private static final Map<String, String> FILLED_ROW = Map.of("/Order/CustomerName", "Acme");

    @Test
    void emptyNumberInAFilledRowIsSubstitutedWithZero() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyLow", "../Quantity", "QTY_LOW",
                "[Quantity] < 100");

        // row filled by a sibling, Quantity empty → treated as 0 → 0 < 100 → fires
        assertTrue(RuntimeLaws.fires(model, "QTY_LOW", ORDER_ROW, FILLED_ROW),
                "an empty number in a filled row is substituted with 0 (0 < 100), so the rule fires");
        // a real value of 200 is not < 100 → does not fire
        assertFalse(RuntimeLaws.fires(model, "QTY_LOW", ORDER_ROW, Map.of("/Order/Quantity", "200")),
                "a filled value of 200 is not < 100, so the rule does not fire");
    }

    @Test
    void anEntirelyEmptyRowIsNotEvaluated() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyLow", "../Quantity", "QTY_LOW",
                "[Quantity] < 100");

        // group row present but with NO field values at all → not evaluated → no 0-substitution, no fire
        assertFalse(RuntimeLaws.fires(model, "QTY_LOW", ORDER_ROW, Map.of()),
                "an entirely empty row is not evaluated, so the 0-substitution does not occur");
    }

    @Test
    void fieldFilledGuardSuppressesTheEmptyZeroError() {
        // the documented mitigation (§2): guard the comparison with FieldFilled
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyLowGuarded", "../Quantity", "QTY_LOW_G",
                "FieldFilled(Quantity) And [Quantity] < 100");

        assertFalse(RuntimeLaws.fires(model, "QTY_LOW_G", ORDER_ROW, FILLED_ROW),
                "the FieldFilled guard suppresses the empty-as-0 false positive even in a filled row");
        assertTrue(RuntimeLaws.fires(model, "QTY_LOW_G", ORDER_ROW, Map.of("/Order/Quantity", "50")),
                "a filled value of 50 is < 100, so the guarded rule fires");
    }
}
