package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.List;
import java.util.Set;

import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.Rule;

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
        return synthesized(profileJson, seed).dmJson();
    }

    /**
     * Synthesis with its gate <b>receipt</b> — the synthesized model is assembled through
     * {@link BoundModel#addAll}, which re-binds through the consistency oracle, so the model this returns has
     * been gated and a consumer publishing provenance must be able to say so.
     *
     * <p>Added because the plain {@link #synthesize(String, long)} returns only text: the gate ran and the
     * receipt was thrown away one frame later, leaving {@code profile synthesize} to publish an <em>ungated</em>
     * write over a model the kernel had just confirmed. Same shape as {@code ModelLoader.loadAndCheck}
     * (API-FINDINGS 111) — a gate hands out a receipt; a consumer never reconstructs one.
     */
    public static Synthesized synthesized(String profileJson, long seed) {
        return synthesized(ModelProfileJson.read(profileJson), seed);
    }

    /**
     * A synthesized model and the gate that cleared it.
     *
     * <p>The constructor is private because this type is a gate <b>witness</b>:
     * {@code ConsistencyEvidence.of(Synthesized)} publishes the receipt as provenance, so a value anyone can
     * construct would let a caller claim {@code KERNEL_CONFIRMED} over JSON no oracle ever saw. Same reason, and
     * the same shape, as {@code ModelReport}/{@code ModelDiff} — see {@code WitnessTypesAreUnforgeableTest}.
     */
    public static final class Synthesized {

        private final String dmJson;
        private final Verification consistencyGate;

        private Synthesized(String dmJson, Verification consistencyGate) {
            this.dmJson = dmJson;
            this.consistencyGate = consistencyGate;
        }

        /** The synthesized DM-JSON. */
        public String dmJson() {
            return dmJson;
        }

        /** The gate that cleared {@link #dmJson()} — {@code addAll} re-binds through the oracle, so it always ran. */
        public Verification consistencyGate() {
            return consistencyGate;
        }
    }

    static String synthesize(SynthesisProfile profile, long seed) {
        return synthesized(profile, seed).dmJson();
    }

    static Synthesized synthesized(SynthesisProfile profile, long seed) {
        if (profile.structure().repeatability().unboundedCount() > 0) {
            throw new ProfileInputException(
                    "profile synthesis requires bounded repeatable groups");
        }
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
        BoundModel assembled = model.addAll(rules, computations);
        return new Synthesized(
                assembled.toJson(),
                assembled.consistencyGate().orElseThrow(() -> new IllegalStateException(
                        "addAll re-binds through the gate, so its result must carry a receipt")));
    }
}
