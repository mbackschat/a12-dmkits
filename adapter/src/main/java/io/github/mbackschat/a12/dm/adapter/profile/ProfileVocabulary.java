package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Arrays;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;
import io.github.mbackschat.a12.dm.rulekit.dsl.ToleranceBand;

/** Closed categorical vocabulary for the identifier-free current model-profile format. */
final class ProfileVocabulary {

    private static final Set<String> ELEMENT_KINDS =
            Set.of("FIELD", "GROUP", "RULE", "COMPUTATION");
    private static final Set<String> VALUE_KINDS = names(ValueKind.values(), Enum::name);
    private static final Set<String> CONSTRAINTS =
            Set.of("NUMBER_BOUNDS", "STRING_LENGTH", "STRING_PATTERN");
    private static final Set<String> REPEATABILITY_OUTCOMES =
            Set.of("BOUNDED", "UNBOUNDED");
    private static final Set<String> UNBOUNDED_CAUSES =
            names(Capacity.Cause.values(), Enum::name);
    private static final Set<String> CONVERSION_OUTCOMES = Set.of("CONVERTED", "OPAQUE");
    private static final Set<String> OPAQUE_REASONS =
            names(OpaqueReasonCategory.values(), Enum::name);
    private static final Set<String> CONSTRUCTS =
            names(ConditionComplexity.Construct.values(), Enum::name);
    private static final Set<String> CATALOG_OPERATORS = OperatorCatalog.load()
            .entries()
            .stream()
            .map(OperatorEntry::id)
            .collect(Collectors.toUnmodifiableSet());
    private static final Set<String> INTRINSIC_OPERATORS = Set.of("+", "-", "*", "/", "^");
    private static final Set<String> SCOPE_CLASSES =
            Set.of(
                    ProfileTopology.MODEL_LEVEL,
                    ProfileTopology.PER_GROUP,
                    ProfileTopology.PER_REPEATABLE_ROW);
    private static final Set<String> SCAN_KINDS =
            names(ConditionComplexity.CollectionKind.values(), Enum::name);
    private static final Set<String> MECHANISMS =
            Set.of("GROUP_FILL", "SEMANTIC_INDEX", "UNIQUENESS", "PARALLEL_REFERENCE");
    private static final Set<String> OUTER_FORMS =
            names(ConditionComplexity.OuterCorrelation.values(), Enum::name);
    private static final Set<String> TOLERANCE_BANDS =
            names(ToleranceBand.values(), Enum::name);
    private static final Set<String> ARCHETYPES =
            Set.of("COPY", "AGGREGATE", "ARITHMETIC", "DATE", "STRING", "CONSTANT");
    private static final Set<String> AGGREGATE_SCALE =
            Set.of("MATCHED", "MISMATCHED");
    private static final Set<String> WIRING = Set.of("ANY");

    private ProfileVocabulary() {}

    static ModelProfile validate(ModelProfile profile) {
        validateStructure(profile.structure());
        validateProgram(profile.rules(), "$.rules");
        validateProgram(profile.computations().programs(), "$.computations.programs");
        validateComputationShape(profile.computations().shape());
        keys(profile.wiring().referencedFields(), "$.wiring.referencedFields", WIRING);
        return profile;
    }

    private static void validateStructure(ModelProfile.StructureProfile structure) {
        keys(structure.elements(), "$.structure.elements", ELEMENT_KINDS);
        keys(structure.fields().kinds(), "$.structure.fields.kinds", VALUE_KINDS);
        keys(
                structure.fields().constrainedFields(),
                "$.structure.fields.constrainedFields",
                CONSTRAINTS);
        keys(
                structure.repeatability().outcomes(),
                "$.structure.repeatability.outcomes",
                REPEATABILITY_OUTCOMES);
        exactDepth(
                structure.repeatability().topology().exactDepth(),
                structure.repeatability().topology().maxDepth());
        capacities(
                structure.repeatability().capacities().maxRepetitions(),
                "$.structure.repeatability.capacities.maxRepetitions");
        capacities(
                structure.repeatability().capacities().groupChains(),
                "$.structure.repeatability.capacities.groupChains");
        capacities(
                structure.repeatability().capacities().fieldChains(),
                "$.structure.repeatability.capacities.fieldChains");
    }

