package io.github.mbackschat.a12.dm.cli.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import io.github.mbackschat.a12.dm.adapter.testing.KernelMessageAddressProjector;
import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;

import org.junit.jupiter.api.Test;

/** The two publishing seams must make the same branch decision on the same typed pointer. */
class MessageAddressPolicyEquivalenceTest {

    private record PointerCase(String name, A12PartiallyKnownMultiPointer pointer) {}

    private static final List<PointerCase> POINTERS = List.of(
            pointer("exact", "/Order/Items/Code", 1, 2, 1),
            pointer("wildcard above the last part", "/Order/Items/Code", 1, 0, 0),
            pointer("trailing zero", "/Order/Items/Code", 1, 2, 0),
            pointer("unresolved index", "/Order/Items/Code", 1, -5, 0),
            pointer("parallel-iteration index", "/Order/Lines/Slots/SlotNo", 1, 1, -6, 1),
            pointer("root (empty repetitions)", "/"));

    @Test
    void cliAndKernelProbeAgreeForEveryPointerShape() {
        for (PointerCase candidate : POINTERS) {
            assertEquals(A12Pointers.canonical(candidate.pointer()),
                    KernelMessageAddressProjector.address(candidate.pointer()), candidate.name());
        }
    }

    private static PointerCase pointer(String name, String fullName, int... repetitions) {
        return new PointerCase(name, A12PartiallyKnownMultiPointer.Companion.of(
                fullName, java.util.Arrays.stream(repetitions).boxed().toList()));
    }
}
