package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;

/**
 * One immutable topology index shared by structure and program extraction.
 *
 * <p>All capacity arithmetic stays explicit: a declared-unbounded group and a bounded-product
 * overflow remain distinguishable and can never be sampled as one during synthesis.
 */
final class ProfileTopology {

    static final String MODEL_LEVEL = "MODEL_LEVEL";
    static final String PER_GROUP = "PER_GROUP";
    static final String PER_REPEATABLE_ROW = "PER_REPEATABLE_ROW";

    private final List<ModelCard.Field> fields;
    private final List<ModelCard.Group> groups;
    private final Map<String, ModelCard.Group> groupByPath;
    private final Map<String, List<String>> childrenByGroup;
    private final Set<String> groupPaths;

    ProfileTopology(ModelCard card) {
        Objects.requireNonNull(card, "card");
        fields = List.copyOf(card.fields());
        groups = List.copyOf(card.groups());
        var byPath = new LinkedHashMap<String, ModelCard.Group>();
        var children = new LinkedHashMap<String, List<String>>();
        for (ModelCard.Group group : groups) {
            if (byPath.putIfAbsent(group.path(), group) != null) {
                throw new IllegalArgumentException("duplicate group path: " + group.path());
            }
            children.computeIfAbsent(group.path(), ignored -> new ArrayList<>());
        }
        for (ModelCard.Group group : groups) {
            String parent = parentOf(group.path());
            if (!parent.equals(group.path()) && byPath.containsKey(parent)) {
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(group.path());
            }
        }
        groupByPath = Map.copyOf(byPath);
        Map<String, List<String>> frozenChildren = new HashMap<>();
        children.forEach((path, childPaths) -> frozenChildren.put(path, List.copyOf(childPaths)));
        childrenByGroup = Map.copyOf(frozenChildren);
        groupPaths = Set.copyOf(byPath.keySet());
    }

    ModelProfile.StructureProfile structureProfile() {
        var elements = LongHistogram.builder()
                .add("FIELD", fields.size())
                .add("GROUP", groups.size())
                .build();
        var fieldKinds = LongHistogram.builder();
        var constrainedFields = LongHistogram.builder();
        var numberScale = LongDistribution.builder();
        var enumCardinality = LongDistribution.builder();
        var fieldsPerGroup = new LinkedHashMap<String, Long>();
        groups.forEach(group -> fieldsPerGroup.put(group.path(), 0L));
        for (ModelCard.Field field : fields) {
            fieldKinds.add(field.kind());
            String groupPath = parentOf(field.path());
            if (!fieldsPerGroup.containsKey(groupPath)) {
                throw new IllegalArgumentException(
                        "field has no indexed containing group: " + field.path());
            }
            fieldsPerGroup.merge(groupPath, 1L, Math::addExact);
            if (field.min().isPresent() || field.max().isPresent()) {
                constrainedFields.add("NUMBER_BOUNDS");
            }
            if (field.minLength().isPresent() || field.maxLength().isPresent()) {
                constrainedFields.add("STRING_LENGTH");
            }
            if (field.pattern().isPresent()) {
                constrainedFields.add("STRING_PATTERN");
            }
            field.scale().ifPresent(numberScale::add);
            if (!field.enumValues().isEmpty()) {
                enumCardinality.add(field.enumValues().size());
            }
        }

        int maxGroupDepth = groups.stream()
                .mapToInt(group -> depthOf(group.path()))
                .max()
                .orElse(0);
        var topology = new ModelProfile.TopologyProfile(
                maxGroupDepth,
                LongDistribution.of(fieldsPerGroup.values()));
        var fieldProfile = new ModelProfile.FieldProfile(
                fieldKinds.build(),
                constrainedFields.build(),
                numberScale.build(),
                enumCardinality.build());
        return new ModelProfile.StructureProfile(
                elements, topology, fieldProfile, repeatabilityProfile());
    }

    Capacity chainCapacity(String path) {
        Capacity product = new Capacity.Bounded(1);
        for (String groupPath : ancestorsOrSelf(path)) {
            ModelCard.Group group = groupByPath.get(groupPath);
            if (group == null || !group.repeatable()) {
                continue;
            }
            product = product.multiply(declaredCapacity(group));
        }
        return product;
    }

    Capacity scanCapacity(Collection<String> repeatableGroupPaths) {
        Capacity product = new Capacity.Bounded(1);
        for (String path : repeatableGroupPaths) {
            ModelCard.Group group = requireGroup(path);
            if (!group.repeatable()) {
                throw new IllegalArgumentException(
                        "scan path is not a repeatable group: " + path);
            }
            product = product.multiply(declaredCapacity(group));
        }
        return product;
    }

