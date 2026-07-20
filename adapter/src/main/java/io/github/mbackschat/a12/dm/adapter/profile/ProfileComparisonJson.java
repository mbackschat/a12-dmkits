package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Canonical serializer for the package-private profile-comparison result. */
final class ProfileComparisonJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProfileComparisonJson() {}

    static String write(ProfileComparison comparison) {
        Objects.requireNonNull(comparison, "comparison");
        var root = JSON.createObjectNode();
        root.put("matches", comparison.matches());
        var axes = root.putArray("axes");
        for (ProfileComparison.AxisResult result : comparison.axes()) {
            var axis = axes.addObject();
            axis.put("path", result.axis().path());
            axis.put("policy", result.axis().policy().name());
            axis.set("target", value(result.observation().target()));
            axis.set("actual", value(result.observation().actual()));
            axis.put("verdict", result.outcome().verdict().name());
            axis.put("rule", result.outcome().rule());
        }
        return ProfileJsonWriter.canonicalJson(root);
    }

    private static JsonNode value(ProfileComparison.Value value) {
        return switch (value) {
            case ProfileComparison.ScalarValue scalar ->
                    JSON.getNodeFactory().numberNode(scalar.value());
            case ProfileComparison.HistogramValue histogram ->
                    ProfileJsonWriter.histogram(histogram.value());
            case ProfileComparison.DistributionValue distribution ->
                    ProfileJsonWriter.distribution(distribution.value());
        };
    }
}
