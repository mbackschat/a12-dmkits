package io.github.mbackschat.a12.dm.cli;

import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff;
import io.github.mbackschat.a12.dm.adapter.profile.ModelSynthesizer;
import io.github.mbackschat.a12.dm.adapter.review.ModelReport;
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter;
import io.github.mbackschat.a12.dm.core.Backends;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * A <b>receipt</b> that a consistency gate ran in <em>this</em> invocation — the only thing from which a verb may
 * publish the envelope's {@code verification} key (CLI-SPEC §3b "The result envelope").
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
 *   <li>{@link #of(PortableCheck)} — the <b>portable</b> gate's witness. A {@code ConsistencyReport} is a
 *       public data class its consumers rebuild, so holding one proves nothing; {@link PortableCheck} can
 *       only be obtained by running the checker, which is what makes it a witness.</li>
 *   <li>{@link #none()} — the explicit "no gate ran here" every ungated route must state out loud, so an absent
 *       {@code verification} key is a decision on the record rather than an omission.</li>
 * </ul>
 *
 * <p><b>The engine half, and why {@code requested} is kept OUT of every witness.</b> Which engine ran is
 * derived from the receipt ({@link #executedEngine()}), so it cannot disagree with the arbitration it is read
 * from. Which engine the caller ASKED for cannot be witnessed by anything the gate produces — it is an input —
 * so a verb that offers a choice records it through {@link #requestedBy(CheckEngine)}, which is the one place
 * both facts meet and therefore the place that <b>refuses a receipt answering a different request</b>. That
 * refusal is what makes engine substitution unrepresentable rather than merely reportable: a dispatch that ran
 * the kernel under {@code --engine=portable} cannot build an envelope at all, however plausible its verdict.
 *
 * <p>There is deliberately <b>no</b> {@code kernelConfirmed()} factory, and no constructor taking a
 * {@code Verification}. That absence is the enforcement.
 */
public final class ConsistencyEvidence {

    private static final ConsistencyEvidence NONE = new ConsistencyEvidence(null, null);

    /** Null iff no gate ran; a gate that ran unbacked is {@link Verification#STRUCTURAL_ONLY}, not null. */
    private final Verification verification;

    /** The engine the caller asked for — null until a verb that offers a choice records it. */
    private final CheckEngine requested;

    private ConsistencyEvidence(Verification verification, CheckEngine requested) {
        this.verification = verification;
        this.requested = requested;
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
     * A synthesized model's receipt — {@code profile synthesize} assembles through {@code BoundModel.addAll},
     * which re-binds through the oracle, so the model it writes has been gated.
     *
     * <p>This route was classified ungated on first reading and was not: the gate ran one frame away, inside a
     * helper that returned only text. That is the shape API-FINDINGS 111 names — a gate that hands back nothing
     * forces its consumer to guess — and guessing here produced a silent {@code none()} rather than a wrong
     * claim, which is why only a review caught it.
     */
    public static ConsistencyEvidence of(ModelSynthesizer.Synthesized synthesized) {
        return synthesized == null ? NONE : new ConsistencyEvidence(synthesized.consistencyGate(), null);
    }

    /**
     * A JSON-Schema import's receipt — present only when Tier-A+ rule synthesis both ran (it assembles through
     * {@code BoundModel}) and was not followed by carriage injection rewriting the tree. A purely structural
     * transcode binds nothing and correctly attests nothing.
     */
    public static ConsistencyEvidence of(JsonSchemaImporter.ImportResult result) {
        return result == null ? NONE : from(result.consistencyGate());
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
        return rejection == null ? NONE : new ConsistencyEvidence(Backends.verification(), null);
    }

    /**
     * The <b>portable</b> checker's receipt, witnessed by the report it produced.
     *
     * <p>{@link PortableCheck} has exactly one producer — its own factory, which runs the checker — so holding
     * one is proof of which engine answered, on the same terms as every witness above: the mode is read off
     * the object rather than named by the caller. It attests {@link Verification#PORTABLE_CHECKED}
     * unconditionally, because the check ran to completion, and the checker states its own coverage in its
     * report rather than in this receipt.
     *
     * <p><b>A report that concluded nothing is still a receipt.</b> An INCONCLUSIVE report means the checker
     * ran and could not establish a verdict, which is a gate that ran — so the envelope publishes its
     * arbitration and withholds {@code valid}. Treating it as {@link #none()} instead would lose the one fact
     * the caller needs: that the engine they selected is the engine that answered.
     */
    public static ConsistencyEvidence of(PortableCheck check) {
        return check == null ? NONE : new ConsistencyEvidence(Verification.PORTABLE_CHECKED, null);
    }

    private static ConsistencyEvidence from(Optional<Verification> receipt) {
        return receipt.map(gate -> new ConsistencyEvidence(gate, null)).orElse(NONE);
    }

    /**
     * Which engine produced this receipt, derived from the arbitration it records so the two cannot disagree.
     *
     * <p>Empty for {@link Verification#STRUCTURAL_ONLY}: that mode means no backend was found and our own
     * structural prechecks ran, which is neither of the two checking engines. The CLI always bundles the
     * backend, so it does not arise here — and naming a third engine for it would invent a concept to describe
     * a path that cannot occur.
     */
    public Optional<CheckEngine> executedEngine() {
        if (verification == null) {
            return Optional.empty();
        }
        return switch (verification) {
            case KERNEL_CONFIRMED -> Optional.of(CheckEngine.A12_KERNEL);
            case PORTABLE_CHECKED -> Optional.of(CheckEngine.PORTABLE_CHECKER);
            case STRUCTURAL_ONLY -> Optional.empty();
        };
    }

    /** The engine the caller asked for, recorded by {@link #requestedBy(CheckEngine)}; empty on verbs with no choice. */
    public Optional<CheckEngine> requestedEngine() {
        return Optional.ofNullable(requested);
    }

    /**
     * The build of the engine that answered, read from that engine rather than stated by this tool.
     *
     * <p>The portable arm reports the checker artifact's own baked version, so a {@code dmtool} resolving a
     * different checker build reports that build; the kernel arm reports the kernel actually on the classpath,
     * never the built-against pin, on the same rule {@code RuntimeEvidence.kernel()} follows. Either way the
     * version describes what ran, which a constant in this class could not do.
     */
    public Optional<String> engineVersion() {
        return executedEngine().map(engine -> switch (engine) {
            case A12_KERNEL -> VersionInfo.current().kernelRuntime();
            case PORTABLE_CHECKER -> io.github.mbackschat.a12.dm.consistency.CheckerIdentityKt.CHECKER_VERSION;
        });
    }

    /**
     * Record the engine the caller asked for, and refuse a receipt that answers a different request.
     *
     * <p>This is the enforcement point for F1's no-substitution rule, and it throws rather than reporting,
     * because a gate answering the wrong request is a defect in this tool and not a fact about the model. A
     * mutant that routes {@code --engine=portable} to the kernel therefore cannot produce an envelope at all,
     * even when the kernel would have returned the same verdict — which is exactly the case a comparison of
     * verdicts cannot catch.
     *
     * @throws IllegalStateException if no gate ran, if the executed engine cannot be determined, or if it is
     *                               not the one that was requested
     */
    public ConsistencyEvidence requestedBy(CheckEngine engine) {
        CheckEngine executed = executedEngine().orElseThrow(() -> new IllegalStateException(
                "a verb that selects its checking engine must hold a receipt naming the engine that ran; this "
                        + "one attests " + (verification == null ? "no gate at all" : verification.name())));
        if (executed != engine) {
            throw new IllegalStateException("engine substitution: `" + engine.wireName() + "` was requested and `"
                    + executed.wireName() + "` answered. The selected engine is never replaced — not on an "
                    + "incomplete answer, not on a failure, and not when the other engine would agree.");
        }
        return new ConsistencyEvidence(verification, engine);
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
