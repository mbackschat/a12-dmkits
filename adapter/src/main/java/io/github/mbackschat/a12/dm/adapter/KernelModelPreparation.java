package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
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

    static ExpandedModel expand(String modelJson, ModelResolver resolver) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        IDocumentModelService service = FACTORY.createDocumentModelService();
        try {
            IDocumentModel model = serializer.deserialize(new StringReader(modelJson));
            service.expand(model, KernelResolvers.of(resolver));
            return new ExpandedModel(model, service);
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to deserialize the model DM-JSON", exception);
        }
    }

    record ExpandedModel(IDocumentModel model, IDocumentModelService service) {

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
         * <p><b>The narrowness is the point.</b> This wraps {@code checkConsistency} alone, never
         * {@link #expand}: a failure to deserialize the DM-JSON, and a reference our own resolver could not
         * resolve, belong to earlier tiers that report themselves and must keep doing so — relabelling either
         * as a kernel consistency verdict would publish a gate receipt for a gate that never ran.
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
                service.checkConsistency(model, raw::add);
            } catch (RuntimeException kernelCouldNotBuildTheModel) {
                String why = kernelCouldNotBuildTheModel.getMessage() != null
                        ? kernelCouldNotBuildTheModel.getMessage()
                        : kernelCouldNotBuildTheModel.getClass().getSimpleName();
                throw new ConsistencyCheckFailedException(
                        "the kernel could not build this model: " + why, kernelCouldNotBuildTheModel);
            }
            return raw.stream().map(KernelNotifications::toCore).toList();
        }

        /**
         * The adapter's WHOLE-MODEL gate, and the third caller of the port's throw contract.
         *
         * <p><b>It folds a build failure exactly as {@code ModelLoader.loadAndCheck} does, and for the same
         * reason:</b> a whole model has nothing injected, so a backend that refuses to construct it is
         * delivering its verdict on the caller's bytes. Left to escape, the typed exception reaches a CLI
         * reader's {@code catch (RuntimeException)} and — being neither of the two types
         * {@code failOrRethrowDomainRejection} rethrows — degrades into an unstructured text failure at exit 2,
         * where the same model's ordinary kernel rejection gets a JSON envelope at exit 1. Measured 2026-09-07
         * on {@code model read} with a blank temporal format; the sibling {@code model check}, which goes
         * through {@code ModelLoader}, was already correct. Two whole-model gates, one folded and one did not.
         */
        IDocumentModel requireConsistent() {
            List<Notification> reported;
            try {
                reported = notifications();
            } catch (ConsistencyCheckFailedException couldNotBuild) {
                throw new ModelConsistencyException(
                        List.of(new Notification(Severity.ERROR, couldNotBuild.getMessage())));
            }
            List<Notification> errors = reported.stream()
                    .filter(notification -> notification.severity() == Severity.ERROR)
                    .toList();
            if (!errors.isEmpty()) {
                throw new ModelConsistencyException(errors);
            }
            return model;
        }

        String variant() {
            return service.extractUsedVariants(model).stream().findFirst().orElse("");
        }
    }
}
