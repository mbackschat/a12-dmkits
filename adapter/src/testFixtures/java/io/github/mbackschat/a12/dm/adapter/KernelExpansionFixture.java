package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelSerializer;

import io.github.mbackschat.a12.dm.core.ModelResolver;

/**
 * The kernel-expansion oracle for tests: the same {@link KernelResolvers#expand} port production uses, so every
 * test that asks "what does the KERNEL expand this to?" exercises the one route the backend ships
 * (KERNEL-31-UPGRADE-PROPOSAL D2), instead of each test wiring its own call into the kernel.
 *
 * <p>It lives in test fixtures, not on the adapter's public surface, because its signatures carry kernel types
 * and the main module keeps those off every public signature (N8).
 */
public final class KernelExpansionFixture {

    private static final IDocumentModelSerializer SERIALIZER =
            new DocumentModelServiceFactory().createDocumentModelSerializer();

    private KernelExpansionFixture() {
    }

    /** Deserialize {@code modelJson} and expand it through the backend's expansion port. */
    public static IDocumentModel expanded(String modelJson, ModelResolver resolver) {
        try {
            return KernelResolvers.expand(SERIALIZER.deserialize(new StringReader(modelJson)), resolver);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("failed to deserialize model for kernel expansion", unreadable);
        }
    }

    /** {@link #expanded} serialized back to DM-JSON by the kernel's own model serializer. */
    public static String expandedJson(String modelJson, ModelResolver resolver) {
        StringWriter out = new StringWriter();
        try {
            SERIALIZER.serialize(expanded(modelJson, resolver), out, notification -> { });
        } catch (IOException unwritable) {
            throw new UncheckedIOException("failed to serialize the expanded model", unwritable);
        }
        return out.toString();
    }
}
