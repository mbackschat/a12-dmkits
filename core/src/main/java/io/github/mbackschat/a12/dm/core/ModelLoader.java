package io.github.mbackschat.a12.dm.core;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.spi.ConsistencyOracle;

/**
 * The consistency gate (PROJECT-DESIGN §5a / F0): confirm a model is valid before rulekit binds to it. The
 * kernel-decoupling puts the real kernel behind a {@link ConsistencyOracle} port, so this class carries
 * <b>no kernel import</b> — it resolves the backend through {@link Backends} and stays kernel-free.
 *
 * <p>Two postures (KERNEL-DECOUPLING-PROPOSAL §2c):
 * <ul>
 *   <li><b>backend present</b> ({@link Verification#KERNEL_CONFIRMED}, the CLI always): deserialize →
 *       expand(resolver) → the real kernel's {@code checkConsistency}, failing fast on any {@code ERROR}.</li>
 *   <li><b>no backend</b> ({@link Verification#STRUCTURAL_ONLY}): the kernel-free {@link ModelReader}
 *       expands the model (throwing on malformed JSON / an unresolvable reference — the structural gate),
 *       and <b>no MVK verdict is fabricated</b>. A consumer learns the posture via {@link Backends#verification()}.</li>
 * </ul>
 */
public final class ModelLoader {

    private ModelLoader() {
    }

    /**
     * Run the consistency gate over a <em>self-contained</em> model (no includes / imported type-defs). If
     * the model does reference a separate artifact, this fails fast (the self-contained resolver refuses).
     * Sugar for {@link #loadAndCheck(String, ModelResolver)} with {@link ReferenceResolvers#selfContained()}.
     */
    public static Verification loadSelfContained(String dmJson) {
        return loadAndCheck(dmJson, ReferenceResolvers.selfContained());
    }

    /**
     * Run the consistency gate over {@code dmJson}, resolving includes / type-def imports via
     * {@code resolver}. Backend present: throws {@link ModelConsistencyException} on any {@code ERROR}-severity
     * notification. No backend: performs the structural expand only (still throws on a structural failure),
     * never a fabricated verdict.
     *
     * @return the gate that ran — the <b>receipt</b>, so a caller that needs to publish how a verdict was
     *     arbitrated reads it from the run rather than from {@link Backends#verification()}. The ambient call
     *     answers "is a backend on the classpath", which is unconditionally true in the CLI and therefore says
     *     nothing about whether this model was checked; every consumer that re-read it was one refactor away
     *     from reporting a gate that had not run.
     */
    public static Verification loadAndCheck(String dmJson, ModelResolver resolver) {
        Optional<ConsistencyOracle> oracle = Backends.consistencyOracle();
        if (oracle.isEmpty()) {
            // Degraded (structural-only): the kernel-free reader is the structural gate — it throws on
            // malformed JSON or an unresolvable reference; it makes no consistency/MVK promise.
            ModelReader.expand(dmJson, resolver);
            return Verification.STRUCTURAL_ONLY;
        }
        List<Notification> errors = oracle.get().checkConsistency(dmJson, resolver).stream()
                .filter(n -> n.severity() == Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            throw new ModelConsistencyException(errors);
        }
        return Verification.KERNEL_CONFIRMED;
    }
}
