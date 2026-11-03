package io.github.mbackschat.a12.dm.adapter;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.mgmtp.a12.kernel.md.combination.a12internal.CombinationModelService;
import com.mgmtp.a12.kernel.md.combination.a12internal.CombinationModelService.CombinationModelExpandParams;
import com.mgmtp.a12.kernel.md.combination.a12internal.IUnexpandedModelResolver;
import com.mgmtp.a12.kernel.md.combination.a12internal.UnexpandedModelResolverImpl;
import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.DocumentModelExpansionException;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;
import com.mgmtp.a12.model.notification.RankedNotification;
import com.mgmtp.a12.model.notification.Severity;

import io.github.mbackschat.a12.dm.core.ConsistencyCheckFailedException;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;

/**
 * The kernel side of the resolver seam, and this module's ONE kernel-expansion port: every kernel-facing
 * expansion (the consistency oracle, {@code KernelAdapter}'s MVK bootstrap and runtime build, the test oracles)
 * goes through {@link #expand}, so a kit hands the backend a plain {@code id → DM-JSON} function and the backend
 * does the kernel wiring.
 *
 * <p>Kernel 31 removed {@code IDocumentModelService#expand} (A12K-4021). Its 30.8.1 implementation was itself
 * deprecated in favour of {@code CombinationModelService#expand} ({@code cb66e51:…/DocumentModelService.java}
 * line 137), and the facade keeps that a12internal static entry point at 31.1.1, so this is the owner-chosen
 * replacement (KERNEL-31-UPGRADE-PROPOSAL D2). It returns a NEW model rather than expanding in place, and reports
 * problems as notifications instead of throwing; {@link #expand} folds an ERROR back into the kernel's own
 * {@link DocumentModelExpansionException}, the type the 30.8.1 entry point threw for the same event, so callers
 * keep one verdict channel.
 */
final class KernelResolvers {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    private KernelResolvers() {
    }

    /**
     * Expand {@code model}'s includes and type-definition imports through {@code resolver}.
     *
     * <p>Kernel 31.1.1's expansion copies the model through its serializer, whose basic consistency pass runs here,
     * so a model the kernel cannot build can die INSIDE this call (a computation targeting a group raises a
     * {@code ClassCastException}; KernelExpansionBuildFailureTest). That throw is the kernel's verdict on the caller's
     * bytes (KERNEL-FINDINGS KF259) and is folded as {@code checkConsistency}'s is. What our resolver bridge raised in
     * the same call is NOT folded: it is told apart by provenance rather than by type, since a consumer's resolver
     * may throw any type, and it rethrows unchanged so the earlier tier keeps reporting itself.
     *
     * @throws DocumentModelExpansionException when the kernel reports an ERROR or yields no model (the returned
     *         model "may only be partial" otherwise, per {@code CombinationModelService#expand}'s own contract)
     * @throws ConsistencyCheckFailedException when the kernel itself throws while building the model
     */
    static IDocumentModel expand(IDocumentModel model, ModelResolver resolver) {
        List<RankedNotification> reported = new ArrayList<>();
        AtomicReference<RuntimeException> bridgeFailure = new AtomicReference<>();
        Optional<IDocumentModel> expanded;
        try {
            expanded = CombinationModelService.expand(model, unexpanded(resolver, bridgeFailure),
                    CombinationModelExpandParams.builder().notificationReceiver(reported::add).build());
        } catch (DocumentModelExpansionException rejected) {
            throw rejected;
        } catch (RuntimeException thrown) {
            RuntimeException ours = bridgeFailure.get();
            if (ours != null) {
                throw ours;
            }
            throw KernelModelInput.couldNotBuild(thrown);
        }
        List<RankedNotification> errors = reported.stream()
                .filter(notification -> notification.getSeverity().isAtLeastAsSevereAs(Severity.ERROR))
                .toList();
        if (expanded.isEmpty() || !errors.isEmpty()) {
            throw new DocumentModelExpansionException("expanding document model '" + model.getHeader().getId()
                    + "' reported: " + errors.stream().map(RankedNotification::getMessage)
                            .collect(Collectors.joining(System.lineSeparator())));
        }
        return expanded.get();
    }

    /**
     * Bridge {@code resolver} to the kernel's unexpanded-model resolver. A {@code null} from the resolver fails
     * fast as an unresolved reference, and a resolved model that does not deserialize fails as
     * {@link ModelInputException} naming the reference — both BEFORE the kernel reads the text, because the
     * kernel's own reader would fold either into an expansion notification and relabel an input defect as a
     * kernel verdict (ModelInputClassificationTest).
     *
     * <p>Each reference is resolved once per bridge: kernel 31.1.1's expansion asks for a mounted model more than
     * once, which doubled the resolver calls of a one-include diff (ModelDiffLoadingTest, 2026-11-02), and a
     * consumer's resolver may read a file per call.
     */
    static IUnexpandedModelResolver unexpanded(ModelResolver resolver) {
        return unexpanded(resolver, new AtomicReference<>());
    }

    /** The resolved {@code json} of {@code reference}, once it is known to be a model the kernel can read. */
    private static String readable(IDocumentModelSerializer serializer, String reference, String json) {
        if (json == null) {
            throw new UnresolvedReferenceException(reference, "no model resolved for reference \"" + reference + "\"");
        }
        try {
            KernelModelInput.deserialize(serializer, json);
        } catch (ModelInputException e) {
            throw new ModelInputException("invalid resolved model \"" + reference + "\": " + e.getMessage(), e);
        }
        return json;
    }

    /** {@link #unexpanded(ModelResolver)}, recording the first failure that leaves the bridge in {@code failure}. */
    private static IUnexpandedModelResolver unexpanded(ModelResolver resolver, AtomicReference<RuntimeException> failure) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        Map<String, String> resolved = new HashMap<>();
        return new UnexpandedModelResolverImpl(reference -> {
            try {
                return new StringReader(resolved.computeIfAbsent(reference, r -> readable(serializer, r, resolver.resolve(r))));
            } catch (RuntimeException leaving) {
                failure.compareAndSet(null, leaving);
                throw leaving;
            }
        });
    }
}
