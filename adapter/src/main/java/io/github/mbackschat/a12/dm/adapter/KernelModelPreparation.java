package io.github.mbackschat.a12.dm.adapter;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.DocumentModelExpansionException;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;

import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.Notification;
import io.github.mbackschat.a12.dm.core.Severity;

/** One adapter-internal kernel deserialize/expand result, shared by checking and service bootstrap. */
final class KernelModelPreparation {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    private KernelModelPreparation() {
    }

    /**
     * Expand, then hold the model to the whole-model gate, folding a build failure of EITHER phase into one
     * rejection. Kernel 31.1.1 can die building the model inside expansion as well as inside the check
     * ({@link KernelResolvers#expand}), and each is a verdict on the caller's bytes (KF259).
     */
    static IDocumentModel requireConsistent(String modelJson, ModelResolver resolver) {
        ExpandedModel expanded;
        try {
            expanded = expand(modelJson, resolver);
        } catch (ConsistencyCheckFailedException failure) {
            throw couldNotBuild(failure);
        }
        return expanded.requireConsistent();
    }

    private static ModelConsistencyException couldNotBuild(ConsistencyCheckFailedException failure) {
        return new ModelConsistencyException(List.of(new Notification(Severity.ERROR, failure.getMessage())));
    }

    /**
     * Deserialize and expand.
     *
     * @throws ConsistencyCheckFailedException when the kernel itself dies building the model during expansion;
     *     a whole-model gate goes through {@link #requireConsistent(String, ModelResolver)}, which folds it
     */
    static ExpandedModel expand(String modelJson, ModelResolver resolver) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();
        IDocumentModel unexpanded = KernelModelInput.deserialize(serializer, modelJson);
        IDocumentModel model;
        try {
            model = KernelResolvers.expand(unexpanded, resolver);
        } catch (ConsistencyCheckFailedException failure) {
            throw KernelModelInput.namingAbsentMember(modelJson, failure);
        } catch (DocumentModelExpansionException rejected) {
            // KernelResolvers.expand throws this type for reported ERRORs; callback defects have no verdict.
            throw ModelConsistencyException.notKernelValid(rejected);
        }
        return new ExpandedModel(model, service, modelJson);
    }

    record ExpandedModel(IDocumentModel model, IDocumentModelService service, String modelJson) {

        /**
         * The kernel's own consistency verdict — with its <em>conversion</em> throws folded into the same
         * channel, because they are a verdict too.
         *
         * <p>The kernel raises a bare {@link RuntimeException} for a model it cannot build at all: a blank
         * temporal {@code format} reaches {@code DateTypeImpl}'s {@code notBlank} precondition and dies there,
         * inside {@code checkConsistency}, before any structured problem exists; a duplicate child name in one
         * group does the same. Left to escape, either arrives at a caller as an internal-defect envelope —
         * "this is a dmtool defect, not an invalid input" — which asserts the opposite of the truth
         * (KERNEL-FINDINGS KF259).
         *
         * <p><b>The narrowness is the point.</b> A failure to deserialize the DM-JSON, and a reference our own
         * resolver could not resolve, belong to earlier tiers that report themselves and must keep doing so —
         * relabelling either as a kernel consistency verdict would publish a gate receipt for a gate that never
         * ran. So only the KERNEL's own throws fold: this one wraps {@code checkConsistency}, and the one during
         * expansion is told apart from our resolver's by provenance in {@link KernelResolvers#expand}.
         *
         * <p>The typed {@link ConsistencyCheckFailedException} is what lets the port's two callers dispose of
         * the same event differently, which they must: the whole-model gate turns it into a verdict, the draft
         * gate keeps publishing no receipt.
         */
        List<Notification> notifications() {
            List<com.mgmtp.a12.model.notification.RankedNotification> raw = new ArrayList<>();
            try {
                // The callback only COLLECTS. Converting inside the guard would put our own code under a catch
                // that reports "the kernel could not build this model" — so a defect of ours (KernelNotifications
                // has an exhaustive switch over the kernel's Severity, which yields a MatchException if the
                // kernel ever adds a constant) would be published as a model rejection with a KERNEL_CONFIRMED
                // receipt. That is the inverse of the defect this guard exists to fix.
                underJudgingZone(() -> service.checkConsistency(model, raw::add));
            } catch (RuntimeException kernelCouldNotBuildTheModel) {
                throw KernelModelInput.namingAbsentMember(modelJson, KernelModelInput.couldNotBuild(kernelCouldNotBuildTheModel));
            }
            return raw.stream().map(KernelNotifications::toCore).toList();
        }

        /**
         * The adapter's WHOLE-MODEL gate, and the third caller of the port's throw contract.
         *
         * <p><b>It folds a build failure exactly as {@code ModelLoader.loadAndCheck} does, and for the same
         * reason:</b> a whole model has nothing injected, so a backend that refuses to construct it is
         * delivering its verdict on the caller's bytes. On 2026-09-07, an escaping build failure from
         * {@code model read} reached the former reader helper's plain exit-2 sink, while {@code model check}
         * already folded it into a kernel rejection. Both whole-model gates must consume the typed build
         * failure; otherwise a kernel refusal reaches the current internal-error mapper (KF259).
         */
        IDocumentModel requireConsistent() {
            List<Notification> reported;
            try {
                reported = notifications();
            } catch (ConsistencyCheckFailedException failure) {
                throw couldNotBuild(failure);
            }
            List<Notification> errors = reported.stream()
                    .filter(notification -> notification.severity() == Severity.ERROR)
                    .toList();
            if (!errors.isEmpty()) {
                throw new ModelConsistencyException(errors);
            }
            return model;
        }
    }

    /** The JVM default zone the consistency verdict is judged under, the kernel probe's too. */
    private static final TimeZone JUDGING_ZONE = TimeZone.getTimeZone("UTC");

    private static final Object JUDGING_ZONE_LOCK = new Object();

    /**
     * Runs {@code check} with the JVM default zone set to {@link #JUDGING_ZONE}, then gives the caller's back.
     *
     * <p>A12 judges a DateTime constant against the JVM default rather than the model's declared zone (KERNEL-FINDINGS
     * kf231), so without this one model's verdict followed the machine: a spring-forward gap label under a
     * Europe/Berlin model was refused on a Berlin default and admitted on a UTC one, which the portable checker and
     * the probe also admit. The default is process-global, so concurrent checks are serialized while it is swapped.
     */
    private static void underJudgingZone(Runnable check) {
        synchronized (JUDGING_ZONE_LOCK) {
            TimeZone previous = TimeZone.getDefault();
            TimeZone.setDefault(JUDGING_ZONE);
            try {
                check.run();
            } finally {
                TimeZone.setDefault(previous);
            }
        }
    }
}
