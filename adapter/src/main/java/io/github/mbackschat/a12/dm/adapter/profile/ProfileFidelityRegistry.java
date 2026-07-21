package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.AT_LEAST;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.BUCKET;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.EXACT;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileComparison.Policy.INFORMATIVE;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** The complete executable classification of current profile axes. */
final class ProfileFidelityRegistry {

    private static final List<Classification> CLASSIFICATIONS = classificationsBuilder();

    private ProfileFidelityRegistry() {}

    static List<Classification> classifications() {
        return CLASSIFICATIONS;
    }

    static Classification require(String concreteComparisonPath) {
        Objects.requireNonNull(concreteComparisonPath, "concreteComparisonPath");
        var matches = CLASSIFICATIONS.stream()
                .filter(classification -> classification.matches(concreteComparisonPath))
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "profile-comparison axis " + concreteComparisonPath
                            + " must have exactly one fidelity classification but had " + matches);
        }
        return matches.getFirst();
    }

    private static List<Classification> classificationsBuilder() {
        var entries = new ArrayList<Classification>();
        structure(entries);
        program(entries, "rules");
        program(entries, "computations.programs");
        computationShape(entries);
        axis(entries, "wiring.referencedFields", Area.WIRING, BUCKET);
        axis(entries, "wiring.fieldFanOut", Area.WIRING, BUCKET);

        var comparisonPaths = new HashSet<String>();
        for (Classification entry : entries) {
            if (!comparisonPaths.add(entry.comparisonPath())) {
                throw new IllegalStateException(
                        "duplicate profile-fidelity classification " + entry.comparisonPath());
            }
        }
        return List.copyOf(entries);
    }

    private static void structure(List<Classification> entries) {
        axis(entries, "structure.elements", Area.STRUCTURE_CARDINALITY, EXACT);
        axis(entries, "structure.topology.maxGroupDepth", Area.STRUCTURE_TOPOLOGY, EXACT);
        axis(entries, "structure.topology.fieldsPerGroup", Area.GROUP_WIDTH, BUCKET);
        axis(entries, "structure.fields.kinds", Area.FIELD_POPULATION, EXACT);
        axis(entries, "structure.fields.constrainedFields", Area.CONSTRAINTS, BUCKET);
        axis(entries, "structure.fields.numberScale", Area.CONSTRAINTS, BUCKET);
        axis(entries, "structure.fields.enumCardinality", Area.CONSTRAINTS, BUCKET);
        axis(entries, "structure.repeatability.outcomes", Area.STRUCTURE_CARDINALITY, EXACT);
        axis(entries, "structure.repeatability.topology.maxDepth", Area.STRUCTURE_TOPOLOGY, EXACT);
        axis(entries, "structure.repeatability.topology.exactDepth", Area.STRUCTURE_TOPOLOGY, EXACT);
        axis(entries, "structure.repeatability.topology.siblingFan", Area.REPEATABILITY, BUCKET);
        capacity(
                entries,
                "structure.repeatability.capacities.maxRepetitions",
                Area.REPEATABILITY,
                BUCKET);
        capacity(
                entries,
                "structure.repeatability.capacities.groupChains",
                Area.REPEATABILITY,
                BUCKET);
        capacity(
                entries,
                "structure.repeatability.capacities.fieldChains",
                Area.REPEATABILITY,
                BUCKET);
    }

    private static void program(List<Classification> entries, String prefix) {
        axis(entries, prefix + ".conversion.outcomes", Area.OPAQUE_ELEMENTS, INFORMATIVE);
        axis(entries, prefix + ".conversion.opaqueReasons", Area.OPAQUE_ELEMENTS, INFORMATIVE);

        String ast = prefix + ".syntax.ast.";
        axis(entries, ast + "nodeCount", Area.AST_SHAPE, BUCKET);
        axis(entries, ast + "depth", Area.AST_SHAPE, BUCKET);
        axis(entries, ast + "booleanArity", Area.AST_SHAPE, BUCKET);
        axis(entries, ast + "variadicArity", Area.AST_SHAPE, BUCKET);
        axis(entries, ast + "distinctFanIn", Area.AST_SHAPE, BUCKET);

        String dispatch = prefix + ".syntax.dispatch.";
        axis(entries, dispatch + "catalogOperators", Area.DISPATCH_MIX, AT_LEAST);
        axis(entries, dispatch + "intrinsicOperators", Area.DISPATCH_MIX, AT_LEAST);
        axis(entries, dispatch + "constructs", Area.CONSTRUCT_MIX, AT_LEAST);
        axis(entries, dispatch + "constructCombinations", Area.CONSTRUCT_MIX, AT_LEAST);
        axis(entries, dispatch + "constructsByScope.*", Area.CONSTRUCT_MIX, AT_LEAST);

        String runtime = prefix + ".runtime.";
        axis(entries, runtime + "scope.classes", Area.SCOPE_WORK, BUCKET);
        capacity(entries, runtime + "scope.capacity", Area.SCOPE_WORK, BUCKET);

        String scans = runtime + "evaluation.scans.";
        axis(entries, scans + "kinds", Area.EXPLICIT_SCANS, AT_LEAST);
        derived(entries, scans + "starDepth.count", scans + "starDepth", Area.EXPLICIT_SCANS, AT_LEAST);
        derived(entries, scans + "starDepth.max", scans + "starDepth", Area.EXPLICIT_SCANS, AT_LEAST);
        derived(
                entries,
                scans + "starDepth.distribution",
                scans + "starDepth",
                Area.EXPLICIT_SCANS,
                INFORMATIVE);
        capacity(entries, scans + "capacity", Area.EXPLICIT_SCANS, BUCKET);
        derived(
                entries,
                scans + "filterComplexity.count",
                scans + "filterComplexity",
                Area.EXPLICIT_SCANS,
                AT_LEAST);
        derived(
                entries,
                scans + "filterComplexity.shape",
                scans + "filterComplexity",
                Area.EXPLICIT_SCANS,
                BUCKET);

        String combined = runtime + "evaluation.combinedWork.";
        capacity(entries, combined + "capacity", Area.COMBINED_WORK, BUCKET);
        axis(entries, combined + "scopeByScan", Area.COMBINED_WORK, BUCKET);

        String mechanisms = runtime + "evaluation.mechanisms.";
        mechanism(entries, mechanisms, "GROUP_FILL", Area.GROUP_FILL, BUCKET, BUCKET);
        mechanism(entries, mechanisms, "SEMANTIC_INDEX", Area.SEMANTIC_INDEX, BUCKET, BUCKET);
        mechanism(entries, mechanisms, "UNIQUENESS", Area.UNIQUENESS, BUCKET, BUCKET);
        mechanism(
                entries,
                mechanisms,
                "PARALLEL_REFERENCE",
                Area.PARALLEL_REFERENCES,
                AT_LEAST,
                AT_LEAST);

        String correlation = runtime + "evaluation.outerCorrelation.";
        axis(entries, correlation + "forms", Area.OUTER_CORRELATION, AT_LEAST);
        capacity(entries, correlation + "capacity", Area.OUTER_CORRELATION, AT_LEAST);
        axis(entries, runtime + "resolution.walkUpDistance", Area.RESOLUTION, BUCKET);
    }

    private static void computationShape(List<Classification> entries) {
        axis(
                entries,
                "computations.shape.computedFieldKinds",
                Area.FIELD_POPULATION,
                EXACT);
        axis(
                entries,
                "computations.shape.tables.alternatives",
                Area.COMPUTATION_TABLES,
                BUCKET);
        axis(
                entries,
                "computations.shape.tables.preconditions",
                Area.COMPUTATION_TABLES,
                BUCKET);
        axis(
                entries,
                "computations.shape.tables.toleranceBands",
                Area.COMPUTATION_TABLES,
                AT_LEAST);
        axis(
                entries,
                "computations.shape.archetypes",
                Area.COMPUTATION_ARCHETYPES,
                AT_LEAST);
        axis(
                entries,
                "computations.shape.aggregateScaleMatch",
                Area.AGGREGATE_SCALE,
                AT_LEAST);
        axis(
                entries,
                "computations.dependencies.maxCascadeDepth",
                Area.COMPUTATION_DEPENDENCY,
                EXACT);
        axis(
                entries,
                "computations.dependencies.feedingComputations",
                Area.COMPUTATION_DEPENDENCY,
                AT_LEAST);
    }

    private static void mechanism(
            List<Classification> entries,
            String prefix,
            String mechanism,
            Area area,
            ProfileComparison.Policy shapePolicy,
            ProfileComparison.Policy capacityPolicy) {
        String profileShape = prefix + mechanism + ".shape";
        derived(entries, prefix + mechanism + ".occurrences", profileShape, area, AT_LEAST);
        axis(entries, profileShape, area, shapePolicy);
        capacity(entries, prefix + mechanism + ".capacity", area, capacityPolicy);
    }

    private static void capacity(
            List<Classification> entries,
            String prefix,
            Area boundedArea,
            ProfileComparison.Policy boundedPolicy) {
        axis(entries, prefix + ".bounded", boundedArea, boundedPolicy);
        axis(entries, prefix + ".unboundedCauses", Area.UNBOUNDED_REPEATABILITY, EXACT);
    }

    private static void axis(
            List<Classification> entries,
            String path,
            Area area,
            ProfileComparison.Policy policy) {
        derived(entries, path, path, area, policy);
    }

    private static void derived(
            List<Classification> entries,
            String comparisonPath,
            String profilePath,
            Area area,
            ProfileComparison.Policy policy) {
        entries.add(new Classification(comparisonPath, profilePath, area, policy));
    }

    enum Area {
        STRUCTURE_CARDINALITY("Structure cardinality"),
        STRUCTURE_TOPOLOGY("Structure topology"),
        FIELD_POPULATION("Field population"),
        GROUP_WIDTH("Group width"),
        REPEATABILITY("Repeatability"),
        UNBOUNDED_REPEATABILITY("Unbounded repeatability"),
        CONSTRAINTS("Constraints"),
        AST_SHAPE("AST shape"),
        DISPATCH_MIX("Dispatch mix"),
        CONSTRUCT_MIX("Construct mix"),
        SCOPE_WORK("Scope work"),
        EXPLICIT_SCANS("Explicit scans"),
        COMBINED_WORK("Combined work"),
        RESOLUTION("Resolution"),
        GROUP_FILL("Group fill"),
        SEMANTIC_INDEX("Semantic index"),
        UNIQUENESS("Uniqueness"),
        PARALLEL_REFERENCES("Parallel references"),
        OUTER_CORRELATION("Outer correlation"),
        COMPUTATION_TABLES("Computation tables"),
        COMPUTATION_ARCHETYPES("Computation archetypes"),
        AGGREGATE_SCALE("Aggregate scale"),
        COMPUTATION_DEPENDENCY("Computation dependency"),
        WIRING("Wiring"),
        OPAQUE_ELEMENTS("Opaque elements");

        private final String label;

        Area(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Classification(
            String comparisonPath,
            String profilePath,
            Area area,
            ProfileComparison.Policy policy) {

        Classification {
            if (Objects.requireNonNull(comparisonPath, "comparisonPath").isBlank()
                    || Objects.requireNonNull(profilePath, "profilePath").isBlank()) {
                throw new IllegalArgumentException("profile-fidelity paths must not be blank");
            }
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(policy, "policy");
        }

        boolean matches(String concretePath) {
            String[] patternParts = comparisonPath.split("\\.");
            String[] concreteParts = concretePath.split("\\.");
            if (patternParts.length != concreteParts.length) {
                return false;
            }
            for (int index = 0; index < patternParts.length; index++) {
                if (!"*".equals(patternParts[index])
                        && !patternParts[index].equals(concreteParts[index])) {
                    return false;
                }
            }
            return true;
        }
    }
}
