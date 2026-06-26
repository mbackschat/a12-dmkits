package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.rt.api.IMessage.MessageType;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberExpr;

/**
 * Behavioral law — <b>{@code NumberOfFilledFields} counting semantics</b> (RT-SEMANTICS-LEDGER:
 * {@code RuntimeController.anzahlAngegebenerEntities}):
 *
 * <ul>
 *   <li>a <b>formal-error</b> operand makes the whole count non-evaluable — the rule is suppressed
 *       (the count neither includes nor skips the broken field; it returns the non-relevant
 *       number);</li>
 *   <li>the count <b>can grow but never shrink</b>: with unfilled operands left, a fired comparison
 *       is an {@code OMISSION_ERROR}; with every operand filled it is a {@code VALUE_ERROR} —
 *       unlike the starred aggregates, a plain field-list count has no omitted-tail problem.</li>
 * </ul>
 */
class FilledCountLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL and rendered to the canonical string fed to
    // the kernel — no free-text condition literals.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    private static String countRule(String op, int n) {
        NumberExpr count = Dsl.numberOfFilledFields(m.field("/Order/Quantity"), m.field("/Order/BackorderQuantity"));
        Condition cond = switch (op) {
            case "==" -> count.eq(n);
            case "!=" -> count.ne(n);
            case "<" -> count.lt(n);
            case "<=" -> count.le(n);
            case ">" -> count.gt(n);
            case ">=" -> count.ge(n);
            default -> throw new IllegalArgumentException("unsupported operator " + op);
        };
        return RuntimeLaws.withRule(ORDER, "/Order", "QtyCount", "../Quantity", "QTY_COUNT", render(cond));
    }

    @Test
    void aFormalErrorOperandSuppressesTheCount() {
        String model = countRule("==", 2);

        assertTrue(RuntimeLaws.fires(model, "QTY_COUNT", ORDER_ROW,
                Map.of("/Order/Quantity", "1", "/Order/BackorderQuantity", "5")),
                "both filled → the count is 2 → fires");
        assertFalse(RuntimeLaws.fires(model, "QTY_COUNT", ORDER_ROW,
                Map.of("/Order/Quantity", "abc", "/Order/BackorderQuantity", "5")),
                "a formal-error operand makes the count non-evaluable — the rule is suppressed,"
                        + " the broken field is neither counted nor skipped");
    }

    @Test
    void thePartialCountIsFillable_theFullCountIsNot() {
        String model = countRule("==", 1);

        Optional<MessageType> partial = RuntimeLaws.typeOf(model, "QTY_COUNT", ORDER_ROW,
                Set.of("/Order/BackorderQuantity"), Map.of("/Order/Quantity", "1"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), partial,
                "one of two operands filled — the count could still grow → OMISSION_ERROR");

        Optional<MessageType> full = RuntimeLaws.typeOf(countRule("==", 2), "QTY_COUNT", ORDER_ROW,
                Set.of(), Map.of("/Order/Quantity", "1", "/Order/BackorderQuantity", "5"));
        assertEquals(Optional.of(MessageType.VALUE_ERROR), full,
                "every operand filled — the count is final → VALUE_ERROR (no omitted-tail problem"
                        + " for a plain field list)");
    }
}
