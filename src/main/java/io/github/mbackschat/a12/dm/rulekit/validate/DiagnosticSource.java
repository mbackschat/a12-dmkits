package io.github.mbackschat.a12.dm.rulekit.validate;

/**
 * Where a {@link Diagnostic} originates (PROJECT-DESIGN §F4a). The origins are unified into one shape so
 * editors and agents parse them identically:
 * <ul>
 *   <li>{@link #PRECHECK} — a rulekit pre-check: hard at {@code build()} (completeness/structural,
 *       a thrown {@code PrecheckException}) or <i>deferred</i> and advisory (a kernel-semantic check
 *       the live kernel arbitrates, F11).</li>
 *   <li>{@link #KERNEL} — an {@code MVK_*} problem from the real kernel's {@code checkConsistency}
 *       (F4), catalog-joined to its curated fix/meaning.</li>
 *   <li>{@link #LINT} — a valid-but-suspicious finding over the AST, plus the gotcha INFO channel.</li>
 *   <li>{@link #ENVIRONMENT} — a toolchain note about the run itself, not the rule: chiefly the
 *       kernel-version skew advisory (F11), always INFO, never an error.</li>
 *   <li>{@link #INTERNAL} — the tool itself failed: an unexpected throwable caught at the command
 *       boundary (e.g. a native-image reflection-metadata gap), NOT a rejection of the input. Always
 *       ERROR; carried by an {@code RK_INTERNAL_ERROR} diagnostic so "the tool broke" is distinguishable
 *       from "the input was rejected".</li>
 * </ul>
 */
public enum DiagnosticSource {
    PRECHECK,
    KERNEL,
    LINT,
    ENVIRONMENT,
    INTERNAL
}
