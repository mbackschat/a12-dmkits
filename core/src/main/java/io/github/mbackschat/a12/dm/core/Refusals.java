package io.github.mbackschat.a12.dm.core;

/**
 * The one place a client-side refusal names the kernel code it refuses on.
 *
 * <p>A refusal used to be written as prose — {@code throw new IllegalArgumentException("… (MVK_NO_DATE)")} —
 * which made the code invisible to the compiler and left "which codes do we refuse on" answerable only by
 * scanning source text for the shapes a refusal happens to be written in. Three such shapes escaped that
 * scan, and one live refusal went unmeasured for as long as it existed.
 *
 * <p>Routing every refusal through here makes that question a set operation: the codes passed to
 * {@link #of} ARE the pre-checked ones, and {@link KernelCode} makes a typo a compile error.
 *
 * <p>The rendered message keeps the {@code (CODE)} suffix, because it is what a human reading a stack trace
 * needs and what {@code Diagnostic} lifts when a caller wants the code back out.
 */
public final class Refusals {

    private Refusals() {
    }

    /**
     * The refusal to throw: {@code throw Refusals.of(KernelCode.MVK_NO_DATE, "Sum needs number operands")}.
     *
     * @param code the kernel code this refusal claims — it must have a measured kernel basis, which
     *     {@code ClientRefusalKernelBasisCensusTest} enforces
     * @param problem what is wrong, without the code; the code is appended
     */
    public static IllegalArgumentException of(KernelCode code, String problem) {
        return new IllegalArgumentException(message(code, problem));
    }

    /**
     * The rendered refusal text, {@code "<problem> (<CODE>)"} — the ONE spelling, so a refusal thrown from
     * here and one reported through a structured {@code Diagnostic} read identically in a stack trace.
     *
     * @param code the kernel code this refusal claims
     * @param problem what is wrong, without the code
     */
    public static String message(KernelCode code, String problem) {
        return problem + " (" + code.name() + ")";
    }
}
