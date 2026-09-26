package io.github.mbackschat.a12.dm.cli.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.core.ComputationInfo;
import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.core.ElementType;
import io.github.mbackschat.a12.dm.core.EntityPaths;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.RuleInfo;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * G44's agent-first model tree projection. The JSON remains a flat, document-ordered node list; only
 * {@code ReportText} turns those same records into a drawn hierarchy.
 */
final class ModelTreeData {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            // ModelReader emits A12 DM-JSON. Keep its parser contract here even though this projection currently
            // selects mostly structural/metadata members; a future numeric facet must not silently degrade scale.
            .enable(JsonParser.Feature.ALLOW_COMMENTS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private static final int ENUM_PREVIEW_SIZE = 5;

    enum Facet {
        RULES("rules"),
        COMPUTATIONS("computations"),
        DESCRIPTIONS("descriptions"),
        ANNOTATIONS("annotations"),
        ENUM("enum");

        private final String wire;

        Facet(String wire) {
            this.wire = wire;
        }

        static EnumSet<Facet> parse(List<String> values) {
            EnumSet<Facet> out = EnumSet.noneOf(Facet.class);
            for (String value : values) {
                String normalized = value.trim();
                if ("all".equalsIgnoreCase(normalized)) {
                    return EnumSet.allOf(Facet.class);
                }
                Facet match = null;
                for (Facet candidate : values()) {
                    if (candidate.wire.equalsIgnoreCase(normalized)) {
                        match = candidate;
                        break;
                    }
                }
                if (match == null) {
                    throw new IllegalArgumentException("unknown --include facet '" + normalized
                            + "' (use rules,computations,descriptions,annotations,enum, or all)");
                }
                out.add(match);
            }
            return out;
        }
    }

    private ModelTreeData() {
    }

    static Projection project(String modelJson, ModelResolver resolver, String requestedRoot,
            Integer depthLimit, List<String> includes) {
        String root = requestedRoot == null ? "/" : requestedRoot;
        int maxDepth = depthLimit == null ? Integer.MAX_VALUE : depthLimit;
        if (maxDepth < 0) {
            throw new IllegalArgumentException("--depth must be zero or greater");
        }

        ModelReader reader = ModelReader.expand(modelJson, resolver);
        ModelIndex index = ModelIndex.of(reader);
        List<String> elementPaths = index.elementPaths().toList();
        if (!"/".equals(root) && !elementPaths.contains(root)) {
            return Projection.missing(root, elementPaths);
        }

        String expandedJson = reader.toJson();
        JsonNode expandedRoot;
        try {
            expandedRoot = MAPPER.readTree(expandedJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to read the expanded model tree", e);
        }
        EnumSet<Facet> facets = Facet.parse(includes);
        Optional<ModelTreeMetadataIndex> metadataIndex =
                facets.contains(Facet.DESCRIPTIONS) || facets.contains(Facet.ANNOTATIONS)
                        ? Optional.of(ModelTreeMetadataIndex.from(expandedRoot))
                        : Optional.empty();
        Map<String, ComputationSource> computationsByPath = computationSources(expandedRoot, index);
        Set<String> computedFields = computedFields(computationsByPath.values());

        List<String> selectedPaths = selectedPaths(root, maxDepth, elementPaths);
        Set<String> emitted = new LinkedHashSet<>(selectedPaths);
        Map<String, List<RuleInfo>> rulesByAnchor = rulesByAnchor(index);
        Map<String, ComputationSource> computationsByAnchor = computationsByAnchor(computationsByPath.values());

        ObjectNode data = MAPPER.createObjectNode();
        data.put("root", root);
        ArrayNode nodes = data.putArray("nodes");
        int fieldsCount = 0;
        int groupsCount = 0;
        int repeatableGroups = 0;
        int deepest = 0;

        for (String path : selectedPaths) {
            int depth = depthFrom(root, path);
            deepest = Math.max(deepest, depth);
            ObjectNode node = nodes.addObject();
            node.put("path", path);
            if (path.equals(root)) {
                node.putNull("parent");
            } else {
                node.put("parent", parentOf(path));
            }
            node.put("depth", depth);

            if ("/".equals(path)) {
                node.put("kind", "model");
            } else if (index.isField(path)) {
                fieldsCount++;
                node.put("kind", "field");
                putField(node, path, index, computedFields.contains(path), facets.contains(Facet.ENUM));
            } else {
                groupsCount++;
                node.put("kind", "group");
                boolean repeatable = index.isRepeating(path);
                node.put("repeatable", repeatable);
                node.put("maxReps", index.maxRepetitions(path));
                if (repeatable) {
                    repeatableGroups++;
                }
            }

            if (!"/".equals(path)) {
                index.originOf(path).ifPresent(origin -> node.put("origin", origin));
                if (index.isField(path)) {
                    index.typeReferenceOf(path).ifPresent(typeRef -> node.put("typeRef", typeRef));
                }
            }
            if (metadataIndex.isPresent() && !"/".equals(path)) {
                ModelTreeMetadataIndex.Metadata metadata = metadataIndex.orElseThrow().metadata(path);
                if (facets.contains(Facet.DESCRIPTIONS)) {
                    putDescriptions(node, metadata);
                }
                if (facets.contains(Facet.ANNOTATIONS)) {
                    putAnnotations(node, metadata.annotations());
                }
            }
            if (facets.contains(Facet.RULES)) {
                putRules(node, rulesByAnchor.getOrDefault(path, List.of()));
            }
            if (facets.contains(Facet.COMPUTATIONS)) {
                putComputation(node, computationsByAnchor.get(path));
            }
        }

        int ruleCount = countAnchoredRules(rulesByAnchor, emitted);
        int computationCount = countAnchoredComputations(computationsByAnchor, emitted);
        ObjectNode summary = data.putObject("summary");
        summary.put("fields", fieldsCount);
        summary.put("groups", groupsCount);
        summary.put("rules", ruleCount);
        summary.put("computations", computationCount);
        summary.put("maxDepth", deepest);
        summary.put("repeatableGroups", repeatableGroups);
        return Projection.found(data);
    }

    private static List<String> selectedPaths(String root, int maxDepth, List<String> elementPaths) {
        List<String> out = new ArrayList<>();
        if ("/".equals(root)) {
            out.add("/");
        }
        for (String path : elementPaths) {
            if (isInSubtree(root, path) && depthFrom(root, path) <= maxDepth) {
                out.add(path);
            }
        }
        return out;
    }

    /** {@code "/"} is this verb's whole-model sentinel, which {@link EntityPaths} deliberately does not absorb. */
    private static boolean isInSubtree(String root, String path) {
        return "/".equals(root) || EntityPaths.isInside(path, root);
    }

    private static int depthFrom(String root, String path) {
        if (root.equals(path)) {
            return 0;
        }
        int rootSegments = "/".equals(root) ? 0 : segmentCount(root);
        return segmentCount(path) - rootSegments;
    }

    private static int segmentCount(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash == 0 ? "/" : path.substring(0, slash);
    }

    private static Set<String> computedFields(Iterable<ComputationSource> computations) {
        Set<String> out = new LinkedHashSet<>();
        for (ComputationSource computation : computations) {
            out.add(computation.target());
        }
        return out;
    }

    private static void putField(ObjectNode node, String path, ModelIndex index,
            boolean computed, boolean fullEnum) {
        ValueKind kind = index.kindOf(path);
        node.put("type", kind.name());
        if (kind == ValueKind.NUMBER) {
            node.put("scale", index.numberScale(path));
        }
        index.numberMin(path).ifPresent(min -> node.put("min", min));
        index.numberMax(path).ifPresent(max -> node.put("max", max));
        index.stringMinLength(path).ifPresent(min -> node.put("minLength", min));
        index.stringMaxLength(path).ifPresent(max -> node.put("maxLength", max));
        index.stringPattern(path).ifPresent(pattern -> node.put("pattern", pattern));
        node.put("computed", computed);

        if (kind == ValueKind.ENUMERATION) {
            List<String> values = index.enumValues(path);
            int limit = fullEnum ? values.size() : Math.min(values.size(), ENUM_PREVIEW_SIZE);
            ArrayNode valueNodes = node.putArray("values");
            values.subList(0, limit).forEach(valueNodes::add);
            node.put("valueCount", values.size());
            if (limit < values.size()) {
                node.put("truncated", true);
            }
            Map<String, List<String>> categories = index.enumCategories(path);
            if (!categories.isEmpty()) {
                ObjectNode categoryNodes = node.putObject("categories");
                categories.forEach((name, categoryValues) -> {
                    ArrayNode valueArray = categoryNodes.putArray(name);
                    categoryValues.forEach(valueArray::add);
                });
            }
        }
    }

    private static void putDescriptions(ObjectNode node, ModelTreeMetadataIndex.Metadata metadata) {
        if (metadata.internal().isEmpty() && metadata.external().isEmpty() && metadata.helper().isEmpty()) {
            return;
        }
        ObjectNode descriptions = node.putObject("descriptions");
        putMap(descriptions, "internal", metadata.internal());
        putMap(descriptions, "external", metadata.external());
        putMap(descriptions, "helper", metadata.helper());
    }

    private static void putAnnotations(ObjectNode node, Map<String, String> annotations) {
        if (annotations.isEmpty()) {
            return;
        }
        ObjectNode out = node.putObject("annotations");
        annotations.forEach(out::put);
    }

    private static void putMap(ObjectNode parent, String key, Map<String, String> values) {
        if (values.isEmpty()) {
            return;
        }
        ObjectNode out = parent.putObject(key);
        values.forEach(out::put);
    }

    private static Map<String, List<RuleInfo>> rulesByAnchor(ModelIndex index) {
        Map<String, List<RuleInfo>> out = new LinkedHashMap<>();
        for (RuleInfo rule : index.rules()) {
            out.computeIfAbsent(rule.errorEntityPath().orElse("/"), ignored -> new ArrayList<>()).add(rule);
        }
        return out;
    }

    private static Map<String, ComputationSource> computationSources(JsonNode expandedRoot, ModelIndex index) {
        Map<String, JsonNode> bodies = new LinkedHashMap<>();
        collectComputationBodies(rootGroups(expandedRoot), bodies);
        Map<String, ComputationSource> out = new LinkedHashMap<>();
        for (ComputationInfo computation : index.computations()) {
            JsonNode body = bodies.get(computation.path());
            if (body != null) {
                out.put(computation.path(), new ComputationSource(
                        computation.path(), computation.targetFieldPath(), computationDsl(body)));
            }
        }
        return out;
    }

    private static Map<String, ComputationSource> computationsByAnchor(Iterable<ComputationSource> computations) {
        Map<String, ComputationSource> out = new LinkedHashMap<>();
        for (ComputationSource computation : computations) {
            out.put(computation.target(), computation);
        }
        return out;
    }

    private static JsonNode rootGroups(JsonNode root) {
        return root.path(DmKeys.CONTENT).path(DmKeys.MODEL_ROOT).path(DmKeys.ROOT_GROUPS);
    }

    private static void collectComputationBodies(JsonNode elements, Map<String, JsonNode> out) {
        for (JsonNode element : elements) {
            ElementType type = ElementType.fromWire(element.path(DmKeys.TYPE).asText(null));
            if (type == ElementType.GROUP) {
                collectComputationBodies(
                        element.path(ElementType.GROUP.wireName()).path(DmKeys.ELEMENTS), out);
            } else if (type == ElementType.COMPUTATION) {
                out.put(element.path(DmKeys.ID).asText(), element.path(ElementType.COMPUTATION.wireName()));
            }
        }
    }

    private static void putRules(ObjectNode node, List<RuleInfo> rules) {
        if (rules.isEmpty()) {
            return;
        }
        ArrayNode out = node.putArray("rules");
        for (RuleInfo rule : rules) {
            out.addObject().put("name", rule.path()).put("dsl", rule.errorCondition());
        }
    }

    private static void putComputation(ObjectNode node, ComputationSource computation) {
        if (computation == null) {
            return;
        }
        node.putObject("computation")
                .put("name", computation.name())
                .put("dsl", computation.dsl());
    }

    private static String computationDsl(JsonNode computation) {
        List<String> alternatives = new ArrayList<>();
        for (JsonNode alternative : computation.path("computationAlternatives")) {
            String operation = alternative.path("operation").asText();
            String precondition = alternative.path("precondition").asText("");
            alternatives.add(precondition.isEmpty() ? operation : precondition + " => " + operation);
        }
        String table = String.join("; ", alternatives);
        String commonPrecondition = computation.path("commonPrecondition").asText("");
        return commonPrecondition.isEmpty() ? table : commonPrecondition + " => (" + table + ")";
    }

    private static int countAnchoredRules(Map<String, List<RuleInfo>> rules, Set<String> emitted) {
        int count = 0;
        for (String path : emitted) {
            count += rules.getOrDefault(path, List.of()).size();
        }
        return count;
    }

    private static int countAnchoredComputations(
            Map<String, ComputationSource> computations, Set<String> emitted) {
        int count = 0;
        for (String path : emitted) {
            if (computations.containsKey(path)) {
                count++;
            }
        }
        return count;
    }

    private record ComputationSource(String name, String target, String dsl) {
    }

    record Projection(ObjectNode data, String missingRoot, List<String> candidates) {

        static Projection found(ObjectNode data) {
            return new Projection(data, null, List.of());
        }

        static Projection missing(String root, List<String> candidates) {
            return new Projection(null, root, List.copyOf(candidates));
        }

        boolean found() {
            return data != null;
        }
    }
}
