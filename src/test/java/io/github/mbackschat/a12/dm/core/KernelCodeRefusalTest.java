package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class KernelCodeRefusalTest {

    /** Withholding keeps what was wrong and drops every trace of the class, so no reader can lift it back out. */
    @Test
    void aWithheldRefusalKeepsTheProblemAndCarriesNoCode() {
        KernelCodeRefusal refusal = Refusals.of(KernelCode.MVK_DUPLICATE_PARAM1, "'/Item/Title' is listed twice");

        IllegalArgumentException withheld = refusal.withheld();

        assertFalse(withheld instanceof KernelCodeRefusal);
        assertEquals("'/Item/Title' is listed twice", withheld.getMessage());
        assertEquals(null, withheld.getCause());
    }
}
