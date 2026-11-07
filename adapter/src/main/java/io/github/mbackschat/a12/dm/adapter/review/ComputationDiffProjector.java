package io.github.mbackschat.a12.dm.adapter.review;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionReferences;
import io.github.mbackschat.a12.dm.rulekit.dsl.RoundingMode;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/** Projects typed or opaque computations into the diff engine's deterministic aspect-map contract. */
final class ComputationDiffProjector {

    private ComputationDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        state.adapter().readAllComputations().forEach((name, read) -> out.put(name,
                aspects(read, OwnerMembers.of(state.adapter(), name))));
        return out;
    }

    /**
     * The element's own members, which A12 keeps apart from the formula, so an Opaque formula still carries them
     * into the diff: the stored preamble and error code ({@code null} for none) and the rounding mode.
     */
    record OwnerMembers(String additionalParserInformation, RoundingMode roundingMode, String errorCode) {
        static OwnerMembers of(KernelAdapter adapter, String computation) {
            return new OwnerMembers(adapter.computationAdditionalParserInformation(computation).orElse(null),
                    adapter.computationRoundingMode(computation), adapter.computationErrorCode(computation).orElse(null));
        }
    }

    static Map<String, String> aspects(ReadComputation read) {
        return switch (read) {
            case ReadComputation.Converted converted -> {
                DraftComputation draft = converted.computation().draft();
                yield convertedAspects(converted.computation(),
                        new OwnerMembers(draft.additionalParserInformation(), draft.roundingMode(), draft.errorCode()));
            }
            case ReadComputation.Opaque opaque ->
                    opaqueAspects(opaque.canonicalText(), new OwnerMembers(null, RoundingMode.EXACT, null));
        };
    }

    static Map<String, String> aspects(ReadComputation read, OwnerMembers owner) {
        return switch (read) {
            case ReadComputation.Converted converted -> convertedAspects(converted.computation(), owner);
            case ReadComputation.Opaque opaque -> opaqueAspects(opaque.canonicalText(), owner);
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

    private static Map<String, String> convertedAspects(Computation computation, OwnerMembers owner) {
        DraftComputation draft = computation.draft();
        Map<String, String> aspects = new LinkedHashMap<>();
        aspects.put("target", computation.computedField().path().value());
        draft.roundingMode().stored().ifPresent(mode -> aspects.put("roundingMode", mode));
        putIfPresent(aspects, "commonPrecondition", draft.commonPrecondition());
        putIfPresent(aspects, "errorCode", owner.errorCode());
        for (int i = 0; i < draft.alternatives().size(); i++) {
            DraftComputation.Alternative alternative = draft.alternatives().get(i);
            String prefix = "alternative[" + i + "].";
            putIfPresent(aspects, prefix + "precondition", alternative.precondition());
            aspects.put(prefix + "operation", alternative.operation());
            if (alternative.toleranceRangeOp() != null) {
                aspects.put(prefix + "tolerance", alternative.toleranceRangeOp().wire());
            }
        }
        putIfPresent(aspects, "additionalParserInformation", owner.additionalParserInformation());
        computation.messages().forEach(message -> aspects.put("message." + message.locale(), message.text()));
        return aspects;
    }

    private static Map<String, String> opaqueAspects(String canonicalText, OwnerMembers owner) {
        Map<String, String> aspects = new LinkedHashMap<>();
        aspects.put("opaque", canonicalText);
        owner.roundingMode().stored().ifPresent(mode -> aspects.put("roundingMode", mode));
        putIfPresent(aspects, "errorCode", owner.errorCode());
        putIfPresent(aspects, "additionalParserInformation", owner.additionalParserInformation());
        return aspects;
    }

    private static void putIfPresent(Map<String, String> aspects, String name, String value) {
        if (value != null) {
            aspects.put(name, value);
        }
    }
}
