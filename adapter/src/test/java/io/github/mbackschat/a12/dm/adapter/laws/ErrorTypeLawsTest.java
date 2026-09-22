package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.rt.api.IMessage.MessageType;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;

/**
 * Behavioral law — <b>the value-vs-omission error typing</b> (RT-SEMANTICS-LEDGER:
 * {@code ValidierungsErgebnis} + {@code BedingungsOperatorHelper}; KERNEL-FINDINGS §12 supplement).
 * Every fired rule message carries a {@code MessageType}: <b>OMISSION_ERROR</b> when the rule could be
 * satisfied by <em>filling</em> more fields (the comparison evaluated a substituted/uncertain value),
 * <b>VALUE_ERROR</b> when only <em>changing</em> a value can satisfy it. KERNEL-SEMANTICS presents the
 * logic as two-valued; the typing is a third, source-mined dimension riding on every message.
 *
 * <p>The combination algebra (source: {@code ValidierungsErgebnis.combineUND/combineODER}):
 * <ul>
 *   <li><b>And</b> — omission wins: {@code VALUE And OMISSION → OMISSION} (filling the empty field
 *       could still flip the other branch's substituted comparison… i.e. the rule as a whole).</li>
 *   <li><b>Or</b> — value wins: {@code VALUE Or OMISSION → VALUE} (the value branch alone already
 *       convicts; no amount of filling rescues it).</li>
 * </ul>
 */
class ErrorTypeLawsTest {

    private static final String ORDER = RuntimeLaws.model("models/order.dm.json");
    private static final Set<String> ORDER_ROW = Set.of("/Order");

    // Conditions are authored with the rulekit typed DSL and rendered to the canonical string fed to
    // the kernel — no free-text condition literals.
    private static final BoundModel m = BoundModel.ofJson(ORDER);

    private static String render(Condition c) {
        return RuntimeLaws.render(c);
    }

    @Test
    void aComparisonOverFilledValuesIsAValueError() {
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyHigh", "../Quantity", "QTY_HIGH",
                render(m.num("/Order/Quantity").gt(5)));

        Optional<MessageType> type = RuntimeLaws.typeOf(model, "QTY_HIGH", ORDER_ROW, Set.of(),
                Map.of("/Order/Quantity", "10"));
        assertEquals(Optional.of(MessageType.VALUE_ERROR), type,
                "a comparison over a filled value can only be satisfied by changing it → VALUE_ERROR");
    }

    @Test
    void aComparisonOverAnEmptySubstitutedNumberIsAnOmissionError() {
        // empty number in a filled row → 0 substituted (EmptyValueLawsTest) — and the kernel marks the
        // result as fillable-away: the message is typed OMISSION_ERROR, not VALUE_ERROR
        String model = RuntimeLaws.withRule(ORDER, "/Order", "QtyPresent", "../Quantity", "QTY_NONNEG",
                render(m.num("/Order/Quantity").ge(0)));

        Optional<MessageType> type = RuntimeLaws.typeOf(model, "QTY_NONNEG", ORDER_ROW,
                Set.of("/Order/Quantity"), Map.of("/Order/CustomerName", "Acme"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), type,
                "a comparison that fired on the 0-substitution of an empty field → OMISSION_ERROR");
    }

    @Test
    void andCombination_omissionWins() {
        // [Quantity] > 5 is a VALUE branch (10 filled); [BackorderQuantity] >= 0 fires on the empty
        // substitution (OMISSION branch). And-combined: OMISSION (combineUND: WF ∧ AF = AF).
        String model = RuntimeLaws.withRule(ORDER, "/Order", "AndTyping", "../Quantity", "AND_TYPING",
                render(m.num("/Order/Quantity").gt(5).and(m.num("/Order/BackorderQuantity").ge(0))));

        Optional<MessageType> type = RuntimeLaws.typeOf(model, "AND_TYPING", ORDER_ROW,
                Set.of("/Order/BackorderQuantity"), Map.of("/Order/Quantity", "10"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), type,
                "And: a fillable branch makes the whole fired rule an OMISSION_ERROR");
    }

    @Test
    void orCombination_valueWins() {
        // same branches, Or-combined: the VALUE branch alone convicts → VALUE_ERROR
        // (combineODER: WF ∨ AF = WF).
        String model = RuntimeLaws.withRule(ORDER, "/Order", "OrTyping", "../Quantity", "OR_TYPING",
                render(m.num("/Order/Quantity").gt(5).or(m.num("/Order/BackorderQuantity").ge(0))));

        Optional<MessageType> type = RuntimeLaws.typeOf(model, "OR_TYPING", ORDER_ROW,
                Set.of("/Order/BackorderQuantity"), Map.of("/Order/Quantity", "10"));
        assertEquals(Optional.of(MessageType.VALUE_ERROR), type,
                "Or: a satisfied value branch makes the whole fired rule a VALUE_ERROR");
    }

    @Test
    void presencePredicateIsAnOmissionError() {
        // the presence predicates' whole point is "fill this" — FieldNotFilled fires as OMISSION_ERROR
        String model = RuntimeLaws.withRule(ORDER, "/Order", "NeedQty", "../Quantity", "NEED_QTY",
                render(m.num("/Order/Quantity").notFilled()));

        Optional<MessageType> type = RuntimeLaws.typeOf(model, "NEED_QTY", ORDER_ROW,
                Set.of("/Order/Quantity"), Map.of("/Order/CustomerName", "Acme"));
        assertEquals(Optional.of(MessageType.OMISSION_ERROR), type,
                "FieldNotFilled is satisfied by filling the field → OMISSION_ERROR");
    }
}
