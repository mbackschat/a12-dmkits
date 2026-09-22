package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The floor under {@link RawCondition} — the type advertises that a raw row names its reason, so these
 * lock the two ways that advertisement could be true on paper and empty in practice: a reason that is
 * absent, and text that the wrapper silently alters on its way to the kernel.
 *
 * <p>What no test here can lock is the part that matters most, and saying so is the point: nothing yet
 * forces a row to use this type at all. That arrives when the bare-{@code String} overloads retire and
 * javac becomes the enumerator (DEV-API-GAPS DG59); until then this is a floor, not a gate.
 */
class RawConditionTest {

    @Test
    void aRawConditionWithoutItsReasonIsRefused() {
        var blank = assertThrows(IllegalArgumentException.class,
                () -> RawCondition.shapeErased("FieldFilled(X)", "  "));
        assertTrue(blank.getMessage().contains("needs the reason it is raw"), blank.getMessage());
        var absent = assertThrows(IllegalArgumentException.class,
                () -> RawCondition.concreteSyntax("FieldFilled(X)", null));
        assertTrue(absent.getMessage().contains("needs the reason it is raw"), absent.getMessage());
    }

    @Test
    void emptyTextIsRefusedSeparatelyFromTheReason() {
        // a distinct message, so a row that passes "" by accident is not told to write a better reason
        var e = assertThrows(IllegalArgumentException.class,
                () -> RawCondition.shapeErased("", "a reason that is present and adequate"));
        assertTrue(e.getMessage().contains("needs its text"), e.getMessage());
    }

    @Test
    void theTwoFactoriesDifferOnlyInTheReasonTheyRecord() {
        String text = "FieldFilled(Effort) And Sum(..*/Budget) > 0";
        assertEquals(RawCondition.Reason.SHAPE_ERASED,
                RawCondition.shapeErased(text, "the builder cannot construct it").reason());
        assertEquals(RawCondition.Reason.CONCRETE_SYNTAX,
                RawCondition.concreteSyntax(text, "the spelling is the subject").reason());
    }

    @Test
    void theTextReachesTheKernelByteIdentical() {
        // the whole safety argument for migrating a row INTO this wrapper: the law's verdict cannot move,
        // because the wrapper is a carrier and not a renderer. Asserted against the exact §10 row that uses
        // it, including the leading-slash absolute spelling a renderer would have rewritten.
        String text = "FieldFilled(Effort) And Sum(/Project/Milestones*/Budget) > 0";
        assertEquals(text,
                RawCondition.concreteSyntax(text, "the absolute spelling is this row's subject").text());
    }
}
