package io.github.mbackschat.a12.dm.adapter.profile;

/** Fresh-process entry point for {@link ModelSynthesizerCrossJvmDeterminismTest}. */
final class ModelSynthesizerDeterminismMain {

    private ModelSynthesizerDeterminismMain() {}

    public static void main(String[] args) {
        long seed = Long.parseLong(args[0]);
        var profileJson = ModelProfileJson.write(profile());
        System.out.print(ModelSynthesizer.synthesize(profileJson, seed));
    }

    static ModelProfile profile() {
        return SynthesisTestProfiles.boundedTiedProfile();
    }
}
