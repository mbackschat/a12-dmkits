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
        List<Notification> errors = notifications(oracle.get(), dmJson, resolver).stream()
                .filter(n -> n.severity() == Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            throw new ModelConsistencyException(errors);
        }
        return Verification.KERNEL_CONFIRMED;
    }

    /**
     * The gate over an <b>edited</b> model: {@link #loadAndCheck}, except that a reader refusal the source does not
     * draw is the gate rejecting the edit, raised as {@link ModelConsistencyException#editUnreadable}. A refusal the
     * source draws too stays the input's {@link ModelInputException}, so blame follows whichever text first
     * carried the unreadable value (CLI-CONFORMANCE-SPEC, `EditInducedReaderRefusalTest`). The source is re-read
     * only on that failure path.
     *
     * @param source the text the edit started from; {@code null} when there is none, which keeps the plain gate
     */
    public static Verification checkEdit(String edited, String source, ModelResolver resolver) {
        try {
            return loadAndCheck(edited, resolver);
        } catch (ModelInputException unreadable) {
            if (source == null || source.equals(edited) || !reads(source, resolver)) {
                throw unreadable;
            }
            throw ModelConsistencyException.editUnreadable(unreadable);
        }
    }

    /** Whether the reader binds {@code json}; a model it binds but the gate then rejects still reads. */
    private static boolean reads(String json, ModelResolver resolver) {
        try {
            loadAndCheck(json, resolver);
            return true;
        } catch (ModelInputException unreadable) {
            return false;
        } catch (RuntimeException readButRejected) {
            return true;
        }
    }

    /**
     * The oracle's verdict, with a backend that could not BUILD the model folded into the same channel.
     *
     * <p>A whole model has nothing injected, so a build failure is the backend's verdict on the bytes it was
     * handed and belongs in the notification list with its receipt intact. Left to escape it reaches a caller
     * as an internal-defect envelope — "this is a tool defect, not an invalid input" — which asserts the
     * opposite of the truth (KERNEL-FINDINGS KF259). The draft gate wants the other disposition and gets it,
     * because {@link ConsistencyCheckFailedException} stays a {@link RuntimeException} for its catch-all.
     */
    private static List<Notification> notifications(ConsistencyOracle oracle, String dmJson,
            ModelResolver resolver) {
        try {
            return oracle.checkConsistency(dmJson, resolver);
        } catch (ConsistencyCheckFailedException couldNotBuild) {
            return List.of(new Notification(Severity.ERROR, couldNotBuild.getMessage()));
        }
    }
}