    String scopeClass(String scopePath) {
        if (repeatableDepth(scopePath) > 0) {
            return PER_REPEATABLE_ROW;
        }
        return depthOf(scopePath) <= 1 ? MODEL_LEVEL : PER_GROUP;
    }

    int repeatableDepth(String path) {
        int count = 0;
        for (String candidate : ancestorsOrSelf(path)) {
            ModelCard.Group group = groupByPath.get(candidate);
            if (group != null && group.repeatable()) {
                count++;
            }
        }
        return count;
    }

    int descendantFieldCount(String groupPath) {
        requireGroup(groupPath);
        int count = 0;
        String prefix = groupPath + "/";
        for (ModelCard.Field field : fields) {
            if (field.path().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    int walkUpDistance(String scopePath, String referencedPath) {
        String referenceGroup = groupPaths.contains(referencedPath)
                ? referencedPath
                : parentOf(referencedPath);
        if (!groupPaths.contains(referenceGroup)
                || !scopePath.startsWith(referenceGroup + "/")) {
            return 0;
        }
        return depthOf(scopePath) - depthOf(referenceGroup);
    }

    Set<String> repeatableBranchRoots(Collection<String> referencedPaths) {
        var branches = new HashSet<String>();
        for (String path : referencedPaths) {
            String nearest = nearestRepeatableAncestor(path);
            if (nearest != null) {
                branches.add(nearest);
            }
        }
        return Set.copyOf(branches);
    }

    private ModelProfile.RepeatabilityProfile repeatabilityProfile() {
        var outcomes = LongHistogram.builder();
        var exactDepth = LongHistogram.builder();
        var siblingFan = LongDistribution.builder();
        var maxRepetitions = new CapacityProfileBuilder();
        var groupChains = new CapacityProfileBuilder();
        var fieldChains = new CapacityProfileBuilder();
        int maxDepth = 0;

        for (ModelCard.Group group : groups) {
            if (!group.repeatable()) {
                continue;
            }
            Capacity declared = declaredCapacity(group);
            outcomes.add(declared instanceof Capacity.Bounded ? "BOUNDED" : "UNBOUNDED");
            maxRepetitions.add(declared);
            groupChains.add(chainCapacity(group.path()));
            int depth = repeatableDepth(group.path());
            exactDepth.add(Integer.toString(depth));
            maxDepth = Math.max(maxDepth, depth);
        }
        for (ModelCard.Field field : fields) {
            fieldChains.add(chainCapacity(parentOf(field.path())));
        }
        for (ModelCard.Group group : groups) {
            int fan = siblingRepeatableFan(group.path());
            if (fan > 0) {
                siblingFan.add(fan);
            }
        }

        var topology = new ModelProfile.RepeatableTopology(
                maxDepth, exactDepth.build(), siblingFan.build());
        var capacities = new ModelProfile.RepeatableCapacities(
                maxRepetitions.build(), groupChains.build(), fieldChains.build());
        return new ModelProfile.RepeatabilityProfile(outcomes.build(), topology, capacities);
    }

    private int siblingRepeatableFan(String groupPath) {
        int fan = 0;
        var pending = new ArrayDeque<String>();
        List<String> children = childrenByGroup.get(groupPath);
        if (children != null) {
            children.forEach(pending::addLast);
        }
        while (!pending.isEmpty()) {
            String candidate = pending.removeFirst();
            ModelCard.Group child = requireGroup(candidate);
            if (child.repeatable()) {
                fan++;
                continue;
            }
            List<String> descendants = childrenByGroup.get(candidate);
            if (descendants != null) {
                descendants.forEach(pending::addLast);
            }
        }
        return fan;
    }

    private String nearestRepeatableAncestor(String path) {
        List<String> ancestors = ancestorsOrSelf(path);
        for (int index = ancestors.size() - 1; index >= 0; index--) {
            ModelCard.Group group = groupByPath.get(ancestors.get(index));
            if (group != null && group.repeatable()) {
                return group.path();
            }
        }
        return null;
    }

    private ModelCard.Group requireGroup(String path) {
        ModelCard.Group group = groupByPath.get(path);
        if (group == null) {
            throw new IllegalArgumentException("no group at path: " + path);
        }
        return group;
    }

    private static Capacity declaredCapacity(ModelCard.Group group) {
        return group.maxRepetitions() > 0
                ? new Capacity.Bounded(group.maxRepetitions())
                : new Capacity.Unbounded(Capacity.Cause.DECLARED);
    }

    private static List<String> ancestorsOrSelf(String path) {
        var reversed = new ArrayList<String>();
        for (String candidate = path; ; ) {
            reversed.add(candidate);
            String parent = parentOf(candidate);
            if (parent.equals(candidate)) {
                break;
            }
            candidate = parent;
        }
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    static int depthOf(String path) {
        int depth = 0;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }

    static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash > 0 ? path.substring(0, slash) : path;
    }
}
