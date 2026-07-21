package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Map;

/** Fluent construction paths for the structural synthesis-profile records. */
final class SynthesisStructureBuilders {

    private SynthesisStructureBuilders() {}

    static StructureBuilder structure() {
        return new StructureBuilder();
    }

    static RepeatabilityBuilder repeatability() {
        return new RepeatabilityBuilder();
    }

    static IterationBuilder iteration() {
        return new IterationBuilder();
    }

    static ConstraintsBuilder constraints() {
        return new ConstraintsBuilder();
    }

    static final class StructureBuilder {
        private int fieldCount;
        private int groupCount;
        private int maxNestingDepth;
        private Distribution fieldsPerGroup = Distribution.empty();
        private Map<String, Integer> fieldKinds = Map.of();
        private SynthesisProfile.Repeatability repeatability =
                SynthesisStructureBuilders.repeatability().build();
        private SynthesisProfile.Constraints constraints =
                SynthesisStructureBuilders.constraints().build();

        StructureBuilder fieldCount(int value) {
            fieldCount = value;
            return this;
        }

        StructureBuilder groupCount(int value) {
            groupCount = value;
            return this;
        }

        StructureBuilder maxNestingDepth(int value) {
            maxNestingDepth = value;
            return this;
        }

        StructureBuilder fieldsPerGroup(Distribution value) {
            fieldsPerGroup = presentOr(fieldsPerGroup, value);
            return this;
        }

        StructureBuilder fieldKinds(Map<String, Integer> value) {
            fieldKinds = nonEmptyOr(fieldKinds, value);
            return this;
        }

        StructureBuilder repeatability(SynthesisProfile.Repeatability value) {
            repeatability = presentOr(repeatability, value);
            return this;
        }

        StructureBuilder constraints(SynthesisProfile.Constraints value) {
            constraints = presentOr(constraints, value);
            return this;
        }

        SynthesisProfile.Structure build() {
            return new SynthesisProfile.Structure(
                    fieldCount,
                    groupCount,
                    maxNestingDepth,
                    fieldsPerGroup,
                    fieldKinds,
                    repeatability,
                    constraints);
        }
    }

    static final class RepeatabilityBuilder {
        private int repeatableGroupCount;
        private int unboundedCount;
        private int maxRepeatableNestingDepth;
        private Distribution maxRepetitions = Distribution.empty();
        private Distribution nestingDepth = Distribution.empty();
        private Distribution chainProduct = Distribution.empty();
        private Distribution fieldChainProduct = Distribution.empty();
        private Distribution siblingBranchFan = Distribution.empty();
        private Map<String, Distribution> chainProductByDepth = Map.of();
        private Map<String, Integer> nestingDepthExact = Map.of();

        RepeatabilityBuilder repeatableGroupCount(int value) {
            repeatableGroupCount = value;
            return this;
        }

        RepeatabilityBuilder unboundedCount(int value) {
            unboundedCount = value;
            return this;
        }

        RepeatabilityBuilder maxRepeatableNestingDepth(int value) {
            maxRepeatableNestingDepth = value;
            return this;
        }

        RepeatabilityBuilder maxRepetitions(Distribution value) {
            maxRepetitions = presentOr(maxRepetitions, value);
            return this;
        }

        RepeatabilityBuilder nestingDepth(Distribution value) {
            nestingDepth = presentOr(nestingDepth, value);
            return this;
        }

        RepeatabilityBuilder chainProduct(Distribution value) {
            chainProduct = presentOr(chainProduct, value);
            return this;
        }

        RepeatabilityBuilder fieldChainProduct(Distribution value) {
            fieldChainProduct = presentOr(fieldChainProduct, value);
            return this;
        }

        RepeatabilityBuilder siblingBranchFan(Distribution value) {
            siblingBranchFan = presentOr(siblingBranchFan, value);
            return this;
        }

        RepeatabilityBuilder chainProductByDepth(Map<String, Distribution> value) {
            chainProductByDepth = nonEmptyOr(chainProductByDepth, value);
            return this;
        }

        RepeatabilityBuilder nestingDepthExact(Map<String, Integer> value) {
            nestingDepthExact = nonEmptyOr(nestingDepthExact, value);
            return this;
        }

        SynthesisProfile.Repeatability build() {
            return new SynthesisProfile.Repeatability(
                    repeatableGroupCount,
                    unboundedCount,
                    maxRepeatableNestingDepth,
                    maxRepetitions,
                    nestingDepth,
                    chainProduct,
                    fieldChainProduct,
                    siblingBranchFan,
                    chainProductByDepth,
                    nestingDepthExact);
        }
    }

    static final class IterationBuilder {
        private Distribution listScanCapacity = Distribution.empty();
        private int parallelIteration;
        private int outerCorrelation;
        private Distribution scopeCapacity = Distribution.empty();
        private Distribution workCapacity = Distribution.empty();
        private Distribution scannedScopeCapacity = Distribution.empty();

        IterationBuilder listScanCapacity(Distribution value) {
            listScanCapacity = presentOr(listScanCapacity, value);
            return this;
        }

        IterationBuilder parallelIteration(int value) {
            parallelIteration = value;
            return this;
        }

        IterationBuilder outerCorrelation(int value) {
            outerCorrelation = value;
            return this;
        }

        IterationBuilder scopeCapacity(Distribution value) {
            scopeCapacity = presentOr(scopeCapacity, value);
            return this;
        }

        IterationBuilder workCapacity(Distribution value) {
            workCapacity = presentOr(workCapacity, value);
            return this;
        }

        IterationBuilder scannedScopeCapacity(Distribution value) {
            scannedScopeCapacity = presentOr(scannedScopeCapacity, value);
            return this;
        }

        SynthesisProfile.Iteration build() {
            return new SynthesisProfile.Iteration(
                    listScanCapacity,
                    parallelIteration,
                    outerCorrelation,
                    scopeCapacity,
                    workCapacity,
                    scannedScopeCapacity);
        }
    }

    static final class ConstraintsBuilder {
        private int numbersWithBounds;
        private int stringsWithLength;
        private int stringsWithPattern;
        private Distribution numberScale = Distribution.empty();
        private Distribution enumCardinality = Distribution.empty();

        ConstraintsBuilder numbersWithBounds(int value) {
            numbersWithBounds = value;
            return this;
        }

        ConstraintsBuilder stringsWithLength(int value) {
            stringsWithLength = value;
            return this;
        }

        ConstraintsBuilder stringsWithPattern(int value) {
            stringsWithPattern = value;
            return this;
        }

        ConstraintsBuilder numberScale(Distribution value) {
            numberScale = presentOr(numberScale, value);
            return this;
        }

        ConstraintsBuilder enumCardinality(Distribution value) {
            enumCardinality = presentOr(enumCardinality, value);
            return this;
        }

        SynthesisProfile.Constraints build() {
            return new SynthesisProfile.Constraints(
                    numbersWithBounds,
                    stringsWithLength,
                    stringsWithPattern,
                    numberScale,
                    enumCardinality);
        }
    }

    private static <T> T presentOr(T current, T candidate) {
        return candidate == null ? current : candidate;
    }

    private static <K, V> Map<K, V> nonEmptyOr(Map<K, V> current, Map<K, V> candidate) {
        return candidate == null || candidate.isEmpty() ? current : candidate;
    }
}
