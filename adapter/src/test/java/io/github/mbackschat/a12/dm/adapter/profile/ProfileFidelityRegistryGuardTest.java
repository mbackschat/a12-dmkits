package io.github.mbackschat.a12.dm.adapter.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Prevents a profile field or documented fidelity classification from drifting out of the registry. */
class ProfileFidelityRegistryGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    @Test
    void registrySchemaComparatorAndClassificationTableStayBidirectionallyComplete() throws Exception {
        var registry = ProfileFidelityRegistry.classifications();

        assertSchemaAndRegistryCoverEachOther(schemaAxes(), registryProfilePaths(registry));
        assertEquals(
                registry.stream()
                        .map(ProfileFidelityRegistry.Classification::comparisonPath)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                exercisedComparisonPaths());

        var documented = documentedClassifications();
        assertEquals(registryPathsByArea(registry), documented.pathsByArea());
        assertEquals(registryPoliciesByArea(registry), documented.policiesByArea());
    }

    private static Set<String> schemaAxes() throws IOException {
        JsonNode schema = JSON.readTree(requiredPath("profile.schema.file").toFile());
        var result = new TreeSet<String>();
        collectSchemaAxes(schema, "", schema.path("$defs"), result);
        return result;
    }

    private static void collectSchemaAxes(
            JsonNode node,
            String path,
            JsonNode definitions,
            Set<String> result) {
        if (node.has("$ref")) {
            String reference = node.path("$ref").textValue();
            String prefix = "#/$defs/";
            assertTrue(reference.startsWith(prefix), "unsupported profile-schema reference " + reference);
            String definition = reference.substring(prefix.length());
            if ("histogram".equals(definition) || "distribution".equals(definition)) {
                result.add(path);
                return;
            }
            collectSchemaAxes(definitions.path(definition), path, definitions, result);
            return;
        }
        if ("integer".equals(node.path("type").textValue())) {
            result.add(path);
            return;
        }
        if (!"object".equals(node.path("type").textValue())) {
            throw new AssertionError("unclassified profile-schema node at " + path + ": " + node);
        }

        var properties = node.path("properties");
        properties.fields().forEachRemaining(property -> collectSchemaAxes(
                property.getValue(), child(path, property.getKey()), definitions, result));
        JsonNode additional = node.path("additionalProperties");
        if (additional.isObject()) {
            collectSchemaAxes(additional, child(path, "*"), definitions, result);
        }
        assertFalse(properties.isEmpty() && !additional.isObject(), "empty profile-schema object at " + path);
    }

    private static Set<String> registryProfilePaths(
            List<ProfileFidelityRegistry.Classification> registry) {
        return registry.stream()
                .map(ProfileFidelityRegistry.Classification::profilePath)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static void assertSchemaAndRegistryCoverEachOther(
            Set<String> schema,
            Set<String> registry) {
        assertEquals(
                Set.of(),
                registry.stream()
                        .filter(path -> schema.stream().noneMatch(candidate -> compatible(candidate, path)))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                "registry paths absent from profile.schema.json");
        assertEquals(
                Set.of(),
                schema.stream()
                        .filter(path -> registry.stream().noneMatch(candidate -> compatible(path, candidate)))
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new)),
                "profile.schema.json axes absent from the fidelity registry");
    }

    private static boolean compatible(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        if (leftParts.length != rightParts.length) {
            return false;
        }
        for (int index = 0; index < leftParts.length; index++) {
            if (!"*".equals(leftParts[index])
                    && !"*".equals(rightParts[index])
                    && !leftParts[index].equals(rightParts[index])) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> exercisedComparisonPaths() {
        return ModelProfileComparator.compare(
                        ProfileJsonFixture.profile(false),
                        ProfileJsonFixture.profile(false))
                .axes()
                .stream()
                .map(result -> ProfileFidelityRegistry.require(result.axis().path()).comparisonPath())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Set<String>> registryPathsByArea(
            List<ProfileFidelityRegistry.Classification> registry) {
        var result = new TreeMap<String, Set<String>>();
        registry.forEach(classification -> result
                .computeIfAbsent(classification.area().label(), ignored -> new TreeSet<>())
                .add(classification.profilePath()));
        return result;
    }

    private static Map<String, Set<ProfileComparison.Policy>> registryPoliciesByArea(
            List<ProfileFidelityRegistry.Classification> registry) {
        var result = new TreeMap<String, Set<ProfileComparison.Policy>>();
        registry.forEach(classification -> result
                .computeIfAbsent(
                        classification.area().label(),
                        ignored -> EnumSet.noneOf(ProfileComparison.Policy.class))
                .add(classification.policy()));
        return result;
    }

    private static DocumentedClassifications documentedClassifications() throws IOException {
        List<String> lines = Files.readAllLines(requiredPath("profile.fidelity.spec"));
        int header = lines.indexOf("| Profile area | Axes | Registry paths | Policy | Reason |");
        assertTrue(header >= 0, "missing fidelity classification-table header");

        var pathsByArea = new TreeMap<String, Set<String>>();
        var policiesByArea = new TreeMap<String, Set<ProfileComparison.Policy>>();
        for (int index = header + 2; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.startsWith("|")) {
                break;
            }
            List<String> cells = tableCells(line);
            assertEquals(5, cells.size(), line);
            String area = cells.get(0);
            assertFalse(pathsByArea.containsKey(area), "duplicate fidelity-table area " + area);
            pathsByArea.put(area, codeValues(cells.get(2)));
            policiesByArea.put(area, policies(cells.get(3)));
        }
        return new DocumentedClassifications(pathsByArea, policiesByArea);
    }

    private static List<String> tableCells(String line) {
        String body = line.substring(1, line.length() - 1);
        return java.util.Arrays.stream(body.split("\\|", -1)).map(String::trim).toList();
    }

    private static Set<String> codeValues(String cell) {
        var result = new TreeSet<String>();
        var matcher = CODE.matcher(cell);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        assertFalse(result.isEmpty(), "classification row has no registry paths: " + cell);
        return result;
    }

    private static Set<ProfileComparison.Policy> policies(String cell) {
        var result = EnumSet.noneOf(ProfileComparison.Policy.class);
        var matcher = CODE.matcher(cell);
        while (matcher.find()) {
            try {
                result.add(ProfileComparison.Policy.valueOf(matcher.group(1)));
            } catch (IllegalArgumentException ignored) {
                // Non-policy code in explanatory prose is not a classification token.
            }
        }
        assertFalse(result.isEmpty(), "classification row has no fidelity policy: " + cell);
        return result;
    }

    private static String child(String parent, String name) {
        return parent.isEmpty() ? name : parent + "." + name;
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must identify the guard's declared input");
        }
        return Path.of(value);
    }

    private record DocumentedClassifications(
            Map<String, Set<String>> pathsByArea,
            Map<String, Set<ProfileComparison.Policy>> policiesByArea) {

        private DocumentedClassifications {
            pathsByArea = immutable(pathsByArea);
            policiesByArea = immutable(policiesByArea);
        }

        private static <V> Map<String, Set<V>> immutable(Map<String, Set<V>> source) {
            var result = new TreeMap<String, Set<V>>();
            source.forEach((key, value) -> result.put(key, Set.copyOf(value)));
            return Map.copyOf(result);
        }
    }
}
