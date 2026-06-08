package io.github.mbackschat.a12.dm.adapter;

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
 * @param path    the computed field's absolute path
 * @param outcome {@code value} | {@code cleared} | {@code error}
 * @param value   the computed value when {@code outcome} is {@code value} (or a partial on {@code error})
 */
public record ComputedField(String path, String outcome, Optional<String> value) {
}
