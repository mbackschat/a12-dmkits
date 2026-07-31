package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff;
import io.github.mbackschat.a12.dm.adapter.review.ModelReport;
import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A <b>receipt</b> that a consistency gate ran in <em>this</em> invocation — the only thing from which a verb may
 * publish the envelope's {@code verification} key (RESULT-CONTRACT-PROPOSAL §3).
 *
 * <p><b>Why a type and not a {@code Verification} argument.</b> {@code verification} answers "how was this
 * invocation's verdict arbitrated", so it may only be emitted where a gate actually ran. Passing a bare
 * {@link Verification} makes the wrong claim one keystroke away: {@code Backends.verification()} returns
 * {@code KERNEL_CONFIRMED} in the CLI <em>unconditionally</em>, because the CLI always bundles the backend — it
 * describes the <em>environment</em>, never a gate that ran. Stamping it onto a result therefore fabricates
 * provenance for read paths that never gated, which is the defect this contract removes. So the evidence cannot be
 * <em>named</em>, only <b>obtained</b>: every factory below takes an object whose type is itself the witness.
 *
 * <ul>
 *   <li>{@link #of(BoundModel)} — a binding attests via {@link BoundModel#consistencyGate()}: present for
 *       {@code ofJson}, <b>absent for {@code ofJsonLenient}</b>. A caller that switches to a lenient bind loses
 *       the evidence automatically, with no second edit and no way to forget.</li>
 *   <li>{@link #of(KernelAdapter)} — the adapter twin: present for {@code checkAndOpen}, absent for {@code of}.</li>
 *   <li>{@link #of(ModelReport)} — the report's <em>existence</em> is the witness: it can only be built by
 *       {@code ModelReport.of}, which always runs {@code checkAndOpen}. Nothing about the report needs to change.</li>
 *   <li>{@link #of(ValidationResult)} — a validator run attests for the candidate verbs, which hold no binding.</li>
 *   <li>{@link #of(ModelConsistencyException)} — a gate that ran and <em>rejected</em> attests through its own
 *       exception, which is the only thing a check verb holds on that branch.</li>
 *   <li>{@link #none()} — the explicit "no gate ran here" every ungated route must state out loud, so an absent
 *       {@code verification} key is a decision on the record rather than an omission.</li>
 * </ul>
 *
 * <p>There is deliberately <b>no</b> {@code kernelConfirmed()} factory, and no constructor taking a
 * {@code Verification}. That absence is the enforcement.
 */
public final class ConsistencyEvidence {

    private static final ConsistencyEvidence NONE = new ConsistencyEvidence(null);

    /** Null iff no gate ran; a gate that ran unbacked is {@link Verification#STRUCTURAL_ONLY}, not null. */
    private final Verification verification;

    private ConsistencyEvidence(Verification verification) {
        this.verification = verification;
    }

    /**
     * No consistency gate ran on this invocation's path, so the envelope makes no verification claim. Stated
     * explicitly at the call site — the contract requires an ungated route to <em>declare</em> its silence.
     */
    public static ConsistencyEvidence none() {
        return NONE;
    }

    /** The gate receipt this binding carries — {@link #none()} for a lenient / index-only binding. */
    public static ConsistencyEvidence of(BoundModel binding) {
        return from(binding.consistencyGate());
    }

    /** The gate receipt this adapter carries — {@link #none()} for the unchecked {@code KernelAdapter.of} path. */
    public static ConsistencyEvidence of(KernelAdapter adapter) {
        return from(adapter.consistencyGate());
    }

    /**
     * A comprehension report's receipt. {@code ModelReport.of} runs {@code KernelAdapter.checkAndOpen}
     * unconditionally, so holding a report <em>is</em> proof the gate ran on this invocation; only the mode has to
     * be read from the environment. Contrast {@code model report --tree}, which builds no report and must pass
     * {@link #none()}.
     */
    public static ConsistencyEvidence of(ModelReport report) {
        return report == null ? NONE : new ConsistencyEvidence(Backends.verification());
    }

    /**
     * A model diff's receipt, on the same witness-by-type basis as {@link #of(ModelReport)}: {@code ModelDiff}
     * loads <em>both</em> sides through {@code KernelAdapter.checkAndOpen}, so a diff object cannot exist without
     * two gates having run.
     */
    public static ConsistencyEvidence of(ModelDiff diff) {
        return diff == null ? NONE : new ConsistencyEvidence(Backends.verification());
    }

    /**
     * A validator run's receipt — for the candidate verbs ({@code rule check}, {@code rule add}) that never hold a
     * gated binding because the gate's <em>rejection</em> is the result they report.
     *
     * <p>Reads {@link ValidationResult#consistencyGate()}, which is <b>empty</b> when the validator rejected before
     * consulting the oracle. An earlier version read the result's mode field directly, which was stamped from
     * {@code Backends.verification()} — the <em>environment</em>, unconditionally {@code KERNEL_CONFIRMED} in the CLI
     * — so a rule whose group did not exist was published as {@code valid:false} with
     * {@code verification: KERNEL_CONFIRMED} beside a {@code PRECHECK}-sourced diagnostic. The envelope contradicted
     * itself, and the receipt was forged by exactly the ambient-mode mistake this class exists to prevent: the hole
     * was one layer down, in the type being trusted as a witness.
     */
    public static ConsistencyEvidence of(ValidationResult result) {
        return result == null ? NONE : from(result.consistencyGate());
    }

    /**
     * The receipt carried by a consistency gate's own <b>rejection</b>. The check verbs learn a model is invalid by
     * catching this exception, so on that branch they hold no binding at all — but the gate unambiguously ran, and
     * its notifications <em>are</em> the verdict. Without this witness the negative branch of a verdict would have
     * had to fabricate its evidence, which is the defect one layer along.
     */
    public static ConsistencyEvidence of(ModelConsistencyException rejection) {
        return rejection == null ? NONE : new ConsistencyEvidence(Backends.verification());
    }

    private static ConsistencyEvidence from(Optional<Verification> receipt) {
        return receipt.map(ConsistencyEvidence::new).orElse(NONE);
    }

    /** The arbitration mode to publish, or empty when no gate ran and the key must be omitted. */
    Optional<Verification> verification() {
        return Optional.ofNullable(verification);
    }

    /** Whether a gate ran at all — what the verdict factories require. */
    public boolean gateRan() {
        return verification != null;
    }
}
