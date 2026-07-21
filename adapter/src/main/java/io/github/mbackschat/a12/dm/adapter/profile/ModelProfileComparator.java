package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Objects;
import java.util.TreeSet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Structured, deterministic comparison of two PR-shaped model profiles. */
final class ModelProfileComparator {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private ModelProfileComparator() {}

    static String compare(SynthesisProfile target, SynthesisProfile actual) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(actual, "actual");
        ObjectNode result = JSON.createObjectNode();
        ArrayNode axes = result.putArray("axes");
        compare("", JSON.valueToTree(target), JSON.valueToTree(actual), axes);
        boolean matches = true;
        for (JsonNode axis : axes) {
            if ("FAIL".equals(axis.path("verdict").asText())) {
                matches = false;
                break;
            }
        }
        result.put("matches", matches);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize profile comparison", e);
        }
    }

    private static void compare(String path, JsonNode target, JsonNode actual, ArrayNode axes) {
        if (isDistribution(target) && isDistribution(actual)) {
            addAxis(path, Policy.BUCKET, target, actual, distributionsMatch(target, actual), axes);
            return;
        }
        if (target.isObject() && actual.isObject()) {
            var names = new TreeSet<String>();
            target.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode targetValue = target.path(name);
                JsonNode actualValue = actual.path(name);
                compare(path.isEmpty() ? name : path + "." + name, targetValue, actualValue, axes);
            }
            return;
        }

        Policy policy = policy(path);
        boolean passes = switch (policy) {
            case EXACT -> target.equals(actual);
            case AT_LEAST -> actual.asLong() >= target.asLong();
            case BUCKET -> adjacentBucket(target.asLong(), actual.asLong());
            case INFORMATIVE -> true;
        };
        addAxis(path, policy, target, actual, passes, axes);
    }

    private static void addAxis(
            String path,
            Policy policy,
            JsonNode target,
            JsonNode actual,
            boolean passes,
            ArrayNode axes) {
        ObjectNode axis = axes.addObject();
        axis.put("path", path);
        axis.put("policy", policy.name());
        axis.set("target", target);
        axis.set("actual", actual);
        axis.put("verdict", policy == Policy.INFORMATIVE ? "INFORMATIVE" : passes ? "PASS" : "FAIL");
        axis.put("rule", policy.rule);
    }

    private static Policy policy(String path) {
        if (path.endsWith("opaqueCount")) {
            return Policy.INFORMATIVE;
        }
        if (path.contains(".operators.")
                || path.contains(".constructs.")
                || path.contains(".archetypes.")) {
            return Policy.AT_LEAST;
        }
        if (path.equals("structure.fieldCount")
                || path.equals("structure.groupCount")
                || path.equals("structure.maxNestingDepth")
                || path.equals("structure.repeatability.repeatableGroupCount")
                || path.equals("structure.repeatability.maxRepeatableNestingDepth")
                || path.startsWith("structure.repeatability.nestingDepthExact.")
                || path.startsWith("structure.fieldKinds.")
                || path.equals("rules.count")
                || path.equals("computations.count")
                || path.equals("computations.maxCascadeDepth")) {
            return Policy.EXACT;
        }
        return Policy.BUCKET;
    }

    private static boolean isDistribution(JsonNode node) {
        return node.isObject()
                && node.has("n")
                && node.has("min")
                && node.has("median")
                && node.has("p90")
                && node.has("max")
                && node.has("buckets");
    }

    private static boolean distributionsMatch(JsonNode target, JsonNode actual) {
        if (target.path("n").asLong() == 0 || actual.path("n").asLong() == 0) {
            return target.path("n").asLong() == actual.path("n").asLong();
        }
        return adjacentBucket(target.path("n").asLong(), actual.path("n").asLong())
                && adjacentBucket(target.path("min").asLong(), actual.path("min").asLong())
                && adjacentBucket(target.path("median").asLong(), actual.path("median").asLong())
                && adjacentBucket(target.path("p90").asLong(), actual.path("p90").asLong())
                && adjacentBucket(target.path("max").asLong(), actual.path("max").asLong());
    }

    private static boolean adjacentBucket(long target, long actual) {
        return Math.abs(bucket(target) - bucket(actual)) <= 1;
    }

    private static int bucket(long value) {
        return value <= 1 ? Math.toIntExact(value) : 65 - Long.numberOfLeadingZeros(value);
    }

    private enum Policy {
        EXACT("actual must equal target"),
        AT_LEAST("actual must be at least target"),
        BUCKET("actual must occupy the same or an adjacent logarithmic bucket"),
        INFORMATIVE("reported without affecting fidelity");

        private final String rule;

        Policy(String rule) {
            this.rule = rule;
        }
    }
}
