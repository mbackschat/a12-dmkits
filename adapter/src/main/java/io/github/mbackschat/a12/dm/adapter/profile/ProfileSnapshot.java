package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.adapter.ModelRead;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.context.ModelContext;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * The single-load, single-read source for one extraction run.
 *
 * <p>Every downstream profiler shares the same strict binding, typed read, topology, reference inverse,
 * and computation-target map. In particular, no section can accidentally reparse every program per field.
 */
final class ProfileSnapshot {

    private final ModelCard card;
    private final ProfileTopology topology;
    private final ModelRead read;
    private final Map<String, Set<String>> referrersByEntity;
    private final Map<String, String> computationTargets;
    private final long referenceParseCount;

    private ProfileSnapshot(
            ModelCard card,
            ProfileTopology topology,
            ModelRead read,
            Map<String, Set<String>> referrersByEntity,
            Map<String, String> computationTargets,
            long referenceParseCount) {
        this.card = Objects.requireNonNull(card, "card");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.read = Objects.requireNonNull(read, "read");
        this.referrersByEntity = immutableInverse(referrersByEntity);
        this.computationTargets = Collections.unmodifiableMap(
                new LinkedHashMap<>(computationTargets));
        this.referenceParseCount = referenceParseCount;
    }

    static ProfileSnapshot load(String modelJson) {
        return load(modelJson, ReferenceResolvers.selfContained());
    }

    static ProfileSnapshot load(String modelJson, ModelResolver resolver) {
        Objects.requireNonNull(modelJson, "modelJson");
        Objects.requireNonNull(resolver, "resolver");
        BoundModel model = BoundModel.ofJson(modelJson, resolver);
        ModelCard card = new ModelContext(model).describe();
        KernelAdapter adapter = KernelAdapter.of(model);
        ModelRead read = adapter.readModel();
        Map<String, Set<String>> inverse = adapter.referrersByEntity();
        long expectedParses = Math.addExact(read.rules().size(), read.computations().size());
        long actualParses = adapter.referenceParseCount();
        if (actualParses != expectedParses) {
            throw new IllegalStateException(
                    "profile reference inverse parsed " + actualParses
                            + " programs; expected exactly " + expectedParses);
        }
        return new ProfileSnapshot(
                card,
                new ProfileTopology(card),
                read,
                inverse,
                adapter.computationTargetFields(),
                actualParses);
    }

    ModelCard card() {
        return card;
    }

    ProfileTopology topology() {
        return topology;
    }

    ModelRead read() {
        return read;
    }

    Map<String, Set<String>> referrersByEntity() {
        return referrersByEntity;
    }

    Map<String, String> computationTargets() {
        return computationTargets;
    }

    long referenceParseCount() {
        return referenceParseCount;
    }

    private static Map<String, Set<String>> immutableInverse(
            Map<String, Set<String>> source) {
        var copy = new LinkedHashMap<String, Set<String>>();
        source.forEach((entity, referrers) ->
                copy.put(entity, Collections.unmodifiableSet(new LinkedHashSet<>(referrers))));
        return Collections.unmodifiableMap(copy);
    }
}
