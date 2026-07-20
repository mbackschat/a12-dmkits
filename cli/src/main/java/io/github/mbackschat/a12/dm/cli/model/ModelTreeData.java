package io.github.mbackschat.a12.dm.cli.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.adapter.review.ModelReport;
import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.context.ModelCard;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;

/**
 * G44's agent-first model tree projection. The JSON remains a flat, document-ordered node list; only
 * {@code ReportText} turns those same records into a drawn hierarchy.
 */
final class ModelTreeData {

    private static final ObjectMapper MAPPER = new ObjectMapper();
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

    static Projection project(String modelJson, ModelResolver resolver, ModelReport report,
            String requestedRoot, Integer depthLimit, List<String> includes) {
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

        ModelDocument expanded = ModelDocument.read(reader.toJson());
        EnumSet<Facet> facets = Facet.parse(includes);
        Map<String, ModelCard.Field> fields = fieldsByPath(report.structure().fields());
        Map<String, ModelCard.Group> groups = groupsByPath(report.structure().groups());
        Set<String> computedFields = computedFields(report.computations());

        List<String> selectedPaths = selectedPaths(root, maxDepth, elementPaths);
        Set<String> emitted = new LinkedHashSet<>(selectedPaths);
        Map<String, List<ModelReport.RuleEntry>> rulesByAnchor = rulesByAnchor(report.rules(), root);
        Map<String, ModelReport.ComputationEntry> computationsByAnchor =
                computationsByAnchor(report.computations());

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
            } else if (fields.containsKey(path)) {
                fieldsCount++;
                node.put("kind", "field");
                putField(node, fields.get(path), index, computedFields.contains(path),
                        facets.contains(Facet.ENUM));
            } else {
                groupsCount++;
                ModelCard.Group group = groups.get(path);
                node.put("kind", "group");
                node.put("repeatable", group.repeatable());
                node.put("maxRepetitions", group.maxRepetitions());
                if (group.repeatable()) {
                    repeatableGroups++;
                }
            }

