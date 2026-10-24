package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * A temporal handle is the proof of what its field is to A12, so {@code BoundModel.date}, {@code time} and
 * {@code dateTime} admit a field by its DECLARED class alone, never by its kind (DEV-API-GAPS DG132). The refusal's
 * kernel basis is {@code validate.laws.ClockDeclaredDateFieldLawsTest}, which uses each refused field as the handle
 * would and is refused, and uses it as its declared class and is admitted.
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
}
