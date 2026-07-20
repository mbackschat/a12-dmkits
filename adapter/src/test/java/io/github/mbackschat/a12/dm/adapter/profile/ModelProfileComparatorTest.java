package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonFixture.profile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelProfileComparatorTest {

    @Test
    void identicalProfilesPassInAStableAxisOrder() {
        var forward = ModelProfileComparator.compare(profile(false), profile(false));
        var reverse = ModelProfileComparator.compare(profile(true), profile(false));

        assertTrue(forward.matches());
        assertEquals(forward, reverse);
        assertEquals(
                List.of(
                        "structure.elements",
                        "structure.topology.maxGroupDepth",
                        "structure.topology.fieldsPerGroup",
                        "structure.fields.kinds"),
                forward.axes().stream()
                        .limit(4)
                        .map(result -> result.axis().path())
                        .toList());
        assertEquals("wiring.fieldFanOut", forward.axes().getLast().axis().path());
    }

    @Test
    void exactAxesRejectAnyDifference() {
        var target = profile(false);
        var actual = withMaxGroupDepth(target, target.structure().topology().maxGroupDepth() + 1);

        var result = ModelProfileComparator.compare(target, actual);
        var axis = result.requireAxis("structure.topology.maxGroupDepth");

        assertFalse(result.matches());
        assertEquals(ProfileComparison.Policy.EXACT, axis.axis().policy());
        assertEquals(ProfileComparison.Verdict.FAIL, axis.outcome().verdict());
    }

    @Test
    void atLeastAxesAllowExtraWorkButRejectUnderRepresentation() {
        var target = profile(false);
        var more = withRuleCatalogCount(target, "FieldFilled", 3);
        var less = withRuleCatalogCount(target, "FieldFilled", 1);

        var moreResult = ModelProfileComparator.compare(target, more);
        var lessResult = ModelProfileComparator.compare(target, less);

        assertEquals(
                ProfileComparison.Verdict.PASS,
                moreResult.requireAxis("rules.syntax.dispatch.catalogOperators")
                        .outcome()
                        .verdict());
        assertEquals(
                ProfileComparison.Verdict.FAIL,
                lessResult.requireAxis("rules.syntax.dispatch.catalogOperators")
                        .outcome()
                        .verdict());
    }

    @Test
    void bucketAxesAcceptOneLogBucketOfMovementButNotTwo() {
        var target = profile(false);
        var adjacent = withFieldsPerGroup(target, LongDistribution.of(List.of(2L, 7L)));
        var distant = withFieldsPerGroup(target, LongDistribution.of(List.of(4L, 15L)));

        var adjacentAxis = ModelProfileComparator.compare(target, adjacent)
                .requireAxis("structure.topology.fieldsPerGroup");
        var distantAxis = ModelProfileComparator.compare(target, distant)
                .requireAxis("structure.topology.fieldsPerGroup");

        assertEquals(ProfileComparison.Policy.BUCKET, adjacentAxis.axis().policy());
        assertEquals(ProfileComparison.Verdict.PASS, adjacentAxis.outcome().verdict());
        assertEquals(ProfileComparison.Verdict.FAIL, distantAxis.outcome().verdict());
    }

    @Test
    void informativeOpaqueReasonsNeverDecideTheOutcome() {
        var target = profile(false);
        var actual = withRuleOpaqueReason(target, "READER_FAILURE");

        var result = ModelProfileComparator.compare(target, actual);
        var axis = result.requireAxis("rules.conversion.opaqueReasons");

        assertTrue(result.matches());
        assertEquals(ProfileComparison.Policy.INFORMATIVE, axis.axis().policy());
        assertEquals(ProfileComparison.Verdict.INFORMATIVE, axis.outcome().verdict());
    }

    @Test
    void combinedWorkMayMoveUpOneBucketButNeverDown() {
        var target = profile(false);
        var greater = withRuleCombinedWork(target, LongDistribution.of(List.of(40L, 160L)));
        var lower = withRuleCombinedWork(target, LongDistribution.of(List.of(10L, 40L)));

        var greaterAxis = ModelProfileComparator.compare(target, greater)
                .requireAxis("rules.runtime.evaluation.combinedWork.capacity.bounded");
        var lowerAxis = ModelProfileComparator.compare(target, lower)
                .requireAxis("rules.runtime.evaluation.combinedWork.capacity.bounded");

        assertEquals(ProfileComparison.Verdict.PASS, greaterAxis.outcome().verdict());
        assertEquals(ProfileComparison.Verdict.FAIL, lowerAxis.outcome().verdict());
    }

    private static ModelProfile withMaxGroupDepth(ModelProfile profile, int depth) {
        var structure = profile.structure();
        var topology = new ModelProfile.TopologyProfile(depth, structure.topology().fieldsPerGroup());
        return withStructure(
                profile,
                new ModelProfile.StructureProfile(
                        structure.elements(),
                        topology,
                        structure.fields(),
                        structure.repeatability()));
    }

    private static ModelProfile withFieldsPerGroup(
            ModelProfile profile, LongDistribution fieldsPerGroup) {
        var structure = profile.structure();
        var topology =
                new ModelProfile.TopologyProfile(structure.topology().maxGroupDepth(), fieldsPerGroup);
        return withStructure(
                profile,
                new ModelProfile.StructureProfile(
                        structure.elements(),
                        topology,
                        structure.fields(),
                        structure.repeatability()));
    }

    private static ModelProfile withRuleCatalogCount(
            ModelProfile profile, String operator, long count) {
        var rules = profile.rules();
        var syntax = rules.syntax();
        var dispatch = syntax.dispatch();
        var catalog = new java.util.TreeMap<>(dispatch.catalogOperators().counts());
        catalog.put(operator, count);
        var changedDispatch = new ModelProfile.DispatchProfile(
                LongHistogram.of(catalog),
                dispatch.intrinsicOperators(),
                dispatch.constructs(),
                dispatch.constructCombinations(),
                dispatch.constructsByScope());
        var changedRules = new ModelProfile.ProgramProfile(
                rules.conversion(),
                new ModelProfile.SyntaxProfile(syntax.ast(), changedDispatch),
                rules.runtime());
        return withRules(profile, changedRules);
    }

    private static ModelProfile withRuleOpaqueReason(ModelProfile profile, String reason) {
        var rules = profile.rules();
        var conversion = new ModelProfile.ConversionProfile(
                rules.conversion().outcomes(),
                LongHistogram.of(Map.of(reason, 1L)));
        return withRules(
                profile,
                new ModelProfile.ProgramProfile(conversion, rules.syntax(), rules.runtime()));
    }

    private static ModelProfile withRuleCombinedWork(
            ModelProfile profile, LongDistribution bounded) {
        var rules = profile.rules();
        var runtime = rules.runtime();
        var evaluation = runtime.evaluation();
        var work = evaluation.combinedWork();
        var capacity = new ModelProfile.CapacityProfile(
                bounded, work.capacity().unboundedCauses());
        var changedWork = new ModelProfile.WorkProfile(capacity, work.scopeByScan());
        var changedEvaluation = new ModelProfile.EvaluationProfile(
                evaluation.scans(),
                changedWork,
                evaluation.mechanisms(),
                evaluation.outerCorrelation());
        var changedRuntime =
                new ModelProfile.RuntimeProfile(runtime.scope(), changedEvaluation, runtime.resolution());
        return withRules(
                profile,
                new ModelProfile.ProgramProfile(
                        rules.conversion(), rules.syntax(), changedRuntime));
    }

    private static ModelProfile withStructure(
            ModelProfile profile, ModelProfile.StructureProfile structure) {
        return new ModelProfile(structure, profile.rules(), profile.computations(), profile.wiring());
    }

    private static ModelProfile withRules(
            ModelProfile profile, ModelProfile.ProgramProfile rules) {
        return new ModelProfile(profile.structure(), rules, profile.computations(), profile.wiring());
    }
}
