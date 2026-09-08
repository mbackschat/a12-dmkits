package io.github.mbackschat.a12.dm.adapter;


import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;

/**
 * The kernel-side of the resolver seam (kernel-decoupling stage 3, moved out of {@code dm.core}): adapts a
 * <b>kernel-free</b> {@link ModelResolver} ({@code id → DM-JSON}) to the kernel's own
 * {@link IDocumentModelReferenceResolver}, so a kit hands the backend a plain {@code id → DM-JSON} function
 * and the backend does the kernel wiring. Every kernel-facing expansion in this module (the consistency
 * oracle, {@code KernelAdapter}'s MVK bootstrap and runtime build) resolves includes / type-def imports
 * through here.
 */
final class KernelResolvers {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

    private KernelResolvers() {
    }

    /**
     * Bridge {@code resolver} to the kernel resolver. Each reference deserializes a <em>fresh</em> model
     * (expansion mutates in place, so a shared instance would corrupt repeated includes). A {@code null}
     * from the resolver, or a resolved model that fails to deserialize, fails fast.
     */
    static IDocumentModelReferenceResolver of(ModelResolver resolver) {
        IDocumentModelSerializer serializer = FACTORY.createDocumentModelSerializer();
        return reference -> {
            String json = resolver.resolve(reference);
            if (json == null) {
                throw new UnresolvedReferenceException(reference, "no model resolved for reference \"" + reference + "\"");
            }
            try {
                return KernelModelInput.deserialize(serializer, json);
            } catch (ModelInputException e) {
                throw new ModelInputException("invalid resolved model \"" + reference + "\": " + e.getMessage(), e);
            }
        };
    }
}
