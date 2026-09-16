package io.github.mbackschat.a12.dm.core;

/**
 * How thoroughly a load/validate result was checked — the honesty marker of the kernel-decoupling
 * degraded mode (KERNEL-DECOUPLING-PROPOSAL §2c). It is a property of the <em>binding</em>: it records
 * whether the authoritative kernel backend governed the run, so a consumer can never read a green result
 * as kernel-confirmed when no backend was present.
 *
 * <ul>
 *   <li>{@link #KERNEL_CONFIRMED} — the kernel {@link io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle}
 *       backend was on the classpath and arbitrated the consistency gate (the only mode the CLI ever reports,
 *       since it always bundles the backend).</li>
 *   <li>{@link #STRUCTURAL_ONLY} — no backend was discovered, so only our own structural prechecks + the
 *       kernel-free reader ran; <b>no MVK verdict is fabricated</b>. A result in this mode may still be
 *       {@code ok()} (no ERROR among the checks that ran), but that must never be read as kernel-green —
 *       {@code verification()} is the signal, paired with an {@code RK_NO_KERNEL_BACKEND} advisory.</li>
 *   <li>{@link #PORTABLE_CHECKED} — the kernel-free portable checker ({@code :consistency}) arbitrated, on a
 *       path where it was <b>explicitly asked for</b>. Distinct from both neighbours on purpose: it is not
 *       {@code KERNEL_CONFIRMED}, because no kernel saw the model, and it is not {@code STRUCTURAL_ONLY},
 *       because a real re-implementation of A12's constraints ran rather than our own structural prechecks.
 *       It says nothing about how COMPLETE that run was — the checker publishes its own coverage evidence
 *       and, today, cannot conclude VALID at all.</li>
 * </ul>
 *
 * <p><b>This is not the engine axis, and a value is never added to name an engine.</b> It records how a
 * verdict was arbitrated; which implementation evaluated a document is {@code RuntimeEvidence}, which is why
 * that type's contract forbids an {@code INTERPRETER_OBSERVED} value here. {@code PORTABLE_CHECKED} earns its
 * place because the portable checker produces a consistency <em>verdict</em>, which is this axis exactly.
 */
public enum Verification {
    /** The kernel backend confirmed the consistency/MVK gate. */
    KERNEL_CONFIRMED,
    /** No backend was present — structural prechecks only, never a kernel verdict. */
    STRUCTURAL_ONLY,
    /** The explicitly selected kernel-free portable checker arbitrated; never a kernel confirmation. */
    PORTABLE_CHECKED
}
