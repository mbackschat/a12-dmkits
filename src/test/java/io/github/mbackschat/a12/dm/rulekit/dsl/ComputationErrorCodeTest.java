package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.KernelCode;
import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * A computation's {@code errorCode}: the code of the validation A12 generates from it, which falls back to the
 * computation's name when unset ({@code validate.laws.ComputationErrorCodeLawsTest}; the runtime code is
 * {@code adapter.laws.ComputationErrorCodeDiffTest}).
 */
class ComputationErrorCodeTest {

    private static final BoundModel m = Fixtures.order();

    private static Computation.Builder backorder() {
        return m.computation(m.num("/Order/BackorderQuantity")).name("BackorderComp")
                .calc(m.num("/Order/Quantity").value())
                .message(Dsl.en("Backorder follows the quantity."));
    }

    @Test
    void theCodeReachesTheDraftAndIsWrittenWhereA12WritesIt() {
        Computation coded = backorder().errorCode("BACKORDER_MISMATCH").build();
        assertEquals(Optional.of("BACKORDER_MISMATCH"), coded.errorCode());
        assertEquals("BACKORDER_MISMATCH", coded.draft().errorCode());
        String json = ModelWriter.addComputation(Fixtures.orderJson(), coded);
        assertTrue(json.contains("\"errorCode\" : \"BACKORDER_MISMATCH\",\n"), () -> json);
        assertTrue(json.indexOf("\"errorCode\" : \"BACKORDER_MISMATCH\"") < json.indexOf("\"computationAlternatives\""),
                "A12's serializer writes errorCode ahead of the alternatives");
        ValidationResult vr = new RuleValidator().check(Fixtures.orderJson(), coded.draft());
        assertTrue(vr.ok(), () -> "the kernel must admit the coded computation: " + vr.diagnostics());
    }

    @Test
    void noCodeWritesNoMember() {
        Computation plain = backorder().build();
        assertEquals(Optional.empty(), plain.errorCode());
        assertEquals(null, plain.draft().errorCode());
        assertTrue(!ModelWriter.addComputation(Fixtures.orderJson(), plain).contains("\"errorCode\" : \"BACKORDER"));
    }

    /** A12 keeps a present blank code and refuses it as the generated rule's missing code, so the builder does too. */
    @Test
    void aBlankCodeIsRefusedWithA12sClass() {
        for (String blank : new String[] {"", "  "}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> backorder().errorCode(blank));
            assertEquals(KernelCode.MVK_ERROR_CODE_MISSING, ((KernelCodeRefusal) refusal).code(), "'" + blank + "'");
        }
    }
}
