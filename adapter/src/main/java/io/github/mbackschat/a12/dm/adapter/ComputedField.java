package io.github.mbackschat.a12.dm.adapter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * One computed field's outcome from {@link KernelAdapter#compute running the kernel's runtime computation}
 * over a document instance — the rulekit-level projection of a kernel {@code IComputedFieldInstance}.
 * The {@code outcome} discriminates what the kernel did with the field:
 *
 * <ul>
 *   <li>{@code "value"} — it computed a value ({@code value} present);</li>
 *   <li>{@code "cleared"} — it computed <em>nothing</em> and cleared the field (e.g. an empty operand —
 *       see {@code DateRangeComputationLawsTest}); {@code value} empty;</li>
 *   <li>{@code "error"} — an operand/computation error; {@code value} may carry the partial/last value.</li>
 * </ul>
 *
 * @param path        the computed field's absolute path — the DECLARATION, identical for every row of a
 *                    repeated computed field
 * @param outcome     {@code value} | {@code cleared} | {@code error}
 * @param value       the computed value when {@code outcome} is {@code value} (or a partial on {@code error})
 * @param repetitions the target cell's repetition indices ({@code IComputedFieldInstance.getRepetitions()}),
 *                    one per path part — what makes a repeated computed field's rows distinguishable. Dropping
 *                    these is why the kernel-side compute projection published four identical {@code path}s for
 *                    four rows, associable only by list position; the kernel had them all along
 */
public record ComputedField(String path, String outcome, Optional<String> value, List<Integer> repetitions) {

    public ComputedField {
        repetitions = repetitions == null ? List.of() : List.copyOf(repetitions);
    }

    /** The instance whose kernel repetitions arrive as the raw {@code int[]} of {@code getRepetitions()}. */
    public static ComputedField of(String path, String outcome, Optional<String> value, int[] repetitions) {
        return new ComputedField(path, outcome, value,
                repetitions == null ? List.of() : Arrays.stream(repetitions).boxed().toList());
    }
}
