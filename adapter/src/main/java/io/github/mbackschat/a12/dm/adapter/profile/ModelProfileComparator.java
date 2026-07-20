package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.AT_LEAST;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.BUCKET;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.EXACT;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.INFORMATIVE;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Verdict.FAIL;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Verdict.PASS;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Deterministic fidelity comparison for the one current model-profile format. */
final class ModelProfileComparator {

    private static final List<String> MECHANISMS =
            List.of("GROUP_FILL", "SEMANTIC_INDEX", "UNIQUENESS", "PARALLEL_REFERENCE");

    private ModelProfileComparator() {}

    static ProfileComparison compare(ModelProfile target, ModelProfile actual) {
        var builder = new Builder();
        builder.compare(
                ProfileVocabulary.validate(Objects.requireNonNull(target, "target")),
                ProfileVocabulary.validate(Objects.requireNonNull(actual, "actual")));
        return builder.build();
    }

    private static final class Builder {
        private final List<ProfileComparison.AxisResult> axes = new ArrayList<>();

        private void compare(ModelProfile target, ModelProfile actual) {
            compareStructure(target.structure(), actual.structure());
            compareProgram("rules", target.rules(), actual.rules());
            compareProgram(
                    "computations.programs",
                    target.computations().programs(),
                    actual.computations().programs());
            compareComputationShape(target.computations(), actual.computations());
            bucketHistogram(
                    "wiring.referencedFields",
                    target.wiring().referencedFields(),
                    actual.wiring().referencedFields());
            bucket(
                    "wiring.fieldFanOut",
                    target.wiring().fieldFanOut(),
                    actual.wiring().fieldFanOut());
        }

        private void compareStructure(
                ModelProfile.StructureProfile target,
                ModelProfile.StructureProfile actual) {
            exact("structure.elements", target.elements(), actual.elements());
            exact(
                    "structure.topology.maxGroupDepth",
                    target.topology().maxGroupDepth(),
                    actual.topology().maxGroupDepth());
            bucket(
                    "structure.topology.fieldsPerGroup",
                    target.topology().fieldsPerGroup(),
                    actual.topology().fieldsPerGroup());
            exact("structure.fields.kinds", target.fields().kinds(), actual.fields().kinds());
            bucketHistogram(
                    "structure.fields.constrainedFields",
                    target.fields().constrainedFields(),
                    actual.fields().constrainedFields());
            bucket(
                    "structure.fields.numberScale",
                    target.fields().numberScale(),
                    actual.fields().numberScale());
            bucket(
                    "structure.fields.enumCardinality",
                    target.fields().enumCardinality(),
                    actual.fields().enumCardinality());
            exact(
                    "structure.repeatability.outcomes",
                    target.repeatability().outcomes(),
                    actual.repeatability().outcomes());
            exact(
                    "structure.repeatability.topology.maxDepth",
                    target.repeatability().topology().maxDepth(),
                    actual.repeatability().topology().maxDepth());
            exact(
                    "structure.repeatability.topology.exactDepth",
                    target.repeatability().topology().exactDepth(),
                    actual.repeatability().topology().exactDepth());
            bucket(
                    "structure.repeatability.topology.siblingFan",
                    target.repeatability().topology().siblingFan(),
                    actual.repeatability().topology().siblingFan());
            capacity(
                    "structure.repeatability.capacities.maxRepetitions",
                    target.repeatability().capacities().maxRepetitions(),
                    actual.repeatability().capacities().maxRepetitions(),
                    CapacityRule.BUCKET);
            capacity(
                    "structure.repeatability.capacities.groupChains",
                    target.repeatability().capacities().groupChains(),
                    actual.repeatability().capacities().groupChains(),
                    CapacityRule.BUCKET);
            capacity(
                    "structure.repeatability.capacities.fieldChains",
                    target.repeatability().capacities().fieldChains(),
                    actual.repeatability().capacities().fieldChains(),
                    CapacityRule.BUCKET);
        }

        private void compareProgram(
                String prefix,
                ModelProfile.ProgramProfile target,
                ModelProfile.ProgramProfile actual) {
            informative(
                    prefix + ".conversion.outcomes",
                    target.conversion().outcomes(),
                    actual.conversion().outcomes());
            informative(
                    prefix + ".conversion.opaqueReasons",
                    target.conversion().opaqueReasons(),
                    actual.conversion().opaqueReasons());
            compareAst(prefix + ".syntax.ast", target.syntax().ast(), actual.syntax().ast());
            compareDispatch(
                    prefix + ".syntax.dispatch",
                    target.syntax().dispatch(),
                    actual.syntax().dispatch());
            compareRuntime(
                    prefix + ".runtime",
                    target.runtime(),
                    actual.runtime());
        }

