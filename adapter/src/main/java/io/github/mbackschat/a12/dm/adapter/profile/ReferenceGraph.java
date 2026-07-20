package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Domain-blind wiring and computation-dependency facts derived from the snapshot's one cached inverse.
 */
final class ReferenceGraph {

    private final ModelProfile.WiringProfile wiring;
    private final ModelProfile.DependencyProfile dependencies;

    private ReferenceGraph(
            ModelProfile.WiringProfile wiring,
            ModelProfile.DependencyProfile dependencies) {
        this.wiring = Objects.requireNonNull(wiring, "wiring");
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    static ReferenceGraph of(ProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Set<String> fieldPaths = new LinkedHashSet<>();
        snapshot.card().fields().forEach(field -> fieldPaths.add(field.path()));

        var fanOut = LongDistribution.builder();
        long referencedFields = 0;
        for (String fieldPath : fieldPaths) {
            Set<String> referrers =
                    snapshot.referrersByEntity().getOrDefault(fieldPath, Set.of());
            if (!referrers.isEmpty()) {
                referencedFields = Math.incrementExact(referencedFields);
                fanOut.add(referrers.size());
            }
        }
        var wiring = new ModelProfile.WiringProfile(
                LongHistogram.builder().put("ANY", referencedFields).build(),
                fanOut.build());
        var dependencies = dependencies(
                snapshot.referrersByEntity(), snapshot.computationTargets());
        return new ReferenceGraph(wiring, dependencies);
    }

    ModelProfile.WiringProfile wiringProfile() {
        return wiring;
    }

    ModelProfile.DependencyProfile dependencyProfile() {
        return dependencies;
    }

    private static ModelProfile.DependencyProfile dependencies(
            Map<String, Set<String>> referrersByEntity,
            Map<String, String> computationTargets) {
        Set<String> computedFields = new LinkedHashSet<>(computationTargets.values());
        Map<String, Set<String>> consumersBySupplier = new LinkedHashMap<>();
        Map<String, Integer> incomingDependencies = new LinkedHashMap<>();
        computedFields.forEach(field -> {
            consumersBySupplier.put(field, new LinkedHashSet<>());
            incomingDependencies.put(field, 0);
        });

        for (String supplier : computedFields) {
            for (String referrer : referrersByEntity.getOrDefault(supplier, Set.of())) {
                String consumer = computationTargets.get(referrer);
                if (consumer == null || consumer.equals(supplier)) {
                    continue;
                }
                if (consumersBySupplier.get(supplier).add(consumer)) {
                    incomingDependencies.compute(
                            consumer,
                            (ignored, count) -> Math.addExact(
                                    Objects.requireNonNull(count, "computed consumer"), 1));
                }
            }
        }

        long feeding = consumersBySupplier.values().stream()
                .filter(consumers -> !consumers.isEmpty())
                .count();
        int maxDepth = longestAcyclicDepth(consumersBySupplier, incomingDependencies);
        return new ModelProfile.DependencyProfile(maxDepth, feeding);
    }

    private static int longestAcyclicDepth(
            Map<String, Set<String>> consumersBySupplier,
            Map<String, Integer> incomingDependencies) {
        if (incomingDependencies.isEmpty()) {
            return 0;
        }
        var remaining = new HashMap<>(incomingDependencies);
        var depth = new HashMap<String, Integer>();
        var ready = new ArrayDeque<String>();
        incomingDependencies.forEach((field, incoming) -> {
            depth.put(field, 1);
            if (incoming == 0) {
                ready.addLast(field);
            }
        });

        int visited = 0;
        int maxDepth = 1;
        while (!ready.isEmpty()) {
            String supplier = ready.removeFirst();
            visited++;
            int supplierDepth = depth.get(supplier);
            maxDepth = Math.max(maxDepth, supplierDepth);
            for (String consumer : consumersBySupplier.getOrDefault(supplier, Set.of())) {
                depth.compute(
                        consumer,
                        (ignored, current) -> Math.max(
                                Objects.requireNonNull(current, "computed consumer depth"),
                                Math.addExact(supplierDepth, 1)));
                int left = remaining.compute(
                        consumer,
                        (ignored, count) -> Math.subtractExact(
                                Objects.requireNonNull(count, "computed consumer"), 1));
                if (left == 0) {
                    ready.addLast(consumer);
                }
            }
        }
        if (visited != incomingDependencies.size()) {
            List<String> cycle = new ArrayList<>();
            remaining.forEach((field, count) -> {
                if (count > 0) {
                    cycle.add(field);
                }
            });
            throw new IllegalArgumentException(
                    "computed-field dependency graph contains a cycle involving "
                            + cycle.size() + " fields");
        }
        return maxDepth;
    }
}
