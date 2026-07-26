package io.github.mbackschat.a12.dm.core;

/**
 * A <b>kernel-free</b> reference resolver: given a model id, return that model's DM-JSON (or {@code null}
 * if unknown). It is the dev-facing common case for multi-model loading — {@code include}d models and
 * imported type-definitions resolved from wherever the app keeps them (a map, a service, a classpath) —
 * <em>without</em> pulling the caller into {@code com.mgmtp.a12.kernel.*}. Kernel-facing backends adapt it at
 * their own boundary; ordinary callers pass it straight to
 * {@code BoundModel.ofJson(json, ModelResolver)} / {@code EditSession.read(json, ModelResolver)}.
 */
@FunctionalInterface
public interface ModelResolver {

    /**
     * The DM-JSON of the model referenced by {@code modelId}, or {@code null} if this resolver has none —
     * a {@code null} lets the downstream load report the missing reference. An implementation MAY instead
     * throw a diagnostic when a miss is an error for it: {@link ReferenceResolvers#directory} fail-fasts on
     * an unresolved id (a referenced-but-absent model in an indexed directory is a hard error), whereas the
     * map/lambda resolvers return {@code null}.
     */
    String resolve(String modelId);
}
