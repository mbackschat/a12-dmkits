package io.github.mbackschat.a12.rulekit.model;

import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelReferenceResolver;

/** Factory methods for the kernel's {@link IDocumentModelReferenceResolver}. */
public final class ReferenceResolvers {

    private ReferenceResolvers() {
    }

    /**
     * A resolver for <em>self-contained</em> models — those with no {@code include}d models or
     * imported type-definitions. If expansion ever asks it to resolve a reference, it fails fast
     * with an actionable message rather than silently indexing a half-resolved model.
     */
    public static IDocumentModelReferenceResolver selfContained() {
        return reference -> {
            throw new IllegalStateException(
                    "model references a separate model (\"" + reference + "\") but was treated as "
                            + "self-contained; supply a resolver that can resolve it");
        };
    }
}
