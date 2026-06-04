package io.github.mbackschat.a12.rulekit.validate;

/**
 * Where a {@link Diagnostic} originates (proposal §F4a). The three origins are unified into one
 * shape so editors and agents parse them identically:
 * <ul>
 *   <li>{@link #PRECHECK} — a rulekit build-time slot/type pre-check (already fix-bearing today, as
 *       a thrown exception; not yet emitted as a {@code Diagnostic}).</li>
 *   <li>{@link #KERNEL} — an {@code MVK_*} problem from the real kernel's {@code checkConsistency}
 *       (F4), catalog-joined to its curated fix/meaning.</li>
 *   <li>{@link #LINT} — a valid-but-suspicious finding over the AST (a later increment).</li>
 * </ul>
 */
public enum DiagnosticSource {
    PRECHECK,
    KERNEL,
    LINT
}
