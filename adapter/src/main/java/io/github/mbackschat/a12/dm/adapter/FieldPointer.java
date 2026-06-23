package io.github.mbackschat.a12.dm.adapter;

import java.util.List;

/**
 * A field-instance address as JDK types — the boundary conversion (N8) of a kernel
 * {@code PartiallyKnownDocumentMultiPointer}: the field's {@code path} plus one 1-based repetition
 * {@code index} per path part (the kernel's {@code repetitionIndexes()}). Kernel pointer types never
 * escape the adapter; the consumer renders this however it likes (e.g. {@code /Order/Items[2]/Count}).
 */
public record FieldPointer(String path, List<Integer> reps) {

    public FieldPointer {
        reps = List.copyOf(reps);
    }
}
