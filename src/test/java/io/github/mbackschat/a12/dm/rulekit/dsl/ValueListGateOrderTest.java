package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * Where the entity-valued value-list record asks its duplicate gate among the kernel's gates. A12 builds the
 * field list, then the value list, asks the value list's base type against the field list, and only then the
 * repeat ACROSS the two (KERNEL-FINDINGS KF401, measured by {@code adapter.laws.CheckerListAdmissionOrderAgreementTest}
 * on {@code Window In Window, Premium}). So a cross-list repeat beside a member of another kind is the base-type
 * class, and the record must not name the duplicate class there; it leaves that model to the kernel.
 */
class ValueListGateOrderTest {

    private static final BoundModel ORDER = Fixtures.order();

    @Test
    void aCrossListRepeatBesideAMemberOfAnotherKindIsLeftToTheKernel() {
        FieldRef window = ORDER.field("/Order/CoverageWindow");
        FieldRef quantity = ORDER.field("/Order/Quantity");
        assertDoesNotThrow(() -> Dsl.atLeastOneDateRangeOverlaps(window, window, quantity));

        FieldRef name = ORDER.field("/Order/CustomerName");
        assertDoesNotThrow(() -> new FieldListEntityListCondition(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                List.<ValueListOperand>of(name), List.<ValueListOperand>of(name, quantity)));
    }

    /** The control: the same repeat beside a member of the subject's kind is the duplicate class. */
    @Test
    void aCrossListRepeatAmongMembersOfOneKindIsTheDuplicateClass() {
        FieldRef name = ORDER.field("/Order/CustomerName");
        FieldRef code = ORDER.field("/Order/ProductCode");
        String message = assertThrows(IllegalArgumentException.class,
                () -> new FieldListEntityListCondition(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                        List.<ValueListOperand>of(name), List.<ValueListOperand>of(name, code))).getMessage();
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
    }

    /** The field list is built first of all, so its own repeat answers whatever the value list holds. */
    @Test
    void aRepeatInsideTheFieldListAnswersFirst() {
        FieldRef name = ORDER.field("/Order/CustomerName");
        FieldRef quantity = ORDER.field("/Order/Quantity");
        String message = assertThrows(IllegalArgumentException.class,
                () -> new FieldListEntityListCondition(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                        List.<ValueListOperand>of(name, name), List.<ValueListOperand>of(quantity))).getMessage();
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
    }

    /** A repeat inside the value list is asked before its base type, so a mixed value list still draws it. */
    @Test
    void aRepeatInsideTheValueListAnswersAheadOfItsBaseType() {
        FieldRef name = ORDER.field("/Order/CustomerName");
        FieldRef quantity = ORDER.field("/Order/Quantity");
        String message = assertThrows(IllegalArgumentException.class,
                () -> new FieldListEntityListCondition(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                        List.<ValueListOperand>of(name), List.<ValueListOperand>of(quantity, quantity))).getMessage();
        assertTrue(message.contains("MVK_DUPLICATE_PARAM1"), () -> "expected the kernel's code: " + message);
    }
}
