package io.github.mbackschat.a12.dm.adapter.testing;

import io.github.mbackschat.a12.dm.interpreter.A12PartiallyKnownMultiPointer;
import io.github.mbackschat.a12.dm.interpreter.A12Pointer;
import io.github.mbackschat.a12.dm.interpreter.A12PointerFormat;
import io.github.mbackschat.a12.dm.interpreter.eval.PointerKt;

/**
 * The kernel-runtime probe's test-only projection of dmtool's published message-address policy.
 *
 * <p>This is deliberately a test fixture rather than adapter production API. The probe itself lives in the
 * adapter test source set, while a CLI test consumes this fixture to compare the independently implemented
 * projection with the policy owner, {@code cli.interp.A12Pointers.canonical}, over the same typed pointers.
 */
public final class KernelMessageAddressProjector {

    private KernelMessageAddressProjector() {
    }

    /** Canonical A12 for one cell; the interpreter house form otherwise. */
    public static String address(A12PartiallyKnownMultiPointer pointer) {
        A12Pointer exact = pointer.getRepetitionIndexes().isEmpty() ? null : pointer.toExactPointer();
        return exact != null ? A12PointerFormat.INSTANCE.render(exact)
                : PointerKt.formatPointer(pointer.getFullName(), pointer.getRepetitionIndexes());
    }
}