        private void compareAst(
                String prefix, ModelProfile.AstProfile target, ModelProfile.AstProfile actual) {
            bucket(prefix + ".nodeCount", target.nodeCount(), actual.nodeCount());
            bucket(prefix + ".depth", target.depth(), actual.depth());
            bucket(prefix + ".booleanArity", target.booleanArity(), actual.booleanArity());
            bucket(prefix + ".variadicArity", target.variadicArity(), actual.variadicArity());
            bucket(prefix + ".distinctFanIn", target.distinctFanIn(), actual.distinctFanIn());
        }

        private void compareDispatch(
                String prefix,
                ModelProfile.DispatchProfile target,
                ModelProfile.DispatchProfile actual) {
            atLeast(
                    prefix + ".catalogOperators",
                    target.catalogOperators(),
                    actual.catalogOperators());
            atLeast(
                    prefix + ".intrinsicOperators",
                    target.intrinsicOperators(),
                    actual.intrinsicOperators());
            atLeast(prefix + ".constructs", target.constructs(), actual.constructs());
            atLeast(
                    prefix + ".constructCombinations",
                    target.constructCombinations(),
                    actual.constructCombinations());
            target.constructsByScope().forEach((scope, targetHistogram) ->
                    atLeast(
                            prefix + ".constructsByScope." + scope,
                            targetHistogram,
                            actual.constructsByScope().get(scope)));
        }

        private void compareRuntime(
                String prefix,
                ModelProfile.RuntimeProfile target,
                ModelProfile.RuntimeProfile actual) {
            bucketHistogram(
                    prefix + ".scope.classes",
                    target.scope().classes(),
                    actual.scope().classes());
            capacity(
                    prefix + ".scope.capacity",
                    target.scope().capacity(),
                    actual.scope().capacity(),
                    CapacityRule.BUCKET);
            compareEvaluation(
                    prefix + ".evaluation",
                    target.evaluation(),
                    actual.evaluation());
            bucket(
                    prefix + ".resolution.walkUpDistance",
                    target.resolution().walkUpDistance(),
                    actual.resolution().walkUpDistance());
        }

        private void compareEvaluation(
                String prefix,
                ModelProfile.EvaluationProfile target,
                ModelProfile.EvaluationProfile actual) {
            atLeast(prefix + ".scans.kinds", target.scans().kinds(), actual.scans().kinds());
            atLeast(
                    prefix + ".scans.starDepth.count",
                    target.scans().starDepth().count(),
                    actual.scans().starDepth().count());
            atLeast(
                    prefix + ".scans.starDepth.max",
                    target.scans().starDepth().max(),
                    actual.scans().starDepth().max());
            informative(
                    prefix + ".scans.starDepth.distribution",
                    target.scans().starDepth(),
                    actual.scans().starDepth());
            capacity(
                    prefix + ".scans.capacity",
                    target.scans().capacity(),
                    actual.scans().capacity(),
                    CapacityRule.BUCKET);
            atLeast(
                    prefix + ".scans.filterComplexity.count",
                    target.scans().filterComplexity().count(),
                    actual.scans().filterComplexity().count());
            bucket(
                    prefix + ".scans.filterComplexity.shape",
                    target.scans().filterComplexity(),
                    actual.scans().filterComplexity());
            capacity(
                    prefix + ".combinedWork.capacity",
                    target.combinedWork().capacity(),
                    actual.combinedWork().capacity(),
                    CapacityRule.NO_LOWER_BUCKET);
            noLowerBucketHistogram(
                    prefix + ".combinedWork.scopeByScan",
                    target.combinedWork().scopeByScan(),
                    actual.combinedWork().scopeByScan());
            for (String mechanism : MECHANISMS) {
                compareMechanism(
                        prefix + ".mechanisms." + mechanism,
                        mechanism,
                        target.mechanisms().get(mechanism),
                        actual.mechanisms().get(mechanism));
            }
            atLeast(
                    prefix + ".outerCorrelation.forms",
                    target.outerCorrelation().forms(),
                    actual.outerCorrelation().forms());
            capacity(
                    prefix + ".outerCorrelation.capacity",
                    target.outerCorrelation().capacity(),
                    actual.outerCorrelation().capacity(),
                    CapacityRule.AT_LEAST);
        }

