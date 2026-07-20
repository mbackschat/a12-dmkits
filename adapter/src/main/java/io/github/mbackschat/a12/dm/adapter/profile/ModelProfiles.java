package io.github.mbackschat.a12.dm.adapter.profile;

import io.github.mbackschat.a12.dm.core.ModelResolver;

/**
 * Public string boundary for extracting the current identifier-free model-performance profile.
 *
 * <p>The returned JSON is the bare, deterministic current profile artifact. Profile records remain
 * implementation details so the public surface does not make an internal planning shape a compatibility
 * contract.
 */
public final class ModelProfiles {

    private ModelProfiles() {}

    /** Extract a bare current profile from one self-contained DM-JSON model. */
    public static String extract(String modelJson) {
        return ModelProfileJson.write(ModelProfileExtractor.extract(modelJson));
    }

    /**
     * Extract a bare current profile after resolving the model's includes and imported type definitions.
     *
     * @param modelJson the source model's DM-JSON
     * @param resolver the kernel-free model-id-to-DM-JSON resolver
     */
    public static String extract(String modelJson, ModelResolver resolver) {
        return ModelProfileJson.write(ModelProfileExtractor.extract(modelJson, resolver));
    }
}
