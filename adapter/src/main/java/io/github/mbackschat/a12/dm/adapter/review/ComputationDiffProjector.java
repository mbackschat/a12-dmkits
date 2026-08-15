package io.github.mbackschat.a12.dm.adapter.review;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/** Projects typed or opaque computations into the diff engine's deterministic aspect-map contract. */
final class ComputationDiffProjector {

    private ComputationDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        state.adapter().readAllComputations().forEach((name, read) -> out.put(name,
                aspects(read, state.adapter().computationErrorCodesToSuppress(name))));
        return out;
    }

    static Map<String, String> aspects(ReadComputation read) {
        List<String> suppressions = read instanceof ReadComputation.Converted converted
                ? converted.computation().errorCodesToSuppress()
                : List.of();
        return aspects(read, suppressions);
    }

    static Map<String, String> aspects(ReadComputation read, List<String> suppressions) {
        return switch (read) {
            case ReadComputation.Converted converted -> convertedAspects(converted.computation());
            case ReadComputation.Opaque opaque -> opaqueAspects(opaque, suppressions);
        };
    }

    static List<String> reasons(ModelDiff.ChangeKind change, List<ModelDiff.Delta> deltas) {
        return switch (change) {
            case ADDED, REMOVED -> List.of("COMPUTATION_CHANGED");
            case MODIFIED -> deltas.stream().map(ComputationDiffProjector::modifiedReason).toList();
            case REORDERED, RENAMED, MOVED -> List.of();
        };
    }

    static String modifiedReason(ModelDiff.Delta delta) {
        return delta.aspect().startsWith("message.") ? "MESSAGE_CHANGED" : "COMPUTATION_CHANGED";
    }

    static Set<String> references(ReadComputation read) {
        if (!(read instanceof ReadComputation.Converted converted)) {
            return Set.of();
        }
        var computation = converted.computation();
        Set<String> fields = new LinkedHashSet<>();
        computation.commonPrecondition().ifPresent(condition ->
                fields.addAll(ConditionReferences.of(condition).fieldPaths()));
        computation.alternatives().forEach(alternative -> {
            alternative.precondition().ifPresent(condition ->
                    fields.addAll(ConditionReferences.of(condition).fieldPaths()));
            fields.addAll(ConditionReferences.of(alternative.operation()).fieldPaths());
        });
        return Set.copyOf(fields);
    }

    private static Map<String, String> convertedAspects(Computation computation) {
        DraftComputation draft = computation.draft();
        Map<String, String> aspects = new LinkedHashMap<>();
        aspects.put("target", computation.computedField().path().value());
        putIfPresent(aspects, "commonPrecondition", draft.commonPrecondition());
        for (int i = 0; i < draft.alternatives().size(); i++) {
            DraftComputation.Alternative alternative = draft.alternatives().get(i);
            String prefix = "alternative[" + i + "].";
            putIfPresent(aspects, prefix + "precondition", alternative.precondition());
            aspects.put(prefix + "operation", alternative.operation());
            if (alternative.toleranceRangeOp() != null) {
                aspects.put(prefix + "tolerance", alternative.toleranceRangeOp().wire());
            }
        }
        if (!draft.errorCodesToSuppress().isEmpty()) {
            aspects.put("errorCodesToSuppress", String.join(",", draft.errorCodesToSuppress()));
        }
        computation.messages().forEach(message -> aspects.put("message." + message.locale(), message.text()));
        return aspects;
    }

    private static Map<String, String> opaqueAspects(
            ReadComputation.Opaque computation, List<String> errorCodesToSuppress) {
        Map<String, String> aspects = new LinkedHashMap<>();
        aspects.put("opaque", computation.canonicalText());
        if (!errorCodesToSuppress.isEmpty()) {
            aspects.put("errorCodesToSuppress", String.join(",", errorCodesToSuppress));
        }
        return aspects;
    }

    private static void putIfPresent(Map<String, String> aspects, String name, String value) {
        if (value != null) {
            aspects.put(name, value);
        }
    }
}