        private void compareMechanism(
                String prefix,
                String mechanism,
                ModelProfile.MechanismProfile target,
                ModelProfile.MechanismProfile actual) {
            atLeast(prefix + ".occurrences", target.shape().count(), actual.shape().count());
            if ("PARALLEL_REFERENCE".equals(mechanism)) {
                atLeast(prefix + ".shape", target.shape(), actual.shape());
                capacity(
                        prefix + ".capacity",
                        target.capacity(),
                        actual.capacity(),
                        CapacityRule.AT_LEAST);
            } else {
                bucket(prefix + ".shape", target.shape(), actual.shape());
                capacity(
                        prefix + ".capacity",
                        target.capacity(),
                        actual.capacity(),
                        CapacityRule.BUCKET);
            }
        }

        private void compareComputationShape(
                ModelProfile.ComputationProfile target,
                ModelProfile.ComputationProfile actual) {
            exact(
                    "computations.shape.computedFieldKinds",
                    target.shape().computedFieldKinds(),
                    actual.shape().computedFieldKinds());
            bucket(
                    "computations.shape.tables.alternatives",
                    target.shape().tables().alternatives(),
                    actual.shape().tables().alternatives());
            bucket(
                    "computations.shape.tables.preconditions",
                    target.shape().tables().preconditions(),
                    actual.shape().tables().preconditions());
            atLeast(
                    "computations.shape.tables.toleranceBands",
                    target.shape().tables().toleranceBands(),
                    actual.shape().tables().toleranceBands());
            atLeast(
                    "computations.shape.archetypes",
                    target.shape().archetypes(),
                    actual.shape().archetypes());
            atLeast(
                    "computations.shape.aggregateScaleMatch",
                    target.shape().aggregateScaleMatch(),
                    actual.shape().aggregateScaleMatch());
            exact(
                    "computations.dependencies.maxCascadeDepth",
                    target.dependencies().maxCascadeDepth(),
                    actual.dependencies().maxCascadeDepth());
            atLeast(
                    "computations.dependencies.feedingComputations",
                    target.dependencies().feedingComputations(),
                    actual.dependencies().feedingComputations());
        }

        private void capacity(
                String prefix,
                ModelProfile.CapacityProfile target,
                ModelProfile.CapacityProfile actual,
                CapacityRule rule) {
            switch (rule) {
                case BUCKET -> bucket(prefix + ".bounded", target.bounded(), actual.bounded());
                case NO_LOWER_BUCKET ->
                        noLowerBucket(prefix + ".bounded", target.bounded(), actual.bounded());
                case AT_LEAST ->
                        atLeast(prefix + ".bounded", target.bounded(), actual.bounded());
            }
            exact(
                    prefix + ".unboundedCauses",
                    target.unboundedCauses(),
                    actual.unboundedCauses());
        }

        private void exact(String path, long target, long actual) {
            add(
                    path,
                    EXACT,
                    new ProfileComparison.ScalarValue(target),
                    new ProfileComparison.ScalarValue(actual),
                    target == actual,
                    "actual must equal target");
        }

        private void exact(String path, LongHistogram target, LongHistogram actual) {
            add(
                    path,
                    EXACT,
                    new ProfileComparison.HistogramValue(target),
                    new ProfileComparison.HistogramValue(actual),
                    target.equals(actual),
                    "actual histogram must equal target");
        }

        private void atLeast(String path, long target, long actual) {
            add(
                    path,
                    AT_LEAST,
                    new ProfileComparison.ScalarValue(target),
                    new ProfileComparison.ScalarValue(actual),
                    actual >= target,
                    "actual must be greater than or equal to target");
        }

        private void atLeast(String path, LongHistogram target, LongHistogram actual) {
            add(
                    path,
                    AT_LEAST,
                    new ProfileComparison.HistogramValue(target),
                    new ProfileComparison.HistogramValue(actual),
                    histogramAtLeast(target, actual),
                    "every target category must be represented at least as often");
        }

        private void atLeast(String path, LongDistribution target, LongDistribution actual) {
            add(
                    path,
                    AT_LEAST,
                    new ProfileComparison.DistributionValue(target),
                    new ProfileComparison.DistributionValue(actual),
                    distributionAtLeast(target, actual),
                    "actual center and tail must not under-represent target");
        }

        private void bucket(String path, LongDistribution target, LongDistribution actual) {
            add(
                    path,
                    BUCKET,
                    new ProfileComparison.DistributionValue(target),
                    new ProfileComparison.DistributionValue(actual),
                    distributionsAreAdjacent(target, actual, false),
                    "actual summaries must occupy the same or an adjacent logarithmic bucket");
        }

