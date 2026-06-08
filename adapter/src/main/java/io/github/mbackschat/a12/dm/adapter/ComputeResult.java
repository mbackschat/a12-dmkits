package io.github.mbackschat.a12.dm.adapter;

import java.util.List;

/**
 * The outcome of {@link KernelAdapter#compute running the kernel's runtime computation} over a document
 * instance: every computed field and what the kernel made of it (value / cleared / error), in kernel
 * order. Read-only — this reports what the computation <em>produces</em>; the input document is never
 * mutated (applying the result back is a separate, opt-in step the kernel leaves to the caller).
 */
public record ComputeResult(List<ComputedField> computed) {

    public ComputeResult {
        computed = List.copyOf(computed);
    }
}
