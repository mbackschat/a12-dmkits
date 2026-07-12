package io.github.mbackschat.a12.dm.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * The whole-model read (F10): a foreign model's <b>validation rules</b> and its <b>computations</b>,
 * each read to a typed AST or preserved {@code Opaque}, as two <em>disjoint</em> maps keyed by full
 * name (insertion-ordered). Produced by {@link KernelAdapter#readModel()}.
 *
 * <p>The split matters: a computation is stored as a rule (it appears in the kernel's {@code getAllRules()}
 * too), but its condition is the generated self-validation of a calculated field — a different thing,
 * read via {@link KernelAdapter#readComputation}. {@code rules} carries only the hand-authored
 * validation rules and {@code computations} only the calculated fields, so nothing is double-counted and
 * each element kind is attributed correctly. Together they cover the model's whole rule surface.
 */
public record ModelRead(Map<String, ReadResult> rules, Map<String, ReadComputation> computations) {

    public ModelRead {
        rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
        computations = Collections.unmodifiableMap(new LinkedHashMap<>(computations));
    }

    /** The number of validation rules that converted to a typed AST (the rest are {@code Opaque}). */
    public int convertedRules() {
        return (int) rules.values().stream().filter(ReadResult::isConverted).count();
    }

    /** The number of computations that converted to a typed AST (the rest are {@code Opaque}). */
    public int convertedComputations() {
        return (int) computations.values().stream().filter(ReadComputation::isConverted).count();
    }

    /**
     * Whether every rule and every computation converted to a typed AST — the model round-trips fully
     * typed (nothing preserved {@code Opaque}), the publishing-safe condition.
     */
    public boolean fullyTyped() {
        return convertedRules() == rules.size() && convertedComputations() == computations.size();
    }
}
