package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * A temporal handle is the proof of what its field is to A12, so {@code BoundModel.date}, {@code time} and
 * {@code dateTime} admit a field by its DECLARED class alone, never by its kind (DEV-API-GAPS DG132). The refusal's
 * kernel basis is {@code validate.laws.ClockDeclaredDateFieldLawsTest}, which uses each refused field as the handle
 * would and is refused, and uses it as its declared class and is admitted. A starred list and a group operand
 * follow the same class, on the same kernel basis (DEV-API-GAPS DG129).
 */
class TemporalHandleDeclaredClassTest {

    private static final BoundModel M = BoundModel.ofJson(DocumentModelDraft.named("visit-handles").locale("en_US")
            .rootGroup("Visit", v -> v
                    .dateField("Arrival", d -> d.format("HH:mm:ss"))
                    .timeField("Shift", t -> t.format("yyyy-MM-dd"))
                    .dateTimeField("Booked", t -> t.format("yyyy-MM-dd")))
            .build().toJson());

    @Test
    void aFieldIsRefusedTheHandleOfItsKindWhenItsDeclarationSaysOtherwise() {
        assertThrows(IllegalArgumentException.class, () -> M.date("/Visit/Arrival"));
        assertThrows(IllegalArgumentException.class, () -> M.time("/Visit/Shift"));
        assertThrows(IllegalArgumentException.class, () -> M.dateTime("/Visit/Booked"));
    }

    @Test
    void itGetsTheHandleOfItsDeclaredClass() {
        assertDoesNotThrow(() -> M.time("/Visit/Arrival"));
        assertDoesNotThrow(() -> M.date("/Visit/Shift"));
        assertDoesNotThrow(() -> M.date("/Visit/Booked"));
    }

    /** A list or a group has no handle, so its operand carries the declared class itself (DG129). */
    private static final BoundModel ROUNDS = BoundModel.ofJson(DocumentModelDraft.named("visit-rounds").locale("en_US")
            .rootGroup("Round", r -> r
                    .group("Stops", s -> s.repeatable(4).dateField("ArrivedAt", d -> d.format("HH:mm:ss")))
                    .group("Pause", p -> p.dateField("BreakAt", d -> d.format("HH:mm:ss")))
                    .group("Checks", c -> c.repeatable(4).timeField("CheckedOn", t -> t.format("yyyy-MM-dd")))
                    .group("Audit", a -> a.timeField("AuditedOn", t -> t.format("yyyy-MM-dd"))))
            .build().toJson());

    @Test
    void aListOrGroupOperandIsTypedByItsFieldsDeclaredClass() {
        assertThrows(IllegalArgumentException.class, () -> Dsl.firstFilledDate(ROUNDS.flatten("/Round/Stops*/ArrivedAt")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.firstFilledDate(ROUNDS.firstFilledGroup("/Round/Pause")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.firstFilledTime(ROUNDS.flatten("/Round/Checks*/CheckedOn")));
        assertThrows(IllegalArgumentException.class, () -> Dsl.firstFilledTime(ROUNDS.firstFilledGroup("/Round/Audit")));
        assertDoesNotThrow(() -> Dsl.firstFilledTime(ROUNDS.flatten("/Round/Stops*/ArrivedAt")));
        assertDoesNotThrow(() -> Dsl.firstFilledTime(ROUNDS.firstFilledGroup("/Round/Pause")));
        assertDoesNotThrow(() -> Dsl.firstFilledDate(ROUNDS.flatten("/Round/Checks*/CheckedOn")));
        assertDoesNotThrow(() -> Dsl.firstFilledDate(ROUNDS.firstFilledGroup("/Round/Audit")));
    }
}