    private static void validateProgram(ModelProfile.ProgramProfile program, String path) {
        keys(program.conversion().outcomes(), path + ".conversion.outcomes", CONVERSION_OUTCOMES);
        keys(
                program.conversion().opaqueReasons(),
                path + ".conversion.opaqueReasons",
                OPAQUE_REASONS);
        keys(program.syntax().dispatch().constructs(), path + ".syntax.dispatch.constructs", CONSTRUCTS);
        keys(
                program.syntax().dispatch().catalogOperators(),
                path + ".syntax.dispatch.catalogOperators",
                CATALOG_OPERATORS);
        keys(
                program.syntax().dispatch().intrinsicOperators(),
                path + ".syntax.dispatch.intrinsicOperators",
                INTRINSIC_OPERATORS);
        exactKeys(
                program.syntax().dispatch().constructsByScope().keySet(),
                path + ".syntax.dispatch.constructsByScope",
                SCOPE_CLASSES);
        program.syntax().dispatch().constructsByScope().forEach((scope, constructs) -> {
            keys(
                    constructs,
                    path + ".syntax.dispatch.constructsByScope." + scope,
                    CONSTRUCTS);
        });
        program.syntax().dispatch().constructCombinations().counts().keySet().forEach(key ->
                constructCombination(key, path + ".syntax.dispatch.constructCombinations"));
        keys(program.runtime().scope().classes(), path + ".runtime.scope.classes", SCOPE_CLASSES);
        capacities(program.runtime().scope().capacity(), path + ".runtime.scope.capacity");
        keys(program.runtime().evaluation().scans().kinds(), path + ".runtime.evaluation.scans.kinds", SCAN_KINDS);
        capacities(
                program.runtime().evaluation().scans().capacity(),
                path + ".runtime.evaluation.scans.capacity");
        program.runtime().evaluation().combinedWork().scopeByScan().counts().keySet().forEach(
                key -> scopeByScan(key, path + ".runtime.evaluation.combinedWork.scopeByScan"));
        capacities(
                program.runtime().evaluation().combinedWork().capacity(),
                path + ".runtime.evaluation.combinedWork.capacity");
        exactKeys(
                program.runtime().evaluation().mechanisms().keySet(),
                path + ".runtime.evaluation.mechanisms",
                MECHANISMS);
        program.runtime().evaluation().mechanisms().forEach((mechanism, value) -> {
            capacities(
                    value.capacity(),
                    path + ".runtime.evaluation.mechanisms." + mechanism + ".capacity");
        });
        keys(
                program.runtime().evaluation().outerCorrelation().forms(),
                path + ".runtime.evaluation.outerCorrelation.forms",
                OUTER_FORMS);
        capacities(
                program.runtime().evaluation().outerCorrelation().capacity(),
                path + ".runtime.evaluation.outerCorrelation.capacity");
    }

    private static void validateComputationShape(ModelProfile.ComputationShape shape) {
        keys(
                shape.computedFieldKinds(),
                "$.computations.shape.computedFieldKinds",
                VALUE_KINDS);
        keys(
                shape.tables().toleranceBands(),
                "$.computations.shape.tables.toleranceBands",
                TOLERANCE_BANDS);
        keys(shape.archetypes(), "$.computations.shape.archetypes", ARCHETYPES);
        keys(
                shape.aggregateScaleMatch(),
                "$.computations.shape.aggregateScaleMatch",
                AGGREGATE_SCALE);
    }

    private static void capacities(ModelProfile.CapacityProfile capacity, String path) {
        keys(capacity.unboundedCauses(), path + ".unboundedCauses", UNBOUNDED_CAUSES);
    }

    private static void exactDepth(LongHistogram histogram, int maxDepth) {
        String path = "$.structure.repeatability.topology.exactDepth";
        for (String key : histogram.counts().keySet()) {
            final int depth;
            try {
                depth = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw invalid(path, key, "a canonical positive decimal depth");
            }
            if (depth < 1 || depth > maxDepth || !Integer.toString(depth).equals(key)) {
                throw invalid(path, key, "a canonical depth from 1 through " + maxDepth);
            }
        }
    }

    private static void constructCombination(String key, String path) {
        if ("NONE".equals(key)) {
            return;
        }
        String[] members = key.split("\\+", -1);
        for (String member : members) {
            require(member, path + "." + key, CONSTRUCTS);
        }
        String canonical = Arrays.stream(members).distinct().sorted().collect(Collectors.joining("+"));
        if (!canonical.equals(key)) {
            throw invalid(path, key, "NONE or a sorted, unique '+' combination of construct names");
        }
    }

    private static void scopeByScan(String key, String path) {
        String[] members = key.split("\\|", -1);
        if (members.length != 2
                || !SCOPE_CLASSES.contains(members[0])
                || !SCAN_KINDS.contains(members[1])) {
            throw invalid(path, key, "<scope-class>|<scan-kind>");
        }
    }

    private static void keys(LongHistogram histogram, String path, Set<String> allowed) {
        histogram.counts().keySet().forEach(key -> require(key, path, allowed));
    }

    private static void require(String key, String path, Set<String> allowed) {
        if (!allowed.contains(key)) {
            throw invalid(path, key, "one of " + allowed.stream().sorted().toList());
        }
    }

    private static void exactKeys(Set<String> actual, String path, Set<String> expected) {
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "invalid model profile vocabulary at " + path + ": keys must be exactly "
                            + expected.stream().sorted().toList() + " but were "
                            + actual.stream().sorted().toList());
        }
    }

    private static IllegalArgumentException invalid(
            String path, String key, String expected) {
        return new IllegalArgumentException(
                "invalid model profile vocabulary at " + path + ": '" + key
                        + "' must be " + expected);
    }

    private static <E> Set<String> names(E[] values, Function<E, String> name) {
        return Arrays.stream(values).map(name).collect(Collectors.toUnmodifiableSet());
    }
}
