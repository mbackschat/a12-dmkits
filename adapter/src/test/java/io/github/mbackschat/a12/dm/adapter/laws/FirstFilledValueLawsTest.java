package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.rt.api.IMessage.MessageType;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * Behavioral law — <b>{@code FirstFilledValue} is prefix-sensitive</b> (RT-SEMANTICS-LEDGER:
 * {@code FirstValueCombiner}; KERNEL-FINDINGS §9 supplement). Only the operands <em>before and
 * including the first filled one</em> matter; everything after it is never read:
 *
 * <ul>
 *   <li>an <b>empty</b> operand before the first filled one marks the result fillable — a comparison
 *       that fires over it is typed {@code OMISSION_ERROR} (filling the earlier field could change
 *       which value is "first");</li>
 *   <li>a <b>formal-error</b> operand (unknown state, e.g. a non-numeric value in a number field)
 *       before the first filled one suppresses the rule entirely;</li>
 *   <li>the same formal error <b>after</b> the first filled operand is invisible to the rule — the
 *       combiner stopped reading at the first filled value.</li>
 * </ul>
 *
 * <p>Multi-row scope over the flatten of {@code Items*} then {@code Count} (rows made present via
 * Sku so the empty-row law doesn't interfere — see {@code EmptyValueLawsTest}).
 */
class FirstFilledValueLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");

    // Conditions are authored with the rulekit typed DSL and rendered to the canonical string fed to
    // the kernel — no free-text condition literals.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return new DslRenderer().render(c);
    }

    /** The error field must be referenced in the condition — hence the CustomerName guard idiom. */
    private static String withFfvRule(Condition ffvClause) {
        return RuntimeLaws.withRule(ORDER, "/Order", "FfvHigh", "../CustomerName", "FFV_HIGH",
                render(m.str("/Order/CustomerName").filled().and(ffvClause)));
    }

    /** Two Items rows, row 1's Count as given, row 2's Count = 7 (the candidate "first filled"). */
    private static RuntimeLaws.Doc rows(String row1Count) {
        RuntimeLaws.Doc doc = RuntimeLaws.doc()
                .group("/Order", 1)
                .field("/Order/CustomerName", "Acme", 1, 1)
                .group("/Order/Items", 1, 1)
                .group("/Order/Items", 1, 2)
                .field("/Order/Items/Sku", "A", 1, 1, 1)
                .field("/Order/Items/Sku", "B", 1, 2, 1)
                .field("/Order/Items/Count", "7", 1, 2, 1);
        if (row1Count == null) {
            doc.emptyField("/Order/Items/Count", 1, 1, 1);
        } else {
            doc.field("/Order/Items/Count", row1Count, 1, 1, 1);
        }
        return doc;
    }

    @Test
    void theFirstFilledValueWins() {
        String model = withFfvRule(Dsl.firstFilledNumber(m.flatten("/Order/Items*/Count")).gt(5));

        assertTrue(RuntimeLaws.fires(model, "FFV_HIGH", rows("9")),
                "row 1 filled with 9 → first filled value is 9 > 5 → fires");
        assertFalse(RuntimeLaws.fires(model, "FFV_HIGH", rows("3")),
                "row 1 filled with 3 → first filled value is 3, not > 5 → does not fire");
    }

    @Test
    void anEmptyOperandBeforeTheFirstFilledOneTypesTheErrorAsOmission() {
        String model = withFfvRule(Dsl.firstFilledNumber(m.flatten("/Order/Items*/Count")).gt(5));

        // row 1 Count empty (row present via Sku) → first filled value is row 2's 7 → fires,
        // but filling row 1 could change which value is first → OMISSION_ERROR
        Optional<MessageType> type = RuntimeLaws.typeOf(model, "FFV_HIGH", rows(null));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), type,
                "an empty operand before the first filled one makes the fired comparison an OMISSION_ERROR");
    }

    @Test
    void aFormalErrorBeforeTheFirstFilledValueSuppressesTheRule() {
        String model = withFfvRule(Dsl.firstFilledNumber(m.flatten("/Order/Items*/Count")).gt(5));

        // row 1 Count = "abc" → formal error → unknown state BEFORE the first filled value → suppressed
        assertFalse(RuntimeLaws.fires(model, "FFV_HIGH", rows("abc")),
                "a formal-error operand before the first filled value suppresses the rule");
    }

    @Test
    void aFormalErrorAfterTheFirstFilledValueIsInvisible() {
        String model = withFfvRule(Dsl.firstFilledNumber(m.flatten("/Order/Items*/Count")).gt(5));

        // row 1 Count = 9 (first filled), row 3 Count = "abc" AFTER it → combiner never reads row 3
        RuntimeLaws.Doc doc = rows("9")
                .group("/Order/Items", 1, 3)
                .field("/Order/Items/Sku", "C", 1, 3, 1)
                .field("/Order/Items/Count", "abc", 1, 3, 1);
        assertTrue(RuntimeLaws.fires(model, "FFV_HIGH", doc),
                "a formal error after the first filled operand does not suppress the rule");
    }
}
