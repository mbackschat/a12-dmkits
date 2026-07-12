package io.github.mbackschat.a12.dm.rulekit.read;

import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;

/**
 * The outcome of reading one computation (the read direction for the model's <em>calculated</em>
 * fields — the {@link ReadResult} peer for computations). Pattern-match it:
 *
 * <ul>
 *   <li>{@link Converted} — the kernel computation mapped to a typed rulekit {@link Computation}
 *       (re-renderable, transformable, re-addable — carrying the recovered error messages so it
 *       survives the full read → modify → write-back cycle).</li>
 *   <li>{@link Opaque} — a computation the converter doesn't model yet (a non-{@code EXACT} rounding
 *       mode, or an operation kind rulekit's computation surface doesn't carry). Not lost, not
 *       corrupt: the computation element stays in the model verbatim and {@code reason} names why it
 *       wasn't typed.</li>
 * </ul>
 *
 * <p>Tolerant by design (N8): reading a model whose computations the kernel accepts never throws —
 * supported ones become typed {@code Computation}s, the rest are reported {@code Opaque}. Lives in
 * {@code rulekit} (kernel-decoupling stage 3), the peer of {@link ReadResult}.
 */
public sealed interface ReadComputation
        permits ReadComputation.Converted, ReadComputation.Opaque {

    /** A computation whose calculated field, preconditions, and operations converted to a typed AST. */
    record Converted(Computation computation) implements ReadComputation {}

    /**
     * A computation preserved verbatim because it uses a not-yet-modeled construct. Carries the
     * {@code canonicalText} — the computation's raw stored condition text (a computation is stored as a
     * rule, so this is its {@code errorConditionText}) — so it round-trips, not just the {@code reason}
     * it wasn't typed. Parity with {@link ReadResult.Opaque}.
     */
    record Opaque(String canonicalText, String reason) implements ReadComputation {}

    /** True iff this is a {@link Converted} result. */
    default boolean isConverted() {
        return this instanceof Converted;
    }
}
