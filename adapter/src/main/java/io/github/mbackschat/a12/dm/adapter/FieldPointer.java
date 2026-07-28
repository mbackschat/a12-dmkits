package io.github.mbackschat.a12.dm.adapter;

import java.util.List;

/**
 * A field-instance address as JDK types — the boundary conversion (N8) of a kernel
 * {@code PartiallyKnownDocumentMultiPointer}: the field's {@code path} plus one 1-based repetition
 * {@code index} per path part (the kernel's {@code repetitionIndexes()}). Kernel pointer types never
 * escape the adapter. This is the same (name, repetition) pair list A12's own {@code DocumentPointerImpl}
 * holds internally; when it has to be rendered, the canonical string form is A12's — {@code Order/Items[2]/Count},
 * {@code [1]} omitted — not a free choice (see {@code A12PointerFormat} and KERNEL-FINDINGS "The canonical
 * Document JSON and DocumentPointer string").
 */
public record FieldPointer(String path, List<Integer> reps) {

    public FieldPointer {
        reps = List.copyOf(reps);
    }
}