            if (!"/".equals(path)) {
                index.originOf(path).ifPresent(origin -> node.put("origin", origin));
                if (fields.containsKey(path)) {
                    index.typeReferenceOf(path).ifPresent(typeRef -> node.put("typeRef", typeRef));
                }
            }
            if (facets.contains(Facet.DESCRIPTIONS) && !"/".equals(path)) {
                putDescriptions(node, expanded, path);
            }
            if (facets.contains(Facet.ANNOTATIONS) && !"/".equals(path)) {
                putAnnotations(node, expanded.annotations(path));
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

    private static boolean isInSubtree(String root, String path) {
        return "/".equals(root) || path.equals(root) || path.startsWith(root + "/");
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

    private static Map<String, ModelCard.Field> fieldsByPath(List<ModelCard.Field> fields) {
        Map<String, ModelCard.Field> out = new LinkedHashMap<>();
        fields.forEach(field -> out.put(field.path(), field));
        return out;
    }

    private static Map<String, ModelCard.Group> groupsByPath(List<ModelCard.Group> groups) {
        Map<String, ModelCard.Group> out = new LinkedHashMap<>();
        groups.forEach(group -> out.put(group.path(), group));
        return out;
    }

    private static Set<String> computedFields(List<ModelReport.ComputationEntry> computations) {
        Set<String> out = new LinkedHashSet<>();
        for (ModelReport.ComputationEntry computation : computations) {
            if (computation.computedField() != null) {
                out.add(computation.computedField());
            }
        }
        return out;
    }

    private static void putField(ObjectNode node, ModelCard.Field field, ModelIndex index,
            boolean computed, boolean fullEnum) {
        node.put("type", field.kind());
        field.scale().ifPresent(scale -> node.put("scale", scale));
        field.min().ifPresent(min -> node.put("min", min));
        field.max().ifPresent(max -> node.put("max", max));
        field.minLength().ifPresent(min -> node.put("minLength", min));
        field.maxLength().ifPresent(max -> node.put("maxLength", max));
        field.pattern().ifPresent(pattern -> node.put("pattern", pattern));
        node.put("computed", computed);

        if (!field.enumValues().isEmpty()) {
            List<String> values = field.enumValues();
            int limit = fullEnum ? values.size() : Math.min(values.size(), ENUM_PREVIEW_SIZE);
            ArrayNode valueNodes = node.putArray("values");
            values.subList(0, limit).forEach(valueNodes::add);
            node.put("valueCount", values.size());
            if (limit < values.size()) {
                node.put("truncated", true);
            }
            Map<String, List<String>> categories = index.enumCategories(field.path());
            if (!categories.isEmpty()) {
                ObjectNode categoryNodes = node.putObject("categories");
                categories.forEach((name, categoryValues) -> {
                    ArrayNode valueArray = categoryNodes.putArray(name);
                    categoryValues.forEach(valueArray::add);
                });
            }
        }
    }

    private static void putDescriptions(ObjectNode node, ModelDocument model, String path) {
        Map<String, String> internal = model.internalDescription(path);
        Map<String, String> external = model.externalDescription(path);
        Map<String, String> helper = model.helperText(path);
        if (internal.isEmpty() && external.isEmpty() && helper.isEmpty()) {
            return;
        }
        ObjectNode descriptions = node.putObject("descriptions");
        putMap(descriptions, "internal", internal);
        putMap(descriptions, "external", external);
        putMap(descriptions, "helper", helper);
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

    private static Map<String, List<ModelReport.RuleEntry>> rulesByAnchor(
            List<ModelReport.RuleEntry> rules, String root) {
        Map<String, List<ModelReport.RuleEntry>> out = new LinkedHashMap<>();
        for (ModelReport.RuleEntry rule : rules) {
            String anchor = rule.errorField() == null ? root : rule.errorField();
            out.computeIfAbsent(anchor, ignored -> new ArrayList<>()).add(rule);
        }
        return out;
    }

    private static Map<String, ModelReport.ComputationEntry> computationsByAnchor(
            List<ModelReport.ComputationEntry> computations) {
        Map<String, ModelReport.ComputationEntry> out = new LinkedHashMap<>();
        for (ModelReport.ComputationEntry computation : computations) {
            if (computation.computedField() != null) {
                out.put(computation.computedField(), computation);
            }
        }
        return out;
    }

    private static void putRules(ObjectNode node, List<ModelReport.RuleEntry> rules) {
        if (rules.isEmpty()) {
            return;
        }
        ArrayNode out = node.putArray("rules");
        for (ModelReport.RuleEntry rule : rules) {
            out.addObject().put("name", rule.rule()).put("dsl", rule.canonicalText());
        }
    }

    private static void putComputation(ObjectNode node, ModelReport.ComputationEntry computation) {
        if (computation == null) {
            return;
        }
        node.putObject("computation")
                .put("name", computation.computation())
                .put("dsl", computationDsl(computation));
    }

    private static String computationDsl(ModelReport.ComputationEntry computation) {
        if (computation.canonicalText() != null) {
            return computation.canonicalText();
        }
        List<String> alternatives = new ArrayList<>();
        for (DraftComputation.Alternative alternative : computation.alternatives()) {
            alternatives.add(alternative.precondition() == null
                    ? alternative.operation()
                    : alternative.precondition() + " => " + alternative.operation());
        }
        String table = String.join("; ", alternatives);
        return computation.commonPrecondition() == null
                ? table
                : computation.commonPrecondition() + " => (" + table + ")";
    }

    private static int countAnchoredRules(Map<String, List<ModelReport.RuleEntry>> rules, Set<String> emitted) {
        int count = 0;
        for (String path : emitted) {
            count += rules.getOrDefault(path, List.of()).size();
        }
        return count;
    }

    private static int countAnchoredComputations(
            Map<String, ModelReport.ComputationEntry> computations, Set<String> emitted) {
        int count = 0;
        for (String path : emitted) {
            if (computations.containsKey(path)) {
                count++;
            }
        }
        return count;
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
