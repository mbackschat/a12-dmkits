package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Map;

/** Current-profile fixtures that exercise synthesis-specific boundaries. */
final class SynthesisTestProfiles {

    private SynthesisTestProfiles() {}

    static ModelProfile boundedTiedProfile() {
        var base = ProfileJsonFixture.profile(false);
        var structure = base.structure();
        var repeatability = structure.repeatability();
        var capacities = repeatability.capacities();
        var boundedRepeatability = new ModelProfile.RepeatabilityProfile(
                LongHistogram.of(Map.of("BOUNDED", 2L)),
                new ModelProfile.RepeatableTopology(
                        repeatability.topology().maxDepth(),
                        LongHistogram.of(Map.of("1", 1L, "2", 1L)),
                        repeatability.topology().siblingFan()),
                new ModelProfile.RepeatableCapacities(
                        bounded(capacities.maxRepetitions()),
                        bounded(capacities.groupChains()),
                        capacities.fieldChains()));
        return new ModelProfile(
                new ModelProfile.StructureProfile(
                        structure.elements(),
                        structure.topology(),
                        structure.fields(),
                        boundedRepeatability),
                base.rules(),
                base.computations(),
                base.wiring());
    }

    static ModelProfile withUnboundedRuleScan(ModelProfile base) {
        var rules = base.rules();
        var runtime = rules.runtime();
        var evaluation = runtime.evaluation();
        var scans = evaluation.scans();
        var unboundedScans = new ModelProfile.ScanProfile(
                scans.kinds(),
                scans.starDepth(),
                new ModelProfile.CapacityProfile(
                        LongDistribution.of(java.util.List.of(20L)),
                        LongHistogram.of(Map.of("DECLARED", 1L))),
                scans.filterComplexity());
        var unboundedRules = new ModelProfile.ProgramProfile(
                rules.conversion(),
                rules.syntax(),
                new ModelProfile.RuntimeProfile(
                        runtime.scope(),
                        new ModelProfile.EvaluationProfile(
                                unboundedScans,
                                evaluation.combinedWork(),
                                evaluation.mechanisms(),
                                evaluation.outerCorrelation()),
                        runtime.resolution()));
        return new ModelProfile(
                base.structure(),
                unboundedRules,
                base.computations(),
                base.wiring());
    }

    private static ModelProfile.CapacityProfile bounded(ModelProfile.CapacityProfile capacity) {
        return new ModelProfile.CapacityProfile(capacity.bounded(), LongHistogram.empty());
    }
}
