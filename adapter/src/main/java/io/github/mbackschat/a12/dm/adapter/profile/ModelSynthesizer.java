package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.List;
import java.util.Set;

import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * Profile-driven own-domain model synthesis.
 *
 * <p>This is the small public boundary over the PR's original algorithm. Structure, rules, and computations
 * retain their donor planning and rendering behavior in focused collaborators; no legacy profile format or
 * command surface is exposed.
 */
public final class ModelSynthesizer {

    private ModelSynthesizer() {}

    /** Synthesize from the one current bare profile artifact or a normal extraction envelope. */
    public static String synthesize(String profileJson, long seed) {
        return synthesize(
                SynthesisProfile.from(ModelProfileJson.read(profileJson)),
                seed);
    }

    static String synthesize(SynthesisProfile profile, long seed) {
        BoundModel model = BoundModel.ofJson(
                StructureModelSynthesizer.plan(profile, seed).toJson());
        ComputationModelSynthesizer.Reservation reservation =
                ComputationModelSynthesizer.reserve(
                        model, profile.computations(), seed);
        Set<String> computedPaths = reservation.fields().stream()
                .map(io.github.mbackschat.a12.dm.rulekit.dsl.FieldInfo::path)
                .collect(java.util.stream.Collectors.toSet());

        RuleModelSynthesizer.Plan rulePlan = RuleModelSynthesizer.plan(
                model, profile, seed, computedPaths);
        ComputationModelSynthesizer.Plan computationPlan =
                ComputationModelSynthesizer.plan(model, profile, reservation, seed);

        List<Rule> rules = RuleModelSynthesizer.render(model, rulePlan, seed);
        List<Computation> computations = ComputationModelSynthesizer.render(
                model,
                computationPlan,
                reservation,
                profile.computations(),
                seed);
        return render(model, rules, computations);
    }

    private static String render(
            BoundModel model,
            List<Rule> rules,
            List<Computation> computations) {
        String json = model.toJson();
        for (Rule rule : rules) {
            json = ModelWriter.addRule(json, rule);
        }
        for (Computation computation : computations) {
            json = ModelWriter.addComputation(json, computation);
        }
        return BoundModel.ofJson(json).toJson();
    }
}
