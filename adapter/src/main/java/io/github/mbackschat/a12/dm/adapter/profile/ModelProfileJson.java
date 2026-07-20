package io.github.mbackschat.a12.dm.adapter.profile;

/** Package boundary for the deterministic current-only model-profile JSON format. */
final class ModelProfileJson {

    private ModelProfileJson() {}

    static String write(ModelProfile profile) {
        return ProfileJsonWriter.write(ProfileVocabulary.validate(profile));
    }

    static ModelProfile read(String json) {
        return ProfileVocabulary.validate(ProfileJsonReader.read(json));
    }
}
