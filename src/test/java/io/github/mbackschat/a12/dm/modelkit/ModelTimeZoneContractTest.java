package io.github.mbackschat.a12.dm.modelkit;

import static io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract.Domain.ABSENT_DEFAULT;
import static io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract.Domain.INTERPRETER_SUPPORTED;
import static io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract.Domain.KERNEL_INVALID;
import static io.github.mbackschat.a12.dm.modelkit.ModelTimeZoneContract.Domain.KERNEL_LEGAL_INTERPRETER_UNSUPPORTED;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ModelTimeZoneContractTest {

    @Test
    void classifiesTheFourAuthoringDomainsWithTheJvmTimeZoneRegistry() {
        assertEquals(ABSENT_DEFAULT, ModelTimeZoneContract.classify(false, null));
        assertEquals(KERNEL_INVALID, ModelTimeZoneContract.classify(true, null));
        assertEquals(KERNEL_INVALID, ModelTimeZoneContract.classify(true, ""));
        assertEquals(KERNEL_INVALID, ModelTimeZoneContract.classify(true, " \t "));
        assertEquals(INTERPRETER_SUPPORTED, ModelTimeZoneContract.classify(true, "UTC"));
        assertEquals(INTERPRETER_SUPPORTED, ModelTimeZoneContract.classify(true, "GMT"));
        assertEquals(INTERPRETER_SUPPORTED, ModelTimeZoneContract.classify(true, "Europe/Berlin"));
        assertEquals(KERNEL_INVALID, ModelTimeZoneContract.classify(true, "Europe/Berln"));
        assertEquals(KERNEL_LEGAL_INTERPRETER_UNSUPPORTED,
                ModelTimeZoneContract.classify(true, "America/New_York"));
    }
}
