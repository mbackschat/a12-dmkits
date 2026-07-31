package io.github.mbackschat.a12.dm.cli;

import io.github.mbackschat.a12.dm.core.Verification;

/**
 * <b>Who</b> rejected the input — the fact that decides whether a {@code rejected} envelope carries a verdict and
 * its provenance (RESULT-CONTRACT-PROPOSAL §2/§3). Two kinds, and they are not interchangeable:
 *
 * <ul>
 *   <li>{@link KernelRejection} — the consistency gate ran and refused. The kernel <em>arbitrated this
 *       invocation</em>, so the envelope reports {@code valid:false} <b>and</b> its {@code verification}. This is
 *       the case the previous shape got wrong: {@code model read} on an inconsistent model returned
 *       {@code rejected} with no {@code verification} at all, even though the kernel is precisely what rejected
 *       it. {@code KERNEL_CONFIRMED} means "the kernel arbitrated", never "the model was valid" — verdict and
 *       evidence are orthogonal, so the marker must stay meaningful on a rejection.</li>
 *   <li>{@link PreflightRejection} — the CLI's own input gate refused before any kernel ran (an unknown spec key,
 *       a wrong-typed value, an unresolvable model reference). No gate ran, so there is <b>neither</b> a
 *       {@code valid} verdict nor a {@code verification} claim to make.</li>
 * </ul>
 *
 * <p>Sealed so the envelope's serialization dispatches on an exhaustive {@code switch} the compiler proves complete
 * — a third rejection kind cannot be added without deciding its verdict/evidence shape here.
 */
public sealed interface RejectionBasis {

    /**
     * The kernel consistency gate ran and rejected. Carries the gate's own receipt, so it can only be built where
     * a gate actually ran.
     */
    record KernelRejection(Verification verification) implements RejectionBasis {
        public KernelRejection {
            if (verification == null) {
                throw new IllegalArgumentException(
                        "a kernel rejection is arbitrated BY a gate, so it must carry that gate's verification mode");
            }
        }
    }

    /** The CLI's own pre-flight refused the input; no kernel was consulted, so no verdict and no evidence. */
    record PreflightRejection() implements RejectionBasis {
    }

    /** The receipt for a rejection the kernel gate produced, taken from that gate's own evidence. */
    static RejectionBasis kernel(ConsistencyEvidence evidence) {
        return evidence.verification().<RejectionBasis>map(KernelRejection::new)
                .orElseThrow(() -> new IllegalStateException(
                        "a kernel rejection needs the gate receipt that produced it; pass "
                                + "RejectionBasis.preflight() for a rejection the CLI itself made"));
    }

    /** The basis for a rejection the CLI made itself, before any kernel ran. */
    static RejectionBasis preflight() {
        return new PreflightRejection();
    }

    /**
     * <b>Derive</b> the basis from the evidence: a gate that ran arbitrated the rejection, one that did not means the
     * CLI rejected on its own. For the routes where <em>either</em> can happen and the caller cannot know which.
     *
     * <p>These routes are why this exists rather than a caller-chosen basis. A validator run can reject two ways —
     * the consistency oracle refused, or an add-time precheck fired before the oracle was reached
     * ({@code RK_NO_SUCH_GROUP}, a duplicate name) — and {@code rule add}, {@code computation add},
     * {@code rule check} and {@code patterns} all asserted the kernel basis unconditionally. That published
     * {@code verification: KERNEL_CONFIRMED} beside a {@code PRECHECK}-sourced diagnostic: an envelope contradicting
     * itself about a kernel run that never happened. Deriving removes the choice, so the mistake is not available.
     */
    static RejectionBasis from(ConsistencyEvidence evidence) {
        return evidence.verification().<RejectionBasis>map(KernelRejection::new).orElseGet(PreflightRejection::new);
    }
}