        private void bucketHistogram(String path, LongHistogram target, LongHistogram actual) {
            add(
                    path,
                    BUCKET,
                    new ProfileComparison.HistogramValue(target),
                    new ProfileComparison.HistogramValue(actual),
                    histogramsAreAdjacent(target, actual, false),
                    "category counts must occupy the same or an adjacent logarithmic bucket");
        }

        private void noLowerBucket(
                String path, LongDistribution target, LongDistribution actual) {
            add(
                    path,
                    BUCKET,
                    new ProfileComparison.DistributionValue(target),
                    new ProfileComparison.DistributionValue(actual),
                    distributionsAreAdjacent(target, actual, true),
                    "actual summaries must occupy the same or next-higher logarithmic bucket");
        }

        private void noLowerBucketHistogram(
                String path, LongHistogram target, LongHistogram actual) {
            add(
                    path,
                    BUCKET,
                    new ProfileComparison.HistogramValue(target),
                    new ProfileComparison.HistogramValue(actual),
                    histogramsAreAdjacent(target, actual, true),
                    "actual category counts must occupy the same or next-higher logarithmic bucket");
        }

        private void informative(String path, LongHistogram target, LongHistogram actual) {
            add(
                    path,
                    INFORMATIVE,
                    new ProfileComparison.HistogramValue(target),
                    new ProfileComparison.HistogramValue(actual),
                    true,
                    "reported without affecting fidelity");
        }

        private void informative(
                String path, LongDistribution target, LongDistribution actual) {
            add(
                    path,
                    INFORMATIVE,
                    new ProfileComparison.DistributionValue(target),
                    new ProfileComparison.DistributionValue(actual),
                    true,
                    "reported without affecting fidelity");
        }

        private void add(
                String path,
                ProfileComparison.Policy policy,
                ProfileComparison.Value target,
                ProfileComparison.Value actual,
                boolean passes,
                String rule) {
            var verdict = policy == INFORMATIVE
                    ? ProfileComparison.Verdict.INFORMATIVE
                    : passes ? PASS : FAIL;
            axes.add(new ProfileComparison.AxisResult(
                    new ProfileComparison.Axis(path, policy),
                    new ProfileComparison.Observation(target, actual),
                    new ProfileComparison.Outcome(verdict, rule)));
        }

        private ProfileComparison build() {
            return new ProfileComparison(axes);
        }
    }

    private enum CapacityRule {
        BUCKET,
        NO_LOWER_BUCKET,
        AT_LEAST
    }

    private static boolean histogramAtLeast(LongHistogram target, LongHistogram actual) {
        return target.counts().entrySet().stream()
                .allMatch(entry ->
                        actual.counts().getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    private static boolean distributionAtLeast(
            LongDistribution target, LongDistribution actual) {
        if (target.count() == 0) {
            return true;
        }
        return actual.count() > 0
                && actual.min() >= target.min()
                && actual.median() >= target.median()
                && actual.p90() >= target.p90()
                && actual.max() >= target.max();
    }

    private static boolean distributionsAreAdjacent(
            LongDistribution target,
            LongDistribution actual,
            boolean forbidLower) {
        if (target.count() == 0 || actual.count() == 0) {
            return target.count() == actual.count();
        }
        return bucketsAreAdjacent(target.min(), actual.min(), forbidLower)
                && bucketsAreAdjacent(target.median(), actual.median(), forbidLower)
                && bucketsAreAdjacent(target.p90(), actual.p90(), forbidLower)
                && bucketsAreAdjacent(target.max(), actual.max(), forbidLower);
    }

    private static boolean histogramsAreAdjacent(
            LongHistogram target,
            LongHistogram actual,
            boolean forbidLower) {
        var categories = new TreeSet<String>();
        categories.addAll(target.counts().keySet());
        categories.addAll(actual.counts().keySet());
        return categories.stream().allMatch(category -> bucketsAreAdjacent(
                target.counts().getOrDefault(category, 0L),
                actual.counts().getOrDefault(category, 0L),
                forbidLower));
    }

    private static boolean bucketsAreAdjacent(long target, long actual, boolean forbidLower) {
        int targetOrdinal = bucketOrdinal(target);
        int actualOrdinal = bucketOrdinal(actual);
        int distance = actualOrdinal - targetOrdinal;
        return distance <= 1 && distance >= (forbidLower && target > 0 ? 0 : -1);
    }

    private static int bucketOrdinal(long value) {
        if (value <= 1) {
            return Math.toIntExact(value);
        }
        return Long.numberOfTrailingZeros(LongDistribution.bucketLower(value)) + 1;
    }
}
