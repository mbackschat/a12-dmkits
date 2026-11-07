package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

/**
 * A computation's {@code errorCode} is read back from A12's stored member, so a typed read → modify → write cycle
 * keeps it, and the adapter reports it whether or not the formula converts (KERNEL-FINDINGS KF678).
 */
class ComputationErrorCodeReadTest {

    private static final String COMPUTATION = "/Order/BackorderQuantityComp";

    private static String withCode(String code) {
        EditSession session = EditSession.read(TestResources.text("models/order.dm.json"));
        session.addComputation(model -> model.computation(model.num("/Order/BackorderQuantity"))
                .name("BackorderQuantityComp").calc(model.num("/Order/Quantity").value()).errorCode(code)
                .message(Dsl.en("Backorder follows the quantity."))
                .build());
        return session.toJson();
    }

    @Test
    void theTypedReadKeepsTheCode() {
        KernelAdapter adapter = KernelAdapter.ofJson(withCode("BACKORDER_MISMATCH"));
        ReadComputation.Converted read = assertInstanceOf(ReadComputation.Converted.class,
                adapter.readComputation(COMPUTATION));
        assertEquals(Optional.of("BACKORDER_MISMATCH"), read.computation().errorCode());
        assertEquals(Optional.of("BACKORDER_MISMATCH"), adapter.computationErrorCode(COMPUTATION));
    }

    @Test
    void noCodeReadsAsNone() {
        KernelAdapter adapter = KernelAdapter.ofJson(withCode(null));
        assertEquals(Optional.empty(), adapter.computationErrorCode(COMPUTATION));
        assertEquals(Optional.empty(), ((ReadComputation.Converted) adapter.readComputation(COMPUTATION))
                .computation().errorCode());
    }
}
