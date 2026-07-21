package io.github.mbackschat.a12.dm.adapter.profile;

import io.github.mbackschat.a12.dm.core.ModelResolver;

/**
 * Public string boundary for extracting and comparing current identifier-free model-performance
 * profiles.
 *
 * <p>The returned JSON is the bare, deterministic current profile artifact. Profile records remain
 * implementation details so the public surface does not make an internal planning shape a compatibility
 * contract.
 */
public final class ModelProfiles {

    private ModelProfiles() {}

    /** Extract a bare current profile from one self-contained DM-JSON model. */
    public static String extract(String modelJson) {
        return ModelProfileJson.write(SynthesisProfile.of(modelJson));
    }

    /**
     * Extract a bare current profile after resolving the model's includes and imported type definitions.
     *
     * @param modelJson the source model's DM-JSON
     * @param resolver the kernel-free model-id-to-DM-JSON resolver
     */
    public static String extract(String modelJson, ModelResolver resolver) {
        return ModelProfileJson.write(SynthesisProfile.of(modelJson, resolver));
    }

    /**
     * Compare two current profile artifacts for performance-calibration fidelity.
     *
     * <p>Each input may be either a bare profile artifact or the normal result envelope containing
     * one under {@code data}. The returned deterministic JSON is the comparison data itself: it
     * reports the overall result and every classified axis without exposing the internal profile
     * records.
     *
     * @param targetJson the target current-profile artifact or extraction envelope
     * @param actualJson the actual current-profile artifact or extraction envelope
     */
    public static String compare(String targetJson, String actualJson) {
        var target = ModelProfileJson.read(targetJson);
        var actual = ModelProfileJson.read(actualJson);
        return ModelProfileComparator.compare(target, actual);
    }
}
