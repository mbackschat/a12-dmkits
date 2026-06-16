package io.github.mbackschat.a12.dm.core;

/**
 * A <b>kernel-free</b> reference resolver: given a model id, return that model's DM-JSON (or {@code null}
 * if unknown). It is the dev-facing common case for multi-model loading — {@code include}d models and
 * imported type-definitions resolved from wherever the app keeps them (a map, a service, a classpath) —
 * <em>without</em> pulling the caller into {@code com.mgmtp.a12.kernel.*}. Bridge it to the kernel's
 * {@code IDocumentModelReferenceResolver} with {@link ReferenceResolvers#of(ModelResolver)}, or pass it
 * straight to {@code BoundModel.ofJson(json, ModelResolver)} / {@code EditSession.read(json, ModelResolver)}.
 */
@FunctionalInterface
public interface ModelResolver {

    /** The DM-JSON of the model referenced by {@code modelId}, or {@code null} if this resolver has none. */
    String resolve(String modelId);
}
