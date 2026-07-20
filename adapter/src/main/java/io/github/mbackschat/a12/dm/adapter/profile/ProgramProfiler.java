package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Aggregates identifier-free syntax and runtime-cost facts for a population of logical programs.
 *
 * <p>A rule has one AST part. A computation can have a common precondition, alternative
 * preconditions, and operations; {@link ConvertedProgram} keeps those parts under one scope so the
 * per-program distributions and work capacities are not inflated by the computation's table shape.
 */
final class ProgramProfiler {

    private static final Capacity ONE = new Capacity.Bounded(1);

    private final ProfileTopology topology;
    private final List<Program> programs;

    private ProgramProfiler(ProfileTopology topology, Collection<? extends Program> programs) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.programs = List.copyOf(programs);
    }

    static Builder builder(ProfileTopology topology) {
        return new Builder(topology);
    }

    static ModelProfile.ProgramProfile of(
            ProfileTopology topology,
            Collection<? extends Program> programs) {
        return new ProgramProfiler(topology, programs).profile();
    }

    sealed interface Program permits ConvertedProgram, OpaqueProgram {
        String scopePath();
    }

    record ConvertedProgram(String scopePath, List<ConditionComplexity.Result> parts)
            implements Program {

        ConvertedProgram {
            scopePath = requireScopePath(scopePath);
            parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
            if (parts.isEmpty()) {
                throw new IllegalArgumentException(
                        "a converted program needs at least one AST part");
            }
            parts.forEach(part -> Objects.requireNonNull(part, "program part"));
        }

        static PartBuilder builder(String scopePath) {
            return new PartBuilder(scopePath);
        }

        static ConvertedProgram of(
                String scopePath,
                ConditionComplexity.Result part) {
            return builder(scopePath).addPart(part).build();
        }

        static final class PartBuilder {
            private final String scopePath;
            private final List<ConditionComplexity.Result> parts = new ArrayList<>();

            private PartBuilder(String scopePath) {
                this.scopePath = requireScopePath(scopePath);
            }

            PartBuilder addPart(ConditionComplexity.Result part) {
                parts.add(Objects.requireNonNull(part, "part"));
                return this;
            }

            ConvertedProgram build() {
                return new ConvertedProgram(scopePath, parts);
            }
        }
    }

    record OpaqueProgram(String scopePath, OpaqueReasonCategory reason) implements Program {
        OpaqueProgram {
            scopePath = requireScopePath(scopePath);
            Objects.requireNonNull(reason, "reason");
        }
    }

    static final class Builder {
        private final ProfileTopology topology;
        private final List<Program> programs = new ArrayList<>();

        private Builder(ProfileTopology topology) {
            this.topology = Objects.requireNonNull(topology, "topology");
        }

        Builder add(Program program) {
            programs.add(Objects.requireNonNull(program, "program"));
            return this;
        }

        Builder converted(String scopePath, ConditionComplexity.Result part) {
            return add(ConvertedProgram.of(scopePath, part));
        }

        Builder opaque(String scopePath, OpaqueReasonCategory reason) {
            return add(new OpaqueProgram(scopePath, reason));
        }

        ModelProfile.ProgramProfile build() {
            return ProgramProfiler.of(topology, programs);
        }
    }

    private ModelProfile.ProgramProfile profile() {
        var aggregation = new Aggregation(topology);
        programs.forEach(aggregation::add);
        return aggregation.build();
    }

    private enum Mechanism {
        GROUP_FILL,
        SEMANTIC_INDEX,
        UNIQUENESS,
        PARALLEL_REFERENCE
    }

    private static final class Aggregation {
        private final ProfileTopology topology;
        private final LongHistogram.Builder conversionOutcomes = LongHistogram.builder();
        private final LongHistogram.Builder opaqueReasons = LongHistogram.builder();
        private final LongDistribution.Builder nodeCounts = LongDistribution.builder();
        private final LongDistribution.Builder depths = LongDistribution.builder();
        private final LongDistribution.Builder booleanArities = LongDistribution.builder();
        private final LongDistribution.Builder variadicArities = LongDistribution.builder();
        private final LongDistribution.Builder distinctFanIns = LongDistribution.builder();
        private final LongHistogram.Builder catalogOperators = LongHistogram.builder();
        private final LongHistogram.Builder intrinsicOperators = LongHistogram.builder();
        private final LongHistogram.Builder constructs = LongHistogram.builder();
        private final LongHistogram.Builder constructCombinations = LongHistogram.builder();
        private final SortedMap<String, LongHistogram.Builder> constructsByScope =
                scopeHistogramBuilders();
        private final LongHistogram.Builder scopeClasses = LongHistogram.builder();
        private final CapacityProfileBuilder scopeCapacities = new CapacityProfileBuilder();
        private final LongHistogram.Builder scanKinds = LongHistogram.builder();
        private final LongDistribution.Builder starDepths = LongDistribution.builder();
        private final CapacityProfileBuilder scanCapacities = new CapacityProfileBuilder();
        private final LongDistribution.Builder filterComplexities = LongDistribution.builder();
        private final CapacityProfileBuilder workCapacities = new CapacityProfileBuilder();
        private final LongHistogram.Builder scopeByScan = LongHistogram.builder();
        private final Map<Mechanism, MechanismAccumulator> mechanisms =
                mechanismAccumulators();
        private final LongHistogram.Builder outerForms = LongHistogram.builder();
        private final CapacityProfileBuilder outerCapacities = new CapacityProfileBuilder();
        private final LongDistribution.Builder walkUpDistances = LongDistribution.builder();

        private Aggregation(ProfileTopology topology) {
            this.topology = Objects.requireNonNull(topology, "topology");
        }

        private void add(Program program) {
            String scopePath = program.scopePath();
            Capacity scopeCapacity = topology.scopeCapacity(scopePath);
            String scopeClass = topology.scopeClass(scopePath);
            scopeClasses.add(scopeClass);
            scopeCapacities.add(scopeCapacity);
            switch (program) {
                case ConvertedProgram converted ->
                        addConverted(converted, scopeClass, scopeCapacity);
                case OpaqueProgram opaque -> {
                    conversionOutcomes.add("OPAQUE");
                    opaqueReasons.add(opaque.reason().name());
                }
            }
        }

        private void addConverted(
                ConvertedProgram program,
                String scopeClass,
                Capacity scopeCapacity) {
            conversionOutcomes.add("CONVERTED");
            long nodeCount = 0;
            int depth = 0;
            int booleanArity = 0;
            int variadicArity = 0;
            var programConstructs = EnumSet.noneOf(ConditionComplexity.Construct.class);
            var fieldReferences = new TreeSet<String>();
            var groupReferences = new TreeSet<String>();
            Capacity greatestScan = ONE;

            for (ConditionComplexity.Result part : program.parts()) {
                nodeCount = Math.addExact(nodeCount, part.tree().nodeCount());
                depth = Math.max(depth, part.tree().depth());
                booleanArity = Math.max(booleanArity, part.tree().maxBooleanArity());
                variadicArity = Math.max(variadicArity, part.tree().maxVariadicArity());
                merge(catalogOperators, part.operators().catalog().counts());
                merge(intrinsicOperators, part.operators().intrinsic().counts());
                programConstructs.addAll(part.constructs());
                fieldReferences.addAll(part.references().fieldPaths());
                groupReferences.addAll(part.references().groupPaths());

                for (ConditionComplexity.CollectionOccurrence scan
                        : part.collections().occurrences()) {
                    Capacity scanCapacity =
                            topology.scanCapacity(scan.repeatableGroupPaths());
                    greatestScan = greatestScan.max(scanCapacity);
                    scanKinds.add(scan.kind().name());
                    starDepths.add(scan.repeatableGroupPaths().size());
                    scanCapacities.add(scanCapacity);
                    scan.filterNodeCount().ifPresent(filterComplexities::add);
                    scopeByScan.add(scopeClass + "|" + scan.kind().name());
                }
                part.groupFills().occurrences().forEach(
                        occurrence -> addGroupFill(program.scopePath(), occurrence));
                part.semanticIndexes().occurrences().forEach(this::addSemanticIndex);
                part.uniquenessChecks().occurrences().forEach(this::addUniqueness);
                for (ConditionComplexity.CorrelationOccurrence correlation
                        : part.correlations().occurrences()) {
                    outerForms.add(correlation.form().name());
                    outerCapacities.add(topology.chainCapacity(correlation.targetPath()));
                }
            }

            nodeCounts.add(nodeCount);
            depths.add(depth);
            booleanArities.add(booleanArity);
            variadicArities.add(variadicArity);
            distinctFanIns.add(fieldReferences.size());
            for (ConditionComplexity.Construct construct : programConstructs) {
                constructs.add(construct.name());
                constructsByScope.get(scopeClass).add(construct.name());
            }
            constructCombinations.add(combinationKey(programConstructs));
            workCapacities.add(scopeCapacity.multiply(greatestScan));

            var allReferences = new ArrayList<String>(
                    fieldReferences.size() + groupReferences.size());
            allReferences.addAll(fieldReferences);
            allReferences.addAll(groupReferences);
            addParallelReference(allReferences);
            int maxWalkUp = allReferences.stream()
                    .mapToInt(path -> topology.walkUpDistance(program.scopePath(), path))
                    .max()
                    .orElse(0);
            if (maxWalkUp > 0) {
                walkUpDistances.add(maxWalkUp);
            }
        }

        private void addGroupFill(
                String scopePath,
                ConditionComplexity.GroupFillOccurrence occurrence) {
            String target = occurrence.targetGroupPath().orElse(scopePath);
            long width = topology.descendantFieldCount(target);
            Capacity capacity = topology.chainCapacity(target)
                    .multiply(new Capacity.Bounded(width));
            mechanisms.get(Mechanism.GROUP_FILL).add(width, capacity);
        }

        private void addSemanticIndex(
                ConditionComplexity.SemanticIndexOccurrence occurrence) {
            int targetDepth = topology.repeatableDepth(occurrence.targetFieldPath());
            Capacity capacity = topology.chainCapacity(occurrence.targetFieldPath());
            mechanisms.get(Mechanism.SEMANTIC_INDEX).add(targetDepth, capacity);
        }

        private void addUniqueness(
                ConditionComplexity.UniquenessOccurrence occurrence) {
            String keyScope = occurrence.fromGroupPath()
                    .orElseGet(() -> ProfileTopology.parentOf(
                            occurrence.keyFieldPaths().getFirst()));
            Capacity capacity = topology.chainCapacity(keyScope);
            mechanisms.get(Mechanism.UNIQUENESS)
                    .add(occurrence.keyFieldPaths().size(), capacity);
        }

        private void addParallelReference(Collection<String> references) {
            Set<String> branchRoots = topology.repeatableBranchRoots(references);
            if (branchRoots.size() < 2) {
                return;
            }
            Capacity capacity = new Capacity.Bounded(0);
            for (String branchRoot : branchRoots) {
                capacity = capacity.max(topology.chainCapacity(branchRoot));
            }
            mechanisms.get(Mechanism.PARALLEL_REFERENCE)
                    .add(branchRoots.size(), capacity);
        }

        private ModelProfile.ProgramProfile build() {
            var conversion = new ModelProfile.ConversionProfile(
                    conversionOutcomes.build(), opaqueReasons.build());
            var ast = new ModelProfile.AstProfile(
                    nodeCounts.build(),
                    depths.build(),
                    booleanArities.build(),
                    variadicArities.build(),
                    distinctFanIns.build());
            var dispatch = new ModelProfile.DispatchProfile(
                    catalogOperators.build(),
                    intrinsicOperators.build(),
                    constructs.build(),
                    constructCombinations.build(),
                    buildScopeHistograms(constructsByScope));
            var syntax = new ModelProfile.SyntaxProfile(ast, dispatch);
            var scope = new ModelProfile.ScopeProfile(
                    scopeClasses.build(), scopeCapacities.build());
            var scans = new ModelProfile.ScanProfile(
                    scanKinds.build(),
                    starDepths.build(),
                    scanCapacities.build(),
                    filterComplexities.build());
            var work = new ModelProfile.WorkProfile(
                    workCapacities.build(), scopeByScan.build());
            var evaluation = new ModelProfile.EvaluationProfile(
                    scans,
                    work,
                    buildMechanisms(mechanisms),
                    new ModelProfile.OuterCorrelationProfile(
                            outerForms.build(), outerCapacities.build()));
            var runtime = new ModelProfile.RuntimeProfile(
                    scope,
                    evaluation,
                    new ModelProfile.ResolutionProfile(walkUpDistances.build()));
            return new ModelProfile.ProgramProfile(conversion, syntax, runtime);
        }
    }

    private static final class MechanismAccumulator {
        private final LongDistribution.Builder shape = LongDistribution.builder();
        private final CapacityProfileBuilder capacity = new CapacityProfileBuilder();

        private void add(long value, Capacity workCapacity) {
            shape.add(value);
            capacity.add(workCapacity);
        }

        private ModelProfile.MechanismProfile build() {
            return new ModelProfile.MechanismProfile(shape.build(), capacity.build());
        }
    }

    private static Map<Mechanism, MechanismAccumulator> mechanismAccumulators() {
        var accumulators = new EnumMap<Mechanism, MechanismAccumulator>(Mechanism.class);
        for (Mechanism mechanism : Mechanism.values()) {
            accumulators.put(mechanism, new MechanismAccumulator());
        }
        return accumulators;
    }

    private static SortedMap<String, LongHistogram.Builder> scopeHistogramBuilders() {
        var builders = new TreeMap<String, LongHistogram.Builder>();
        builders.put(ProfileTopology.MODEL_LEVEL, LongHistogram.builder());
        builders.put(ProfileTopology.PER_GROUP, LongHistogram.builder());
        builders.put(ProfileTopology.PER_REPEATABLE_ROW, LongHistogram.builder());
        return builders;
    }

    private static SortedMap<String, LongHistogram> buildScopeHistograms(
            Map<String, LongHistogram.Builder> builders) {
        var histograms = new TreeMap<String, LongHistogram>();
        builders.forEach((scope, builder) -> histograms.put(scope, builder.build()));
        return histograms;
    }

    private static SortedMap<String, ModelProfile.MechanismProfile> buildMechanisms(
            Map<Mechanism, MechanismAccumulator> accumulators) {
        var profiles = new TreeMap<String, ModelProfile.MechanismProfile>();
        accumulators.forEach((mechanism, accumulator) ->
                profiles.put(mechanism.name(), accumulator.build()));
        return profiles;
    }

    private static void merge(
            LongHistogram.Builder target,
            Map<String, Integer> source) {
        source.forEach((key, count) -> target.add(key, count.longValue()));
    }

    private static String combinationKey(
            Set<ConditionComplexity.Construct> constructs) {
        if (constructs.isEmpty()) {
            return "NONE";
        }
        return constructs.stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining("+"));
    }

    private static String requireScopePath(String scopePath) {
        Objects.requireNonNull(scopePath, "scopePath");
        if (scopePath.isBlank()) {
            throw new IllegalArgumentException("scope path must not be blank");
        }
        return scopePath;
    }
}
