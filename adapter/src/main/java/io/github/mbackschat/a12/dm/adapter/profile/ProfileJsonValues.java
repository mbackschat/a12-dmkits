package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared strict readers for the profile's histogram, distribution, and JSON primitives. */
final class ProfileJsonValues {

    private ProfileJsonValues() {}

    static LongHistogram histogram(JsonNode source, String path) {
        ObjectNode node = object(source, path);
        var counts = new LinkedHashMap<String, Long>();
        node.properties().forEach(entry ->
                counts.put(entry.getKey(), integer64(entry.getValue(), path + "." + entry.getKey())));
        return validated(path, () -> LongHistogram.of(counts));
    }

    static SortedMap<String, LongHistogram> histogramMap(JsonNode source, String path) {
        ObjectNode node = object(source, path);
        var result = new TreeMap<String, LongHistogram>();
        node.properties().forEach(entry ->
                result.put(entry.getKey(), histogram(entry.getValue(), path + "." + entry.getKey())));
        return result;
    }

    static LongDistribution distribution(JsonNode source, String path) {
        ObjectNode node = exactObject(
                source,
                path,
                Set.of("count", "min", "median", "p90", "max", "buckets"));
        ArrayNode bucketNodes = array(required(node, "buckets", path), path + ".buckets");
        var buckets = new ArrayList<LongDistribution.Bucket>(bucketNodes.size());
        for (int index = 0; index < bucketNodes.size(); index++) {
            String bucketPath = path + ".buckets[" + index + "]";
            ObjectNode bucket =
                    exactObject(bucketNodes.get(index), bucketPath, Set.of("lower", "upper", "count"));
            buckets.add(new BucketBuilder()
                    .lower(integer64(bucket, "lower", bucketPath))
                    .upper(integer64(bucket, "upper", bucketPath))
                    .count(integer64(bucket, "count", bucketPath))
                    .build(bucketPath));
        }
        return new DistributionBuilder()
                .count(integer64(node, "count", path))
                .min(integer64(node, "min", path))
                .median(integer64(node, "median", path))
                .p90(integer64(node, "p90", path))
                .max(integer64(node, "max", path))
                .buckets(buckets)
                .build(path);
    }

    static ObjectNode exactObject(JsonNode source, String path, Set<String> fields) {
        ObjectNode node = object(source, path);
        exactFields(node, path, fields, fields);
        return node;
    }

    static void exactFields(
            ObjectNode node,
            String path,
            Set<String> allowed,
            Set<String> required) {
        node.properties().forEach(entry -> {
            String field = entry.getKey();
            if (!allowed.contains(field)) {
                throw invalid(path + "." + field, "unknown field");
            }
        });
        for (String field : required) {
            if (!node.has(field) || node.get(field).isNull()) {
                throw invalid(path + "." + field, "required field is missing");
            }
        }
    }

    static JsonNode required(ObjectNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw invalid(path + "." + field, "required field is missing");
        }
        return value;
    }

    static ObjectNode object(JsonNode node, String path) {
        if (!(node instanceof ObjectNode object)) {
            throw invalid(path, "expected an object");
        }
        return object;
    }

    static ArrayNode array(JsonNode node, String path) {
        if (!(node instanceof ArrayNode array)) {
            throw invalid(path, "expected an array");
        }
        return array;
    }

    static String text(ObjectNode node, String field, String path) {
        JsonNode value = required(node, field, path);
        if (!value.isTextual()) {
            throw invalid(path + "." + field, "expected a string");
        }
        return value.textValue();
    }

    static boolean bool(ObjectNode node, String field, String path) {
        JsonNode value = required(node, field, path);
        if (!value.isBoolean()) {
            throw invalid(path + "." + field, "expected a boolean");
        }
        return value.booleanValue();
    }

    static int integer(ObjectNode node, String field, String path) {
        JsonNode value = required(node, field, path);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(path + "." + field, "expected a 32-bit integer");
        }
        return value.intValue();
    }

    static long integer64(ObjectNode node, String field, String path) {
        return integer64(required(node, field, path), path + "." + field);
    }

    static long integer64(JsonNode value, String path) {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(path, "expected a 64-bit integer");
        }
        return value.longValue();
    }

    static void optionalBoolean(ObjectNode node, String field, String path) {
        if (node.has(field)) {
            bool(node, field, path);
        }
    }

    static void optionalText(ObjectNode node, String field, String path) {
        if (node.has(field)) {
            text(node, field, path);
        }
    }

    static void optionalObject(ObjectNode node, String field, String path) {
        if (node.has(field)) {
            object(required(node, field, path), path + "." + field);
        }
    }

    static <T> T validated(String path, Supplier<T> constructor) {
        try {
            return constructor.get();
        } catch (RuntimeException e) {
            throw invalid(
                    path,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    e);
        }
    }

    static IllegalArgumentException invalid(String path, String detail) {
        return new IllegalArgumentException("invalid model profile JSON at " + path + ": " + detail);
    }

    static IllegalArgumentException invalid(String path, String detail, Throwable cause) {
        return new IllegalArgumentException(
                "invalid model profile JSON at " + path + ": " + detail, cause);
    }

    private static final class DistributionBuilder {
        private long count;
        private long min;
        private long median;
        private long p90;
        private long max;
        private List<LongDistribution.Bucket> buckets;

        DistributionBuilder count(long value) {
            count = value;
            return this;
        }

        DistributionBuilder min(long value) {
            min = value;
            return this;
        }

        DistributionBuilder median(long value) {
            median = value;
            return this;
        }

        DistributionBuilder p90(long value) {
            p90 = value;
            return this;
        }

        DistributionBuilder max(long value) {
            max = value;
            return this;
        }

        DistributionBuilder buckets(List<LongDistribution.Bucket> value) {
            buckets = value;
            return this;
        }

        LongDistribution build(String path) {
            return validated(
                    path,
                    () -> new LongDistribution(count, min, median, p90, max, buckets));
        }
    }

    private static final class BucketBuilder {
        private long lower;
        private long upper;
        private long count;

        BucketBuilder lower(long value) {
            lower = value;
            return this;
        }

        BucketBuilder upper(long value) {
            upper = value;
            return this;
        }

        BucketBuilder count(long value) {
            count = value;
            return this;
        }

        LongDistribution.Bucket build(String path) {
            return validated(
                    path, () -> new LongDistribution.Bucket(lower, upper, count));
        }
    }
}
