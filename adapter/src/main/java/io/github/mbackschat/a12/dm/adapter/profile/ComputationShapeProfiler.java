package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;

/**
 * Computation-table and operation-shape facts derived from the shared typed read.
 *
 * <p>Opaque computations contribute only the kind of their known target field. Table, archetype,
 * and aggregate facts require a converted operation tree and are never guessed from opaque text.
 */
final class ComputationShapeProfiler {

    private enum Archetype {
        COPY,
        AGGREGATE,
        ARITHMETIC,
        DATE,
        STRING,
        CONSTANT
    }

    private ComputationShapeProfiler() {}

    static ModelProfile.ComputationShape profile(ProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        var fieldsByPath = fieldsByPath(snapshot.card());
        requireMatchingTargets(snapshot);

        var computedKinds = LongHistogram.builder();
        var alternatives = LongDistribution.builder();
        var preconditions = LongDistribution.builder();
        var toleranceBands = LongHistogram.builder();
        var archetypes = LongHistogram.builder();
        var aggregateScaleMatch = LongHistogram.builder();

        snapshot.read().computations().forEach((computationName, outcome) -> {
            String targetPath = snapshot.computationTargets().get(computationName);
            ModelCard.Field target = requireField(fieldsByPath, targetPath, "computed target");
            computedKinds.add(target.kind());

            if (outcome instanceof ReadComputation.Converted converted) {
                var computation = converted.computation();
                alternatives.add(computation.alternatives().size());
                preconditions.add(preconditionCount(computation));

                var operationConstructs = EnumSet.noneOf(ConditionComplexity.Construct.class);
                boolean referencesField = false;
                for (var alternative : computation.alternatives()) {
                    alternative.toleranceRangeOp()
                            .ifPresent(band -> toleranceBands.add(band.name()));
                    var complexity = ConditionComplexity.of(alternative.operation());
                    operationConstructs.addAll(complexity.constructs());
                    referencesField |= !complexity.references().fieldPaths().isEmpty();
                    addAggregateScaleMatches(
                            target,
                            complexity.aggregates(),
                            fieldsByPath,
                            aggregateScaleMatch);
                }
                archetypes.add(archetype(
                        target.kind(), operationConstructs, referencesField).name());
            }
        });

        return new ModelProfile.ComputationShape(
                computedKinds.build(),
                new ModelProfile.TableProfile(
                        alternatives.build(),
                        preconditions.build(),
                        toleranceBands.build()),
                archetypes.build(),
                aggregateScaleMatch.build());
    }

    private static long preconditionCount(Computation computation) {
        long count = computation.commonPrecondition().isPresent() ? 1 : 0;
        for (var alternative : computation.alternatives()) {
            if (alternative.precondition().isPresent()) {
                count = Math.incrementExact(count);
            }
        }
        return count;
    }

    private static Archetype archetype(
            String computedKind,
            Set<ConditionComplexity.Construct> constructs,
            boolean referencesField) {
        if (constructs.contains(ConditionComplexity.Construct.ARITHMETIC)) {
            return Archetype.ARITHMETIC;
        }
        if (constructs.contains(ConditionComplexity.Construct.AGGREGATE)) {
            return Archetype.AGGREGATE;
        }
        if (constructs.contains(ConditionComplexity.Construct.DATE_MATH)) {
            return Archetype.DATE;
        }
        if ("STRING".equals(computedKind)) {
            return Archetype.STRING;
        }
        return referencesField ? Archetype.COPY : Archetype.CONSTANT;
    }

    private static void addAggregateScaleMatches(
            ModelCard.Field target,
            ConditionComplexity.Aggregates aggregates,
            Map<String, ModelCard.Field> fieldsByPath,
            LongHistogram.Builder matches) {
        if (target.scale().isEmpty()) {
            return;
        }
        int computedScale = target.scale().orElseThrow();
        for (var occurrence : aggregates.occurrences()) {
            if (!scaleBearing(occurrence.form())) {
                continue;
            }
            for (String operandPath : occurrence.operandFieldPaths()) {
                ModelCard.Field operand =
                        requireField(fieldsByPath, operandPath, "aggregate operand");
                operand.scale().ifPresent(operandScale -> matches.add(
                        operandScale == computedScale ? "MATCHED" : "MISMATCHED"));
            }
        }
    }

    private static boolean scaleBearing(ConditionComplexity.AggregateForm form) {
        return switch (form) {
            case LIST, FIRST_FILLED, SUM_OF_PRODUCTS -> true;
            case COUNT, DATE_LIST, NUMBER_OF_VALUE_IN_FIELDS, ROOT_GROUP_COUNT -> false;
        };
    }

    private static Map<String, ModelCard.Field> fieldsByPath(ModelCard card) {
        var indexed = new LinkedHashMap<String, ModelCard.Field>();
        for (ModelCard.Field field : card.fields()) {
            if (indexed.putIfAbsent(field.path(), field) != null) {
                throw new IllegalArgumentException(
                        "model card contains duplicate field path " + field.path());
            }
        }
        return Map.copyOf(indexed);
    }

    private static ModelCard.Field requireField(
            Map<String, ModelCard.Field> fieldsByPath,
            String path,
            String role) {
        ModelCard.Field field = fieldsByPath.get(path);
        if (field == null) {
            throw new IllegalArgumentException(
                    role + " " + path + " is absent from the model card");
        }
        return field;
    }

    private static void requireMatchingTargets(ProfileSnapshot snapshot) {
        if (!snapshot.computationTargets().keySet()
                .equals(snapshot.read().computations().keySet())) {
            throw new IllegalStateException(
                    "computation target map must cover exactly the shared computation read");
        }
    }
}
