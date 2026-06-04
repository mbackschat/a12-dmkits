package io.github.mbackschat.a12.rulekit.catalog;

/**
 * Whether rulekit builds a typed handle for a catalog operator yet (proposal §9, stub-entries
 * policy). The catalog is a complete <em>map of the kernel operator space</em>, not only the built
 * subset, so an LLM (and {@code describe}/docs) can see that an operator exists and is deferred —
 * instead of hallucinating a handle that isn't there.
 *
 * <ul>
 *   <li>{@link #SUPPORTED} — rulekit has a typed AST node / handle; the entry carries an N7-validated
 *       {@code validExample}. The default (an absent {@code status} in the JSON means SUPPORTED, so
 *       existing entries need no change).</li>
 *   <li>{@link #PLANNED} — a <em>stub</em>: known to the kernel (keywords grounded in the lexer
 *       bundles), described for context, but not yet buildable — so it carries no example and is
 *       exempt from the "SUPPORTED ⇒ has a validExample" invariant.</li>
 * </ul>
 */
public enum OperatorStatus {
    SUPPORTED,
    PLANNED
}
