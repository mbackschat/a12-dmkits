package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff;
import io.github.mbackschat.a12.dm.adapter.review.ModelReport;
import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
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
 *   <li>{@link #of(ModelDocument)} / {@link #of(EditSession)} — the <b>edit</b> gates, which is what a mutating verb
 *       holds. Both are mutable, so their receipts are <b>state-checked</b>: an edit after the gate revokes the
 *       receipt rather than leaving it vouching for a tree that no longer exists.</li>
 *   <li>{@link #of(ModelReport)} / {@link #of(ModelDiff)} — a review artefact stores the gate it was assembled
 *       over, so it attests exactly as a binding does; both are closed types, so the receipt cannot be hand-built.</li>
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
     * The edit gate's receipt for the document as it stands — {@link ModelDocument#consistencyGate()}. This is the
     * witness almost every mutating verb holds: they edit a {@code ModelDocument}, gate it with
     * {@code validate(resolver)}, then write.
     *
     * <p>Unlike every witness above it, a document is <b>mutable</b>, so its receipt is state-checked and an edit
     * after the gate revokes it. A caller therefore cannot publish {@code KERNEL_CONFIRMED} over a tree the kernel
     * never saw, however it orders its calls.
     */
    public static ConsistencyEvidence of(ModelDocument document) {
        return document == null ? NONE : from(document.consistencyGate());
    }

    /**
     * The terminal gate's receipt for a session — {@link EditSession#consistencyGate()}, on the same state-checked
     * terms as {@link #of(ModelDocument)}. What {@code apply} publishes: its staged steps are ungated by design and
     * attest nothing, and this attests for the state the transaction actually commits.
     */
    public static ConsistencyEvidence of(EditSession session) {
        return session == null ? NONE : from(session.consistencyGate());
    }

    /**
     * A comprehension report's receipt — the gate the report was <em>assembled over</em>
     * ({@link ModelReport#consistencyGate()}), not the ambient mode. {@code ModelReport.of} runs
     * {@code KernelAdapter.checkAndOpen}, so the receipt is present there and absent for an assembly over the
     * unchecked adapter. Contrast {@code model report --tree}, which builds no report and must pass
     * {@link #none()}.
     *
     * <p>This used to read {@code Backends.verification()} on the grounds that holding a report proves the gate
     * ran, so "only the mode" came from the environment. Both halves failed: the mode is exactly the claim being
     * published, and the report was a {@code public record}, so the proof was one constructor call away from
     * being forged.
     */
    public static ConsistencyEvidence of(ModelReport report) {
        return report == null ? NONE : from(report.consistencyGate());
    }

    /**
     * A model diff's receipt, on the same basis as {@link #of(ModelReport)} — read from the diff, which records
     * the gate <b>both</b> sides passed through and leaves it empty when they disagree or either went ungated.
     */
    public static ConsistencyEvidence of(ModelDiff diff) {
        return diff == null ? NONE : from(diff.consistencyGate());
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
     * the ambient mode is what arbitrated it. Without this witness the negative branch of a verdict would have had
     * to fabricate its evidence, which is the defect one layer along.
     *
     * <p><b>Two tiers, one receipt — and why that is sound here.</b> The exception has a second, notification-free
     * constructor for a refusal that came <em>before</em> {@code checkConsistency} (deserialize/expand), so its
     * notifications are not always the verdict; a claim that they are was wrong. The receipt still holds, because
     * either tier means the load ran and refused, and {@code Backends.verification()} names which engine did it —
     * the kernel oracle, or our own reader in the backend-free mode. What does <em>not</em> transfer is the
     * diagnostic's origin, which is why the notification-free tier derives it from this receipt rather than
     * defaulting to {@code PRECHECK}
     * ({@link io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic#gateRefusal}).
     *
     * <p>This is only sound because the gates no longer funnel <em>our own</em> failures into this type: an
     * {@code UnresolvedReferenceException} from our resolver now escapes both edit gates unwrapped, since a
     * refusal we made ourselves would otherwise be published with the kernel's receipt on it.
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

    /**
     * The gate that ran, for a receipt already known to carry one — what a diagnostic needs when its origin
     * depends on which engine refused ({@link io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic#gateRefusal}).
     * Fails loudly rather than substituting a mode, since a fabricated one is the defect this class prevents.
     */
    Verification gate() {
        if (verification == null) {
            throw new IllegalStateException(
                    "no gate ran, so there is no arbitration mode to read — check gateRan() first");
        }
        return verification;
    }
}
